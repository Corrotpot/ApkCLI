package dev.apkcli.core.runtime

/**
 * A command's output as its terminal shows it: what the agent reads once the
 * command is done, and the live view under the call while it runs.
 *
 * On a terminal a program redraws instead of appending. A progress bar goes
 * back to its own start with `\r`, a spinner erases its line, pip and npm
 * move the cursor up to repaint a block. Keeping the bytes, as the pipe
 * capture does, would keep every frame of that; this keeps the screen, so a
 * download that drew its bar a thousand times reads as the one line it ended
 * on. Colour is kept for the live view and dropped from [text].
 *
 * Bounded like [OutputCapture], by characters: the screen holds up to half
 * of [limit], and of the lines that scroll off its top the first half of
 * [limit] is kept, the start of the output. The rest is counted, and [text]
 * says where it was.
 */
internal class TerminalCapture(columns: Int, limit: Int) {
    private val headLimit = limit / 2
    private val head = StringBuilder()
    private var omittedLines = 0L

    private val screen = TerminalBuffer(
        maxLines = MAX_LINES,
        maxLineLength = (limit - headLimit).coerceAtLeast(1),
        maxChars = (limit - headLimit).toLong(),
        onDropped = ::scrolledOff,
    ).also { it.columns = columns }

    /** Changes whenever what [rows] returns may have. */
    val revision: Int get() = screen.revision

    val truncated: Boolean get() = omittedLines > 0

    fun append(text: String) = screen.feed(text)

    /** The last [count] lines with their colours, each cut to [maxLength], for the live view. */
    fun rows(count: Int, maxLength: Int): List<TerminalBuffer.Row> = screen.rows(count, maxLength)

    /** Everything kept, as plain text, with a marker where lines were left out. */
    fun text(): String = buildString {
        append(head)
        if (omittedLines > 0) append("[… $omittedLines lines omitted …]\n")
        append(screen.plainText())
    }.trimEnd()

    private fun scrolledOff(line: String) {
        // The head is the unbroken start: once one line does not fit, every
        // later one is counted rather than kept.
        if (omittedLines == 0L && head.length + line.length + 1 <= headLimit) {
            head.append(line).append('\n')
        } else {
            omittedLines++
        }
    }

    private companion object {
        /** Characters are what bound this; the line count only caps a flood of empty lines. */
        const val MAX_LINES = 100_000
    }
}
