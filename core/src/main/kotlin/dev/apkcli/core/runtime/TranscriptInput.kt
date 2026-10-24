package dev.apkcli.core.runtime

import dev.apkcli.core.sched.TaskClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lines the user types into a chat's transcript — the terminal the agent's
 * commands show in — run the way the agent's are: one command line at a
 * time, on a terminal, shown under the same prompt with how it ended.
 *
 * A transcript has no shell behind it to keep state, so the directory is
 * carried from one line to the next instead: each line reports where it
 * ended up with an `OSC 7`, the convention shells use to tell a terminal,
 * and the next one starts there. `cd src` then `ls` lists `src`.
 *
 * What it is not: an interactive shell. stdin is not the terminal, as for
 * the agent's commands, so a program that waits for keys reads end of
 * input instead of hanging. A shell session is the place for those.
 *
 * Lines run in [scope], the process's, like the sessions themselves: a
 * build typed here goes on when the screen that started it is gone.
 */
class TranscriptInput(
    private val shell: ShellRunner,
    private val terminals: TerminalSessions,
    private val layout: EnvLayout,
    private val scope: CoroutineScope,
) {
    /** Per transcript, where its last line left off. */
    private val cwds = ConcurrentHashMap<String, File>()

    /**
     * Run [line] in transcript [id], then call [onEnded], from a background
     * thread. False, and nothing is run, when [id] is not a transcript.
     */
    fun start(id: String, line: String, onEnded: () -> Unit): Boolean {
        if (terminals.get(id)?.isTranscript != true) return false
        scope.launch(Dispatchers.IO) {
            try {
                run(id, line)
            } finally {
                onEnded()
            }
        }
        return true
    }

    /** Run [line] in transcript [id]; returns when it has ended. */
    suspend fun run(id: String, line: String): Boolean {
        val terminal = terminals.get(id)?.takeIf { it.isTranscript } ?: return false
        val echo = terminal.echo
        val dir = withContext(Dispatchers.IO) {
            cwds[id]?.takeIf { it.isDirectory } ?: File(terminal.workingDir)
        }
        // Only what this line reports counts, not an earlier one's.
        terminal.takeReportedCwd()
        var begun = false
        val result = shell.exec(
            command = reportingCwd(line),
            workingDir = dir,
            timeoutMs = TIMEOUT_MS,
            // The output is shown, not kept: what the capture holds is never read.
            maxOutputBytes = 64 * 1024,
            taskClass = TaskClass.INTERACTIVE,
            terminal = true,
            echo = object : ExecEcho {
                // The line as typed, not the wrapper around it.
                override fun begin(command: String, cwd: String, interrupt: (() -> Unit)?) {
                    begun = true
                    echo.begin(line, cwd, interrupt)
                }

                override fun output(text: String) = echo.output(text)

                override fun end(exitCode: Int?, signal: Int?, timedOut: Boolean, durationMs: Long) {
                    val moved = terminal.takeReportedCwd()?.let(::File)?.takeIf { it.isDirectory }
                    if (moved != null) cwds[id] = moved
                    echo.end(exitCode, signal, timedOut, durationMs, nextCwd = layout.shorten(moved ?: dir))
                }

                override fun detached(id: String) = echo.detached(id)
            },
        )
        // Refused before it started (no shell may run here): said where it was typed.
        if (!begun) {
            echo.begin(line, layout.shorten(dir), null)
            echo.output(result.stderr.ifBlank { result.stdout }.replace("\n", "\r\n") + "\r\n")
            echo.end(result.exitCode, result.signal, result.timedOut, result.durationMs)
        }
        return true
    }

    /** Forget where transcript [id] was, e.g. once it is closed. */
    fun forget(id: String) {
        cwds.remove(id)
    }

    internal companion object {
        /** Long enough for a build or a dev server; a line can always be stopped with Ctrl-C. */
        const val TIMEOUT_MS = 6L * 60 * 60 * 1000

        private const val ESC = '\u001b'
        private const val BEL = '\u0007'

        /**
         * [line], then where it left the shell as `OSC 7`, keeping its exit
         * status. On lines of their own, so a trailing comment or `&` in the
         * user's line ends where it should. The escape characters are in the
         * command itself rather than left to printf's escapes, which differ
         * between the shells this may run under.
         */
        fun reportingCwd(line: String): String =
            line + "\n" +
                "__apkcli_status=\$?\n" +
                "printf '$ESC]7;file://localhost%s$BEL' \"\$PWD\"\n" +
                "exit \$__apkcli_status\n"
    }
}
