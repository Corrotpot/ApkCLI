package dev.apkcli.ui.chat.markdown

/**
 * Block structure of an agent reply: GitHub-flavoured Markdown, the subset
 * models actually write.
 *
 * Hand-written rather than a library, for the reasons the project avoids most
 * dependencies, plus one of its own: the input is usually *incomplete*. A reply
 * is re-parsed on every streamed chunk, so an unclosed code fence must read as
 * a code block that is still growing, and a table whose delimiter row has not
 * arrived yet as a plain line — never as an exception or a flicker of raw
 * syntax. A library built for finished documents does not promise either.
 *
 * Not supported, deliberately: indented code blocks (models use fences, and
 * four-space indentation is how they nest list content), reference-style link
 * definitions, and HTML blocks beyond what [MarkdownInline] does with inline
 * tags.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    /** [closed] is false while the closing fence has not streamed in yet. */
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock

    /**
     * Display maths: `$$…$$` or `\[…\]` on its own. [latex] is the source,
     * transcribed when it is drawn; [closed] is false while the closing
     * delimiter has not arrived.
     */
    data class Math(val latex: String, val closed: Boolean) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock

    /** [checked] is null for an ordinary item, true/false for `- [x]` / `- [ ]`. */
    data class ListItem(val blocks: List<MdBlock>, val checked: Boolean?)

    data class Table(val header: List<String>, val alignments: List<Align>, val rows: List<List<String>>) : MdBlock

    data object Rule : MdBlock
}

internal enum class Align { START, CENTER, END }

internal object MarkdownParser {

    fun parse(text: String): List<MdBlock> = parseLines(lines(text), depth = 0)

