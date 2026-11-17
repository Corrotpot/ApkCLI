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

    private fun conversation(tokens: Int, on: ModelSpec, measured: ContextBudget.Measured? = null) = budget.account(
        systemPrompt = "You are a coding agent.",
        toolSchemaTokens = 0,
        digest = null,
        memory = null,
        messages = listOf(
            Message(id = "c", sessionId = "s", seq = 0, role = Role.USER, content = listOf(Block.Text("hi")), tokens = tokens),
        ),
        model = on,
        maxOutputTokens = on.maxOutputTokens,
        measured = measured,
    )

    /** OpenCode's `outputLimit` for a prompt none of which the provider has counted. */
    private fun uncounted(accounting: ContextBudget.Accounting): Long =
        accounting.contextWindow * 99L / 100 - (accounting.promptTokens * 115L + 99) / 100

    @Test
    fun `compaction keeps 15 percent of the window free whatever the reply's cap, as OpenCode does`() {
        val accounting = conversation(93_000, on = roomy)

        // Not the whole cap, which left a budget of 103k and compacted the chat at 93k.
        assertEquals(1_048_576 - 157_286, accounting.promptBudget)
        assertFalse(budget.needsCompaction(accounting))
        assertEquals(accounting.promptBudget - accounting.promptTokens, accounting.remaining)
        assertTrue(budget.needsCompaction(conversation(accounting.promptBudget, on = roomy)))
        // A cap under half the window, the same: DeepSeek V4 Flash.
        assertEquals(891_290, ModelSpec(id = "deepseek-v4-flash", contextWindow = 1_048_576, maxOutputTokens = 393_216).promptBudget)
        // At least 16k from a 32k window up.
        assertEquals(64_000 - 16_000, ModelSpec(id = "small", contextWindow = 64_000).promptBudget)
    }

    @Test
    fun `the input limit holds the prompt where the listing gives one`() {
        // Kiro: the window is the input limit plus the cap.
        val kiro = ModelSpec(id = "claude-opus-4.6", contextWindow = 1_032_000, maxOutputTokens = 32_000, maxInputTokens = 1_000_000)
        val accounting = conversation(500_000, on = kiro)

        assertEquals(1_000_000 - 150_000, accounting.promptBudget)
        assertEquals(32_000, accounting.replyTokens)
        // GPT-5: a 272k input limit under a 400k window.
        val gpt = ModelSpec(id = "gpt-5", contextWindow = 400_000, maxOutputTokens = 128_000, maxInputTokens = 272_000)
        assertEquals(272_000 - 40_800, gpt.promptBudget)
    }

    @Test
    fun `a reply is the cap, at most 256k, while the window has room`() {
        assertEquals(256_000, conversation(93_000, on = roomy).replyTokens)
        assertEquals(model.maxOutputTokens, conversation(10_000, on = model).replyTokens)
    }

    @Test
    fun `near the compaction point the reply fits what the window has left`() {
        // Not counted: the whole prompt may be 15% more than estimated, and
        // 1% of the window stays free.
        val late = conversation(880_000, on = roomy)
        assertEquals(uncounted(late), late.replyTokens.toLong())
        assertTrue(late.replyTokens < 50_000)

        // Counted by the provider at the last request: only what was added since may be.
        val counted = ContextBudget.Measured(reported = 860_000, estimated = 860_000)
        val measured = conversation(880_000, on = roomy, measured = counted)
        val added = measured.promptTokens - 860_000L
        assertEquals(roomy.contextWindow * 99L / 100 - 860_000 - (added * 115 + 99) / 100, measured.replyTokens.toLong())
        assertTrue(measured.replyTokens > 150_000)
    }

    @Test
    fun `a count of a longer prompt than this one is not used`() {
        // Compacted since: what the provider counted is not in the prompt now.
        val counted = ContextBudget.Measured(reported = 860_000, estimated = 860_000)
        val after = conversation(700_000, on = roomy, measured = counted)

        assertEquals(uncounted(after), after.replyTokens.toLong())
        assertTrue(after.replyTokens < 256_000)
    }

    @Test
    fun `past the window the reply is never less than 1024`() {
        assertEquals(ContextBudget.MIN_REPLY_TOKENS, conversation(99_000, on = model).replyTokens)
    }
}
