package dev.apkcli.core.llm

import okio.BufferedSource
import java.io.IOException

/**
 * Server-Sent Events reader.
 *
 * Hand-rolled rather than using OkHttp's `EventSource`, for reasons that all
 * come from dealing with real endpoints rather than the spec:
 *
 *   * `EventSource` wants to own reconnection. Reconnecting a half-finished
 *     completion is actively harmful — you get a duplicate partial answer
 *     billed twice.
 *   * Several gateways emit `data:` with no `event:`, some emit bare JSON lines
 *     with no `data:` prefix at all, and some send `[DONE]` with inconsistent
 *     spacing. A strict parser rejects streams that work fine elsewhere.
 *   * Reading from an Okio source directly lets the caller apply its own idle
 *     timeout, which matters because a reasoning model can legitimately send
 *     nothing for half a minute.
 *
 * The parser is deliberately lenient and never throws on a malformed frame; it
 * skips it. A single bad keep-alive comment should not kill a response that is
 * 90% delivered.
 */
object Sse {

    data class Event(
        val type: String?,
        val data: String,
        val id: String? = null,
        val retryMs: Long? = null,
    )

    const val DONE_SENTINEL = "[DONE]"

    /**
     * Read frames until the stream ends.
     *
     * @param onEvent return false to stop reading early (user pressed stop).
     */
    @Throws(IOException::class)
    inline fun read(source: BufferedSource, onEvent: (Event) -> Boolean) {
        var eventType: String? = null
        val data = StringBuilder()
        var lastId: String? = null
        var retry: Long? = null

        while (true) {
            val line = source.readUtf8Line() ?: break

            // Blank line terminates a frame.
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val payload = data.toString().removeSuffix("\n")
                    data.setLength(0)
                    val proceed = onEvent(Event(eventType, payload, lastId, retry))
                    eventType = null
                    retry = null
                    if (!proceed) return
                }
                continue
            }

            // Comment / keep-alive. Many providers send ": ping" every 15s.
            if (line.startsWith(":")) continue

            val colon = line.indexOf(':')
            if (colon < 0) {
                // A field name with no value. Per spec the value is empty; in
                // practice this is also how some servers send bare JSON, so
                // treat a line that looks like JSON as data.
                if (line.startsWith("{") || line.startsWith("[")) {
                    data.append(line).append('\n')
                }
                continue
            }

            val field = line.substring(0, colon)
            // Exactly one optional leading space is stripped, per spec.
            var value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            when (field) {
                "data" -> data.append(value).append('\n')
                "event" -> eventType = value
                "id" -> lastId = value
                "retry" -> retry = value.toLongOrNull()
                else -> Unit // unknown field: ignore, per spec
            }
        }

        // Some servers close without a trailing blank line.
        if (data.isNotEmpty()) {
            onEvent(Event(eventType, data.toString().removeSuffix("\n"), lastId, retry))
        }
    }

    /** True for the terminator, tolerating the spacing variations seen in the wild. */
    fun isDone(data: String): Boolean {
        val trimmed = data.trim()
        return trimmed == DONE_SENTINEL || trimmed == "\"$DONE_SENTINEL\"" || trimmed.equals("done", true)
    }
}
