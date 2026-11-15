package dev.apkcli.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Which service [KeylessSearch] asks, in what order, and how long one that failed is left alone. */
class KeylessSearchTest {

    /** Answers what it was given, in order, and never suspends. */
    private class FakeService(override val name: String) : SearchService {
        val searchAnswers = ArrayDeque<ServiceAnswer<List<SearchHit>>>()
        val readAnswers = ArrayDeque<ServiceAnswer<ReadPage>>()
        var searches = 0
        var reads = 0

        override suspend fun search(query: SearchQuery): ServiceAnswer<List<SearchHit>> {
            searches++
            return searchAnswers.removeFirstOrNull() ?: ServiceAnswer.Failed("nothing queued", RemoteMcp.Rest.None)
        }

        override suspend fun read(url: String, maxChars: Int, chatId: String?): ServiceAnswer<ReadPage> {
            reads++
            return readAnswers.removeFirstOrNull() ?: ServiceAnswer.Failed("nothing queued", RemoteMcp.Rest.None)
        }
    }

    private val exa = FakeService("Exa")
    private val parallel = FakeService("Parallel")
    private var clock = 1_000_000L
    private val keyless = KeylessSearch(searchOrder = listOf(exa, parallel), readOrder = listOf(parallel, exa)) { clock }

    private val query = SearchQuery("kotlin flow", "How to collect a flow", count = 10)
    private fun hits(by: String) = listOf(SearchHit("Flow", "https://kotlinlang.org/docs/flow.html", "excerpt from $by"))
    private fun found(by: String) = ServiceAnswer.Ok(hits(by))
    private fun failed(reason: String, rest: RemoteMcp.Rest) = ServiceAnswer.Failed(reason, rest)

    /** [block] run to its end; it must not suspend, which the fakes never do. */
    private fun <T> finish(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return checkNotNull(result) { "the block suspended" }.getOrThrow()
    }

    @Test
    fun `Exa searches first, and Parallel is not asked when it answers`() {
        exa.searchAnswers += found("Exa")
        assertEquals(KeylessSearch.Outcome.Found("Exa", hits("Exa")), finish { keyless.search(query) })
        assertEquals(0, parallel.searches)
    }

    @Test
    fun `a failed search goes to the other service, and a failure about the request alone leaves no rest`() {
        exa.searchAnswers += failed("bad query", RemoteMcp.Rest.None)
        parallel.searchAnswers += found("Parallel")
        assertEquals(KeylessSearch.Outcome.Found("Parallel", hits("Parallel")), finish { keyless.search(query) })

        exa.searchAnswers += found("Exa")
        assertEquals(KeylessSearch.Outcome.Found("Exa", hits("Exa")), finish { keyless.search(query) })
        assertEquals(2, exa.searches)
    }

    @Test
    fun `a service that timed out is passed over until its rest is up`() {
        exa.searchAnswers += failed("timed out", RemoteMcp.Rest.Brief)
        parallel.searchAnswers += found("Parallel")
        finish { keyless.search(query) }

        clock += KeylessSearch.BRIEF_REST_MS - 5_000
        parallel.searchAnswers += found("Parallel")
        assertEquals(KeylessSearch.Outcome.Found("Parallel", hits("Parallel")), finish { keyless.search(query) })
        assertEquals(1, exa.searches)

        clock += 5_000
        exa.searchAnswers += found("Exa")
        assertEquals(KeylessSearch.Outcome.Found("Exa", hits("Exa")), finish { keyless.search(query) })
    }

    @Test
    fun `when both fail the reasons are given, with when the sooner may be asked again`() {
        exa.searchAnswers += failed("HTTP 502", RemoteMcp.Rest.Brief)
        parallel.searchAnswers += failed("asked to slow down (HTTP 429)", RemoteMcp.Rest.Asked(null))
        assertEquals(
            KeylessSearch.Outcome.Failed(
                listOf("Exa: HTTP 502", "Parallel: asked to slow down (HTTP 429)"),
                KeylessSearch.BRIEF_REST_MS,
            ),
            finish { keyless.search(query) },
        )

        clock += 5_000
        assertEquals(
            KeylessSearch.Outcome.Failed(
                listOf("Exa: resting for another 15 s", "Parallel: resting for another 55 s"),
                KeylessSearch.BRIEF_REST_MS - 5_000,
            ),
            finish { keyless.search(query) },
        )
        assertEquals(1, exa.searches)
        assertEquals(1, parallel.searches)
    }

    @Test
    fun `failures that leave no rest give no time to retry in`() {
        exa.searchAnswers += failed("bad query", RemoteMcp.Rest.None)
        parallel.searchAnswers += failed("bad query", RemoteMcp.Rest.None)
        val outcome = finish { keyless.search(query) } as KeylessSearch.Outcome.Failed
        assertNull(outcome.retryInMs)
    }

    @Test
    fun `Parallel reads a page first, and a rest from a search holds for reading too`() {
        val page = ReadPage("https://stackoverflow.com/questions/1", "Question", "text")
        parallel.readAnswers += ServiceAnswer.Ok(page)
        assertEquals(KeylessSearch.Outcome.Found("Parallel", page), finish { keyless.read(page.url, 1_000, "chat") })
        assertEquals(0, exa.reads)

        exa.searchAnswers += failed("refused this device (HTTP 403)", RemoteMcp.Rest.Closed)
        parallel.searchAnswers += found("Parallel")
        finish { keyless.search(query) }
        parallel.readAnswers += failed("Parallel could not read it (connect_error)", RemoteMcp.Rest.None)
        assertEquals(
            KeylessSearch.Outcome.Failed(
                listOf("Parallel: Parallel could not read it (connect_error)", "Exa: resting for another 600 s"),
                KeylessSearch.CLOSED_REST_MS,
            ),
            finish { keyless.read(page.url, 1_000, "chat") },
        )
        assertEquals(0, exa.reads)
    }

    @Test
    fun `each kind of failure rests for its own time, a Retry-After within bounds`() {
        assertNull(KeylessSearch.restFor(RemoteMcp.Rest.None))
        assertEquals(KeylessSearch.BRIEF_REST_MS, KeylessSearch.restFor(RemoteMcp.Rest.Brief))
        assertEquals(KeylessSearch.CLOSED_REST_MS, KeylessSearch.restFor(RemoteMcp.Rest.Closed))
        assertEquals(KeylessSearch.ASKED_REST_MS, KeylessSearch.restFor(RemoteMcp.Rest.Asked(null)))
        assertEquals(300_000L, KeylessSearch.restFor(RemoteMcp.Rest.Asked(300_000L)))
        assertEquals(KeylessSearch.MAX_ASKED_REST_MS, KeylessSearch.restFor(RemoteMcp.Rest.Asked(3_600_000L)))
        assertEquals(1_000L, KeylessSearch.restFor(RemoteMcp.Rest.Asked(0L)))
    }
}
