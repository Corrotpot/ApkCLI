package dev.apkcli.ui.chat.markdown

import dev.apkcli.core.text.LatexMath

/** A semantic inline style; [InlineStyles] decides what each looks like. */
internal enum class InlineStyle { STRONG, EMPHASIS, STRONG_EMPHASIS, STRIKE, UNDERLINE, CODE, MARK, SUB, SUP, SMALL, MATH }

/** Where a link goes, once classified. Anything else (javascript:, file:, intent:…) is not a link. */
internal sealed interface LinkTarget {
    data class Web(val url: String) : LinkTarget

    /** A project file, optionally at a line: `src/Main.kt#L12`. */
    data class Path(val path: String, val line: Int?) : LinkTarget
}

/** What the parser writes into. Kept apart from Compose so the parser runs, and is tested, anywhere. */
internal interface InlineSink {
    val length: Int

    fun append(text: String)

    fun style(style: InlineStyle, start: Int, end: Int)

    fun link(target: LinkTarget, start: Int, end: Int)
}

/**
 * Inline Markdown: emphasis, code spans, links and autolinks, strikethrough,
 * entities, and the inline HTML tags models actually emit (`<b>`, `<br>`,
 * `<a href>`, `<kbd>`, `<sub>`…). Any other tag is dropped and its text kept;
 * nothing is ever executed or loaded.
 *
 * Emphasis is matched by a simplified version of CommonMark's rules —
 * flanking checks, no intraword `_`, a `**` run never closing a single `*` —
 * which covers what models write without the full delimiter-stack algorithm.
 * Line breaks are kept as line breaks: in a chat reply a newline is meant.
 *
 * Maths in `$…$`, `$$…$$` or `\(…\)` is transcribed by [LatexMath]. A single
 * `$` is only a delimiter when what it encloses reads as a formula, so prices
 * and `$PREFIX` are left alone, and `$` inside a code span is never looked at
 * because the code span is consumed first.
 */
internal object MarkdownInline {

    fun parse(text: String, sink: InlineSink) {
        InlineParser(sink).run {
            emit(text, 0)
            closeOpenTags()
        }
    }

    /** `src/Main.kt`, `./a.go`, `README.md#L12` — a project path, not a page. */
    fun looksLikePath(target: String): Boolean {
        if (target.isBlank() || target.startsWith("#") || target.startsWith("//")) return false
        if (target.any { it.isWhitespace() }) return false
        val path = splitLineAnchor(target).first
        return '.' in path.substringAfterLast('/') || path.endsWith("/") || '/' in path
    }

    /** `file.kt#L12`, `file.kt:12` -> (`file.kt`, 12). */
    fun splitLineAnchor(target: String): Pair<String, Int?> {
        ANCHOR.find(target)?.let { match ->
            return target.substring(0, match.range.first) to match.groupValues[1].toIntOrNull()
        }
        return target.substringBefore('#') to null
    }

