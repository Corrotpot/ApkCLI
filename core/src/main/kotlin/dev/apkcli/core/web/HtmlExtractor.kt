package dev.apkcli.core.web

/**
 * HTML to readable Markdown-ish text.
 *
 * Hand-written rather than pulling in jsoup. The job is narrow — produce text a
 * language model can read, from arbitrary and frequently malformed HTML — and a
 * full DOM parser plus a Markdown converter is ~600 KB of dependency for it.
 * There is no DOM to query here; nothing downstream needs selectors.
 *
 * What it does, in order:
 *   1. Delete the elements that are never content: `script`, `style`, `svg`,
 *      `head`, `noscript`, and the navigational chrome (`nav`, `header`,
 *      `footer`, `aside`).
 *   2. Walk the remaining tags, converting the structural ones to Markdown and
 *      dropping the rest, keeping block boundaries so paragraphs survive.
 *   3. Preserve `pre`/`code` verbatim, because on a documentation page the code
 *      is usually the reason for fetching it, and whitespace there is load-bearing.
 *   4. Collapse whitespace everywhere else. Raw HTML indentation can easily be
 *      half the token count of a page.
 *
 * Deliberately tolerant: unbalanced tags, unquoted attributes and missing
 * closes are the norm, and bailing out on them would mean failing on most of
 * the real web.
 */
object HtmlExtractor {

    data class Result(
        val title: String,
        val text: String,
        /** Links found in the body, deduplicated, in document order. */
        val links: List<Link>,
        /** True if the page looked like an empty JS shell. */
        val looksLikeEmptyShell: Boolean,
    ) {
        data class Link(val text: String, val href: String)
    }

    /** A set, not a list: this is tested once per tag on an 8 MB document. */
    private val DROP_ELEMENTS = setOf(
        "script", "style", "noscript", "svg", "head", "template", "iframe",
        "nav", "header", "footer", "aside", "form", "button", "select",
    )

    private val BLOCK_ELEMENTS = setOf(
        "p", "div", "section", "article", "main", "ul", "ol", "dl", "dt", "dd",
        "table", "thead", "tbody", "tfoot", "blockquote", "figure", "figcaption",
        "address", "fieldset", "hr", "details", "summary",
    )

