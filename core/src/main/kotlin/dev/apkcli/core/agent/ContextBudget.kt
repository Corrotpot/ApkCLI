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
         * The part of the prompt that compaction cannot shrink.
         *
         * The system prompt with the project's instructions and memory, and
         * the tool schemas, are fixed overhead — if they alone exceed the
         * window, compaction cannot help and the real problem is the model
         * choice.
         */
        val fixedOverhead: Int get() = systemTokens + memoryTokens + toolSchemaTokens

        fun describe(): String = buildString {
            append("${promptTokens / 1000}k/${promptBudget / 1000}k")
            append(" (${(fraction * 100).toInt()}%)")
        }
    }

    /**
     * What the next request holds, part by part, each counted once.
     *
     * @param memory the project's instructions and remembered facts, which
     *   [systemPrompt] carries: counted on their own row and taken out of the
     *   system prompt's. Both rows used to hold them, so the meter read high
     *   by all of it and compaction came early.
     * @param messages the window and any recalled history, without the
     *   digest's message: [digest] is that.
     */
    fun account(
        systemPrompt: String,
        toolSchemaTokens: Int,
        digest: String?,
        memory: String?,
        messages: List<Message>,
        model: ModelSpec,
        maxOutputTokens: Int,
    ): Accounting {
        val memoryTokens = memory?.let { NativeCore.estimateTokens(it) } ?: 0
        return Accounting(
            systemTokens = (NativeCore.estimateTokens(systemPrompt) - memoryTokens).coerceAtLeast(0),
            toolSchemaTokens = toolSchemaTokens,
            digestTokens = digest?.let { NativeCore.estimateTokens(it) } ?: 0,
            memoryTokens = memoryTokens,
            liveMessageTokens = messages.sumOf { it.tokens },
            reserveTokens = settings.safetyReserveTokens,
            maxOutputTokens = maxOutputTokens.coerceAtMost(model.maxOutputTokens),
            contextWindow = model.contextWindow,
        )
    }

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
     * Happens when a small-window model is paired with the full tool set, or
     * with a long instruction file. The honest response is to tell the user
     * to pick a bigger model or carry less, not to silently drop tools; the
     * agents a user can pick all declare the same tools, so a narrower one is
     * not on offer.
     */
    fun isHopeless(accounting: Accounting): Boolean =
        accounting.fixedOverhead > accounting.promptBudget * 0.7f

    fun hopelessExplanation(accounting: Accounting): String =
        "The system prompt, the project's instructions and memory, and the tool " +
            "definitions alone need ${accounting.fixedOverhead} tokens of a " +
            "${accounting.promptBudget} token budget, leaving no room for a conversation. " +
            "Choose a model with a larger context window, or shorten the project's " +
            "instruction file and the remembered facts."

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
        /**
         * Messages inside the range that stay in the window: what the user
         * said in the turn being worked on, when that turn's own oldest
         * rounds are archived ([planDeep]).
         */
        val keepSeqs: Set<Long> = emptySet(),
    ) {
        val isNoop: Boolean get() = archivedCount == 0

        /** [archiveFromSeq]..[archiveToSeq] less [keepSeqs], as the runs the store archives one by one. */
        val archiveRuns: List<LongRange>
            get() {
                if (isNoop) return emptyList()
                val runs = mutableListOf<LongRange>()
                var start = archiveFromSeq
                for (keep in keepSeqs.filter { it in archiveFromSeq..archiveToSeq }.sorted()) {
                    if (keep > start) runs += start until keep
                    start = keep + 1
                }
                if (start <= archiveToSeq) runs += start..archiveToSeq
                return runs
            }
    }

    /**
     * What to archive before the next request.
     *
     * First by whole turns, keeping the latest [ContextSettings.keepRecentTurns]
     * verbatim, as it always was. When that frees too little to bring the
     * window under [TARGET_FRACTION] of the budget — a young chat, or one long
     * chain of tool calls inside a single turn, where the current turn is
     * nearly all of the window — [planDeep] goes past that rule. Without it a
     * turn that read a dozen large logs could not be compacted at all and ran
     * until the provider refused the request.
     *
     * @param keepSeqs what the user said in the turn being worked on: kept
     *   verbatim by [planDeep] even when the rounds around it are archived.
     */
    fun planCompaction(allMessages: List<Message>, accounting: Accounting, keepSeqs: Set<Long> = emptySet()): Plan {
        val messages = allMessages.filter { it.seq >= 0 }
        val byTurns = planByTurns(allMessages, accounting)
        if (messages.isEmpty()) return byTurns
        val need = accounting.promptTokens - (accounting.promptBudget * TARGET_FRACTION).toInt()
        if (!byTurns.isNoop && byTurns.estimatedTokensFreed >= need) return byTurns
        val deep = planDeep(messages, accounting, keepSeqs, need)
        return if (deep.estimatedTokensFreed > byTurns.estimatedTokensFreed) deep else byTurns
    }

    /**
     * Whatever it takes, oldest first, to bring the window down by [need]:
     * older turns whole, then the oldest rounds of the turn being worked on.
     *
     * Kept whatever the need: what the user said in that turn ([keepSeqs], or
     * the last user message when none are given), its latest round of calls
     * with everything after it — the results the model is about to read —
     * and pinned messages. The cut falls only before a user or an assistant
     * message, so calls are never parted from their results.
     */
    private fun planDeep(messages: List<Message>, accounting: Accounting, keepSeqs: Set<Long>, need: Int): Plan {
        val keep = keepSeqs.ifEmpty {
            messages.lastOrNull { it.role == Role.USER }?.let { setOf(it.seq) }.orEmpty()
        }
        val turnStart = messages.indexOfFirst { it.seq in keep }.let { if (it < 0) messages.size else it }
        val lastRound = messages.indexOfLast { it.role == Role.ASSISTANT && it.toolUses.isNotEmpty() }
        val protectFrom = if (lastRound > turnStart) lastRound else turnStart

        var freed = 0
        var archived = 0
        var cut = -1
        var cutFreed = 0
        var cutArchived = 0
        for (index in 0 until protectFrom) {
            val message = messages[index]
            if (message.seq !in keep && !message.pinned) {
                freed += message.tokens
                archived++
            }
            val next = messages.getOrNull(index + 1) ?: break
            if (next.role == Role.USER || next.role == Role.ASSISTANT) {
                cut = index
                cutFreed = freed
                cutArchived = archived
                if (freed >= need) break
            }
        }
        if (cut < 0 || cutArchived == 0) {
            return Plan(0, -1, 0, messages.size, 0, "nothing older than the latest round to archive")
        }
        val from = messages.first().seq
        val to = messages[cut].seq
        val into = if (cut >= turnStart) "the oldest rounds of the current turn among them" else "older turns only"
        return Plan(
            archiveFromSeq = from,
            archiveToSeq = to,
            archivedCount = cutArchived,
            keptCount = messages.size - cutArchived,
            estimatedTokensFreed = cutFreed,
            reason = "at ${(accounting.fraction * 100).toInt()}% of the prompt budget; archiving " +
                "$cutArchived message(s), $into",
            keepSeqs = keep.filterTo(HashSet()) { it in from..to },
        )
    }

    private fun planByTurns(allMessages: List<Message>, accounting: Accounting): Plan {
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

        /**
         * Where a compaction aims to leave the window, as a share of the
         * prompt budget: low enough that the next rounds fit before the hard
         * threshold comes round again, high enough to keep recent work verbatim.
         */
        const val TARGET_FRACTION = 0.6f
    }
}
