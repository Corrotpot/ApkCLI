package dev.apkcli.core.web

import dev.apkcli.core.llm.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A page a search found: what it is, where, and the part of it that answers the query. */
data class SearchHit(val title: String, val url: String, val excerpt: String, val published: String? = null)

/** What a search service is asked. */
data class SearchQuery(
    /** Keywords, as a developer types them. */
    val query: String,
    /** What the search is for, in a sentence: the services rank pages and pick excerpts by it. */
    val objective: String,
    val count: Int,
    /** Only pages on this host and its subdomains. */
    val site: String? = null,
    /** The chat asking, for a service that keeps a session per conversation (Parallel). */
    val chatId: String? = null,
)

/** A page as a service read it on our behalf: where it is, its title, its text as Markdown. */
data class ReadPage(val url: String, val title: String, val text: String)

/** A free search service, Exa's or Parallel's: search, and read one page. */
internal interface SearchService {
    val name: String

    suspend fun search(query: SearchQuery): ServiceAnswer<List<SearchHit>>

    suspend fun read(url: String, maxChars: Int, chatId: String?): ServiceAnswer<ReadPage>
}

internal sealed interface ServiceAnswer<out T> {
    data class Ok<T>(val value: T) : ServiceAnswer<T>

    data class Failed(val reason: String, val rest: RemoteMcp.Rest) : ServiceAnswer<Nothing>
}

private fun RemoteMcp.Outcome.Failed.answer() = ServiceAnswer.Failed(reason, rest)

/** A search's answer is at most this large; Parallel's ten results are about 60 KB. */
private const val SEARCH_BYTES = 1L * 1024 * 1024

/** A page read for us is at most this large: Parallel sends the whole page. */
private const val READ_BYTES = 8L * 1024 * 1024

/** One service's turn; two of them fit in the tool's minute. */
private const val CALL_TIMEOUT_MS = 25_000L

/**
 * Exa's free MCP server (`mcp.exa.ai`): `web_search_exa` and `web_fetch_exa`.
 * Its answers are text: per page a header of `Title:`, `URL:`, `Published:`
 * and `Author:` lines, then `Highlights:` and the excerpt.
 */
internal object ExaService : SearchService {
    override val name = "Exa"

    private const val ENDPOINT = "https://mcp.exa.ai/mcp"

    override suspend fun search(query: SearchQuery): ServiceAnswer<List<SearchHit>> {
        val arguments = buildJsonObject {
            // Exa keeps to a host given as `site:` in the query.
            put("query", query.site?.let { "${query.query} site:$it" } ?: query.query)
            put("objective", query.objective)
            put("numResults", query.count)
        }
        return when (val outcome = RemoteMcp.call(ENDPOINT, "web_search_exa", arguments, CALL_TIMEOUT_MS, SEARCH_BYTES)) {
            is RemoteMcp.Outcome.Failed -> outcome.answer()
            is RemoteMcp.Outcome.Done -> ServiceAnswer.Ok(parseResults(outcome.text))
        }
    }

    override suspend fun read(url: String, maxChars: Int, chatId: String?): ServiceAnswer<ReadPage> {
        val arguments = buildJsonObject {
            putJsonArray("urls") { add(url) }
            put("maxCharacters", maxChars)
        }
        return when (val outcome = RemoteMcp.call(ENDPOINT, "web_fetch_exa", arguments, CALL_TIMEOUT_MS, READ_BYTES)) {
            is RemoteMcp.Outcome.Failed -> outcome.answer()
            is RemoteMcp.Outcome.Done -> parsePage(outcome.text, url)?.let { ServiceAnswer.Ok(it) }
                ?: ServiceAnswer.Failed("Exa returned no text for $url", RemoteMcp.Rest.None)
        }
    }

    /**
     * Pages are separated by a ruled line before the next `Title:`; a rule
     * inside an excerpt, as Markdown pages have, does not split it.
     */
    fun parseResults(text: String): List<SearchHit> = text.split(NEXT_PAGE).mapNotNull { block ->
        var title: String? = null
        var url: String? = null
        var published: String? = null
        val lines = block.trim().lines()
        var excerpt = ""
        for ((index, line) in lines.withIndex()) {
            val head = EXCERPT_HEAD.matchEntire(line)
            if (head != null) {
                excerpt = (listOf(head.groupValues[2]) + lines.drop(index + 1)).joinToString("\n").trim()
                break
            }
            when {
                line.startsWith("Title:") -> title = known(line.substringAfter(':'))
                line.startsWith("URL:") -> url = known(line.substringAfter(':'))
                line.startsWith("Published:") -> published = known(line.substringAfter(':'))
            }
        }
        val address = url ?: return@mapNotNull null
        SearchHit(title ?: address, address, excerpt, published)
    }