    fun extract(html: String, baseUrl: String = ""): Result {
        val title = findTitle(html)
        val working = stripComments(html)

        val links = mutableListOf<Result.Link>()
        val out = StringBuilder(working.length / 3)
        var index = 0
        var inPre = false
        var listDepth = 0
        var pendingLinkHref: String? = null
        val linkText = StringBuilder()

        // Nesting depth inside an element whose contents are discarded. A
        // counter rather than a boolean because `<div>` inside a dropped
        // `<nav>` is common, and `<svg>` legitimately nests.
        var dropDepth = 0
        var dropElement: String? = null

        while (index < working.length) {
            val ch = working[index]

            if (ch != '<') {
                val end = working.indexOf('<', index).let { if (it < 0) working.length else it }
                if (dropDepth == 0) {
                    val chunk = decodeEntities(working.substring(index, end))
                    if (inPre) {
                        out.append(chunk)
                    } else {
                        appendCollapsed(out, chunk)
                    }
                    if (pendingLinkHref != null) linkText.append(chunk)
                }
                index = end
                continue
            }

            val close = working.indexOf('>', index)
            if (close < 0) break
            val raw = working.substring(index + 1, close)
            val selfClosing = raw.endsWith("/")
            index = close + 1

            val isClosing = raw.startsWith("/")
            val body = raw.removePrefix("/").trim()
            if (body.isEmpty() || body.startsWith("!")) continue
            val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()

            // Elements whose contents are never content. Skipped inline rather
            // than by pre-filtering the document: the previous version ran one
            // full scan-and-rebuild per element type, so a 12-name list meant
            // twelve copies of the whole page — around 96 MB of allocation
            // churn for an 8 MB document, on a device where that is the
            // difference between working and being killed.
            if (dropDepth > 0) {
                if (name == dropElement) {
                    if (isClosing) {
                        dropDepth--
                        if (dropDepth == 0) dropElement = null
                    } else if (!selfClosing) {
                        dropDepth++
                    }
                }
                continue
            }
            if (!isClosing && name in DROP_ELEMENTS && !selfClosing) {
                dropDepth = 1
                dropElement = name
                continue
            }
            if (isClosing && name in DROP_ELEMENTS) {
                // A stray close tag with no open. Ignore it.
                continue
            }

            when {
                name == "pre" -> {
                    inPre = !isClosing
                    out.append(if (inPre) "\n\n```\n" else "\n```\n\n")
                }

                name == "code" && !inPre -> out.append('`')

                name == "br" -> out.append('\n')

                name == "hr" -> out.append("\n\n---\n\n")

                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> {
                    if (isClosing) {
                        out.append("\n\n")
                    } else {
                        val level = name[1] - '0'
                        out.append("\n\n").append("#".repeat(level)).append(' ')
                    }
                }

                name == "li" -> {
                    if (!isClosing) {
                        out.append('\n').append("  ".repeat(maxOf(0, listDepth - 1))).append("- ")
                    }
                }

                name == "ul" || name == "ol" -> {
                    if (isClosing) listDepth = maxOf(0, listDepth - 1) else listDepth++
                    out.append("\n")
                }

                name == "tr" -> if (isClosing) out.append('\n')

                name == "td" || name == "th" -> if (isClosing) out.append(" | ")

                name == "a" -> {
                    if (isClosing) {
                        val href = pendingLinkHref
                        val label = linkText.toString().trim()
                        if (href != null && label.isNotEmpty()) {
                            val resolved = resolveUrl(baseUrl, href)
                            // Inline the URL only when it adds information. A
                            // page of 400 nav links otherwise doubles in size.
                            if (resolved.isNotEmpty() && !label.startsWith("http")) {
                                out.append(" (").append(resolved).append(')')
                            }
                            links += Result.Link(label, resolved)
                        }
                        pendingLinkHref = null
                        linkText.setLength(0)
                    } else {
                        pendingLinkHref = attribute(body, "href")
                        linkText.setLength(0)
                    }
                }

                name == "img" -> {
                    val alt = attribute(body, "alt")?.trim()
                    if (!alt.isNullOrEmpty()) out.append("[image: ").append(alt).append(']')
                }

                name in BLOCK_ELEMENTS -> out.append("\n\n")
            }
        }

        val text = tidy(out.toString())
        return Result(
            title = title,
            text = text,
            links = links.distinctBy { it.href }.take(200),
            looksLikeEmptyShell = isEmptyShell(text, html),
        )
    }

    /* ------------------------------------------------------------- helpers */

