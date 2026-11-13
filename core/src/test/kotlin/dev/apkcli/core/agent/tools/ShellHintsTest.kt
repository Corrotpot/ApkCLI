package dev.apkcli.core.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What [ShellHints.afterRun] adds after a command that Android killed. */
class ShellHintsTest {

    private fun after(exitCode: Int?, signal: Int? = null, timedOut: Boolean = false) =
        ShellHints.afterRun("make", exitCode, stdout = "", stderr = "", canInstall = true, signal = signal, timedOut = timedOut)

    @Test
    fun `a SIGKILL nobody here sent is explained, directly or through a shell`() {
        assertEquals(ShellHints.KILLED, after(exitCode = null, signal = 9))
        assertEquals(ShellHints.KILLED, after(exitCode = 137))
    }

    @Test
    fun `the runner's own kill at the deadline and an ordinary failure are not`() {
        assertNull(after(exitCode = null, signal = 9, timedOut = true))
        assertNull(after(exitCode = 2))
    }
}
