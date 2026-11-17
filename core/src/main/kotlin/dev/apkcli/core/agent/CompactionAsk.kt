package dev.apkcli.core.agent

import dev.apkcli.core.llm.CompletionRequest
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import dev.apkcli.core.nativebridge.NativeCore
import kotlinx.serialization.json.Json

/**
 * How [Compactor] asks for a summary, and how it reads the answer.
 *
 * First the way OpenCode does it ([within]): the request the agent was about
 * to send, unchanged, with one more user message asking for the summary. The
 * model writes it from everything it worked with, tool output whole, and a
 * provider that caches prompts by prefix (Kiro does) reads that prefix back
 * instead of again. The answer is the part the digest does not have yet, as
 * one JSON object in the reply's text: short to write, and no forced tool
 * call, which Kiro cannot send and Claude Opus 5.5 and Sonnet 5.5 refuse
 * with a 400. Nor a temperature, which Claude from Opus 4.7 and Sonnet 5 and
 * OpenAI's reasoning models refuse too.
 *
 * When that request would not fit, or fails, a transcript of the span goes
 * in a request of its own ([fitted]), cut down level by level until it fits.
 */
internal object CompactionAsk {

    /**
     * The most a summary may take to write. What is asked for is far
     * shorter; the room is for a model that cannot stop reasoning.
     */
    const val OUTPUT_TOKENS = 16_000

    /** Added to the request when a reply held no summary; asked once. */
    const val NUDGE = "Your reply had no summary object. Do not call tools. " +
        "Reply with only the JSON object, in the format given above."

    /** Added instead when the reply stopped at the output limit. */
    const val CUT_NUDGE = "Your reply was cut off at the output limit. " +
        "Reply with a shorter JSON object: fewer and terser entries."

    /** How much of a message a transcript keeps. */
    data class Clip(val result: Int, val arguments: Int, val thinking: Boolean)

    /** From the most to the least; past the last, the oldest messages are left out ([fitted]). */
    val LEVELS = listOf(
        Clip(result = 2_000, arguments = 400, thinking = true),
        Clip(result = 600, arguments = 400, thinking = false),
        Clip(result = 200, arguments = 200, thinking = false),
        Clip(result = 0, arguments = 100, thinking = false),
    )

    /**
     * [base], the request the agent was about to send, with the summary asked
     * at its end: system prompt, tools and messages unchanged, so its prefix
     * is the one the provider has seen. Tools stay declared because the
     * history calls them; the request says not to call any.
     *
     * Null when it would not fit: the agent's request kept room for the reply
     * ([ModelSpec.replyReserve], the model's whole output cap while that is
     * at most half the window), and with this one more message the input
     * must still leave that room.
     *
     * @param promptTokens what [base] holds, as the budget counted it.
     * @param nudge said after the request on the second asking.
     */
    fun within(base: CompletionRequest, promptTokens: Int, nudge: String? = null): CompletionRequest? {
        val model = base.model
        val ask = SystemPrompt.compactionRequest() + nudge?.let { "\n\n$it" }.orEmpty()
        if (promptTokens + NativeCore.estimateTokens(ask) > model.contextWindow - model.replyReserve) return null
        val message = Message(
            id = "compaction-ask",
            sessionId = base.messages.lastOrNull()?.sessionId.orEmpty(),
            role = Role.USER,
            content = listOf(Block.Text(ask)),
        )
        return base.copy(
            messages = base.messages + message,
            thinking = ThinkingMode.OFF,
            reasoningEffort = quickest(model),
            maxOutputTokens = OUTPUT_TOKENS.coerceAtMost(model.maxOutputTokens),
            temperature = null,
            topP = null,
            stopSequences = emptyList(),
            toolChoice = CompletionRequest.ToolChoice.Auto,
        )
    }

    /**
     * The least reasoning a model that takes named levels in the request can
     * do. For the rest "off" is [ThinkingMode.OFF] with no level, which each
     * adapter turns into its own way of saying it.
     */
    fun quickest(model: ModelSpec): String? =
        if (model.reasoning == ReasoningSupport.EFFORT) model.reasoningEfforts.firstOrNull() else null

    /** [span] as text for a summary, each message cut to [clip]. */
    fun transcript(span: List<Message>, clip: Clip): String = buildString {
        span.forEach { appendMessage(it, clip) }
    }

    /** A transcript, the level it was cut to, and how many of the oldest messages it leaves out. */
    class Fitted(val text: String, val level: Int, val leftOut: Int)