    private val TITLE_PATTERN = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val OG_TITLE_PATTERN = Regex(
        """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    private fun findTitle(html: String): String {
        TITLE_PATTERN.find(html)?.groupValues?.getOrNull(1)
            ?.let { return decodeEntities(it).trim().take(300) }
        // Fall back to og:title, which SPAs often have even when <title> is generic.
        OG_TITLE_PATTERN.find(html)?.groupValues?.getOrNull(1)
            ?.let { return decodeEntities(it).trim().take(300) }
        return ""
    }

    private val COMMENT_PATTERN = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    private fun stripComments(html: String): String = html.replace(COMMENT_PATTERN, " ")

    /**
     * Attribute patterns, compiled once.
     *
     * These are consulted per tag, and a page has thousands. Building a `Regex`
     * inside the loop recompiled the pattern every time — compilation dominated
     * the extraction cost on any real document.
     */
    private val ATTRIBUTE_PATTERNS: Map<String, Regex> = listOf("href", "alt", "src", "content")
        .associateWith { name ->
            Regex(
                """\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""",
                RegexOption.IGNORE_CASE,
            )
        }

    private fun attribute(tagBody: String, name: String): String? {
        val pattern = ATTRIBUTE_PATTERNS[name] ?: return null
        val match = pattern.find(tagBody) ?: return null
        return (
            match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                ?: match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
                ?: match.groupValues.getOrNull(4)
            )?.let { decodeEntities(it) }
    }

    private fun appendCollapsed(out: StringBuilder, chunk: String) {
        var lastWasSpace = out.isNotEmpty() && out.last().isWhitespace()
        for (c in chunk) {
            if (c.isWhitespace()) {
                if (!lastWasSpace) {
                    out.append(' ')
                    lastWasSpace = true
                }
            } else {
                out.append(c)
                lastWasSpace = false
            }
        }
    }

    private val TRAILING_SPACE = Regex("[ \\t]+\n")
    private val BLANK_RUN = Regex("\n{3,}")
    private val DOUBLE_SPACE = Regex(" {2,}")
    private val DANGLING_CELL = Regex("\\|\\s*\n")

    private fun tidy(text: String): String = text
        .replace(TRAILING_SPACE, "\n")
        .replace(BLANK_RUN, "\n\n")
        .replace(DOUBLE_SPACE, " ")
        .replace(DANGLING_CELL, "\n")
        .trim()

    /**
     * Did we get a client-rendered shell rather than content?
     *
     * The signal is a page whose markup is substantial but whose extracted text
     * is tiny — a React or Vue app before hydration. When this is true the
     * caller is told to retry with JavaScript rendering, instead of handing the
     * model three words and a copyright notice.
     */
    private fun isEmptyShell(text: String, html: String): Boolean {
        if (text.length > 800) return false
        if (html.length < 2_000) return false
        val markers = listOf(
            "id=\"root\"", "id=\"app\"", "id=\"__next\"", "data-reactroot",
            "ng-app", "id=\"q-app\"", "__NUXT__", "data-svelte",
        )
        val hasShellMarker = markers.any { html.contains(it, ignoreCase = true) }
        // A big document that yields almost no prose is suspicious even without
        // a recognised framework marker.
        return hasShellMarker || (html.length > 20_000 && text.length < 400)
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"",
        "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "plusmn" to "±", "times" to "×",
        "divide" to "÷", "micro" to "µ", "para" to "¶", "sect" to "§",
        "bull" to "•", "middot" to "·", "euro" to "€", "pound" to "£",
        "yen" to "¥", "cent" to "¢", "larr" to "←", "rarr" to "→",
        "uarr" to "↑", "darr" to "↓", "harr" to "↔", "shy" to "",
        "zwj" to "", "zwnj" to "", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
    )

    fun decodeEntities(input: String): String {
        if ('&' !in input) return input
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = input.indexOf(';', i + 1)
            // An unterminated or absurdly long entity is just a literal '&'.
            if (semi < 0 || semi - i > 12) {
                out.append(c)
                i++
                continue
            }
            val entity = input.substring(i + 1, semi)
            val replacement = when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let { codePointToString(it) }
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull()?.let { codePointToString(it) }
                else -> NAMED_ENTITIES[entity]
            }
            if (replacement != null) {
                out.append(replacement)
                i = semi + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun codePointToString(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

    /** Minimal URL resolution: enough for href rewriting, not a URL library. */
    fun resolveUrl(base: String, href: String): String {
        val trimmed = href.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.startsWith("javascript:") || trimmed.startsWith("#") -> ""
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            base.isEmpty() -> trimmed
            trimmed.startsWith("/") -> {
                val scheme = base.substringBefore("://", "https")
                val host = base.removePrefix("$scheme://").substringBefore('/')
                "$scheme://$host$trimmed"
            }
            else -> base.substringBeforeLast('/', base).trimEnd('/') + "/" + trimmed
        }
    }
}
