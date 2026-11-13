package dev.apkcli.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** What a program asks of the app through its terminal: [TerminalBuffer.takeRequests]. */
class TerminalRequestsTest {

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun `OSC 52 sets the clipboard, and a query for it is not answered`() {
        val buffer = TerminalBuffer()
        buffer.feed("before\u001b]52;c;${b64("ls -la ~/проекты")}\u0007after")
        assertEquals(listOf(TerminalBuffer.Request.Clipboard("ls -la ~/проекты")), buffer.takeRequests())
        buffer.feed("\u001b]52;c;?\u0007")
        assertTrue(buffer.takeRequests().isEmpty())
    }

    @Test
    fun `the app's own request comes with its token and its arguments decoded`() {
        val buffer = TerminalBuffer()
        buffer.feed("\u001b]$TERMINAL_REQUEST_OSC;t0k;notify;${b64("Build done")};${b64("")}\u001b\\")
        assertEquals(listOf(TerminalBuffer.Request.App("t0k", "notify", listOf("Build done", ""))), buffer.takeRequests())
        assertTrue("taken once", buffer.takeRequests().isEmpty())
    }

    @Test
    fun `a request too long to keep whole is dropped, not read cut off`() {
        val buffer = TerminalBuffer()
        buffer.feed("\u001b]52;c;" + "QUJD".repeat(200 * 1024) + "\u0007")
        assertTrue(buffer.takeRequests().isEmpty())
        buffer.feed("\u001b]2;still a title\u0007")
        assertEquals("still a title", buffer.title)
    }
}
