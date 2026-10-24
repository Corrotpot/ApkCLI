package dev.apkcli.core.runtime

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A colour as the terminal named it.
 *
 * Kept as the terminal's own notion rather than an ARGB value: the sixteen
 * basic colours have to come from the app's theme, or a shell prompt that
 * prints "bright black" is invisible on a dark canvas. The UI resolves
 * [Indexed] against its palette; [Rgb] is already absolute.
 */
sealed interface TermColor {
    data object Default : TermColor

    /** 0–7 basic, 8–15 bright, 16–231 the 6x6x6 cube, 232–255 greys. */
    data class Indexed(val index: Int) : TermColor

    /** `0xRRGGBB`, from a 24-bit SGR sequence. */
    data class Rgb(val value: Int) : TermColor
}

/** Character attributes, as one immutable value shared by every cell that has them. */
data class TermStyle(
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    /** A space in this style cannot be seen: no background, not inverted, not underlined. */
    val invisibleSpace: Boolean
        get() = bg == TermColor.Default && !inverse && !underline

    companion object {
        val Plain = TermStyle()
    }
}

/**
 * What a terminal has printed, as styled lines.
 *
 * A line buffer, not a character grid. A grid is what a full terminal
 * emulator keeps, and it is what `vim` and `htop` need: they address the
 * screen by row and column and redraw it in place. A shell session is the
 * opposite shape — append-only output, occasionally rewriting the *current*
 * line (a progress bar, readline editing a command, `\r` over a spinner) —
 * and a line buffer with scrollback renders that correctly, re-wraps when the
 * width changes, and lets text be selected and copied as it was printed.
 * Where a line is wider than the terminal, cursor movement follows the screen
 * rows it wraps onto (see [columns]), because that is what the program
 * writing it assumes.
 *
 * The cost is deliberate and bounded: a program that switches to the
 * alternate screen is detected rather than mis-drawn, [fullScreenProgram]
 * goes true, and the UI says so instead of showing debris. Everything else a
 * shell emits — colour, `\r`, backspace, tabs, erase-line, erase-screen,
 * insert and delete within the line — is handled here.
 *
 * Not thread-safe by itself. [TerminalSessions] feeds it from one reader
 * thread and snapshots it for the UI under its own lock.
 */
