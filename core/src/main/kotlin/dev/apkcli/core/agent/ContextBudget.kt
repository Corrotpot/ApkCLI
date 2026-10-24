package dev.apkcli.core.agent

import dev.apkcli.core.model.ContextSettings
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Role
import dev.apkcli.core.nativebridge.NativeCore

/**
 * Decides what fits in the next request, and what to do when it does not.
 *
 * With a million-token window the naive approach — send everything until the
 * provider complains — fails in three specific ways:
 *
 *   1. The failure arrives as a 400 *after* the user waited, and the turn is
 *      lost. There is no partial success.
 *   2. Cost grows quadratically. Every turn re-sends the whole history, so a
 *      200-turn session at 800k tokens per request is enormously more expensive
 *      than the same work with compaction.
 *   3. Attention degrades well before the hard limit. A model given 900k tokens
 *      of history reliably loses details from the middle.
 *
 * So the window is budgeted explicitly, measured before each request, and
 * compacted at thresholds that leave room to recover.
 */
class ContextBudget(private val settings: ContextSettings) {

    /**
     * Where the window is going.
     *
     * Broken down rather than reported as one number because the breakdown is
     * what tells you whether to compact, trim tool results, or just drop a few
     * turns — and it is shown in the UI, where "60% used, of which 40% is one
     * build log" is actionable and "60% used" is not.
     */
    data class Accounting(
        val systemTokens: Int,
        val toolSchemaTokens: Int,
        val digestTokens: Int,
        val memoryTokens: Int,
        val liveMessageTokens: Int,
        val reserveTokens: Int,
        val maxOutputTokens: Int,
        val contextWindow: Int,
    ) {
        /** Tokens the next request will actually send. */
        val promptTokens: Int
            get() = systemTokens + toolSchemaTokens + digestTokens + memoryTokens + liveMessageTokens

        /**
         * Tokens available for the prompt: the window minus the reply we asked
         * for, minus a safety margin for estimator error.
         */
        val promptBudget: Int
            get() = (contextWindow - maxOutputTokens - reserveTokens).coerceAtLeast(2_048)

        val fraction: Float
            get() = if (promptBudget <= 0) 1f else promptTokens.toFloat() / promptBudget

        val remaining: Int get() = (promptBudget - promptTokens).coerceAtLeast(0)

        /**
         * The part of the prompt that compaction can actually shrink.
         *
         * The system prompt and tool schemas are fixed overhead — if they alone
         * exceed the window, compaction cannot help and the real problem is the
         * model choice.
         */
        val fixedOverhead: Int get() = systemTokens + toolSchemaTokens

        fun describe(): String = buildString {
            append("${promptTokens / 1000}k/${promptBudget / 1000}k")
            append(" (${(fraction * 100).toInt()}%)")
        }
    }

    fun account(
        systemPrompt: String,
        toolSchemaTokens: Int,
        digest: String?,
        memory: String?,
        messages: List<Message>,
        model: ModelSpec,
        maxOutputTokens: Int,
    ): Accounting = Accounting(
        systemTokens = NativeCore.estimateTokens(systemPrompt),
        toolSchemaTokens = toolSchemaTokens,
        digestTokens = digest?.let { NativeCore.estimateTokens(it) } ?: 0,
        memoryTokens = memory?.let { NativeCore.estimateTokens(it) } ?: 0,
        liveMessageTokens = messages.sumOf { it.tokens },
        reserveTokens = settings.safetyReserveTokens,
        maxOutputTokens = maxOutputTokens.coerceAtMost(model.maxOutputTokens),
        contextWindow = model.contextWindow,
    )

    /** Compact before the next request. */
    fun needsCompaction(accounting: Accounting): Boolean =
        accounting.fraction >= settings.hardThreshold

    /**
     * Start preparing a digest in the background.
     *
     * Done ahead of the hard threshold so the summarising request — itself a
     * full round trip to the model — happens while the user is reading the
     * previous answer, not while they are waiting for the next one.
     */
    fun shouldPrepareEarly(accounting: Accounting): Boolean =
        accounting.fraction >= settings.softThreshold

    /**
     * Unrecoverable: fixed overhead alone leaves no room to work.
     *
     * Happens when a small-window model is paired with the full tool set. The
     * honest response is to tell the user to pick a bigger model or a narrower
     * agent, not to silently drop tools.
     */
    fun isHopeless(accounting: Accounting): Boolean =
        accounting.fixedOverhead > accounting.promptBudget * 0.7f

    fun hopelessExplanation(accounting: Accounting): String =
        "The system prompt and tool definitions alone need " +
            "${accounting.fixedOverhead} tokens of a ${accounting.promptBudget} token " +
            "budget, leaving no room for a conversation. Either choose a model with a " +
            "larger context window, or switch to an agent with fewer tools (the Explorer " +
            "agent declares about a third as many)."

    /**
     * What to archive and what to keep.
     *
     * The split is by turn boundary, not by message: archiving an assistant
     * message while keeping the tool results that answer it leaves orphaned
     * `tool` messages referencing a call the provider cannot see, which is a
     * hard 400 on both protocols. A turn here means "a user message and
     * everything that followed it until the next user message".
     */
    data class Plan(
        val archiveFromSeq: Long,
        val archiveToSeq: Long,
        val archivedCount: Int,
        val keptCount: Int,
        val estimatedTokensFreed: Int,
        val reason: String,
    ) {
        val isNoop: Boolean get() = archivedCount == 0
    }

