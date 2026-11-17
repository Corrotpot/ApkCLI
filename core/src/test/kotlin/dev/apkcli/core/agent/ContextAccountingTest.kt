package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ContextSettings
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Role
import dev.apkcli.core.nativebridge.NativeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextBudget.account] counts each part of a request once, and keeps room
 * in the window for the reply. On the JVM the native estimator is absent and
 * NativeCore's Kotlin one answers.
 */
class ContextAccountingTest {

    private val budget = ContextBudget(ContextSettings())
    private val model = ModelSpec(id = "m", contextWindow = 100_000, maxOutputTokens = 4_000)

    private val memory = "From AGENTS.md:\n\nBuild with ./gradlew assembleDebug.\n\n" +
        "Remembered about this project (memory_write):\n- style: " + "short words ".repeat(300)
    private val system = "You are a coding agent.\n\n# Project instructions and memory\n\n$memory"

    @Test
    fun `memory the system prompt carries is counted once, on its own row`() {
        val accounting = budget.account(
            systemPrompt = system,
            toolSchemaTokens = 300,
            digest = null,
            memory = memory,
            messages = emptyList(),
            model = model,
            maxOutputTokens = model.maxOutputTokens,
        )

        assertTrue(accounting.memoryTokens > 500)
        assertEquals(NativeCore.estimateTokens(system), accounting.systemTokens + accounting.memoryTokens)
        assertEquals(NativeCore.estimateTokens(system) + 300, accounting.promptTokens)
        // Compaction cannot shrink it, so it stays fixed overhead.
        assertEquals(accounting.promptTokens, accounting.fixedOverhead)
    }

    @Test
    fun `the prompt is the sum of its parts`() {
        val message = Message(
            id = "m1", sessionId = "s", seq = 0, role = Role.USER,
            content = listOf(Block.Text("hello")), tokens = 1_000,
        )
        val digest = "# Digest\n\n" + "decided things ".repeat(100)

        val accounting = budget.account(
            systemPrompt = system,
            toolSchemaTokens = 300,
            digest = digest,
            memory = memory,
            messages = listOf(message),
            model = model,
            maxOutputTokens = model.maxOutputTokens,
        )

        assertEquals(
            NativeCore.estimateTokens(system) + 300 + NativeCore.estimateTokens(digest) + 1_000,
            accounting.promptTokens,
        )
    }

    /** OpenRouter's listing for DeepSeek V4.1 Flash: a reply may take nearly all of the window. */
    private val roomy = ModelSpec(id = "deepseek/deepseek-v4.1-flash-1017", contextWindow = 1_048_576, maxOutputTokens = 943_718)

    private fun conversation(tokens: Int, on: ModelSpec) = budget.account(
        systemPrompt = "You are a coding agent.",
        toolSchemaTokens = 0,
        digest = null,
        memory = null,
        messages = listOf(
            Message(id = "c", sessionId = "s", seq = 0, role = Role.USER, content = listOf(Block.Text("hi")), tokens = tokens),
        ),
        model = on,
        maxOutputTokens = on.maxOutputTokens,
    )

    @Test
    fun `a reply cap near the window leaves the prompt most of it`() {
        val accounting = conversation(93_000, on = roomy)

        // 15% of the window is kept for the reply, not the whole cap, which
        // left a budget of 103k and compacted the chat at 93k.
        assertEquals(157_286, accounting.replyReserveTokens)
        assertEquals(1_048_576 - 157_286 - 2_000, accounting.promptBudget)
        assertFalse(budget.needsCompaction(accounting))
        // To where compaction runs, 90% of the budget, not to its end.
        assertEquals(800_361 - accounting.promptTokens, accounting.untilCompaction)
    }

    @Test
    fun `the reply asked for fits what the prompt leaves of the window`() {
        val early = conversation(93_000, on = roomy)
        // Room for the estimate to run 15% low, and 1% of the window.
        assertTrue(early.replyTokens > 900_000)
        assertTrue(early.promptTokens * 115 / 100 + early.replyTokens <= roomy.contextWindow * 99 / 100)

        // Past the compaction threshold, within the budget: the reserve whole.
        val late = conversation(850_000, on = roomy)
        assertTrue(budget.needsCompaction(late))
        assertEquals(late.replyReserveTokens, late.replyTokens)
        assertTrue(late.promptTokens + late.replyTokens <= roomy.contextWindow - late.reserveTokens)
    }

    @Test
    fun `a cap within half the window is kept and asked whole, as before`() {
        // Kiro: the window is the input limit plus the cap.
        val kiro = ModelSpec(id = "claude-opus-4.6", contextWindow = 1_032_000, maxOutputTokens = 32_000)
        val accounting = conversation(500_000, on = kiro)

        assertEquals(32_000, accounting.replyReserveTokens)
        assertEquals(1_032_000 - 32_000 - 2_000, accounting.promptBudget)
        assertEquals(32_000, accounting.replyTokens)
        // GPT-5: a 272k input limit under a 400k window.
        assertEquals(128_000, ModelSpec(id = "gpt-5", contextWindow = 400_000, maxOutputTokens = 128_000).replyReserve)
    }

    @Test
    fun `past the budget the reply shrinks to what the window has left`() {
        val over = conversation(96_500, on = model)
        assertEquals(model.contextWindow - over.promptTokens - 2_000, over.replyTokens)
        assertTrue(over.replyTokens < model.maxOutputTokens)

        assertEquals(ContextBudget.MIN_REPLY_TOKENS, conversation(99_000, on = model).replyTokens)
    }
}
