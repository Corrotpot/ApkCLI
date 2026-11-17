package dev.apkcli.core.agent

import android.util.Log
import dev.apkcli.core.llm.CompletionRequest
import dev.apkcli.core.llm.LlmClient
import dev.apkcli.core.llm.LlmError
import dev.apkcli.core.llm.StreamEvent
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.MessageStatus
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.store.ConversationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Turns a full context window into a digest.
 *
 * The design constraint that shapes everything here: **compaction must not be
 * amnesia.** The naive implementation ("summarise the conversation so far in a
 * paragraph") produces an agent that re-reads files it already read, re-proposes
 * approaches that already failed, and asks the user things they already
 * answered — each of which costs more than the summary saved.
 *
 * Four mechanisms avoid that:
 *
 *  1. **Asked where the work is.** The summary is asked at the end of the
 *     request the agent was about to send ([CompactionAsk.within]), so the
 *     model writes it from everything it worked with, tool output whole, and
 *     the work goes on from the same place right after. Only when that request
 *     would not fit does a transcript of the span go in a request of its own.
 *
 *  2. **A schema, not prose.** The model fills in [ConversationDigest] as a
 *     JSON object, so the fields that matter — failed approaches, decisions
 *     with reasons, the file map — cannot be omitted in favour of a readable
 *     narrative.
 *
 *  3. **Cumulative merging.** The model writes what the digest does not have
 *     yet and the app merges it in ([ConversationDigest.mergedWith]). Repeated
 *     lossy passes over the same text is how detail evaporates; and a reply
 *     that wrote the whole digest out again grew with the session until it
 *     no longer fitted the output limit.
 *
 *  4. **Nothing is deleted.** Archived messages stay in SQLite and stay indexed
 *     for full-text search. The digest is a fast path; `conversation_search` is
 *     the fallback. That turns "I no longer have that in context" into a lookup
 *     rather than a dead end.
 */
class Compactor(
    private val store: ConversationStore,
    private val llm: LlmClient,
) {

    /**
     * The request the agent is about to send, and what it holds as the budget
     * counted it: where [compact] asks for the summary first.
     */
    class Within(val request: CompletionRequest, val promptTokens: Int)

    sealed interface Result {
        data class Compacted(
            val digest: ConversationDigest,
            val digestId: String,
            val archivedMessages: Int,
            val tokensFreed: Long,
            val digestTokens: Int,
        ) : Result

        /** Nothing to do, or not enough history to be worth it. */
        data class Skipped(val reason: String) : Result

        /**
         * The summarising request itself failed.
         *
         * Deliberately not fatal and deliberately not silent: archiving without
         * a digest would destroy the history the agent needs, so on failure
         * nothing is archived and the caller decides. The usual right answer is
         * to drop the oldest turns without summarising, which
         * [emergencyTrim] does.
         */
        data class Failed(
            val message: String,
            /** What went wrong, in the provider's or the parser's words, when known. */
            val detail: String? = null,
        ) : Result
    }

    /**
     * Produce a digest for [plan] and archive the messages it covers.
     *
     * Order matters: the digest is generated and persisted *before* anything is
     * archived. If the process dies in between, the worst case is a duplicate
     * digest, not a session whose history was discarded without a summary.
     *
     * @param focus what /compact was asked to keep in detail, for the
     *   summary's `keptByRequest`.
     * @param trigger who asked: [ConversationDigest.TRIGGER_COMMAND] or
     *   [ConversationDigest.TRIGGER_AUTO], kept with the digest for the
     *   chat's seam.
     * @param minGain below this many tokens given back, the summary is not
     *   kept and nothing is archived ([Result.Skipped] with [NOT_SHORTER]).
     *   At least one: a summary that frees nothing only costs a request,
     *   and the chat said it "freed 0". For /compact, [MIN_MANUAL_GAIN].
     * @param within the request the agent is about to send, to ask the
     *   summary at its end; null asks with a transcript straight away.
     */
    suspend fun compact(
        sessionId: String,
        messages: List<Message>,
        plan: ContextBudget.Plan,
        endpoint: Endpoint,
        model: ModelSpec,
        previous: ConversationDigest?,
        focus: String? = null,
        trigger: String = ConversationDigest.TRIGGER_AUTO,
        minGain: Int = 1,
        within: Within? = null,
    ): Result {
        if (plan.isNoop) return Result.Skipped(plan.reason)

        val span = messages.filter { it.seq in plan.archiveFromSeq..plan.archiveToSeq }
        if (span.isEmpty()) return Result.Skipped("no messages in the planned range")

        Log.i(TAG, "compacting ${span.size} message(s) of session $sessionId")

        val (asked, failure) = requestDigest(span, endpoint, model, previous, focus, within)
        // Stamped with where they were learned, so that a long session's
        // merge keeps the newest files when it has to drop some.
        val produced = asked?.stamped(plan.archiveToSeq)
        if (produced == null) {
            return Result.Failed(
                "The model did not return a usable digest. Nothing was archived, so no " +
                    "history has been lost.",
                detail = failure,
            )
        }

        val merged = previous?.mergedWith(produced) ?: produced
        // What the window gives back: the archived messages, less what the
        // summary grew by over the one it replaces. Pinned messages stay, and
        // so do the ones the plan keeps; the summary still read them.
        val archivedTokens = span.filterNot { it.pinned || it.seq in plan.keepSeqs }.sumOf { it.tokens.toLong() }
        val previousTokens = previous?.let { NativeCore.estimateTokens(it.render()).toLong() } ?: 0L
        val drafted = merged.copy(
            coversFromSeq = previous?.coversFromSeq ?: plan.archiveFromSeq,
            coversToSeq = plan.archiveToSeq,
            createdAt = System.currentTimeMillis(),
            model = model.id,
            trigger = trigger,
            focus = focus?.trim()?.takeIf { it.isNotEmpty() },
        )
        val digestTokens = NativeCore.estimateTokens(drafted.render())
        val gain = archivedTokens + previousTokens - digestTokens
        if (gain < minGain) {
            Log.i(TAG, "digest of session $sessionId not kept: frees $gain token(s), wanted $minGain")
            return Result.Skipped(NOT_SHORTER)
        }
        val finalDigest = drafted.copy(tokensFreed = gain)

        // Both or neither: a digest saved without its messages archived would
        // go into every request beside the very messages it summarises.
        val (digestId, archived) = withContext(NonCancellable) {
            val id = store.saveDigest(sessionId, finalDigest)
            id to archiveRuns(sessionId, plan, id)
        }

        return Result.Compacted(
            digest = finalDigest,
            digestId = digestId,
            archivedMessages = archived.archived,
            tokensFreed = gain,
            digestTokens = digestTokens,
        )
    }

    /**
     * Ask the model for what the digest does not have yet: within the
     * agent's own request first, then, if that cannot be sent or brings no
     * summary, with a transcript of [span]. Returns the digest, or why there
     * is none.
     */
    private suspend fun requestDigest(
        span: List<Message>,
        endpoint: Endpoint,
        model: ModelSpec,
        previous: ConversationDigest?,
        focus: String?,
        within: Within?,
    ): Pair<ConversationDigest?, String?> {
        val quick = within?.let { askWithin(it) }
        quick?.first?.let { return it to null }
        quick?.second?.let { Log.i(TAG, "summary within the request failed ($it); asking with a transcript") }
        val (digest, failure) = askWithTranscript(span, endpoint, model, previous, focus)
        if (digest != null) return digest to null
        return null to listOfNotNull(quick?.second, failure).joinToString("; then ")
    }

    /** The summary asked at the end of the agent's own request, and once more if the reply held none. */
    private suspend fun askWithin(within: Within): Pair<ConversationDigest?, String?> {
        var nudge: String? = null
        var failure = "the model answered without the summary"
        repeat(2) {
            val request = CompactionAsk.within(within.request, within.promptTokens, nudge)
                ?: return null to "the conversation was too long to ask within it"
            val reply = send(request)
            reply.error?.let { return null to it }
            CompactionAsk.parse(reply.text)?.let { return it to null }
            if (reply.cutOff) failure = "the summary was cut off at the output limit"
            nudge = if (reply.cutOff) CompactionAsk.CUT_NUDGE else CompactionAsk.NUDGE
        }
        return null to failure
    }

    /**
     * The summary asked with a transcript of [span] in a request of its own,
     * cut to fit the window ([CompactionAsk.fitted]); cut further when the
     * provider still finds it too long, and asked once more if the reply held
     * no summary.
     */
    private suspend fun askWithTranscript(
        span: List<Message>,
        endpoint: Endpoint,
        model: ModelSpec,
        previous: ConversationDigest?,
        focus: String?,
    ): Pair<ConversationDigest?, String?> {
        val system = SystemPrompt.compactionInstruction(focus)
        val header = "## Conversation span to digest (turns ${span.first().seq}–${span.last().seq})\n\n"
        val previousBlock = previous?.let { "\n\n## Previous digest, to merge into\n\n${it.render()}" }.orEmpty()
        val output = CompactionAsk.OUTPUT_TOKENS.coerceAtMost(model.replyCap)
        val around = NativeCore.estimateTokens(system) + NativeCore.estimateTokens(header + previousBlock) +
            NativeCore.estimateTokens(CompactionAsk.NUDGE)
        // What the agent's own requests are kept to (ModelSpec.promptBudget,
        // OpenCode's ceiling, which a transcript is fitted into there too):
        // within the input limit, which Kiro refuses past whatever the reply
        // asks, with 15% of it left for this reply.
        var room = model.promptBudget - around
        var level = 0
        var shrinks = 0
        var nudge: String? = null
        var failure = "the model answered without the summary"
        while (true) {
            val fitted = CompactionAsk.fitted(span, room, from = level)
                ?: return null to "not even the latest message of the span fits the window"
            level = fitted.level
            val request = CompletionRequest(
                endpoint = endpoint,
                model = model,
                apiKey = null, // resolved by LlmClient
                system = system,
                messages = listOf(
                    Message(
                        id = "compaction-input",
                        sessionId = "compaction",
                        role = Role.USER,
                        content = listOf(Block.Text(header + fitted.text + previousBlock + nudge?.let { "\n\n$it" }.orEmpty())),
                    ),
                ),
                tools = emptyList(),
                thinking = ThinkingMode.OFF,
                reasoningEffort = CompactionAsk.quickest(model),
                maxOutputTokens = output,
                stream = true,
            )
            val reply = send(request)
            val error = reply.error
            when {
                // The estimate ran low, or Kiro measures the body in bytes:
                // the next level down, and past the last a smaller room.
                error != null && reply.tooLong && level < CompactionAsk.LEVELS.size -> level++
                error != null && reply.tooLong && shrinks < MAX_SHRINKS -> {
                    shrinks++
                    room = room * 7 / 10
                }
                error != null -> return null to error
                else -> {
                    CompactionAsk.parse(reply.text)?.let { return it to null }
                    if (reply.cutOff) failure = "the summary was cut off at the output limit"
                    if (nudge != null) return null to failure
                    nudge = if (reply.cutOff) CompactionAsk.CUT_NUDGE else CompactionAsk.NUDGE
                }
            }
        }
    }

    /** What a summary request brought back. */
    private class Reply(val text: String, val cutOff: Boolean, val error: String?, val tooLong: Boolean)

    /**
     * Send [request] and keep the text of the reply. Calls are not read: no
     * tool is asked for, and a model that calls one anyway gets the nudge.
     */
    private suspend fun send(request: CompletionRequest): Reply {
        val text = StringBuilder()
        var finish: String? = null
        var failed: LlmError? = null
        var thrown: String? = null
        try {
            llm.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> text.append(event.text)
                    is StreamEvent.Done -> finish = event.finishReason
                    is StreamEvent.Failed -> failed = event.error
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            // Stopped is not failed: the emergency trim follows a failure.
            throw cancelled
        } catch (t: Throwable) {
            thrown = t.message ?: t.javaClass.simpleName
        }
        val error = failed?.message ?: thrown
        error?.let { Log.w(TAG, "summary request failed: $it") }
        return Reply(
            text = text.toString(),
            cutOff = StopReasons.outputLimit(finish),
            error = error,
            tooLong = failed?.kind == LlmError.Kind.CONTEXT_OVERFLOW,
        )
    }

    /** Each entry with the turn it was learned by, where the model gave none. */
    private fun ConversationDigest.stamped(seq: Long): ConversationDigest = copy(
        decisions = decisions.map { if (it.atSeq == 0L) it.copy(atSeq = seq) else it },
        fileMap = fileMap.map { if (it.lastReadSeq == 0L) it.copy(lastReadSeq = seq) else it },
        edits = edits.map { if (it.atSeq == 0L) it.copy(atSeq = seq) else it },
        commands = commands.map { if (it.atSeq == 0L) it.copy(atSeq = seq) else it },
        failedApproaches = failedApproaches.map { if (it.atSeq == 0L) it.copy(atSeq = seq) else it },
    )

    /**
     * Last resort when the digest request itself cannot succeed — the endpoint
     * is down, or the span is so large that summarising it also overflows.
     *
     * Archives the oldest turns with a placeholder digest that says plainly that
     * detail was lost and points at `conversation_search`. Losing the summary is
     * bad; being unable to send another request at all is worse.
     */
    suspend fun emergencyTrim(
        sessionId: String,
        plan: ContextBudget.Plan,
        reason: String,
        /** The window the plan was made from, for what the trim gives back. */
        messages: List<Message> = emptyList(),
        /** The summary there is, which the notice merges into. */
        previous: ConversationDigest? = null,
    ): Result {
        if (plan.isNoop) return Result.Skipped(plan.reason)

        val span = messages.filter {
            it.seq in plan.archiveFromSeq..plan.archiveToSeq && !it.pinned && it.seq !in plan.keepSeqs
        }
        val removed = "Turns ${plan.archiveFromSeq}–${plan.archiveToSeq} were removed " +
            "from the context window without being summarised, because the summarising " +
            "request could not be made: $reason.\n\n" +
            "The full text of those turns is still stored and indexed. Use " +
            "conversation_search to find anything from them, and conversation_read to " +
            "read it verbatim. Do not assume a topic was never discussed without " +
            "searching first."
        val notice = ConversationDigest(
            coversFromSeq = plan.archiveFromSeq,
            coversToSeq = plan.archiveToSeq,
            createdAt = System.currentTimeMillis(),
            // After where things stood at the last summary, not in its place.
            currentState = listOfNotNull(previous?.currentState?.takeIf { it.isNotBlank() }, removed).joinToString("\n\n"),
        )
        // Into the summary there was, as a compaction merges: standing alone,
        // the notice replaced it, and everything earlier summaries had kept
        // left the prompt with it.
        val merged = previous?.mergedWith(notice) ?: notice.copy(objective = "(not summarised)")
        val previousTokens = previous?.let { NativeCore.estimateTokens(it.render()).toLong() } ?: 0L
        val drafted = merged.copy(
            coversFromSeq = previous?.coversFromSeq ?: plan.archiveFromSeq,
            coversToSeq = plan.archiveToSeq,
            trigger = ConversationDigest.TRIGGER_AUTO,
            focus = null,
        )
        val digestTokens = NativeCore.estimateTokens(drafted.render())
        val placeholder = drafted.copy(
            tokensFreed = (span.sumOf { it.tokens.toLong() } + previousTokens - digestTokens).coerceAtLeast(0),
        )
        val (digestId, archived) = withContext(NonCancellable) {
            val id = store.saveDigest(sessionId, placeholder)
            id to archiveRuns(sessionId, plan, id)
        }
        Log.w(TAG, "emergency trim of session $sessionId: $reason")
        return Result.Compacted(
            digest = placeholder,
            digestId = digestId,
            archivedMessages = archived.archived,
            tokensFreed = placeholder.tokensFreed,
            digestTokens = digestTokens,
        )
    }

    /**
     * Archive [plan]'s range under [digestId], run by run around the messages
     * it keeps ([ContextBudget.Plan.archiveRuns]). Called inside the
     * NonCancellable block that saved the digest: both or neither.
     */
    private suspend fun archiveRuns(sessionId: String, plan: ContextBudget.Plan, digestId: String): ConversationStore.ArchiveResult {
        var archived = 0
        var freed = 0L
        for (run in plan.archiveRuns) {
            val result = store.archive(
                sessionId = sessionId,
                fromSeq = run.first,
                toSeq = run.last,
                digestId = digestId,
                keepPinned = true,
            )
            archived += result.archived
            freed += result.tokensFreed
        }
        return ConversationStore.ArchiveResult(archived, freed)
    }

    /**
     * The digest as a message for the prompt.
     *
     * Given its own role so the prompt builder can place it ahead of the live
     * window and the UI can draw a seam where the history was compacted.
     */
    fun asMessage(sessionId: String, digest: ConversationDigest): Message {
        val rendered = digest.render()
        return Message(
            id = "digest-v${digest.version}",
            sessionId = sessionId,
            seq = -1,
            role = Role.DIGEST,
            status = MessageStatus.COMPLETE,
            createdAt = digest.createdAt,
            content = listOf(Block.Text(rendered)),
            tokens = NativeCore.estimateTokens(rendered),
            pinned = true,
        )
    }

    /**
     * Full-text recall: archived turns relevant to what the user just asked.
     *
     * The digest is a lossy summary by construction, so this is the second half
     * of the mechanism — it pulls back the handful of archived messages that
     * actually bear on the current question, which is how a compacted session
     * can still quote an exact error from two hundred turns ago.
     */
    suspend fun recall(
        sessionId: String,
        query: String,
        limit: Int,
    ): Message? {
        if (limit <= 0 || query.isBlank()) return null

        val hits = runCatching {
            store.search(query, sessionId, limit = limit, archivedOnly = true)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

        val body = buildString {
            appendLine("# Recalled from compacted history")
            appendLine()
            appendLine(
                "These turns are no longer in the context window but match the current " +
                    "request. Read them before concluding something was not discussed.",
            )
            appendLine()
            for (hit in hits) {
                appendLine("- turn ${hit.seq} (${hit.role}), id ${hit.messageId}:")
                appendLine("  ${hit.snippet.replace("\n", " ")}")
            }
            appendLine()
            appendLine("Use conversation_read to see any of these in full.")
        }

        return Message(
            id = "recall-${System.currentTimeMillis()}",
            sessionId = sessionId,
            seq = -1,
            role = Role.DIGEST,
            content = listOf(Block.Text(body)),
            tokens = NativeCore.estimateTokens(body),
        )
    }

    companion object {
        private const val TAG = "Compactor"

        /** [Result.Skipped]'s reason when the summary would give back less than asked. */
        const val NOT_SHORTER = "the summary is no shorter than what it replaces"

        /**
         * /compact: below this much conversation since the last summary there
         * is nothing worth folding, and the command says so instead of
         * spending a request; and a summary that gives back less than
         * [MIN_MANUAL_GAIN] is not kept.
         */
        const val MIN_MANUAL_TOKENS = 2_000
        const val MIN_MANUAL_GAIN = 500

        /** Times a transcript past the last level is cut by a third more when still refused as too long. */
        private const val MAX_SHRINKS = 2
    }
}