    /** Web addresses keep their scheme; scheme-less paths become [LinkTarget.Path]; the rest is refused. */
    fun classify(url: String): LinkTarget? {
        val target = url.trim()
        val scheme = SCHEME.find(target)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme != null && scheme in WEB_SCHEMES -> LinkTarget.Web(target)
            scheme == null && looksLikePath(target) -> splitLineAnchor(target).let { (path, line) -> LinkTarget.Path(path, line) }
            else -> null
        }
    }

    private val ANCHOR = Regex("""(?:#L|:)(\d+)(?:-L?\d+)?$""")
    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]{1,31}):""")
    private val WEB_SCHEMES = setOf("http", "https", "mailto", "tel")
}

private class InlineParser(private val sink: InlineSink) {

    private class OpenTag(val name: String, val start: Int, val href: String?)

    private val tags = ArrayList<OpenTag>()

    fun emit(s: String, depth: Int) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                sink.append(plain.toString())
                plain.clear()
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            var next = -1
            when (c) {
                '\\' -> if (i + 1 < s.length) {
                    val escaped = s[i + 1]
                    // `\(x\)` and `\[x\]` are maths; the brackets themselves
                    // are escapable punctuation, so this comes first.
                    val math = if (escaped == '(' || escaped == '[') delimitedMath(s, i) else null
                    when {
                        math != null -> {
                            flush()
                            appendMath(math.first)
                            next = math.second
                        }
                        escaped in PUNCTUATION -> {
                            plain.append(escaped)
                            next = i + 2
                        }
                        escaped == '\n' -> {
                            plain.append('\n')
                            next = i + 2
                        }
                    }
                }

                '`' -> {
                    val span = codeSpan(s, i)
                    if (span != null) {
                        flush()
                        val start = sink.length
                        sink.append(span.first)
                        sink.style(InlineStyle.CODE, start, sink.length)
                        next = span.second
                    } else {
                        // An unmatched run is literal, all of it.
                        var j = i
                        while (j < s.length && s[j] == '`') j++
                        plain.append(s, i, j)
                        next = j
                    }
                }

                '*', '_', '~' -> if (depth < MAX_DEPTH) {
                    flush()
                    next = emphasis(s, i, depth)
                }

                '!' -> if (s.getOrNull(i + 1) == '[' && depth < MAX_DEPTH) {
                    flush()
                    next = link(s, i + 1, depth, image = true)
                }

                '[' -> if (depth < MAX_DEPTH) {
                    flush()
                    next = link(s, i, depth, image = false)
                }

                '<' -> {
                    flush()
                    next = angle(s, i)
                }

                '$' -> dollarMath(s, i)?.let { math ->
                    flush()
                    appendMath(math.first)
                    next = math.second
                }

                '&' -> ENTITY.matchAt(s, i)?.let { match ->
                    decodeEntity(match.groupValues[1])?.let { decoded ->
                        plain.append(decoded)
                        next = i + match.value.length
                    }
                }

                'h', 'w', 'H', 'W' -> if (i == 0 || !s[i - 1].isLetterOrDigit() && s[i - 1] != '/' && s[i - 1] != '@') {
                    val url = bareUrl(s, i)
                    if (url != null) {
                        flush()
                        val start = sink.length
                        sink.append(url)
                        addLink(if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url, start, sink.length)
                        next = i + url.length
                    }
                }
            }
            if (next > i) {
                i = next
            } else {
                plain.append(c)
                i++
            }
        }
        flush()
    }

    /* ---------------------------------------------------------- code spans */

    /** `` `code` `` at [i]: the content and the index after the closing run, or null if unclosed. */
    private fun codeSpan(s: String, i: Int): Pair<String, Int>? {
        var n = 0
        while (i + n < s.length && s[i + n] == '`') n++
        var k = i + n
        while (k < s.length) {
            if (s[k] == '`') {
                var m = 0
                while (k + m < s.length && s[k + m] == '`') m++
                if (m == n) {
                    var content = s.substring(i + n, k).replace('\n', ' ')
                    if (content.length >= 2 && content.startsWith(' ') && content.endsWith(' ') && content.isNotBlank()) {
                        content = content.substring(1, content.length - 1)
                    }
                    return content to k + m
                }
                k += m
            } else {
                k++
            }
        }
        return null
    }

    /* --------------------------------------------------------------- maths */

    private fun appendMath(latex: String) {
        val start = sink.length
        sink.append(LatexMath.translate(latex))
        sink.style(InlineStyle.MATH, start, sink.length)
    }

    /** `\(x\)` or `\[x\]` with its backslash at [i]: the source and the index after it. */
    private fun delimitedMath(s: String, i: Int): Pair<String, Int>? {
        val closer = if (s[i + 1] == '(') "\\)" else "\\]"
        val end = s.indexOf(closer, i + 2)
        if (end < 0) return null
        val body = s.substring(i + 2, end)
        if (body.isBlank()) return null
        return body to end + closer.length
    }

    /**
     * `$…$` or `$$…$$` with its first `$` at [i].
     *
     * `$$` is always maths. A single `$` is maths only when the opener is
     * followed by something other than a space, the closer is preceded by
     * something other than a space, the closer is not followed by a digit —
     * "from $5 to $7" fails there — and the body reads as a formula
     * ([LatexMath.looksLikeMath]). The first candidate closer decides: a `$`
     * that fails the test ends the attempt rather than looking further, so a
     * formula never swallows a sentence.
     */
    private fun dollarMath(s: String, i: Int): Pair<String, Int>? {
        if (s.startsWith("$$", i)) {
            val end = s.indexOf("$$", i + 2)
            if (end < 0) return null
            return s.substring(i + 2, end).takeIf { it.isNotBlank() }?.let { it to end + 2 }
        }
        val after = s.getOrNull(i + 1) ?: return null
        if (after.isWhitespace() || after == '$') return null
        var k = i + 1
        while (k < s.length) {
            when (s[k]) {
                '\\' -> {
                    k += 2
                    continue
                }
                // A formula does not run past a blank line.
                '\n' -> if (s.getOrNull(k + 1) == '\n') return null
                '$' -> {
                    if (s[k - 1].isWhitespace()) return null
                    if (s.getOrNull(k + 1)?.isDigit() == true) return null
                    val body = s.substring(i + 1, k)
                    return if (LatexMath.looksLikeMath(body)) body to k + 1 else null
                }
            }
            k++
        }
        return null
    }

    /* ------------------------------------------------------------ emphasis */

    /** Emphasis opening at [i]: the index after its closer, or -1 to treat the run as text. */
    private fun emphasis(s: String, i: Int, depth: Int): Int {
        val c = s[i]
        var n = 1
        while (i + n < s.length && s[i + n] == c) n++
        val after = s.getOrNull(i + n)
        val before = s.getOrNull(i - 1)
        if (after == null || after.isWhitespace()) return -1
        // snake_case_names are not emphasis.
        if (c == '_' && before != null && before.isLetterOrDigit()) return -1

        if (c == '~') {
            // Only "~~": a lone tilde is "approximately" far more often than strikethrough.
            if (n != 2) return -1
            val close = findCloser(s, i + 2, c, 2) ?: return -1
            return wrap(s, i + 2, close, InlineStyle.STRIKE, depth) + 2
        }

        for (len in minOf(n, 3) downTo 1) {
            val close = findCloser(s, i + len, c, len) ?: continue
            if (close <= i + len) continue
            val style = when (len) {
                3 -> InlineStyle.STRONG_EMPHASIS
                2 -> InlineStyle.STRONG
                else -> InlineStyle.EMPHASIS
            }
            return wrap(s, i + len, close, style, depth) + len
        }
        return -1
    }

    /** Emit `s[from, to)` styled; returns [to]. */
    private fun wrap(s: String, from: Int, to: Int, style: InlineStyle, depth: Int): Int {
        val start = sink.length
        emit(s.substring(from, to), depth + 1)
        if (sink.length > start) sink.style(style, start, sink.length)
        return to
    }

    /**
     * Where a run of [len] [c] closes the emphasis opened before [from]: the
     * index of the closing delimiter, or null. Skips code spans and escapes;
     * a `**` run is never the closer of a single `*`; a longer run closes with
     * its last characters, so `***x***` pairs as strong around emphasis.
     */
    private fun findCloser(s: String, from: Int, c: Char, len: Int): Int? {
        var k = from
        while (k < s.length) {
            val ch = s[k]
            if (ch == '\\') {
                k += 2
                continue
            }
            if (ch == '`') {
                val span = codeSpan(s, k)
                if (span != null) {
                    k = span.second
                    continue
                }
            }
            if (ch == c) {
                var end = k
                while (end < s.length && s[end] == c) end++
                val run = end - k
                val prev = s[k - 1]
                val next = s.getOrNull(end)
                val canClose = !prev.isWhitespace() && (c != '_' || next == null || !next.isLetterOrDigit())
                if (canClose && run >= len && !(len == 1 && run == 2)) return end - len
                k = end
                continue
            }
            k++
        }
        return null
    }

    /* --------------------------------------------------------------- links */

    /** `[text](url)` or `![alt](url)` with its `[` at [i]; returns the index after it, or -1. */
    private fun link(s: String, i: Int, depth: Int, image: Boolean): Int {
        val close = matchingBracket(s, i) ?: return -1
        if (s.getOrNull(close + 1) != '(') return -1
        val destination = destination(s, close + 2) ?: return -1
        val label = s.substring(i + 1, close)
        val start = sink.length
        if (image) {
            // No image loading in chat: the alt text, linked to the image.
            sink.append(label.ifBlank { destination.first.substringAfterLast('/') })
        } else {
            emit(label, depth + 1)
        }
        addLink(destination.first, start, sink.length)
        return destination.second
    }

    private fun matchingBracket(s: String, open: Int): Int? {
        var depth = 0
        var k = open
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '`' -> codeSpan(s, k)?.let { k = it.second - 1 }
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return k
                }
                '\n' -> if (k + 1 < s.length && s[k + 1] == '\n') return null
            }
            k++
        }
        return null
    }

    /** The `(url "title")` part from just after `(`: url and the index after `)`. */
    private fun destination(s: String, from: Int): Pair<String, Int>? {
        var k = from
        while (k < s.length && s[k] == ' ') k++
        val url: String
        if (s.getOrNull(k) == '<') {
            val end = s.indexOf('>', k + 1).takeIf { it > 0 } ?: return null
            url = s.substring(k + 1, end)
            k = end + 1
        } else {
            val start = k
            var parens = 0
            while (k < s.length) {
                val ch = s[k]
                if (ch.isWhitespace()) break
                if (ch == '\\' && k + 1 < s.length) {
                    k += 2
                    continue
                }
                if (ch == '(') parens++
                if (ch == ')') {
                    if (parens == 0) break
                    parens--
                }
                k++
            }
            url = s.substring(start, k)
        }
        while (k < s.length && s[k] == ' ') k++
        // Optional title, which is not shown.
        val quote = s.getOrNull(k)
        if (quote == '"' || quote == '\'' || quote == '(') {
            val closing = if (quote == '(') ')' else quote
            val end = s.indexOf(closing, k + 1).takeIf { it > 0 } ?: return null
            k = end + 1
            while (k < s.length && s[k] == ' ') k++
        }
        if (s.getOrNull(k) != ')') return null
        return url to k + 1
    }

    private fun bareUrl(s: String, i: Int): String? {
        val www = s.length - i > 4 && s.regionMatches(i, "www.", 0, 4, ignoreCase = true)
        val http = s.regionMatches(i, "http://", 0, 7, ignoreCase = true) ||
            s.regionMatches(i, "https://", 0, 8, ignoreCase = true)
        if (!www && !http) return null
        var end = i
        while (end < s.length && !s[end].isWhitespace() && s[end] !in "<>\"'`[]{}|") end++
        var url = s.substring(i, end)
        // Trailing punctuation belongs to the sentence, not the address; a
        // closing paren only when it is unbalanced, as in "(see https://x)".
        while (url.isNotEmpty()) {
            val last = url.last()
            url = when {
                last in ".,;:!?*_~" -> url.dropLast(1)
                last == ')' && url.count { it == '(' } < url.count { it == ')' } -> url.dropLast(1)
                else -> break
            }
        }
        val minimum = if (www) 6 else 10
        return url.takeIf { it.length >= minimum && it.substringAfter("://", it).contains('.') }
    }

    private fun addLink(url: String, start: Int, end: Int) {
        if (end <= start) return
        MarkdownInline.classify(url)?.let { sink.link(it, start, end) }
    }

    /* ---------------------------------------------------- <tags> and <urls> */

    /** Something starting with `<` at [i]: an autolink, an HTML tag or comment. Returns the next index or -1. */
    private fun angle(s: String, i: Int): Int {
        AUTOLINK.matchAt(s, i)?.let { match ->
            val target = match.groupValues[1]
            val start = sink.length
            sink.append(target)
            addLink(target, start, sink.length)
            return i + match.value.length
        }
        EMAIL_AUTOLINK.matchAt(s, i)?.let { match ->
            val address = match.groupValues[1]
            val start = sink.length
            sink.append(address)
            addLink("mailto:$address", start, sink.length)
            return i + match.value.length
        }
        if (s.startsWith("<!--", i)) {
            val end = s.indexOf("-->", i + 4)
            return if (end < 0) s.length else end + 3
        }
        val tag = TAG.matchAt(s, i) ?: return -1
        val closing = tag.groupValues[1] == "/"
        val name = tag.groupValues[2]
        // Only lowercase tags this knows: `Vec<String>`, `Pair<B>` and
        // `a < b > c` stay text. Models write HTML in lowercase.
        if (name !in KNOWN_TAGS) return -1
        handleTag(name, closing, tag.groupValues[3], selfClosing = tag.groupValues[4] == "/")
        return i + tag.value.length
    }

    private fun handleTag(name: String, closing: Boolean, attributes: String, selfClosing: Boolean) {
        when (name) {
            "br" -> sink.append("\n")
            "hr" -> sink.append("\n")
            "p", "div", "ul", "ol", "table", "tr", "details" -> if (closing) sink.append("\n")
            "li" -> if (!closing) sink.append("\n• ")
            "td", "th" -> if (closing) sink.append("  ")
            "summary" -> {
                if (closing) {
                    close("b")
                    sink.append("\n")
                } else {
                    tags += OpenTag("b", sink.length, null)
                }
            }
            "img" -> attribute(attributes, "alt")?.takeIf { it.isNotBlank() }?.let { alt ->
                val start = sink.length
                sink.append(alt)
                attribute(attributes, "src")?.let { addLink(it, start, sink.length) }
            }
            else -> {
                if (closing) {
                    close(name)
                } else if (!selfClosing) {
                    tags += OpenTag(name, sink.length, if (name == "a") attribute(attributes, "href") else null)
                }
            }
        }
    }

    private fun close(name: String) {
        val index = tags.indexOfLast { it.name == name }
        if (index < 0) return
        apply(tags.removeAt(index), sink.length)
    }

    /** Tags still open at the end apply to the end, as a browser would. */
    fun closeOpenTags() {
        for (tag in tags.asReversed()) apply(tag, sink.length)
        tags.clear()
    }

    private fun apply(tag: OpenTag, end: Int) {
        if (end <= tag.start) return
        val style = when (tag.name) {
            "b", "strong" -> InlineStyle.STRONG
            "i", "em", "cite", "var" -> InlineStyle.EMPHASIS
            "u", "ins" -> InlineStyle.UNDERLINE
            "s", "del", "strike" -> InlineStyle.STRIKE
            "code", "kbd", "samp", "tt" -> InlineStyle.CODE
            "mark" -> InlineStyle.MARK
            "sub" -> InlineStyle.SUB
            "sup" -> InlineStyle.SUP
            "small" -> InlineStyle.SMALL
            "a" -> {
                tag.href?.let { addLink(decodeEntities(it), tag.start, end) }
                return
            }
            else -> return
        }
        sink.style(style, tag.start, end)
    }

    private fun attribute(attributes: String, name: String): String? =
        Regex("""(?i)\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""").find(attributes)?.let { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }
        }

    /* ------------------------------------------------------------ entities */

    private fun decodeEntities(text: String): String =
        ENTITY.replace(text) { match -> decodeEntity(match.groupValues[1]) ?: match.value }

    private fun decodeEntity(body: String): String? = when {
        body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
        body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
        else -> NAMED_ENTITIES[body]
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null

    companion object {
        /** Nesting beyond this is drawn as text rather than recursed into. */
        const val MAX_DEPTH = 16

        const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        val KNOWN_TAGS = setOf(
            "br", "hr", "p", "div", "ul", "ol", "li", "table", "tr", "td", "th", "details", "summary", "img",
            "b", "strong", "i", "em", "cite", "var", "u", "ins", "s", "del", "strike",
            "code", "kbd", "samp", "tt", "mark", "sub", "sup", "small", "a", "span",
        )

        val AUTOLINK = Regex("""<([a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^\s<>]+)>""")
        val EMAIL_AUTOLINK = Regex("""<([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})>""")
        val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^<>]*?)?)\s*(/?)>""")
        val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});""")

        val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
            "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
            "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
            "rarr" to "→", "larr" to "←", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "times" to "×",
            "divide" to "÷", "plusmn" to "±", "middot" to "·", "bull" to "•", "deg" to "°", "euro" to "€",
            "check" to "✓", "ne" to "≠", "le" to "≤", "ge" to "≥", "infin" to "∞", "para" to "¶", "sect" to "§",
        )
    }
}
