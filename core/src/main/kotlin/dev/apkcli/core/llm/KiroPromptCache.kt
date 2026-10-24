package dev.apkcli.core.llm

import dev.apkcli.core.model.ModelSpec

/**
 * Prompt caching for Kiro: what keeps a request's prefix reusable, and where
 * its cache points go.
 *
 * Measured against the service (October 2026, claude-opus-5; one agent turn
 * of five requests, sent with and without each part below; the cost of each
 * request in credits from `meteringEvent`, since the stream carries no token
 * counts at all):
 *
 *  * The service caches a repeated prefix on its own. Once a turn's content
 *    has been sent twice, its requests cost 0.12–0.15 credits where a cold one
 *    costs 0.21–0.25.
 *  * Whether there is a prefix to reuse is decided by the system turn staying
 *    the same byte for byte. With the per-request part of the prompt (the
 *    window's fill: `SystemPrompt.Parts.volatile`) left inside it, every
 *    request of the turn cost the cold price, 0.23–0.25 each, about 40% more.
 *  * The cache points made no difference: the same turn cost the same to the
 *    third decimal with and without them, with a short history and with a
 *    55 KB one in it.
 *
 * So what pays is the split, done in [KiroWire.body]: the stable part is the
 * system turn, and the volatile part goes with the current message, after
 * everything a cached prefix could cover. The tool declarations ride on the
 * current message too; they are identical on every request.
 *
 * The points stay, on the system turn and on the last user turn of the
 * history, as Kiro Account Manager sends them (`cachePoint: {type:
 * "default"}`) for a client that asks for caching: they did no harm, and a
 * service that stopped placing its own would need them. Only for a model
 * whose listing says it caches (`promptCaching`, read into
 * [ModelSpec.caching]), at most as many as it allows, and only on a prefix
 * past its minimum length: an unsupported field risks a 400.
 */
internal object KiroPromptCache {

    /** Which turns carry a cache point. */
    class Plan(val system: Boolean, val lastHistoryTurn: Boolean) {
        val any: Boolean get() = system || lastHistoryTurn

        val count: Int get() = (if (system) 1 else 0) + (if (lastHistoryTurn) 1 else 0)
    }

    val NONE = Plan(system = false, lastHistoryTurn = false)

    /**
     * @param systemChars the system prompt's stable part, in characters.
     * @param historyChars everything in `history` after the system turn.
     * @param historyTurns how many turns the history has besides the system one.
     */
    fun plan(model: ModelSpec, systemChars: Int, historyChars: Int, historyTurns: Int): Plan {
        val caching = model.caching ?: return NONE
        if (caching.checkpoints < 1) return NONE
        // Rough on purpose: four characters a token is close enough to decide
        // whether a prefix is long enough to be worth a cache write at all.
        val minimum = caching.minimumTokens.coerceAtLeast(1) * CHARS_PER_TOKEN
        val system = systemChars >= minimum
        // A point on the last history turn covers the system turn and the
        // whole history before it.
        val wholePrefix = systemChars + historyChars >= minimum
        val history = historyTurns > 0 && wholePrefix && caching.checkpoints >= (if (system) 2 else 1)
        return Plan(system = system, lastHistoryTurn = history)
    }

    private const val CHARS_PER_TOKEN = 4
}
