package dev.apkcli.core.runtime

/**
 * Where a command the agent runs is also shown as it runs: the "Agent"
 * terminal in the terminal panel, the one a desktop agent's commands would
 * appear in. Each command reads as it would typed at a prompt — the prompt
 * line, everything it printed with its colours and progress bars, and how
 * it ended.
 *
 * Called from the command's own coroutine, one command at a time per echo.
 */
interface ExecEcho {
    /**
     * A command starts in [cwd] (already shortened for display).
     * [interrupt] sends it Ctrl-C; null where it cannot be interrupted.
     */
    fun begin(command: String, cwd: String, interrupt: (() -> Unit)?)

    /** What it printed, raw: escape sequences, `\r` and all. */
    fun output(text: String)

    /** It ended: by itself ([exitCode]), by a signal, by its timeout, or stopped (all null and false). */
    fun end(exitCode: Int?, signal: Int?, timedOut: Boolean, durationMs: Long)

    /** It goes on in the background as session [id], whose output is there rather than here. */
    fun detached(id: String) = end(0, null, false, 0)
}

/**
 * The transcript side of [ExecEcho]: writes into a [TerminalSessions.Terminal]
 * of kind [TerminalSessions.Kind.TRANSCRIPT].
 */
internal class TranscriptEcho(private val terminal: TerminalSessions.Terminal) : ExecEcho {
    /** The cursor is at the start of a line, so the next prompt needs no newline first. */
    private var atLineStart = true
    private var commands = 0

    /**
     * The directory of the prompt left waiting after the last command, as a
     * shell leaves one; null before the first. The next command is written
     * after it rather than under a prompt of its own.
     */
    private var waiting: String? = null
    private var lastCwd: String? = null

    @Synchronized
    override fun begin(command: String, cwd: String, interrupt: (() -> Unit)?) {
        val prompt = buildString {
            // Whatever colour or half-line the last command left behind ends here.
            append(RESET)
            when (waiting) {
                cwd -> Unit
                null -> {
                    if (!atLineStart) append("\r\n")
                    if (commands > 0) append("\r\n")
                    prompt(cwd)
                }
                // The waiting prompt names another directory: this one's replaces it.
                else -> {
                    append('\r').append(ERASE_LINE)
                    prompt(cwd)
                }
            }
            append(BOLD)
            // A multi-line command continues under a "> ", as a shell shows it.
            append(command.trim().replace("\r\n", "\n").replace("\n", "\r\n> "))
            append(RESET).append("\r\n")
        }
        terminal.feed(prompt)
        atLineStart = true
        waiting = null
        lastCwd = cwd
        commands++
        terminal.commandStarted(command, cwd, interrupt ?: {})
    }

    private fun StringBuilder.prompt(cwd: String) {
        append(GREEN).append(cwd).append(RESET).append(" $ ")
    }

    /** A blank line and the prompt, waiting for the next command in [cwd]. */
    private fun leavePrompt(cwd: String?) {
        val where = cwd ?: lastCwd ?: "~"
        terminal.feed("\r\n" + buildString { prompt(where) })
        waiting = where
        atLineStart = false
    }

    @Synchronized
    override fun output(text: String) {
        if (text.isEmpty()) return
        terminal.feed(text)
        atLineStart = text.endsWith('\n')
    }

    override fun end(exitCode: Int?, signal: Int?, timedOut: Boolean, durationMs: Long) =
        end(exitCode, signal, timedOut, durationMs, nextCwd = null)

    /** [nextCwd]: where the prompt left waiting is, when the command moved; null for where it ran. */
    @Synchronized
    fun end(exitCode: Int?, signal: Int?, timedOut: Boolean, durationMs: Long, nextCwd: String?) {
        val took = formatDuration(durationMs)
        val (colour, words) = when {
            timedOut -> RED to "stopped: out of time after $took"
            signal == SIGINT -> YELLOW to "interrupted · $took"
            signal != null -> RED to "stopped by signal $signal · $took"
            exitCode == null -> YELLOW to "stopped · $took"
            exitCode == 0 -> DIM to "exit 0 · $took"
            else -> RED to "exit $exitCode · $took"
        }
        terminal.feed(RESET + (if (atLineStart) "" else "\r\n") + colour + "[" + words + "]" + RESET + "\r\n")
        atLineStart = true
        leavePrompt(nextCwd)
        terminal.commandEnded()
    }

    @Synchronized
    override fun detached(id: String) {
        terminal.feed(RESET + (if (atLineStart) "" else "\r\n") + DIM + "[running in the background as $id]" + RESET + "\r\n")
        atLineStart = true
        leavePrompt(null)
        terminal.commandEnded()
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }

    private companion object {
        const val SIGINT = 2
        const val RESET = "\u001b[0m"
        const val ERASE_LINE = "\u001b[2K"
        const val BOLD = "\u001b[1m"
        const val DIM = "\u001b[2m"
        const val RED = "\u001b[31m"
        const val GREEN = "\u001b[32m"
        const val YELLOW = "\u001b[33m"
    }
}
