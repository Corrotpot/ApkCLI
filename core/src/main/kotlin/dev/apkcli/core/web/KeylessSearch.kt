package dev.apkcli.core.web

import java.util.concurrent.ConcurrentHashMap

/**
 * Web search with no key and no account: the free MCP servers Exa and
 * Parallel run for agents, the ones OpenCode searches with. Results come with
 * an excerpt of each page picked for the search's objective, rather than the
 * one line a results page has, and there is no bot check to run into.
 *
 * Exa is asked first for a search, Parallel first to read a page (it sends
 * the whole page as Markdown, following redirects). A service that fails
 * makes way for the other, and one that asked to slow down, timed out or
 * refused this device is left alone for a while ([RemoteMcp.Rest]), for every
 * chat in the process: their free limits are per address.
 */
class KeylessSearch internal constructor(
    private val searchOrder: List<SearchService>,
    private val readOrder: List<SearchService>,
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface Outcome<out T> {
        /** [service] answered, with [value]. */
        data class Found<T>(val service: String, val value: T) : Outcome<T>

        /** Every service failed: [reasons] says how each did; [retryInMs] when the soonest of them may be asked again. */
        data class Failed(val reasons: List<String>, val retryInMs: Long?) : Outcome<Nothing>
    }

    /** Until when each service is left alone, by name. */
    private val restingUntil = ConcurrentHashMap<String, Long>()

    suspend fun search(query: SearchQuery): Outcome<List<SearchHit>> = firstAnswer(searchOrder) { it.search(query) }

    /** [url] read by a service, for a page that would not answer the device itself. */
    suspend fun read(url: String, maxChars: Int, chatId: String?): Outcome<ReadPage> =
        firstAnswer(readOrder) { it.read(url, maxChars, chatId) }

    private suspend fun <T> firstAnswer(
        services: List<SearchService>,
        ask: suspend (SearchService) -> ServiceAnswer<T>,
    ): Outcome<T> {
        val reasons = mutableListOf<String>()
        for (service in services) {
            val left = resting(service.name)
            if (left > 0) {
                reasons += "${service.name}: resting for another ${seconds(left)} s"
                continue
            }
            when (val answer = ask(service)) {
                is ServiceAnswer.Ok -> return Outcome.Found(service.name, answer.value)
                is ServiceAnswer.Failed -> {
                    reasons += "${service.name}: ${answer.reason}"
                    restFor(answer.rest)?.let { restingUntil[service.name] = now() + it }
                }
            }
        }
        val soonest = services.map { resting(it.name) }.filter { it > 0 }.minOrNull()
        return Outcome.Failed(reasons, soonest)
    }

    private fun resting(name: String): Long = (restingUntil[name] ?: return 0) - now()

    companion object {
        /** One for the process: a service's request to slow down holds for every chat. */
        val shared = KeylessSearch(
            searchOrder = listOf(ExaService, ParallelService),
            readOrder = listOf(ParallelService, ExaService),
        )

        /** After a timeout or a 5xx: long enough not to wait for it on the next search too. */
        const val BRIEF_REST_MS = 20_000L

        /** When it asked to slow down and did not say for how long. */
        const val ASKED_REST_MS = 60_000L

        /** It refused this device outright. */
        const val CLOSED_REST_MS = 10 * 60_000L

        /** A Retry-After is taken up to this much: past it the other service carries the searches. */
        const val MAX_ASKED_REST_MS = 15 * 60_000L

        /** How long [rest] leaves a service alone, or null for not at all. */
        internal fun restFor(rest: RemoteMcp.Rest): Long? = when (rest) {
            RemoteMcp.Rest.None -> null
            RemoteMcp.Rest.Brief -> BRIEF_REST_MS
            RemoteMcp.Rest.Closed -> CLOSED_REST_MS
            is RemoteMcp.Rest.Asked -> (rest.ms ?: ASKED_REST_MS).coerceIn(1_000, MAX_ASKED_REST_MS)
        }

        private fun seconds(ms: Long) = (ms + 999) / 1000
    }
}
