package dev.apkcli.core.web

import dev.apkcli.core.llm.Http
import dev.apkcli.core.llm.executeCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import java.util.concurrent.TimeUnit

/**
 * One tool of a remote MCP server, called the way the free search services
 * take it: a JSON-RPC `tools/call` POSTed with no session, answered with the
 * JSON-RPC message itself or with a server-sent event that carries it. Exa and
 * Parallel answer that with no `initialize` first, so there is no session to
 * keep and no MCP library to bundle for one request and one parse.
 */
internal object RemoteMcp {

    sealed interface Outcome {
        /** [text] is the tool's text content, joined; [structured] its `structuredContent`, when it sent one. */
        data class Done(val text: String, val structured: JsonObject?) : Outcome

        /** The service refused or failed; [rest] says how long to leave it alone. */
        data class Failed(val reason: String, val rest: Rest) : Outcome
    }

    /** How long a failure says to leave the service alone. */
    sealed interface Rest {
        /** The failure was about this request alone: another may go now. */
        data object None : Rest

        /** The service did not answer properly: a timeout, a 5xx, an answer that does not parse. */
        data object Brief : Rest

        /** It refused this device: the free tier is closed to it for now. */
        data object Closed : Rest

        /** It asked to slow down, for [ms] when it said how long. */
        data class Asked(val ms: Long?) : Rest
    }

    suspend fun call(url: String, tool: String, arguments: JsonObject, timeoutMs: Long, maxBytes: Long): Outcome {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", tool)
                put("arguments", arguments)
            }
        }.toString()
        // The whole call, not each read: a service that trickles its answer
        // must not hold the search past its turn.
        val client = Http.client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).executeCancellable { response ->
                val text = readUpTo(response.body.source(), maxBytes)
                    ?: return@executeCancellable Outcome.Failed("its answer was over ${maxBytes / 1024} KB", Rest.None)
                when {
                    response.code == 429 -> Outcome.Failed(
                        "asked to slow down (HTTP 429)",
                        Rest.Asked(retryAfterMs(response.header("Retry-After"), System.currentTimeMillis())),
                    )
                    response.code == 401 || response.code == 403 ->
                        Outcome.Failed("refused this device (HTTP ${response.code})", Rest.Closed)
                    response.code >= 500 -> Outcome.Failed("HTTP ${response.code}", Rest.Brief)
                    !response.isSuccessful -> Outcome.Failed("HTTP ${response.code}: ${text.trim().take(200)}", Rest.None)
                    else -> parse(text)
                }
            }
        } catch (t: Throwable) {
            // Stopped from outside: unwind, it is no failure of the service's.
            currentCoroutineContext().ensureActive()
            Outcome.Failed(Http.classify(t).message, Rest.Brief)
        }
    }

    /** [body], as JSON or as server-sent events, read for the JSON-RPC answer. */
    fun parse(body: String): Outcome {
        val message = messages(body).firstOrNull { it["result"] != null || it["error"] != null }
            ?: return Outcome.Failed("its answer held no JSON-RPC result: ${body.trim().take(200)}", Rest.Brief)
        (message["error"] as? JsonObject)?.let { error ->
            val reason = (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
            return Outcome.Failed(reason.take(300), if (RATE_LIMITED.containsMatchIn(reason)) Rest.Asked(null) else Rest.None)
        }
        val result = message["result"] as? JsonObject
            ?: return Outcome.Failed("its result was not an object", Rest.Brief)
        val text = (result["content"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
            .joinToString("\n")
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
            val reason = text.trim().ifEmpty { "the tool failed" }
            return Outcome.Failed(reason.take(300), if (RATE_LIMITED.containsMatchIn(reason)) Rest.Asked(null) else Rest.None)
        }
        return Outcome.Done(text, result["structuredContent"] as? JsonObject)
    }

    /**
     * The JSON objects in [body]: the body itself when it is one, otherwise
     * the `data` of each server-sent event, its lines joined as the format has it.
     */
    private fun messages(body: String): List<JsonObject> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return listOfNotNull(Http.parseObject(trimmed))
        val found = mutableListOf<JsonObject>()
        val data = StringBuilder()
        fun endEvent() {
            if (data.isEmpty()) return
            Http.parseObject(data.toString())?.let(found::add)
            data.setLength(0)
        }
        for (line in body.lineSequence()) {
            when {
                line.isBlank() -> endEvent()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        endEvent()
        return found
    }

    /** `Retry-After` as milliseconds from [now]: seconds, or an HTTP date. Null when absent or unreadable. */
    fun retryAfterMs(value: String?, now: Long): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
        val date = runCatching { java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) }
            .getOrNull() ?: return null
        return (date.toInstant().toEpochMilli() - now).coerceAtLeast(0)
    }

    /** At most [limit] bytes as UTF-8, or null when there are more. */
    private fun readUpTo(source: BufferedSource, limit: Long): String? {
        val buffer = Buffer()
        while (buffer.size <= limit) {
            if (source.read(buffer, CHUNK_BYTES) == -1L) return buffer.readUtf8()
        }
        return null
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val CHUNK_BYTES = 64L * 1024

    /** Who is asking, for the services' own logs. */
    private const val USER_AGENT = "ApkCLI (+https://github.com/Corrotpot/ApkCLI)"

    /** How a tool error says it was a rate limit; none of them gives a status for it. */
    private val RATE_LIMITED = Regex("rate.?limit|too many requests|quota|throttl", RegexOption.IGNORE_CASE)
}