    /**
     * The transcript of [span] at the first level, from [from], whose
     * estimate fits [room] tokens. Past the last level, the newest messages
     * that fit at it, after a line saying how many older ones are left out;
     * [Fitted.level] is then [LEVELS]' size. Null when not even the newest
     * message fits.
     */
    fun fitted(span: List<Message>, room: Int, from: Int = 0): Fitted? {
        for (level in from until LEVELS.size) {
            val text = transcript(span, LEVELS[level])
            if (NativeCore.estimateTokens(text) <= room) return Fitted(text, level, leftOut = 0)
        }
        val clip = LEVELS.last()
        val kept = ArrayDeque<String>()
        var used = 0
        for (message in span.asReversed()) {
            val text = buildString { appendMessage(message, clip) }
            val tokens = NativeCore.estimateTokens(text)
            if (used + tokens > room) break
            kept.addFirst(text)
            used += tokens
        }
        if (kept.isEmpty()) return null
        val leftOut = span.size - kept.size
        val note = "(The $leftOut oldest message(s) of this span are left out for length. They stay in " +
            "the archive, where conversation_search finds them.)\n\n"
        return Fitted(note + kept.joinToString(""), LEVELS.size, leftOut)
    }

    private fun StringBuilder.appendMessage(message: Message, clip: Clip) {
        append("### turn ").append(message.seq).append(" — ")
        appendLine(message.role.name.lowercase())
        for (block in message.content) {
            when (block) {
                is Block.Text -> appendLine(block.text)

                // Reasoning rarely survives usefully into a digest, and it is
                // often the largest part of a turn.
                is Block.Thinking -> if (clip.thinking && !block.redacted && block.text.length < 400) {
                    appendLine("(reasoning) ${block.text}")
                }

                // Which file was edited and what command ran is the durable
                // part, so arguments keep their start.
                is Block.ToolUse -> appendLine("CALL ${block.name} ${head(block.argumentsJson, clip.arguments)}")

                // A result keeps its start and its end: a build log's verdict
                // is at the bottom.
                is Block.ToolResult -> {
                    val status = if (block.isError) "ERROR" else "OK"
                    val body = ends(block.content, clip.result).replace("\n", " ⏎ ")
                    appendLine("RESULT ${block.name} [$status]" + if (body.isEmpty()) "" else " $body")
                }

                is Block.Image -> appendLine("(image attached)")

                is Block.Notice -> when (block.kind) {
                    Block.Notice.OUTPUT_LIMIT -> appendLine("(this reply was cut off at the output limit)")
                    Block.Notice.FAILED -> appendLine("(this request failed: ${head(block.text.orEmpty(), 400)})")
                    else -> Unit
                }
            }
        }
        appendLine()
    }

    /** The first [n] characters of [text], not ending inside a surrogate pair. */
    private fun head(text: String, n: Int): String {
        if (n <= 0) return ""
        if (text.length <= n) return text
        val end = if (Character.isHighSurrogate(text[n - 1])) n - 1 else n
        return text.substring(0, end)
    }

    /** [text] whole when it is at most [n] characters, else its two ends with the middle marked. */
    private fun ends(text: String, n: Int): String {
        if (n <= 0) return ""
        if (text.length <= n) return text
        var tailStart = text.length - n / 2
        if (Character.isLowSurrogate(text[tailStart])) tailStart++
        return head(text, n / 2) + " … " + text.substring(tailStart)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    /** Objects tried in one reply before it counts as holding none. */
    private const val CANDIDATES = 8

    /**
     * The summary in a reply's text: the first JSON object there that reads
     * as a digest with an objective or a state, wherever it stands, fenced or
     * among words. What only the app sets is dropped, so a reply cannot set
     * it. Null when there is none, as when a cut-off reply ends inside it.
     */
    fun parse(text: String): ConversationDigest? {
        var from = 0
        repeat(CANDIDATES) {
            val range = objectIn(text, from) ?: return null
            val candidate = text.substring(range.first, range.last + 1).replace(TRAILING_COMMA, "$1")
            val digest = runCatching { json.decodeFromString(ConversationDigest.serializer(), candidate) }.getOrNull()
            if (digest != null && (digest.objective.isNotBlank() || digest.currentState.isNotBlank())) {
                return digest.copy(
                    version = 1,
                    coversFromSeq = 0,
                    coversToSeq = 0,
                    createdAt = 0,
                    model = null,
                    trigger = null,
                    focus = null,
                    tokensFreed = 0,
                )
            }
            from = range.first + 1
        }
        return null
    }

    /** Where the first whole `{…}` at or after [from] lies; braces inside strings do not count. */
    fun objectIn(text: String, from: Int = 0): IntRange? {
        val start = text.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return start..i
                }
            }
        }
        return null
    }
}
