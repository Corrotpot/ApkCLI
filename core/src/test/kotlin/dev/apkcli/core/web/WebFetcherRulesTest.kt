package dev.apkcli.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the static fetch makes of an answer: a bot check ([WebFetcher.isBotCheck]), a Markdown page's title. */
class WebFetcherRulesTest {

    @Test
    fun `a bot check is a 403 or 503 that Cloudflare marks as a challenge`() {
        assertTrue(WebFetcher.isBotCheck(403, "challenge"))
        assertTrue(WebFetcher.isBotCheck(503, " Challenge "))
        assertFalse(WebFetcher.isBotCheck(403, null))
        assertFalse(WebFetcher.isBotCheck(429, "challenge"))
        assertFalse(WebFetcher.isBotCheck(200, "challenge"))
    }

    @Test
    fun `a Markdown page's title comes from its front matter first`() {
        val page = "---\ntitle: \"Markdown for Agents\"\ndescription: how\n---\n\n# Another heading\n"
        assertEquals("Markdown for Agents", WebFetcher.markdownTitle(page))
        assertEquals("Quoted", WebFetcher.markdownTitle("---\ntitle: 'Quoted'\n---\nText"))
    }

    @Test
    fun `with no title in front matter the first top heading is the title`() {
        assertEquals("Heading", WebFetcher.markdownTitle("---\nsource: x\n---\n# Heading"))
        assertEquals("Heading", WebFetcher.markdownTitle("---\ntitle: never closed\n# Heading"))
        assertEquals("Getting started", WebFetcher.markdownTitle("\n\nIntro\n\n## Before\n\n# Getting started\n"))
    }

    @Test
    fun `no title when there is no top heading near the start`() {
        assertEquals("", WebFetcher.markdownTitle("## Only a subheading\n\ntext"))
        assertEquals("", WebFetcher.markdownTitle("#Not a heading"))
        assertEquals("", WebFetcher.markdownTitle("line\n".repeat(45) + "# Late"))
    }
}
