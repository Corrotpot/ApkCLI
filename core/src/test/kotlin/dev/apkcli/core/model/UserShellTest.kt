package dev.apkcli.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A command the user ran with `!`, as its message keeps it and the chat reads it back: [UserShell]. */
class UserShellTest {

    private fun decoded(text: String): UserShell.Run = checkNotNull(UserShell.decode(text)) { "not a run: $text" }

    private fun roundTrip(run: UserShell.Run): UserShell.Run = decoded(UserShell.encode(run))

    @Test
    fun `the line as typed comes first, then the output in its block`() {
        val text = UserShell.encode(UserShell.Run("git status", "On branch main\n", exitCode = 0, durationMs = 214))
        assertEquals("!git status\n<user_shell exit=\"0\" ms=\"214\">\nOn branch main\n</user_shell>", text)
        val run = decoded(text)
        assertEquals("git status", run.command)
        assertEquals("On branch main", run.output)
        assertEquals(0, run.exitCode)
        assertEquals(214L, run.durationMs)
        assertTrue(run.succeeded)
    }

    @Test
    fun `a multi-line command, and output that prints the tags itself, read back whole`() {
        val command = "for f in a b; do\n  echo \$f\ndone"
        val output = "<user_shell exit=\"1\" ms=\"9\">\nnot this one\n</user_shell>\ntail"
        val run = roundTrip(UserShell.Run(command, output, exitCode = 2, durationMs = 40))
        assertEquals(command, run.command)
        assertEquals(output, run.output)
        assertEquals(2, run.exitCode)
    }

    @Test
    fun `how it ended survives, stopped, timed out or killed, with no output too`() {
        val stopped = roundTrip(UserShell.Run("make", "", exitCode = null, stopped = true, durationMs = 4_100))
        assertNull(stopped.exitCode)
        assertTrue(stopped.stopped)
        assertEquals("", stopped.output)
        assertFalse(stopped.succeeded)

        assertTrue(roundTrip(UserShell.Run("sleep 9", "", exitCode = null, timedOut = true)).timedOut)
        assertEquals(9, roundTrip(UserShell.Run("yes", "y", exitCode = null, signal = 9)).signal)
    }

    @Test
    fun `words that only look like it are words`() {
        assertNull(UserShell.decode("!important: fix the build"))
        assertNull(UserShell.decode("see <user_shell exit=\"0\" ms=\"1\">\nx\n</user_shell>"))
        assertNull(UserShell.decode("!\n<user_shell exit=\"0\" ms=\"1\">\n</user_shell>"))
    }
}
