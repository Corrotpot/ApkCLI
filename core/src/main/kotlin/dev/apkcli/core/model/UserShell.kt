package dev.apkcli.core.model

/**
 * A command the user ran themselves (`!` in the composer), as its message
 * keeps it: the line as typed, then what it printed in a `<user_shell>`
 * block that says how it ended.
 *
 *     !git status
 *     <user_shell exit="0" ms="214">
 *     On branch main
 *     </user_shell>
 *
 * Plain text in a user message, so every adapter sends it as the user's words
 * with no change of its own, the store indexes it, and a chat's preview and
 * title read `!git status`. The system prompt tells the agent what the block
 * is; the chat draws it back as a command card ([decode]).
 *
 * The output is not escaped. The command ends at the first opening tag and
 * the output at the closing one that ends the message, so output that
 * prints the tags itself still reads back whole.
 */
object UserShell {

    /** One run. [exitCode] is null when a signal ended it, it was stopped, or it never started. */
    data class Run(
        val command: String,
        val output: String,
        val exitCode: Int?,
        val signal: Int? = null,
        val timedOut: Boolean = false,
        /** The user stopped it; [output] is what its screen showed then. */
        val stopped: Boolean = false,
        val durationMs: Long = 0,
    ) {
        val succeeded: Boolean get() = exitCode == 0 && !timedOut && !stopped
    }

    private const val OPEN = "<user_shell"
    private const val CLOSE = "</user_shell>"
    private val ATTRIBUTE = Regex("(\\w+)=\"(-?\\d+|true)\"")

    fun encode(run: Run): String = buildString {
        append('!').append(run.command.trim()).append('\n')
        append(OPEN)
        run.exitCode?.let { append(" exit=\"$it\"") }
        run.signal?.let { append(" signal=\"$it\"") }
        if (run.timedOut) append(" timed_out=\"true\"")
        if (run.stopped) append(" stopped=\"true\"")
        append(" ms=\"${run.durationMs.coerceAtLeast(0)}\">\n")
        val output = run.output.trimEnd()
        if (output.isNotEmpty()) append(output).append('\n')
        append(CLOSE)
    }

    /** [text] as the run it records; null when it is not one. */
    fun decode(text: String): Run? {
        if (!text.startsWith('!') || !text.endsWith(CLOSE)) return null
        val open = text.indexOf("\n$OPEN")
        if (open < 0) return null
        val tagEnd = text.indexOf('\n', open + 1)
        if (tagEnd < 0 || tagEnd > text.length - CLOSE.length) return null
        val tag = text.substring(open + 1, tagEnd)
        if (!tag.endsWith('>')) return null
        val attributes = ATTRIBUTE.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
        val command = text.substring(1, open).trim()
        if (command.isEmpty()) return null
        return Run(
            command = command,
            output = text.substring(tagEnd + 1, text.length - CLOSE.length).removeSuffix("\n"),
            exitCode = attributes["exit"]?.toIntOrNull(),
            signal = attributes["signal"]?.toIntOrNull(),
            timedOut = attributes["timed_out"] == "true",
            stopped = attributes["stopped"] == "true",
            durationMs = attributes["ms"]?.toLongOrNull() ?: 0,
        )
    }
}
