package dev.apkcli.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the reasoning picker marks ([AppSettings.reasoningChoiceFor]) beside what a request sends ([AppSettings.reasoningEffortFor]). */
class ReasoningChoiceTest {

    /** DeepSeek V4.1 Flash as OpenRouter lists it: four levels, `high` its default. */
    private val flash = ModelSpec(
        id = "deepseek/deepseek-v4.1-flash-1017",
        reasoning = ReasoningSupport.OPENROUTER_UNIFIED,
        reasoningEfforts = ReasoningLevels.normalize(listOf("max", "high", "low", "none")),
        defaultReasoningEffort = "high",
    )

    private fun picked(level: String?) = AppSettings(
        reasoningEfforts = level?.let { mapOf(ReasoningLevels.key("or", flash.id) to it) }.orEmpty(),
    )

    @Test
    fun `with nothing picked the picker marks Default and the request carries the model's own level`() {
        assertNull(picked(null).reasoningChoiceFor("or", flash))
        assertEquals("high", picked(null).reasoningEffortFor("or", flash))
    }

    @Test
    fun `a level the model offers is marked and sent as picked`() {
        assertEquals("max", picked("max").reasoningChoiceFor("or", flash))
        assertEquals("none", picked("none").reasoningEffortFor("or", flash))
    }

    @Test
    fun `a pick the model no longer offers is marked as the level sent in its place`() {
        assertEquals("low", picked("medium").reasoningChoiceFor("or", flash))
        assertEquals("low", picked("medium").reasoningEffortFor("or", flash))
    }
}