class TerminalBuffer(
    /** Lines of scrollback kept. Older lines are dropped from the front. */
    private val maxLines: Int = 2_000,
    /**
     * Longest single line kept. A program printing megabytes without a newline
     * (a minified bundle, `cat` on a binary) must not grow one line without
     * limit.
     */
    private val maxLineLength: Int = 4_000,
    /**
     * Characters kept in the lines above the one being written. Past it the
     * oldest lines go, as past [maxLines]: what bounds a buffer of long lines,
     * which a line count alone does not.
     */
    private val maxChars: Long = Long.MAX_VALUE,
    /** Told each line dropped from the front, as plain text: for a caller that keeps the start. */
    private val onDropped: ((String) -> Unit)? = null,
) {

    /** A run of characters sharing one style, as half-open indices into [Row.text]. */
    class Span(val start: Int, val end: Int, val style: TermStyle)

    /** One line, ready to render. */
    class Row(val text: String, val spans: List<Span>)

    private class Line {
        val chars = StringBuilder()

        /**
         * One style per character, parallel to [chars].
         *
         * Per character rather than as runs because overwriting matters: a
         * progress bar returns to column 0 and prints over what is there, and
         * splicing style runs on every overwrite is where this kind of code
         * goes subtly wrong. Styles are shared immutable objects, so the cost
         * is one reference per column.
         */
        val styles = ArrayList<TermStyle>()

        val length: Int get() = chars.length

        fun put(col: Int, ch: Char, style: TermStyle, maxLength: Int) {
            if (col >= maxLength) return
            while (chars.length < col) {
                chars.append(' ')
                styles.add(TermStyle.Plain)
            }
            if (col < chars.length) {
                chars.setCharAt(col, ch)
                styles[col] = style
            } else {
                chars.append(ch)
                styles.add(style)
            }
        }

        /** Erase from [from] to the end of the line. */
        fun truncate(from: Int) {
            if (from >= chars.length) return
            chars.setLength(from)
            styles.subList(from, styles.size).clear()
        }

        /** Spaces over `[from, to)`, as erasing part of a row does. Never lengthens the line. */
        fun blank(from: Int, to: Int) {
            val end = minOf(to, chars.length)
            for (i in from.coerceAtLeast(0) until end) {
                chars.setCharAt(i, ' ')
                styles[i] = TermStyle.Plain
            }
        }

        fun clear() {
            chars.setLength(0)
            styles.clear()
        }

        fun insertBlanks(col: Int, count: Int, maxLength: Int) {
            if (col > chars.length) return
            repeat(count.coerceAtMost(maxLength)) {
                if (chars.length >= maxLength) return
                chars.insert(col, ' ')
                styles.add(col, TermStyle.Plain)
            }
        }

        fun deleteChars(col: Int, count: Int) {
            if (col >= chars.length) return
            val end = minOf(col + count, chars.length)
            chars.delete(col, end)
            styles.subList(col, end).clear()
        }

        /**
         * Trailing blanks are left out: a line editor's redraw pads with spaces
         * to the edge of the screen, and once the panel narrows those spaces
         * would wrap onto a row of their own. A space with a background or an
         * underline is visible and stays.
         */
        fun toRow(maxLength: Int = Int.MAX_VALUE): Row {
            var visible = chars.length
            while (visible > 0 && chars[visible - 1] == ' ' && styles[visible - 1].invisibleSpace) visible--
            visible = minOf(visible, maxLength)
            val text = chars.substring(0, visible)
            if (text.isEmpty()) return Row(text, emptyList())
            val spans = ArrayList<Span>(4)
            var start = 0
            var style = styles[0]
            for (i in 1 until text.length) {
                val next = styles[i]
                if (next != style) {
                    if (style != TermStyle.Plain) spans.add(Span(start, i, style))
                    start = i
                    style = next
                }
            }
            if (style != TermStyle.Plain) spans.add(Span(start, text.length, style))
            return Row(text, spans)
        }
    }

    private val lines = ArrayDeque<Line>().apply { addLast(Line()) }

    /** Index into [lines] of the line being written, and the column within it. */
    private var row = 0
    private var col = 0

    /**
     * The last character went into the last column of a screen row, and the
     * wrap it implies has not happened yet (a terminal moves to the next row
     * only when another character arrives). [col] is already past it; see
     * [cursorColumn]. Only ever set when [columns] is known.
     */
    private var wrapPending = false

    private var style = TermStyle.Plain

    /** Bumped whenever anything visible changed, so the UI can recompose on it. */
    var revision: Int = 0
        private set

    /** From `OSC 0` / `OSC 2`: what the program calls itself. */
    var title: String? = null
        private set

    /** From `OSC 7` (`file://host/path`): where the shell says it is now; see [osc7Path]. */
    var cwd: String? = null
        private set

    /** [cwd], once: the next call returns null until another `OSC 7`. */
    fun takeCwd(): String? = cwd.also { cwd = null }

    /**
     * A program asked for the alternate screen (`CSI ? 1049 h` and friends),
     * i.e. it wants to own a character grid. Its drawing is not rendered; the
     * UI reports this instead.
     */
    var fullScreenProgram: Boolean = false
        private set

    /** Total characters ever printed, including what scrollback has dropped. */
    var totalChars: Long = 0L
        private set

    private var droppedLines: Long = 0L

    /**
     * Characters in the lines above the last, for [maxChars]. Counted as each
     * line is left behind, which is when its length settles; a line rewritten
     * later by cursor movement makes it approximate, which a budget can be.
     */
    private var heldChars: Long = 0L

    /**
     * The width the program was told it has (`TIOCSWINSZ`), or 0 when unknown.
     *
     * Lines are kept whole, but the program writing them thinks in screen
     * rows: once a line is longer than this it has wrapped, and `\r` means the
     * start of the row the cursor is on, not of the line. Line editors rely on
     * exactly that when they redraw a prompt wider than the screen. Taking
     * every `\r` to column 0 printed the prompt's tail over its head —
     * `ts/scratch $` where `/data/…/projects/scratch $` had been.
     */
    var columns: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            // A wrap is deferred only at the end of a row, which the cursor
            // may no longer be at.
            if (field == 0 || col % field != 0) wrapPending = false
        }

    /* ------------------------------------------------------------- decoding */

    /**
     * Bytes are decoded with a stateful decoder, and the escape parser keeps
     * its state too: a read can split a UTF-8 sequence, or an escape sequence,
     * anywhere. Decoding each chunk on its own turns a split "ё" into two
     * replacement characters, and a split `CSI` into visible garbage.
     */
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var pending: ByteArray = ByteArray(0)

    private enum class EscState { GROUND, ESC, CSI, OSC, STRING, CHARSET }

    private var escState = EscState.GROUND
    private var csiPrivate = 0.toChar()
    private val csiParams = StringBuilder()
    private val stringParam = StringBuilder()

    /** True while the previous character was ESC inside an OSC/DCS string. */
    private var stringEscape = false

    /**
     * Text already decoded, as `proc.step` returns it (it keeps a split UTF-8
     * sequence for the next read itself). The escape parser's state still
     * carries over between calls.
     */
    fun feed(text: CharSequence) {
        if (text.isEmpty()) return
        accept(CharBuffer.wrap(text))
        revision++
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        if (length <= 0) return
        val input = if (pending.isEmpty()) {
            ByteBuffer.wrap(bytes, 0, length)
        } else {
            ByteBuffer.allocate(pending.size + length).apply {
                put(pending)
                put(bytes, 0, length)
                flip()
            }
        }
        val out = CharBuffer.allocate(pending.size + length + 8)
        decoder.decode(input, out, false)
        pending = if (input.hasRemaining()) ByteArray(input.remaining()).also(input::get) else ByteArray(0)
        out.flip()
        if (out.hasRemaining()) {
            accept(out)
            revision++
        }
    }

    private fun accept(chars: CharBuffer) {
        while (chars.hasRemaining()) {
            val ch = chars.get()
            when (escState) {
                EscState.GROUND -> ground(ch)
                EscState.ESC -> escape(ch)
                EscState.CSI -> csi(ch)
                EscState.OSC, EscState.STRING -> string(ch)
                EscState.CHARSET -> escState = EscState.GROUND
            }
        }
    }

    private fun ground(ch: Char) {
        when (ch) {
            '\u001b' -> escState = EscState.ESC
            '\n' -> newLine()
            '\r' -> moveTo(rowStart(cursorColumn()))
            '\b' -> {
                // Within the row: a terminal does not back over a wrap.
                val c = cursorColumn()
                moveTo(if (c > rowStart(c)) c - 1 else c)
            }
            '\t' -> {
                // Tab stops count from the start of the screen row, and the
                // last column is as far as a tab goes.
                val c = cursorColumn()
                val start = rowStart(c)
                var next = start + ((c - start) / TAB + 1) * TAB
                if (columns > 0) next = minOf(next, start + columns - 1)
                if (next > c) {
                    moveTo(c)
                    while (col < next) current().put(col++, ' ', style, maxLineLength)
                }
            }
            '\u0007' -> Unit // bell
            '\u000b', '\u000c' -> newLine() // vertical tab, form feed
            else -> if (ch >= ' ' || ch.code >= 0x80) {
                current().put(col, ch, style, maxLineLength)
                if (col < maxLineLength) col++
                wrapPending = columns > 0 && col % columns == 0
                totalChars++
            }
        }
    }

    private fun escape(ch: Char) {
        when (ch) {
            '[' -> {
                escState = EscState.CSI
                csiParams.setLength(0)
                csiPrivate = 0.toChar()
            }
            ']' -> {
                escState = EscState.OSC
                stringParam.setLength(0)
                stringEscape = false
            }
            // DCS, SOS, PM, APC: a string we do not interpret, ended by ST.
            'P', 'X', '^', '_' -> {
                escState = EscState.STRING
                stringParam.setLength(0)
                stringEscape = false
            }
            // Charset designation: one more byte to swallow.
            '(', ')', '*', '+', '-', '.', '/' -> escState = EscState.CHARSET
            'c' -> {
                reset()
                escState = EscState.GROUND
            }
            'M' -> {
                // Reverse index: up a row. Scrolling the screen down at the
                // top is grid work and is not attempted.
                cursorUp(1)
                escState = EscState.GROUND
            }
            'D' -> {
                newLine()
                escState = EscState.GROUND
            }
            'E' -> {
                // Next line: a newline already lands at the start of the row.
                newLine()
                escState = EscState.GROUND
            }
            '7', '8', '=', '>' -> escState = EscState.GROUND // cursor save/restore, keypad
            else -> escState = EscState.GROUND
        }
    }

    private fun csi(ch: Char) {
        when {
            ch in '0'..'9' || ch == ';' || ch == ':' -> csiParams.append(ch)
            ch == '?' || ch == '<' || ch == '=' || ch == '>' -> csiPrivate = ch
            ch == ' ' || ch == '!' || ch == '"' || ch == '$' || ch == '\'' -> Unit // intermediates
            ch.code in 0x40..0x7E -> {
                applyCsi(ch)
                escState = EscState.GROUND
            }
            else -> escState = EscState.GROUND
        }
    }

    private fun string(ch: Char) {
        when {
            // String terminator: BEL, or ESC \.
            ch == '\u0007' -> {
                finishString()
                escState = EscState.GROUND
            }
            stringEscape -> {
                finishString()
                escState = if (ch == '\\') EscState.GROUND else EscState.GROUND
            }
            ch == '\u001b' -> stringEscape = true
            // Room for a title or a long path; a runaway string stops growing.
            else -> if (stringParam.length < MAX_STRING) stringParam.append(ch)
        }
    }

    private fun finishString() {
        stringEscape = false
        if (escState != EscState.OSC) return
        val text = stringParam.toString()
        val separator = text.indexOf(';')
        if (separator <= 0) return
        // 0 sets icon name and title, 2 sets the title, 7 says where the
        // shell is. Nothing else is used.
        when (text.substring(0, separator)) {
            "0", "2" -> title = text.substring(separator + 1).takeIf { it.isNotBlank() }?.take(120)
            "7" -> osc7Path(text.substring(separator + 1))?.let { cwd = it }
        }
    }

    private fun params(): List<Int> {
        if (csiParams.isEmpty()) return emptyList()
        return csiParams.split(';').map { part ->
            // A sub-parameter (SGR 38:2:...) is split on ':' by the caller;
            // here only the leading number matters.
            part.substringBefore(':').toIntOrNull() ?: 0
        }
    }

    private fun applyCsi(final: Char) {
        val p = params()
        val first = p.firstOrNull() ?: 0
        val count = if (first <= 0) 1 else first

        if (csiPrivate == '?') {
            // DEC private modes. Only the alternate screen matters: it means a
            // program is about to draw a grid this buffer cannot represent.
            val set = final == 'h'
            if (final == 'h' || final == 'l') {
                for (mode in p) {
                    if (mode == 1049 || mode == 47 || mode == 1047) fullScreenProgram = set
                }
            }
            return
        }

        when (final) {
            'm' -> applySgr()
            'K' -> eraseInRow(first)
            'J' -> when (first) {
                // To the end of the screen: this line from the cursor, and
                // every line after it.
                0 -> {
                    current().truncate(col)
                    if (lines.size > row + 1) {
                        while (lines.size > row + 1) lines.removeLast()
                        // Rare enough (a redraw clearing below itself) to count afresh.
                        heldChars = lines.sumOf { it.length.toLong() } - lines.last().length
                    }
                }
                // `clear` sends 2 (and often 3, which also drops scrollback).
                2, 3 -> reset()
            }
            // Horizontal moves stay inside the cursor's screen row, as they
            // do on a terminal.
            'C' -> {
                val c = cursorColumn()
                moveTo(
                    if (columns > 0) {
                        minOf(c + count, rowStart(c) + columns - 1)
                    } else {
                        (c + count).coerceAtMost(maxLineLength)
                    },
                )
            }
            'D' -> {
                val c = cursorColumn()
                moveTo(maxOf(rowStart(c), c - count))
            }
            'G', '`' -> {
                val target = (count - 1).coerceAtLeast(0)
                moveTo(
                    if (columns > 0) {
                        rowStart(cursorColumn()) + target.coerceAtMost(columns - 1)
                    } else {
                        target
                    },
                )
            }
            'A' -> cursorUp(count)
            'B' -> cursorDown(count)
            'd' -> {
                row = (count - 1).coerceIn(0, lines.size - 1)
                wrapPending = false
            }
            'H', 'f' -> {
                // Cursor position. Addressed from the top of the *screen*,
                // which a line buffer does not have; the best available
                // reading is "the last screenful", so row 1 is the first line
                // currently held.
                row = (count - 1).coerceIn(0, lines.size - 1)
                moveTo(((p.getOrNull(1) ?: 1) - 1).coerceAtLeast(0))
            }
            'P' -> current().deleteChars(col, count)
            '@' -> current().insertBlanks(col, count, maxLineLength)
            'X' -> {
                // Erase characters in place, up to the end of the row.
                val end = if (columns > 0) rowStart(cursorColumn()) + columns else Int.MAX_VALUE
                current().blank(col, minOf(col.toLong() + count, end.toLong()).toInt())
            }
            'L', 'M', 'S', 'T', 'r' -> Unit // insert/delete line, scroll, margins: grid work
            else -> Unit
        }
    }

    private fun applySgr() {
        val raw = if (csiParams.isEmpty()) "0" else csiParams.toString()
        // 38/48 take sub-parameters, separated by ';' or ':' depending on the
        // program. Flattening both and walking with an index handles each.
        val parts = raw.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < parts.size) {
            when (val code = parts[i]) {
                0 -> style = TermStyle.Plain
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                21, 22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(fg = TermColor.Indexed(code - 30))
                39 -> style = style.copy(fg = TermColor.Default)
                in 40..47 -> style = style.copy(bg = TermColor.Indexed(code - 40))
                49 -> style = style.copy(bg = TermColor.Default)
                in 90..97 -> style = style.copy(fg = TermColor.Indexed(code - 90 + 8))
                in 100..107 -> style = style.copy(bg = TermColor.Indexed(code - 100 + 8))
                38, 48 -> {
                    val foreground = code == 38
                    when (parts.getOrNull(i + 1)) {
                        5 -> {
                            val index = parts.getOrNull(i + 2) ?: 0
                            val colour = TermColor.Indexed(index.coerceIn(0, 255))
                            style = if (foreground) style.copy(fg = colour) else style.copy(bg = colour)
                            i += 2
                        }
                        2 -> {
                            val r = (parts.getOrNull(i + 2) ?: 0).coerceIn(0, 255)
                            val g = (parts.getOrNull(i + 3) ?: 0).coerceIn(0, 255)
                            val b = (parts.getOrNull(i + 4) ?: 0).coerceIn(0, 255)
                            val colour = TermColor.Rgb((r shl 16) or (g shl 8) or b)
                            style = if (foreground) style.copy(fg = colour) else style.copy(bg = colour)
                            i += 4
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
            i++
        }
    }

    /* -------------------------------------------------------------- writing */

    private fun current(): Line {
        while (row >= lines.size) appendLine()
        return lines.elementAt(row)
    }

    private fun appendLine() {
        heldChars += lines.last().length
        lines.addLast(Line())
        while (lines.size > maxLines || (heldChars > maxChars && lines.size > 1)) {
            val dropped = lines.removeFirst()
            heldChars = (heldChars - dropped.length).coerceAtLeast(0)
            onDropped?.invoke(dropped.chars.toString().trimEnd())
            droppedLines++
            if (row > 0) row--
        }
        row = lines.size - 1
    }

    /**
     * Line feed, and back to the start of the row.
     *
     * A terminal's LF strictly only moves down, keeping the column; the kernel's
     * `ONLCR` adds the carriage return on the way out of the tty, so what
     * arrives here is almost always `\r\n`. Almost: a program that turns off
     * output processing emits a bare `\n`, and honouring LF literally then
     * indents every line by the length of the one above it — which is a wrong
     * answer in the display *and* in what the agent reads back. Column 0 is the
     * wrong answer only for a program drawing a grid, and those are detected
     * ([fullScreenProgram]) rather than rendered.
     */
    private fun newLine() {
        // Inside a wrapped line the next screen row is still this line: that
        // is where readline goes when it moves down with `\n` to redraw.
        if (columns > 0) {
            val next = rowStart(cursorColumn()) + columns
            if (next < current().length) {
                moveTo(next)
                return
            }
        }
        if (row >= lines.size - 1) {
            appendLine()
        } else {
            row++
        }
        moveTo(0)
    }

    /* ---------------------------------------------------------- screen rows */

    /** Put the cursor at column [c] of the current line. Any move ends a deferred wrap. */
    private fun moveTo(c: Int) {
        col = c.coerceAtLeast(0)
        wrapPending = false
    }

    /**
     * The column the cursor is really at.
     *
     * A character printed in the last column leaves a terminal's cursor there
     * until the next character arrives (deferred wrap), while [col] has
     * already moved past it. Cursor movement starts from where the program
     * believes the cursor is.
     */
    private fun cursorColumn(): Int = if (wrapPending) col - 1 else col

    /** Where, in its line, the screen row holding column [c] starts. */
    private fun rowStart(c: Int): Int = if (columns > 0) c - c % columns else 0

    /** Screen rows [line] takes up when wrapped at [columns]. */
    private fun rowsOf(line: Line): Int =
        if (columns <= 0 || line.length <= columns) 1 else (line.length + columns - 1) / columns

    /** Up [n] screen rows, keeping the column; a wrapped line counts as the rows it fills. */
    private fun cursorUp(n: Int) {
        if (columns <= 0) {
            row = (row - n).coerceAtLeast(0)
            return
        }
        val c = cursorColumn()
        val x = c % columns
        var segment = c / columns
        var left = n
        while (left > 0) {
            when {
                segment > 0 -> segment--
                row > 0 -> {
                    row--
                    segment = rowsOf(current()) - 1
                }
                else -> break
            }
            left--
        }
        moveTo(segment * columns + x)
    }

    /** Down [n] screen rows, keeping the column. Below the last line, lines are added. */
    private fun cursorDown(n: Int) {
        if (columns <= 0) {
            repeat(n) { if (row >= lines.size - 1) appendLine() else row++ }
            return
        }
        val c = cursorColumn()
        val x = c % columns
        var segment = c / columns
        repeat(n) {
            if (segment + 1 < rowsOf(current())) {
                segment++
            } else {
                if (row >= lines.size - 1) appendLine() else row++
                segment = 0
            }
        }
        moveTo(segment * columns + x)
    }

    /**
     * `CSI K`: erase within the cursor's screen row.
     *
     * Only that row: when a wrapped line is redrawn row by row, the rows
     * below the cursor are still on screen and are cleared separately.
     */
    private fun eraseInRow(mode: Int) {
        val line = current()
        val c = cursorColumn()
        val start = rowStart(c)
        val end = if (columns > 0) start + columns else Int.MAX_VALUE
        when (mode) {
            0 -> if (end >= line.length) line.truncate(col) else line.blank(col, end)
            1 -> line.blank(start, c + 1)
            2 -> if (end >= line.length) line.truncate(start) else line.blank(start, end)
        }
    }

    private fun reset() {
        // A screen clear (`clear`, a test runner starting over) wipes what a
        // viewer sees, but a caller keeping the output should still have it:
        // the error before the clear is often the one that matters.
        onDropped?.let { dropped ->
            val last = lines.indexOfLast { it.length > 0 }
            for (i in 0..last) dropped(lines.elementAt(i).chars.toString().trimEnd())
        }
        lines.clear()
        lines.addLast(Line())
        heldChars = 0
        row = 0
        moveTo(0)
    }

    /** Wipe the scrollback, keeping the live style. Used by the UI's clear action. */
    fun clear() {
        reset()
        revision++
    }

    /* ------------------------------------------------------------- snapshots */

    /** Number of lines held, including the one being written. */
    val lineCount: Int get() = lines.size

    /**
     * The last [maxRows] lines, oldest first, ready to render; each cut to
     * its first [maxLength] characters, for a view that cannot lay out a
     * megabyte of one line a few times a second.
     */
    fun rows(maxRows: Int = maxLines, maxLength: Int = Int.MAX_VALUE): List<Row> {
        val from = (lines.size - maxRows).coerceAtLeast(0)
        val out = ArrayList<Row>(lines.size - from)
        for (i in from until lines.size) out.add(lines.elementAt(i).toRow(maxLength))
        return out
    }

    /** Where the cursor is, as (line within the result of [rows], column). */
    fun cursor(maxRows: Int = maxLines): Pair<Int, Int> {
        val from = (lines.size - maxRows).coerceAtLeast(0)
        return (row - from).coerceAtLeast(0) to col
    }

    /**
     * The tail as plain text, for the agent and for `sh_job_output`.
     *
     * Styles are dropped rather than rendered as escape codes: the model reads
     * this as text, and escape sequences would be noise it has to learn to
     * ignore — and tokens paid for on every read.
     */
    fun plainTail(maxChars: Int): String {
        if (maxChars <= 0) return ""
        // Newest first, joined once: inserting each line at the front of the
        // text copied all of it again, line by line, under the session's lock.
        val taken = ArrayList<String>()
        var length = 0
        for (i in lines.indices.reversed()) {
            val text = lines[i].chars.toString().trimEnd()
            if (length + text.length + 1 > maxChars && taken.isNotEmpty()) break
            taken += text
            length += text.length + if (taken.size > 1) 1 else 0
            if (length >= maxChars) break
        }
        return taken.asReversed().joinToString("\n").trimStart('\n')
    }

    /** Every line held, as plain text, trailing blanks dropped: what the agent reads of a finished command. */
    fun plainText(): String {
        val builder = StringBuilder()
        for (line in lines) builder.append(line.chars.toString().trimEnd()).append('\n')
        return builder.toString().trimEnd()
    }

    /** The last line with anything on it, trimmed — "what is happening" in one line. */
    fun lastNonBlankLine(limit: Int = 120): String? {
        for (i in lines.indices.reversed()) {
            val text = lines.elementAt(i).chars.toString().trim()
            if (text.isNotEmpty()) return if (text.length > limit) text.take(limit) + "…" else text
        }
        return null
    }

    private companion object {
        const val TAB = 8

        /** Longest OSC/DCS payload kept: a title, or a deep path for OSC 7. */
        const val MAX_STRING = 2048
    }
}

/**
 * The path in an `OSC 7` payload, `file://host/path`, or null when it is not
 * one. Percent escapes are decoded, as the convention has them; a path that
 * exists as sent is still better found by the caller trying both.
 */
internal fun osc7Path(payload: String): String? {
    if (!payload.startsWith("file://")) return null
    val afterScheme = payload.substring("file://".length)
    val slash = afterScheme.indexOf('/')
    if (slash < 0) return null
    val raw = afterScheme.substring(slash)
    if ('%' !in raw) return raw
    // Decoded as UTF-8 bytes; a '%' not followed by two hex digits stays.
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '%' && i + 2 < raw.length && raw[i + 1].isHex() && raw[i + 2].isHex()) {
            bytes.write(raw.substring(i + 1, i + 3).toInt(16))
            i += 3
        } else {
            // Whole code points, so a character outside the BMP survives.
            val end = i + Character.charCount(raw.codePointAt(i))
            bytes.write(raw.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
