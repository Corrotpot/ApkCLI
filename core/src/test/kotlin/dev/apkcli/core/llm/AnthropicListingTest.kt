package dev.apkcli.core.llm

import dev.apkcli.core.runtime.osc7Path
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic's Models API as it answered in October 2026: limits and
 * capabilities per model, which decide the request shape — Claude 4.7 and
 * later refuse `thinking.type: enabled` with a 400.
 */
class AnthropicListingTest {

    private fun entry(id: String, adaptive: Boolean, enabled: Boolean, disabled: Boolean) = Json.parseToJsonElement(
        """
        {"type":"model","id":"$id","display_name":"$id","max_input_tokens":1000000,"max_tokens":128000,
         "capabilities":{
           "image_input":{"supported":true},
           "thinking":{"supported":true,"types":{"adaptive":{"supported":$adaptive},"enabled":{"supported":$enabled},"disabled":{"supported":$disabled}}},
           "effort":{"supported":true,"low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"xhigh":{"supported":true},"max":{"supported":true}}
         }}
        """.trimIndent(),
    )

    @Test
    fun `limits, effort levels and thinking types are read from the listing`() {
        val spec = ModelDiscovery.parseEntry(entry("claude-opus-5-5", adaptive = true, enabled = false, disabled = false))!!
        assertEquals(1_000_000, spec.contextWindow)
        assertEquals(128_000, spec.maxOutputTokens)
        assertTrue(spec.metadataFromEndpoint)
        assertTrue(spec.supportsImages)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), spec.reasoningEfforts)
        assertEquals(listOf("adaptive"), spec.thinkingTypes)
    }

    @Test
    fun `a model that only takes a budget says so`() {
        val spec = ModelDiscovery.parseEntry(entry("claude-haiku-4-5", adaptive = false, enabled = true, disabled = true))!!
        assertEquals(listOf("enabled", "disabled"), spec.thinkingTypes)
    }

    @Test
    fun `OSC 7 carries the path, with percent escapes decoded`() {
        assertEquals("/data/home/projects/bot", osc7Path("file://localhost/data/home/projects/bot"))
        assertEquals("/a b/ж", osc7Path("file://host/a%20b/%D0%B6"))
        assertEquals("/50%", osc7Path("file:///50%"))
        assertNull(osc7Path("http://host/x"))
    }
}
