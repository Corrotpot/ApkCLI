package dev.apkcli.core.agent.tools

import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of http_request and sqlite_query that need no network and no device. */
class ProbeToolsTest {

    @Test
    fun `a URL without a scheme gets http on the phone itself and https elsewhere`() {
        assertEquals("http://localhost:3000/api", HttpRequestTool.withScheme("localhost:3000/api"))
        assertEquals("http://127.0.0.1:8080", HttpRequestTool.withScheme("127.0.0.1:8080"))
        assertEquals("https://api.github.com/users", HttpRequestTool.withScheme("api.github.com/users"))
        assertEquals("http://example.com", HttpRequestTool.withScheme("http://example.com"))
    }

    @Test
    fun `headers are read one per line, and a line that is no header is refused`() {
        val headers = HttpRequestTool.parseHeaders("Authorization: Bearer abc\n\nX-Trace: 1:2")
        assertEquals("Bearer abc", headers?.get("authorization"))
        assertEquals("1:2", headers?.get("X-Trace"))
        assertNull(HttpRequestTool.parseHeaders("just words"))
    }

    @Test
    fun `json, xml and form bodies read as text, an image does not`() {
        assertTrue(HttpRequestTool.textual("application/json; charset=utf-8".toMediaType()))
        assertTrue(HttpRequestTool.textual("application/problem+json".toMediaType()))
        assertTrue(HttpRequestTool.textual("text/html".toMediaType()))
        assertTrue(HttpRequestTool.textual(null))
        assertFalse(HttpRequestTool.textual("image/png".toMediaType()))
    }

    @Test
    fun `a long body is cut, pointing at where the whole of it is`() {
        val reply = HttpRequestTool.Reply(
            status = 201,
            reason = "",
            headers = headersOf("Content-Type", "application/json"),
            text = "x".repeat(500),
            bytes = 500,
            contentType = "application/json",
            cut = false,
            elapsedMs = 12,
        )

        val result = HttpRequestTool.format("POST", "http://localhost:3000/items", reply, maxChars = 100, blobId = "blob-1")

        assertTrue(result.content.startsWith("HTTP 201 Created  (POST http://localhost:3000/items, 12 ms)"))
        assertTrue(result.content.contains("Content-Type: application/json"))
        assertTrue(result.content.contains("--- body (500 characters) ---"))
        assertTrue(result.content.endsWith("[cut at 100 of 500 characters; read the rest with conversation_read(blobId: \"blob-1\")]"))
        assertFalse(result.isError)
    }

    @Test
    fun `rows read as a table and say when there were more`() {
        val text = SqliteQueryTool.render(listOf("id", "name"), listOf(listOf("1", "Ann\nLee"), listOf("2", "NULL")), more = true)

        assertEquals("id | name\n1 | Ann\\nLee\n2 | NULL\n(2 rows shown; there are more: narrow the query or raise maxRows)", text)
        assertEquals("id\n(no rows)", SqliteQueryTool.render(listOf("id"), emptyList(), more = false))
    }
}