    /** [text] as the parser reads it: one string per line, tabs as four spaces. */
    fun lines(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')

    /**
     * The top-level blocks of [lines], and in [starts] the line each one
     * begins on. Text after a block's start cannot change the blocks before
     * it — nothing here looks back past the paragraph being read — which is
     * what [Streaming] relies on.
     */
    fun parseTop(lines: List<String>, starts: MutableList<Int>): List<MdBlock> = parseLines(lines, depth = 0, starts)

    /**
     * A reply that grows at its end, parsed again only from its last few
     * blocks: the ones before are kept, the same objects, so their views are
     * skipped. Parsed whole on every update, a long reply cost the main
     * thread more with every line, twenty times a second. Gives what
     * [parse] gives for the same text.
     */
    class Streaming {
        private var text = ""
        /** Blocks that more text can no longer change, and where the rest begins. */
        private val settled = ArrayList<MdBlock>()
        private var settledLine = 0
        private var blocks: List<MdBlock> = emptyList()

        fun update(next: String): List<MdBlock> {
            if (next == text) return blocks
            if (!next.startsWith(text)) {
                settled.clear()
                settledLine = 0
            }
            text = next
            val all = lines(next)
            val starts = ArrayList<Int>()
            val from = settledLine.coerceAtMost(all.size)
            val rest = parseTop(all.subList(from, all.size), starts)
            // A block is settled once the one after it has begun two whole
            // lines before the end: where a block ends is decided by the
            // first line of the next, and for a table by the line after it,
            // and the last line may still be growing ("1" becoming "1.").
            // The last block always waits: it may grow, or become a heading.
            val lastLine = all.size - 1 - from
            var k = rest.size - 1
            while (k >= 1 && starts[k] > lastLine - 2) k--
            if (k >= 1) {
                settled += rest.subList(0, k)
                settledLine = from + starts[k]
            }
            val pending = if (k >= 1) rest.subList(k, rest.size) else rest
            blocks = ArrayList<MdBlock>(settled.size + pending.size).apply {
                addAll(settled)
                addAll(pending)
            }
            return blocks
        }
    }

    private const val MAX_DEPTH = 12

    private fun parseLines(lines: List<String>, depth: Int, starts: MutableList<Int>? = null): List<MdBlock> {
        // Pathological nesting (a thousand `>`) is drawn flat instead of
        // recursing until the stack runs out.
        if (depth > MAX_DEPTH) return listOf(MdBlock.Paragraph(lines.joinToString("\n")))

        val out = ArrayList<MdBlock>()
        /** A block, and the line it began on: a paragraph's first, or the line it was found on. */
        fun emit(block: MdBlock, from: Int) {
            out += block
            starts?.add(from)
        }
        val paragraph = ArrayList<String>()
        var paragraphStart = 0
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                val text = paragraph.joinToString("\n").trim()
                if (text.isNotEmpty()) emit(MdBlock.Paragraph(text), paragraphStart)
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length

            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // A block found on this line begins here, at i; flushParagraph,
            // before it, gives the paragraph its own start.
            val fence = openingFence(line)
            if (fence != null) {
                flushParagraph()
                val body = ArrayList<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (isClosingFence(lines[j], fence)) {
                        closed = true
                        j++
                        break
                    }
                    body += dropIndent(lines[j], fence.indent)
                    j++
                }
                // Still streaming: the newline just received is not a blank
                // line of code yet.
                if (!closed) while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
                emit(MdBlock.Code(fence.info, body.joinToString("\n"), closed), i)
                i = j
                continue
            }

            if (indent <= 3) {
                // Before the rest: `$$` and `\[` are unambiguous, and a
                // formula's own line must not be read as a paragraph.
                val math = mathDelimiters(trimmed)
                if (math != null) {
                    flushParagraph()
                    val body = StringBuilder()
                    var rest = trimmed.substring(math.first.length)
                    var j = i
                    var closed = false
                    while (true) {
                        val at = rest.indexOf(math.second)
                        if (at >= 0) {
                            body.append(rest, 0, at)
                            closed = true
                            break
                        }
                        body.append(rest).append('\n')
                        j++
                        if (j >= lines.size) break
                        rest = lines[j]
                    }
                    emit(MdBlock.Math(body.toString().trim(), closed), i)
                    i = j + 1
                    continue
                }

                val heading = ATX_HEADING.matchEntire(trimmed)
                if (heading != null) {
                    flushParagraph()
                    val title = heading.groupValues[2].replace(CLOSING_HASHES, "").trim()
                    emit(MdBlock.Heading(heading.groupValues[1].length, title), i)
                    i++
                    continue
                }

                // "Title\n=====" — only directly under paragraph text, or a
                // `---` would turn every preceding line into a heading.
                if (paragraph.isNotEmpty() && SETEXT.matches(trimmed)) {
                    val level = if (trimmed.startsWith("=")) 1 else 2
                    val title = paragraph.joinToString(" ").trim()
                    paragraph.clear()
                    // The paragraph's lines are the heading's: it began with them.
                    emit(MdBlock.Heading(level, title), paragraphStart)
                    i++
                    continue
                }

                if (RULE.matches(trimmed)) {
                    flushParagraph()
                    emit(MdBlock.Rule, i)
                    i++
                    continue
                }

                if (trimmed.startsWith(">")) {
                    flushParagraph()
                    val inner = ArrayList<String>()
                    var j = i
                    while (j < lines.size) {
                        val t = lines[j].trimStart()
                        when {
                            t.startsWith(">") -> inner += t.removePrefix(">").let { if (it.startsWith(" ")) it.substring(1) else it }
                            // Lazy continuation: a plain line right after quoted
                            // text still belongs to the quote's paragraph.
                            t.isNotEmpty() && inner.isNotEmpty() && inner.last().isNotBlank() && !startsBlock(lines[j]) -> inner += t
                            else -> break
                        }
                        j++
                    }
                    emit(MdBlock.Quote(parseLines(inner, depth + 1)), i)
                    i = j
                    continue
                }

                if ('|' in trimmed && i + 1 < lines.size && isDelimiterRow(lines[i + 1])) {
                    val header = splitRow(trimmed)
                    val alignments = splitRow(lines[i + 1].trim()).map(::alignmentOf)
                    if (header.size == alignments.size) {
                        flushParagraph()
                        val rows = ArrayList<List<String>>()
                        var j = i + 2
                        while (j < lines.size && lines[j].isNotBlank() && '|' in lines[j] && !startsBlock(lines[j])) {
                            rows += splitRow(lines[j].trim()).let { cells ->
                                // GFM: extra cells are dropped, missing ones are empty.
                                List(header.size) { c -> cells.getOrElse(c) { "" } }
                            }
                            j++
                        }
                        emit(MdBlock.Table(header, alignments, rows), i)
                        i = j
                        continue
                    }
                }
            }

            val marker = listMarker(line)
            // An ordered list may interrupt a paragraph only when it starts at 1,
            // so "in 2024. we moved" is not a list.
            if (marker != null && (paragraph.isEmpty() || !marker.ordered || marker.start == 1)) {
                flushParagraph()
                val (block, next) = parseList(lines, i, marker, depth)
                emit(block, i)
                i = next
                continue
            }

            if (paragraph.isEmpty()) paragraphStart = i
            paragraph += line.trimStart()
            i++
        }
        flushParagraph()
        return out
    }

    /* ------------------------------------------------------------- lists */

    private class Marker(
        val indent: Int,
        val ordered: Boolean,
        val bullet: Char,
        val start: Int,
        /** Column where the item's text starts; continuation lines are indented at least this far. */
        val contentIndent: Int,
        val content: String,
    )

    private fun listMarker(line: String): Marker? {
        val match = LIST_ITEM.matchEntire(line) ?: return null
        val indent = match.groupValues[1].length
        val symbol = match.groupValues[2]
        val gap = match.groupValues[3].length
        val content = match.groupValues[4]
        val ordered = symbol.last() == '.' || symbol.last() == ')'
        return Marker(
            indent = indent,
            ordered = ordered,
            bullet = symbol.last(),
            start = if (ordered) symbol.dropLast(1).toIntOrNull() ?: 1 else 1,
            // Five or more spaces after the marker would be an indented code
            // block in CommonMark; for content placement that means "one".
            contentIndent = indent + symbol.length + if (gap in 1..4) gap else 1,
            content = content,
        )
    }

    private fun sameList(a: Marker, b: Marker): Boolean =
        a.ordered == b.ordered && (a.ordered || a.bullet == b.bullet)

    private fun parseList(lines: List<String>, from: Int, first: Marker, depth: Int): Pair<MdBlock, Int> {
        val items = ArrayList<MdBlock.ListItem>()
        var current: ArrayList<String>? = null
        var checked: Boolean? = null
        var contentIndent = first.contentIndent
        var afterBlank = false

        fun closeItem() {
            current?.let { items += MdBlock.ListItem(parseLines(it, depth + 1), checked) }
            current = null
        }

        var i = from
        while (i < lines.size) {
            val line = lines[i]
            val marker = listMarker(line)
            val indent = line.length - line.trimStart().length

            if (marker != null && marker.indent < contentIndent && marker.indent <= first.indent + 3) {
                if (!sameList(marker, first)) {
                    // Another kind of list may not interrupt the item's text
                    // unless it is a bullet list or starts at 1: "3. three"
                    // right under "- item" continues the item.
                    if (!afterBlank && marker.ordered && marker.start != 1) {
                        current?.add(line.trimStart())
                        i++
                        continue
                    }
                    break
                }
                closeItem()
                var content = marker.content
                checked = null
                TASK.matchEntire(content)?.let { task ->
                    checked = task.groupValues[1].isNotBlank()
                    content = task.groupValues[2]
                }
                current = arrayListOf(content)
                contentIndent = marker.contentIndent
                afterBlank = false
                i++
                continue
            }

            if (line.isBlank()) {
                // A blank line stays inside the list only if what follows is
                // more of it: an indented continuation or a sibling item.
                val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                val nextLine = lines[next]
                val nextIndent = nextLine.length - nextLine.trimStart().length
                val nextMarker = listMarker(nextLine)
                val continues = nextIndent >= contentIndent ||
                    (nextMarker != null && nextMarker.indent < contentIndent && sameList(nextMarker, first))
                if (!continues) break
                current?.add("")
                afterBlank = true
                i++
                continue
            }

            if (indent >= contentIndent) {
                current?.add(line.substring(contentIndent))
                i++
                continue
            }

            // Lazy continuation of the item's first paragraph.
            if (!afterBlank && !startsBlock(line) && marker == null) {
                current?.add(line.trimStart())
                i++
                continue
            }
            break
        }
        closeItem()
        return MdBlock.ListBlock(first.ordered, first.start, items) to i
    }

    /* ------------------------------------------------------------ fences */

    private class Fence(val char: Char, val length: Int, val indent: Int, val info: String)

    private fun openingFence(line: String): Fence? {
        val match = FENCE_OPEN.matchEntire(line) ?: return null
        val run = match.groupValues[2]
        val info = match.groupValues[3].trim()
        // A backtick fence's info string may not contain backticks: "``` `x` ```" is inline code.
        if (run[0] == '`' && '`' in info) return null
        return Fence(run[0], run.length, match.groupValues[1].length, info)
    }

    private fun isClosingFence(line: String, fence: Fence): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < fence.length) return false
        if (trimmed.any { it != fence.char }) return false
        return line.length - line.trimStart().length <= 3
    }

    private fun dropIndent(line: String, indent: Int): String {
        var n = 0
        while (n < indent && n < line.length && line[n] == ' ') n++
        return line.substring(n)
    }

    /* ------------------------------------------------------------ tables */

    private fun isDelimiterRow(line: String): Boolean {
        val trimmed = line.trim()
        if ('|' !in trimmed && !trimmed.startsWith(":") && !trimmed.startsWith("-")) return false
        if ('-' !in trimmed) return false
        val cells = splitRow(trimmed)
        if (cells.isEmpty()) return false
        // A single-column table needs a pipe somewhere, or "---" is a rule.
        if (cells.size == 1 && '|' !in trimmed) return false
        return cells.all { DELIMITER_CELL.matches(it.trim()) }
    }

    private fun alignmentOf(cell: String): Align {
        val c = cell.trim()
        return when {
            c.startsWith(':') && c.endsWith(':') -> Align.CENTER
            c.endsWith(':') -> Align.END
            else -> Align.START
        }
    }

    /** Split a table row on unescaped pipes outside code spans. */
    internal fun splitRow(row: String): List<String> {
        var text = row.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var inCode = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] == '|' -> {
                    cell.append('|')
                    i += 2
                    continue
                }
                c == '`' -> inCode = !inCode
                c == '|' && !inCode -> {
                    cells += cell.toString().trim()
                    cell.clear()
                    i++
                    continue
                }
            }
            cell.append(c)
            i++
        }
        cells += cell.toString().trim()
        return cells
    }

    /* ------------------------------------------------------------- maths */

    /** The opening and closing delimiters of display maths starting [trimmed], or null. */
    private fun mathDelimiters(trimmed: String): Pair<String, String>? = when {
        trimmed.startsWith("$$") -> "$$" to "$$"
        trimmed.startsWith("\\[") -> "\\[" to "\\]"
        else -> null
    }

    /* ----------------------------------------------------------- helpers */

    /** Whether [line] begins a block that ends a paragraph. */
    private fun startsBlock(line: String): Boolean {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3) return false
        return openingFence(line) != null ||
            mathDelimiters(trimmed) != null ||
            ATX_HEADING.matches(trimmed) ||
            RULE.matches(trimmed) ||
            trimmed.startsWith(">") ||
            listMarker(line)?.let { !it.ordered || it.start == 1 } == true
    }

    private val ATX_HEADING = Regex("""^(#{1,6})(?:[ \t]+(.*))?$""")
    private val CLOSING_HASHES = Regex("""[ \t]+#+[ \t]*$""")
    private val SETEXT = Regex("""^(=+|-+)[ \t]*$""")
    private val RULE = Regex("""^((\*[ \t]*){3,}|(-[ \t]*){3,}|(_[ \t]*){3,})$""")
    private val LIST_ITEM = Regex("""^( *)([-*+]|\d{1,9}[.)])( +|$)(.*)$""")
    private val TASK = Regex("""^\[([ xX])\][ \t]+(.*)$""")
    private val FENCE_OPEN = Regex("""^( {0,3})(`{3,}|~{3,})(.*)$""")
    private val DELIMITER_CELL = Regex("""^:?-+:?$""")
}
