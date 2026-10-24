package dev.apkcli.core.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool calls that arrive as text.
 *
 * Kiro's backend now and then passes a model's tool call through as the
 * XML it was written in — `<function_calls><invoke name="…"><parameter
 * name="…">…</parameter></invoke></function_calls>`, the opening tag
 * sometimes mangled into a bare `count` — inside ordinary text events, and
 * split across them. Shown as text, the call is never made and the turn
 * stops. Kiro Account Manager catches these across frames and makes the
 * calls after all; this does the same, written from that behaviour.
 *
 * Text goes through [accept]; what it returns is the text to show, with any
 * complete call taken out. A tail that could be the start of a call is held
 * back until the next chunk shows whether it is one, so ordinary text is
 * delayed by a few characters at most. Only calls to a [declared] tool are
 * taken: anything else, an example in a reply about XML say, stays text.
 * [flush] at the end of the stream gives back whatever is still held.
 */
internal class KiroToolLeak(private val declared: Set<String>) {

    class Tool(val name: String, val input: JsonObject)

    /** The calls taken out of the text so far, in order. */
    val tools = mutableListOf<Tool>()

    private val carry = StringBuilder()

    fun accept(chunk: String): String {
        carry.append(chunk)
        return drain(flush = false)
    }

    fun flush(): String = drain(flush = true)

    private fun drain(flush: Boolean): String {
        val out = StringBuilder()
        while (true) {
            val text = carry.toString()
            val start = text.indexOf(INVOKE_OPEN)
            if (start < 0 || text.indexOf(INVOKE_CLOSE, start) < 0) break
            val first = INVOKE.matchAt(text, start)
            if (first == null || first.groupValues[1] !in declared) {
                // Not a call this request can make: text, opener and all.
                val upTo = first?.range?.last?.plus(1) ?: (start + INVOKE_OPEN.length)
                out.append(text, 0, upTo)
                carry.delete(0, upTo)
                continue
            }
            out.append(stripOpener(text.substring(0, start)))
            // Calls written one after another are one block.
            var consumed = start
            var match: MatchResult? = first
            while (match != null && match.range.first <= consumed + BLOCK_GAP && match.groupValues[1] in declared) {
                tools += Tool(match.groupValues[1], parameters(match.groupValues[2]))
                consumed = match.range.last + 1
                match = INVOKE.find(text, consumed)
            }
            CLOSE_CALLS.matchAt(text, consumed)?.let { consumed = it.range.last + 1 }
            carry.delete(0, consumed)
        }

        val text = carry.toString()
        // The loop above stopped at the first opener without its close, if any.
        val open = text.indexOf(INVOKE_OPEN)
        when {
            flush -> {
                out.append(text)
                carry.clear()
            }
            open >= 0 && text.indexOf(INVOKE_CLOSE, open) < 0 -> {
                // A call still arriving: show what comes before it.
                val before = stripOpener(text.substring(0, open))
                out.append(before)
                carry.delete(0, before.length)
            }
            else -> {
                val hold = pendingTail(text)
                out.append(text, 0, text.length - hold)
                carry.delete(0, text.length - hold)
            }
        }
        return out.toString()
    }

    companion object {
        private const val INVOKE_OPEN = "<invoke name="
        private const val INVOKE_CLOSE = "</invoke>"

        /** How far apart two calls may be and still be one block. */
        private const val BLOCK_GAP = 30

        private val INVOKE = Regex("""<invoke name="([^"]+)">([\s\S]*?)</invoke>""")
        private val PARAMETER = Regex("""<parameter name="([^"]+)">([\s\S]*?)</parameter>""")
        private val CLOSE_CALLS = Regex("""\s*</function_calls>""")
        private val OPENER = Regex("""(<function_calls>|count)\s*$""")
        private val INTEGER = Regex("""^-?\d+$""")
        private val DECIMAL = Regex("""^-?\d*\.\d+$""")

        private val MARKERS = listOf(
            "<function_calls>", INVOKE_OPEN, INVOKE_CLOSE, "</function_calls>", "<parameter name=", "</parameter>", "count",
        )

        /** [before] without the `<function_calls>` (or its mangled `count`) that opened the call. */
        private fun stripOpener(before: String): String = OPENER.find(before)?.let { before.substring(0, it.range.first) } ?: before

        /**
         * How much of the end of [text] might begin a call: the longest tail
         * that a marker starts with, or `count` with what may follow it.
         */
        private fun pendingTail(text: String): Int {
            var hold = 0
            for (marker in MARKERS) {
                for (k in minOf(text.length, marker.length - 1) downTo 1) {
                    if (text.regionMatches(text.length - k, marker, 0, k)) {
                        hold = maxOf(hold, k)
                        break
                    }
                }
            }
            // "count", spaces, and the beginning of an <invoke.
            Regex("""count\s*(<[a-z ]{0,12})?$""").find(text)?.let { tail ->
                val rest = tail.groupValues[1]
                if (rest.isEmpty() || INVOKE_OPEN.startsWith(rest)) hold = maxOf(hold, tail.value.length)
            }
            return hold
        }

        /** The parameters of a call, typed the way the model meant them: true, 3, 1.5, null, or text. */
        private fun parameters(body: String): JsonObject {
            val values = LinkedHashMap<String, JsonElement>()
            for (match in PARAMETER.findAll(body)) {
                val raw = match.groupValues[2]
                val trimmed = raw.trim()
                values[match.groupValues[1]] = when {
                    trimmed == "true" -> JsonPrimitive(true)
                    trimmed == "false" -> JsonPrimitive(false)
                    trimmed == "null" -> JsonNull
                    INTEGER.matches(trimmed) -> trimmed.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                    DECIMAL.matches(trimmed) -> trimmed.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                    else -> JsonPrimitive(raw)
                }
            }
            return JsonObject(values)
        }
    }
}
