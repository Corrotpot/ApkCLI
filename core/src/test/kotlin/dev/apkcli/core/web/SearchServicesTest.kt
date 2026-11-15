package dev.apkcli.core.web

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How Exa's text answers and Parallel's JSON ones become results and pages: [ExaService], [ParallelService]. */
class SearchServicesTest {

    @Test
    fun `Exa's results split at the rule before a title, not at a rule inside an excerpt`() {
        val text = listOf(
            "Title: Flow | Kotlin Documentation",
            "URL: https://kotlinlang.org/docs/flow.html",
            "Published: 2024-05-01T00:00:00.000Z",
            "Author: N/A",
            "Highlights:",
            "Asynchronous flow",
            "",
            "---",
            "",
            "A rule inside the page stays in the excerpt.",
            "",
            "---",
            "",
            "Title: N/A",
            "URL: https://stackoverflow.com/questions/1",
            "Published: N/A",
            "Highlights: collect it on the main thread",
            "",
            "---",
            "",
            "Title: A page with no address",
            "Highlights:",
            "nothing to link to",
        ).joinToString("\n")

        assertEquals(
            listOf(
                SearchHit(
                    title = "Flow | Kotlin Documentation",
                    url = "https://kotlinlang.org/docs/flow.html",
                    excerpt = "Asynchronous flow\n\n---\n\nA rule inside the page stays in the excerpt.",
                    published = "2024-05-01T00:00:00.000Z",
                ),
                // No title: the address stands in.
                SearchHit(
                    title = "https://stackoverflow.com/questions/1",
                    url = "https://stackoverflow.com/questions/1",
                    excerpt = "collect it on the main thread",
                    published = null,
                ),
            ),
            ExaService.parseResults(text),
        )
    }

    @Test
    fun `Exa's page is its heading, its address and the text after them`() {
        assertEquals(
            ReadPage("https://kotlinlang.org/docs/flow.html", "Flow", "Body\n\nMore"),
            ExaService.parsePage("# Flow\nURL: https://kotlinlang.org/docs/flow.html\n\nBody\n\nMore\n", "https://kotlinlang.org/docs/flow"),
        )
        assertEquals(ReadPage("https://asked.example/", "Title", "Body"), ExaService.parsePage("# Title\n\nBody", "https://asked.example/"))
        assertEquals(ReadPage("https://asked.example/", "", "Only text"), ExaService.parsePage("Only text", "https://asked.example/"))
        assertNull(ExaService.parsePage("# Title\nURL: https://asked.example/\n", "https://asked.example/"))
    }

    @Test
    fun `Parallel's results keep their excerpts, and a result with no address is dropped`() {
        val answer = buildJsonObject {
            putJsonArray("results") {
                addJsonObject {
                    put("url", "https://stackoverflow.com/questions/8200945")
                    put("title", "How to get HTML content from a WebView")
                    put("publish_date", "2011-11-20")
                    putJsonArray("excerpts") {
                        add(" first part ")
                        add("second part")
                        add("  ")
                    }
                }
                addJsonObject {
                    put("url", "https://developer.android.com/reference/android/webkit/WebView")
                    put("publish_date", JsonNull)
                    putJsonArray("excerpts") {}
                }
                addJsonObject { put("title", "no address") }
            }
            put("session_id", "session_1")
        }
        assertEquals(
            listOf(
                SearchHit(
                    "How to get HTML content from a WebView",
                    "https://stackoverflow.com/questions/8200945",
                    "first part\n…\nsecond part",
                    "2011-11-20",
                ),
                SearchHit(
                    "https://developer.android.com/reference/android/webkit/WebView",
                    "https://developer.android.com/reference/android/webkit/WebView",
                    "",
                    null,
                ),
            ),
            ParallelService.parseResults(answer),
        )
    }

    @Test
    fun `Parallel's page is its full content, or its excerpts when that is all it sent`() {
        val whole = buildJsonObject {
            putJsonArray("results") {
                addJsonObject {
                    put("url", "https://stackoverflow.com/questions/1")
                    put("title", "Question")
                    put("full_content", "  # Question\n\nText  ")
                }
            }
        }
        assertEquals(
            ServiceAnswer.Ok(ReadPage("https://stackoverflow.com/questions/1", "Question", "# Question\n\nText")),
            ParallelService.parsePage(whole, "https://stackoverflow.com/q/1"),
        )

        val excerpts = buildJsonObject {
            putJsonArray("results") {
                addJsonObject {
                    putJsonArray("excerpts") {
                        add("one")
                        add("two")
                    }
                }
            }
        }
        assertEquals(
            ServiceAnswer.Ok(ReadPage("https://asked.example/", "", "one\n\ntwo")),
            ParallelService.parsePage(excerpts, "https://asked.example/"),
        )
    }

    @Test
    fun `Parallel's page that could not be read says why`() {
        fun errors(type: String?, status: Int?) = buildJsonObject {
            putJsonArray("results") {}
            putJsonArray("errors") {
                addJsonObject {
                    put("url", "https://asked.example/")
                    put("error_type", type)
                    put("http_status_code", status)
                }
            }
        }
        assertEquals(
            ServiceAnswer.Failed("Parallel could not read it (connect_error)", RemoteMcp.Rest.None),
            ParallelService.parsePage(errors("connect_error", null), "https://asked.example/"),
        )
        assertEquals(
            ServiceAnswer.Failed("Parallel could not read it (fetch_error, HTTP 403)", RemoteMcp.Rest.None),
            ParallelService.parsePage(errors("fetch_error", 403), "https://asked.example/"),
        )
        assertEquals(
            ServiceAnswer.Failed("Parallel could not read it (no content)", RemoteMcp.Rest.None),
            ParallelService.parsePage(buildJsonObject {}, "https://asked.example/"),
        )
    }
}
