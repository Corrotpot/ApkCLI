package dev.apkcli.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the model was told about a command, read back for its card: [ShellRunner.Result.readBack]. */
class CommandReadBackTest {

    private fun result(
        stdout: String = "",
        exit: Int? = 0,
        signal: Int? = null,
        timedOut: Boolean = false,
        note: String? = null,
    ) = ShellRunner.Result(
        exitCode = exit,
        signal = signal,
        timedOut = timedOut,
        durationMs = 412,
        stdout = stdout,
        stderr = "",
        stdoutTruncated = false,
        stderrTruncated = false,
        command = "x",
        workingDir = "/",
        note = note,
        onTerminal = true,
    )

    @Test
    fun `a command that printed nothing reads back as nothing, not as the model's words`() {
        val printed = checkNotNull(ShellRunner.Result.readBack(result().forModel()))
        assertEquals("", printed.output)
        assertEquals(0, printed.exitCode)
        assertEquals(412L, printed.durationMs)
        assertEquals("only the note", "It left a daemon.", checkNotNull(ShellRunner.Result.readBack(result(note = "It left a daemon.").forModel())).output)
    }

    @Test
    fun `the output alone, though it holds a line like the ending and a hint follows`() {
        val text = result(stdout = "Exit code 9 in 1ms.\nreal output", exit = 2).forModel() + "\n\nInstall it with pkg_install."
        val printed = checkNotNull(ShellRunner.Result.readBack(text))
        assertEquals("Exit code 9 in 1ms.\nreal output", printed.output)
        assertEquals(2, printed.exitCode)
    }

    @Test
    fun `killed and timed out read back as such, and other text as none`() {
        assertEquals(9, ShellRunner.Result.readBack(result(exit = null, signal = 9).forModel())?.signal)
        assertTrue(ShellRunner.Result.readBack(result(exit = null, timedOut = true).forModel())?.timedOut == true)
        assertNull(ShellRunner.Result.readBack("Created a.txt (3 lines)."))
    }
}