    fun planCompaction(allMessages: List<Message>, accounting: Accounting): Plan {
        // Drop the synthetic entries the prompt builder prepends — the rendered
        // digest and the FTS recall block. They carry `seq = -1` because they
        // are not rows in the store.
        //
        // Letting them through is subtly expensive. `archivable.first().seq`
        // becomes -1, so the archive range starts below every real message; in
        // the common case `toSeq` still lands on a real message and it happens
        // to work, but when the only archivable candidate *is* a synthetic one
        // the range becomes [-1, -1], the store archives nothing, and the plan
        // is not a no-op — so the agent loop compacts again on the next
        // iteration, burning a summarising request each time until it hits the
        // iteration cap.
        val messages = allMessages.filter { it.seq >= 0 }

        if (messages.isEmpty()) {
            return Plan(0, -1, 0, 0, 0, "nothing to compact")
        }

        // Turn boundaries, by index.
        val turnStarts = messages.indices.filter { messages[it].role == Role.USER }
        if (turnStarts.size <= settings.keepRecentTurns + 1) {
            return Plan(
                0, -1, 0, messages.size, 0,
                "only ${turnStarts.size} turn(s); keeping the most recent " +
                    "${settings.keepRecentTurns} leaves nothing to archive",
            )
        }

        // Keep the last N turns verbatim; everything before the start of that
        // span is a candidate.
        val keepFromIndex = turnStarts[turnStarts.size - settings.keepRecentTurns]
        val candidates = messages.subList(0, keepFromIndex)

        // Pinned messages stay regardless — the store honours this too, but the
        // token estimate here has to agree or the UI shows the wrong forecast.
        val archivable = candidates.filterNot { it.pinned }
        if (archivable.isEmpty()) {
            return Plan(0, -1, 0, messages.size, 0, "all older messages are pinned")
        }

        return Plan(
            archiveFromSeq = archivable.first().seq,
            archiveToSeq = archivable.last().seq,
            archivedCount = archivable.size,
            keptCount = messages.size - archivable.size,
            estimatedTokensFreed = archivable.sumOf { it.tokens },
            reason = "at ${(accounting.fraction * 100).toInt()}% of the prompt budget; " +
                "archiving ${archivable.size} message(s) across " +
                "${turnStarts.size - settings.keepRecentTurns} turn(s)",
        )
    }

    /**
     * What /compact archives: every live message, the latest turns too,
     * pinned ones aside, which the store keeps whatever the range. The user
     * asked for all of it to become the summary; keeping the recent turns is
     * the app's own rule when it compacts by itself ([planCompaction]).
     */
    fun planWhole(allMessages: List<Message>): Plan {
        val messages = allMessages.filter { it.seq >= 0 }
        val archivable = messages.filterNot { it.pinned }
        if (archivable.isEmpty()) return Plan(0, -1, 0, messages.size, 0, "nothing to compact")
        return Plan(
            archiveFromSeq = archivable.first().seq,
            archiveToSeq = archivable.last().seq,
            archivedCount = archivable.size,
            keptCount = messages.size - archivable.size,
            estimatedTokensFreed = archivable.sumOf { it.tokens },
            reason = "on request: archiving ${archivable.size} message(s)",
        )
    }

    /**
     * Would compacting actually help?
     *
     * Guards against the degenerate case where the window is full but almost
     * nothing is archivable — a handful of enormous pinned messages, or one
     * tool result bigger than the whole recent-turn budget. Compacting then
     * costs a summarising request and frees nothing, and the caller would try
     * again on the next iteration.
     */
    fun isWorthCompacting(plan: Plan, accounting: Accounting): Boolean {
        if (plan.isNoop) return false
        // Freeing less than 5% of the budget will not get us under the
        // threshold, so it buys one more iteration at best.
        val meaningful = (accounting.promptBudget * 0.05f).toInt().coerceAtLeast(500)
        return plan.estimatedTokensFreed >= meaningful
    }

    /**
     * Budget for one tool result before it is spilled to a blob.
     *
     * Scaled down as the window fills: early in a session a 4000-token build log
     * is affordable, at 85% full it is not, and truncating it is better than
     * triggering a compaction that throws away the conversation to make room for
     * a log.
     */
    fun toolResultBudget(accounting: Accounting): Int {
        val base = settings.toolResultTokenCap
        return when {
            accounting.fraction < 0.5f -> base
            accounting.fraction < 0.75f -> base / 2
            else -> (base / 4).coerceAtLeast(500)
        }
    }

    /**
     * How many archived messages to pull back in via full-text recall.
     *
     * Zero when the window is already tight: recall is a luxury that costs
     * tokens, and at 90% full the priority is fitting the live conversation.
     */
    fun recallBudget(accounting: Accounting): Int =
        if (accounting.fraction > 0.85f) 0 else settings.recallHits

    companion object {
        /** Mirrors `tokens.rs::TOOL_DEF_OVERHEAD`. */
        const val TOOL_DEF_OVERHEAD = 8
    }
}
