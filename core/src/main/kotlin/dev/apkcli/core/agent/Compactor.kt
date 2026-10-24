package dev.apkcli.core.agent

import android.util.Log
import dev.apkcli.core.llm.CompletionRequest
import dev.apkcli.core.llm.LlmClient
import dev.apkcli.core.llm.StreamEvent
import dev.apkcli.core.llm.ToolDeclaration
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ContextSettings
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Turns a full context window into a digest.
 *
 * The design constraint that shapes everything here: **compaction must not be
 * amnesia.** The naive implementation ("summarise the conversation so far in a
 * paragraph") produces an agent that re-reads files it already read, re-proposes
 * approaches that already failed, and asks the user things they already
 * answered — each of which costs more than the summary saved.
 *
 * Three mechanisms avoid that:
 *
 *  1. **A schema, not prose.** The model fills in
 *     [ConversationDigest] through a forced tool call, so the fields that matter
 *     — failed approaches, decisions with reasons, the file map — cannot be
 *     omitted in favour of a readable narrative.
 *
 *  2. **Cumulative merging.** Each compaction merges into the previous digest
 *     rather than re-summarising from scratch. Repeated lossy passes over the
 *     same text is how detail evaporates; carrying entries forward verbatim is
 *     how it does not.
 *
 *  3. **Nothing is deleted.** Archived messages stay in SQLite and stay indexed
 *     for full-text search. The digest is a fast path; `conversation_search` is
 *     the fallback. That turns "I no longer have that in context" into a lookup
 *     rather than a dead end.
 */
class Compactor(
    private val store: ConversationStore,
    private val llm: LlmClient,
    private val settings: ContextSettings,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

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
    ): Result {
        if (plan.isNoop) return Result.Skipped(plan.reason)

        val span = messages.filter { it.seq in plan.archiveFromSeq..plan.archiveToSeq }
        if (span.isEmpty()) return Result.Skipped("no messages in the planned range")

        Log.i(TAG, "compacting ${span.size} message(s) of session $sessionId")

        val (produced, failure) = requestDigest(span, endpoint, model, previous, focus)
        if (produced == null) {
            return Result.Failed(
                "The model did not return a usable digest. Nothing was archived, so no " +
                    "history has been lost.",
                detail = failure,
            )
        }

        val merged = previous?.mergedWith(produced) ?: produced
        // What the window gives back: the archived messages, less what the
        // summary grew by over the one it replaces. Pinned messages stay.
        val archivedTokens = span.filterNot { it.pinned }.sumOf { it.tokens.toLong() }
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
            id to store.archive(
                sessionId = sessionId,
                fromSeq = plan.archiveFromSeq,
                toSeq = plan.archiveToSeq,
                digestId = id,
                keepPinned = true,
            )
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
     * Ask the model to fill in the digest schema.
     *
     * Uses a forced tool call rather than "reply with JSON". Forcing the call
     * means the provider validates the shape, and a model that would otherwise
     * wrap its answer in prose or a code fence cannot.
     */
    private suspend fun requestDigest(
        span: List<Message>,
        endpoint: Endpoint,
        model: ModelSpec,
        previous: ConversationDigest?,
        focus: String?,
    ): Pair<ConversationDigest?, String?> {
        val tool = ToolDeclaration(
            name = DIGEST_TOOL,
            description = "Record the structured digest of this conversation span.",
            parameters = runCatching {
                json.parseToJsonElement(ConversationDigest.REQUEST_SCHEMA).jsonObject
            }.getOrElse { return null to "the digest schema did not parse" },
        )

        val transcript = renderTranscript(span)
        val previousBlock = previous?.let {
            "\n\n## Previous digest, to merge into\n\n${it.render()}"
        } ?: ""

        val request = CompletionRequest(
            endpoint = endpoint,
            model = model,
            apiKey = null, // resolved by LlmClient
            system = SystemPrompt.compactionInstruction(focus),
            messages = listOf(
                Message(
                    id = "compaction-input",
                    sessionId = "compaction",
                    role = Role.USER,
                    content = listOf(
                        Block.Text(
                            "## Conversation span to digest (turns " +
                                "${span.first().seq}–${span.last().seq})\n\n$transcript$previousBlock",
                        ),
                    ),
                ),
            ),
            tools = listOf(tool),
            // Reasoning off: this is a structured extraction task, and a
            // reasoning pass over a 600k-token transcript costs more than the
            // compaction saves.
            thinking = ThinkingMode.OFF,
            maxOutputTokens = DIGEST_OUTPUT_TOKENS.coerceAtMost(model.maxOutputTokens),
            temperature = 0.0,
            stream = true,
            toolChoice = CompletionRequest.ToolChoice.Required(DIGEST_TOOL),
        )

        val arguments = StringBuilder()
        var failed: String? = null

        try {
            llm.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.ToolCallArgsDelta -> arguments.append(event.fragment)
                    is StreamEvent.Failed -> failed = event.error.message
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            // Stopped is not failed. runCatching took a stop for the model's
            // failure, and a failure is what the emergency trim follows.
            throw cancelled
        } catch (t: Throwable) {
            failed = t.message ?: t.javaClass.simpleName
        }

        failed?.let { reason ->
            Log.w(TAG, "digest request failed: $reason")
            return null to reason
        }
        if (arguments.isBlank()) {
            Log.w(TAG, "digest request produced no tool arguments")
            return null to "the model answered without the summary"
        }

        var parseError: String? = null
        val digest = runCatching {
            json.decodeFromString(ConversationDigest.serializer(), arguments.toString())
        }.getOrElse { error ->
            Log.w(TAG, "digest did not parse: ${error.message}")
            parseError = error.message
            // One salvage attempt: a model that emitted a fenced block or a
            // trailing comma still produced usable content.
            val salvaged = arguments.toString()
                .removePrefix("```json").removePrefix("```").removeSuffix("```")
                .trim()
                .replace(Regex(",\\s*([}\\]])"), "$1")
            runCatching {
                json.decodeFromString(ConversationDigest.serializer(), salvaged)
            }.getOrNull()
        }
        return digest to if (digest == null) "the summary did not parse: $parseError" else null
    }

    /**
     * Flatten the span for the summarising request.
     *
     * Tool *results* are heavily clipped: a 40k-token build log contributes
     * nothing to a digest beyond "the build failed on X", and including it in
     * full would make the compaction request itself overflow the window — the
     * exact problem being solved. Tool *arguments* are kept, because which file
     * was edited and what command was run is precisely the durable information.
     */
    private fun renderTranscript(span: List<Message>): String = buildString {
        for (message in span) {
            append("### turn ").append(message.seq).append(" — ")
            appendLine(message.role.name.lowercase())
            for (block in message.content) {
                when (block) {
                    is Block.Text -> appendLine(block.text)

                    is Block.Thinking -> {
                        // Reasoning rarely survives usefully into a digest, and
                        // it is often the largest part of a turn.
                        if (block.text.length < 400) appendLine("(reasoning) ${block.text}")
                    }

                    is Block.ToolUse ->
                        appendLine("CALL ${block.name} ${block.argumentsJson.take(TOOL_ARG_CLIP)}")

                    is Block.ToolResult -> {
                        val status = if (block.isError) "ERROR" else "OK"
                        appendLine(
                            "RESULT ${block.name} [$status] " +
                                block.content.take(TOOL_RESULT_CLIP).replace("\n", " ⏎ "),
                        )
                    }

                    is Block.Image -> appendLine("(image attached)")

                    is Block.Notice -> when (block.kind) {
                        Block.Notice.OUTPUT_LIMIT -> appendLine("(this reply was cut off at the output limit)")
                        Block.Notice.FAILED -> appendLine("(this request failed: ${block.text.orEmpty().take(TOOL_ARG_CLIP)})")
                        else -> Unit
                    }
                }
            }
            appendLine()
        }
    }

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

        val span = messages.filter { it.seq in plan.archiveFromSeq..plan.archiveToSeq && !it.pinned }
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
            id to store.archive(
                sessionId = sessionId,
                fromSeq = plan.archiveFromSeq,
                toSeq = plan.archiveToSeq,
                digestId = id,
                keepPinned = true,
            )
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
        private const val DIGEST_TOOL = "record_digest"

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

        /** Generous: a thorough digest of 200 turns is not short. */
        private const val DIGEST_OUTPUT_TOKENS = 8_000

        private const val TOOL_ARG_CLIP = 400
        private const val TOOL_RESULT_CLIP = 600
    }
}
