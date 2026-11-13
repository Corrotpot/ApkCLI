package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ContextSettings
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Role
import dev.apkcli.core.nativebridge.NativeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextBudget.account] counts each part of a request once. On the JVM
 * the native estimator is absent and NativeCore's Kotlin one answers.
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
}
