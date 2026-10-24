package dev.apkcli.core.web

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Keyless web search over DuckDuckGo's no-JavaScript results page.
 *
 * DuckDuckGo has two such pages and, measured in October 2026, they do not
 * behave alike towards a scripted client:
 *
 *  * `html.duckduckgo.com/html/` (POST) answered with HTTP 202 and an image
 *    CAPTCHA (`anomaly.js`) — no results.
 *  * `lite.duckduckgo.com/lite/` (GET) returned ten results.
 *
 * So this uses lite. It is rendered on the server — one table row per result,
 * no script — which is why a plain HTTPS GET and a small parser are enough. A
 * WebView would cost a renderer process, ~100 MB and the main thread, and would
 * not get past a CAPTCHA either, because that one wants a human.
 *
 * The markup this depends on, per result:
 *
 * ```
 * <a rel="nofollow" href="//duckduckgo.com/l/?uddg=<encoded target>&amp;rut=…" class='result-link'>Title</a>
 * <td class='result-snippet'>… <b>matched</b> words …</td>
 * <span class='link-text'>host/path</span>
 * ```
 *
 * It is scraping, so it can break. Everything here fails loudly instead of
 * returning an empty list that reads as "no results": a page with neither
 * results nor a no-results marker is reported as a format change.
 *
 * Pure Kotlin, no Android types, so it can be exercised on a plain JVM.
 */
object DuckDuckGoLite {

    const val ENDPOINT = "https://lite.duckduckgo.com/lite/"

    data class Hit(val title: String, val url: String, val snippet: String)

    enum class Recency(val code: String) { DAY("d"), WEEK("w"), MONTH("m"), YEAR("y") }

    sealed interface Parsed {
        data class Results(val hits: List<Hit>) : Parsed

        /** The page says there is nothing. Distinct from a page we cannot read. */
        data object NoResults : Parsed

        /** DuckDuckGo served its bot check instead of results. */
        data object BotCheck : Parsed

        /** Neither results nor a known empty page: the markup has changed. */
        data object Unrecognised : Parsed
    }

    fun buildUrl(query: String, region: String, recency: Recency?): String = buildString {
        append(ENDPOINT)
        append("?q=").append(URLEncoder.encode(query, "UTF-8"))
        append("&kl=").append(URLEncoder.encode(region.ifBlank { "wt-wt" }, "UTF-8"))
        recency?.let { append("&df=").append(it.code) }
    }

    fun parse(html: String): Parsed {
        val anchors = RESULT_LINK.findAll(html).toList()
        if (anchors.isEmpty()) {
            return when {
                BOT_CHECK.containsMatchIn(html) -> Parsed.BotCheck
                NO_RESULTS.containsMatchIn(html) -> Parsed.NoResults
                else -> Parsed.Unrecognised
            }
        }

        val hits = ArrayList<Hit>(anchors.size)
        for ((index, anchor) in anchors.withIndex()) {
            val attributes = anchor.groupValues[1]
            val href = HREF.find(attributes)?.groupValues?.get(1) ?: continue
            val target = resolveTarget(HtmlExtractor.decodeEntities(href)) ?: continue
            val title = clean(anchor.groupValues[2])
            if (title.isBlank()) continue

            // The snippet is the first result-snippet cell after this link and
            // before the next one; results without an abstract simply have none.
            val regionEnd = anchors.getOrNull(index + 1)?.range?.first ?: html.length
            val region = html.substring(anchor.range.last + 1, regionEnd)
            val snippet = SNIPPET.find(region)?.groupValues?.get(1)?.let(::clean).orEmpty()

            hits += Hit(title = title, url = target, snippet = snippet)
        }

        // Every link was an ad or unparseable. Report that as unrecognised
        // rather than "no results": it means the parser, not the web, is wrong.
        if (hits.isEmpty()) return Parsed.Unrecognised
        return Parsed.Results(hits.distinctBy { it.url })
    }

    /**
     * The real destination of a result link.
     *
     * Results go through DuckDuckGo's redirector, `/l/?uddg=<target>`. Ads go
     * through `/y.js` and never point anywhere but DuckDuckGo itself, so any
     * target on duckduckgo.com is dropped.
     */
    private fun resolveTarget(href: String): String? {
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val encoded = UDDG.find(absolute)?.groupValues?.get(1)
        val target = if (encoded != null) {
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        } else {
            absolute
        }
        if (!target.startsWith("https://") && !target.startsWith("http://")) return null
        val host = target.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) return null
        return target
    }

    /**
     * Markup to text. Inline emphasis — how matched words are highlighted —
     * is removed outright, because `kotlinx.<b>coroutines</b>` must stay one
     * token; any other tag becomes a space.
     */
    private fun clean(fragment: String): String =
        HtmlExtractor.decodeEntities(TAG.replace(INLINE.replace(fragment, ""), " "))
            .replace(WHITESPACE, " ")
            .trim()

    private val FLAGS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val RESULT_LINK =
        Regex("""<a\b([^>]*\bclass\s*=\s*['"]result-link['"][^>]*)>(.*?)</a>""", FLAGS)
    private val HREF = Regex("""\bhref\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val SNIPPET =
        Regex("""<td\b[^>]*\bclass\s*=\s*['"]result-snippet['"][^>]*>(.*?)</td>""", FLAGS)
    private val UDDG = Regex("""[?&]uddg=([^&]+)""")
    private val INLINE = Regex("""</?(b|strong|em|i|span)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")

    private val BOT_CHECK = Regex("anomaly|captcha|unusual traffic|bots use duckduckgo", RegexOption.IGNORE_CASE)
    private val NO_RESULTS = Regex("""no\s+(more\s+)?results""", RegexOption.IGNORE_CASE)
}