    /** `# Title`, `URL: …`, a blank line, then the page as Markdown. */
    fun parsePage(text: String, requested: String): ReadPage? {
        val lines = text.trim().lines()
        val title = lines.firstOrNull()?.takeIf { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        val urlLine = lines.indexOfFirst { it.startsWith("URL:") }.takeIf { it in 0..2 }
        val url = urlLine?.let { known(lines[it].substringAfter(':')) } ?: requested
        val body = (if (urlLine != null) lines.drop(urlLine + 1) else lines.drop(if (title != null) 1 else 0))
            .joinToString("\n").trim()
        if (body.isEmpty()) return null
        return ReadPage(url, title.orEmpty(), body)
    }

    private fun known(value: String): String? = value.trim().takeIf { it.isNotEmpty() && it != "N/A" }

    private val NEXT_PAGE = Regex("\n\n---\n\n(?=Title: )")
    private val EXCERPT_HEAD = Regex("^(Highlights|Text|Summary):\\s*(.*)$")
}

/**
 * Parallel's free MCP server (`mcp.parallel.ai`): `web_search` and
 * `web_fetch`, answering in JSON, structured and as text. Each answer names
 * a session that the next call in the same conversation is to pass back.
 */
internal object ParallelService : SearchService {
    override val name = "Parallel"

    private const val ENDPOINT = "https://mcp.parallel.ai/mcp"

    /** Parallel's session for each chat, the newest [SESSIONS_KEPT]. */
    private val sessions = object : LinkedHashMap<String, String>(SESSIONS_KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > SESSIONS_KEPT
    }

    override suspend fun search(query: SearchQuery): ServiceAnswer<List<SearchHit>> {
        val arguments = buildJsonObject {
            put("objective", query.objective)
            putJsonArray("search_queries") { add(keywords(query)) }
            sessionOf(query.chatId)?.let { put("session_id", it) }
        }
        return when (val outcome = RemoteMcp.call(ENDPOINT, "web_search", arguments, CALL_TIMEOUT_MS, SEARCH_BYTES)) {
            is RemoteMcp.Outcome.Failed -> outcome.answer()
            is RemoteMcp.Outcome.Done -> {
                val answer = outcome.structured ?: Http.parseObject(outcome.text)
                    ?: return ServiceAnswer.Failed("Parallel's answer was not JSON", RemoteMcp.Rest.Brief)
                keepSession(query.chatId, answer)
                ServiceAnswer.Ok(parseResults(answer))
            }
        }
    }

    override suspend fun read(url: String, maxChars: Int, chatId: String?): ServiceAnswer<ReadPage> {
        val arguments = buildJsonObject {
            putJsonArray("urls") { add(url) }
            // The page whole, not the excerpts for an objective there is none of.
            put("full_content", true)
            sessionOf(chatId)?.let { put("session_id", it) }
        }
        return when (val outcome = RemoteMcp.call(ENDPOINT, "web_fetch", arguments, CALL_TIMEOUT_MS, READ_BYTES)) {
            is RemoteMcp.Outcome.Failed -> outcome.answer()
            is RemoteMcp.Outcome.Done -> {
                val answer = outcome.structured ?: Http.parseObject(outcome.text)
                    ?: return ServiceAnswer.Failed("Parallel's answer was not JSON", RemoteMcp.Rest.Brief)
                keepSession(chatId, answer)
                parsePage(answer, url)
            }
        }
    }

    fun parseResults(answer: JsonObject): List<SearchHit> = answer.objects("results").mapNotNull { result ->
        val url = result.text("url") ?: return@mapNotNull null
        SearchHit(
            title = result.text("title") ?: url,
            url = url,
            excerpt = result.strings("excerpts").joinToString("\n…\n") { it.trim() },
            published = result.text("publish_date"),
        )
    }

    fun parsePage(answer: JsonObject, requested: String): ServiceAnswer<ReadPage> {
        answer.objects("results").firstOrNull()?.let { result ->
            val text = result.text("full_content") ?: result.strings("excerpts").joinToString("\n\n").takeIf { it.isNotBlank() }
            if (text != null) {
                return ServiceAnswer.Ok(ReadPage(result.text("url") ?: requested, result.text("title").orEmpty(), text.trim()))
            }
        }
        // Why it could not: `connect_error`, or the site's own status.
        val error = answer.objects("errors").firstOrNull()
        val why = listOfNotNull(error?.text("error_type"), error?.text("http_status_code")?.let { "HTTP $it" })
            .joinToString(", ").ifEmpty { "no content" }
        return ServiceAnswer.Failed("Parallel could not read it ($why)", RemoteMcp.Rest.None)
    }

    /** Its queries take no quoted phrases, and `site:` with a bare host. */
    private fun keywords(query: SearchQuery): String {
        val words = query.query.replace("\"", " ").replace(Regex("\\s+"), " ").trim()
        return query.site?.let { "$words site:$it" } ?: words
    }

    private fun sessionOf(chatId: String?): String? = chatId?.let { synchronized(sessions) { sessions[it] } }

    private fun keepSession(chatId: String?, answer: JsonObject) {
        val session = answer.text("session_id") ?: return
        chatId ?: return
        synchronized(sessions) { sessions[chatId] = session }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

    private const val SESSIONS_KEPT = 64
}
