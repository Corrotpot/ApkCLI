package dev.apkcli.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** How a remote MCP tool's answer is read: [RemoteMcp.parse] and [RemoteMcp.retryAfterMs]. */
class RemoteMcpTest {

    private val prettyJson = Json { prettyPrint = true }

    /** A JSON-RPC answer whose result has [texts] as its content. */
    private fun answer(vararg texts: String, isError: Boolean = false, structured: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            putJsonObject("result") {
                putJsonArray("content") {
                    texts.forEach { text ->
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                }
                if (isError) put("isError", true)
                structured?.let { put("structuredContent", it) }
            }
        }

    private fun rpcError(message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        putJsonObject("error") {
            put("code", -32000)
            put("message", message)
        }
    }

    @Test
    fun `a plain JSON answer gives its text, joined, and its structured content`() {
        val structured = buildJsonObject { put("session_id", "session_1") }
        val outcome = RemoteMcp.parse(answer("first", "second", structured = structured).toString())
        assertEquals(RemoteMcp.Outcome.Done("first\nsecond", structured), outcome)
    }

    @Test
    fun `an event stream is read for the event that carries the answer`() {
        val note = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/progress")
        }
        val found = answer("found").toString()
        val body = "event: message\ndata: $note\n\nevent: message\nid: 2\ndata: $found\n\n"
        assertEquals(RemoteMcp.Outcome.Done("found", null), RemoteMcp.parse(body))
    }

    @Test
    fun `an event's data on several lines is joined before it is parsed`() {
        val pretty = prettyJson.encodeToString(JsonObject.serializer(), answer("on\nlines"))
        val body = ": keep-alive\n\nevent: message\n" + pretty.lines().joinToString("\n") { "data: $it" } + "\n"
        assertEquals(RemoteMcp.Outcome.Done("on\nlines", null), RemoteMcp.parse(body))
    }

    @Test
    fun `a JSON-RPC error fails, and one about a rate limit asks for a rest`() {
        assertEquals(
            RemoteMcp.Outcome.Failed("Invalid arguments", RemoteMcp.Rest.None),
            RemoteMcp.parse(rpcError("Invalid arguments").toString()),
        )
        assertEquals(
            RemoteMcp.Outcome.Failed("Rate limit exceeded, try later", RemoteMcp.Rest.Asked(null)),
            RemoteMcp.parse(rpcError("Rate limit exceeded, try later").toString()),
        )
    }

    @Test
    fun `a tool error fails with the tool's own words`() {
        val crawl = "Error fetching URL(s) https://example.invalid/x CRAWL_UNKNOWN_ERROR"
        assertEquals(
            RemoteMcp.Outcome.Failed(crawl, RemoteMcp.Rest.None),
            RemoteMcp.parse(answer(crawl, isError = true).toString()),
        )
        assertEquals(
            RemoteMcp.Outcome.Failed("Too many requests from this address", RemoteMcp.Rest.Asked(null)),
            RemoteMcp.parse(answer("Too many requests from this address", isError = true).toString()),
        )
        assertEquals(
            RemoteMcp.Outcome.Failed("the tool failed", RemoteMcp.Rest.None),
            RemoteMcp.parse(answer(isError = true).toString()),
        )
    }

    @Test
    fun `an answer that is not JSON-RPC, or whose result is no object, is a brief failure`() {
        assertEquals(RemoteMcp.Rest.Brief, (RemoteMcp.parse("<html>Bad gateway</html>") as RemoteMcp.Outcome.Failed).rest)
        val odd = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", "text")
        }
        assertEquals(
            RemoteMcp.Outcome.Failed("its result was not an object", RemoteMcp.Rest.Brief),
            RemoteMcp.parse(odd.toString()),
        )
    }

    @Test
    fun `Retry-After counts from now, in seconds or to a date`() {
        val now = Instant.parse("2025-03-01T12:00:00Z").toEpochMilli()
        assertEquals(120_000L, RemoteMcp.retryAfterMs("120", now))
        assertEquals(0L, RemoteMcp.retryAfterMs(" 0 ", now))
        assertEquals(90_000L, RemoteMcp.retryAfterMs("Sat, 01 Mar 2025 12:01:30 GMT", now))
        assertEquals(0L, RemoteMcp.retryAfterMs("Sat, 01 Mar 2025 11:00:00 GMT", now))
        assertNull(RemoteMcp.retryAfterMs(null, now))
        assertNull(RemoteMcp.retryAfterMs("", now))
        assertNull(RemoteMcp.retryAfterMs("soon", now))
    }
}
