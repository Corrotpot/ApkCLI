package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ContextSettings
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionPlanTest {

    private val budget = ContextBudget(ContextSettings())
    private var nextSeq = 0L

    private fun user(tokens: Int = 50) = message(Role.USER, tokens, Block.Text("do the thing"))
    private fun reply(tokens: Int = 50) = message(Role.ASSISTANT, tokens, Block.Text("done"))
    private fun round(resultTokens: Int): List<Message> {
        val callId = "c$nextSeq"
        return listOf(
            message(Role.ASSISTANT, 50, Block.ToolUse(callId, "fs_read", """{"path":"log.txt"}""")),
            message(Role.TOOL, resultTokens, Block.ToolResult(callId, "fs_read", "...")),
        )
    }

    private fun message(role: Role, tokens: Int, vararg content: Block) =
        Message(id = "m$nextSeq", sessionId = "s", seq = nextSeq++, role = role, content = content.toList(), tokens = tokens)

    /** A window of [window] tokens whose live messages are [messages], nothing else in the prompt. */
    private fun accounting(messages: List<Message>, window: Int) = ContextBudget.Accounting(
        systemTokens = 0,
        toolSchemaTokens = 0,
        digestTokens = 0,
        memoryTokens = 0,
        liveMessageTokens = messages.sumOf { it.tokens },
        reserveTokens = 0,
        maxOutputTokens = 0,
        contextWindow = window,
    )

    @Test
    fun `one long turn of tool calls is compacted by its oldest rounds, keeping what the user said`() {
        val ask = user()
        val rounds = (1..10).flatMap { round(resultTokens = 1_000) }
        val messages = listOf(ask) + rounds
        val acc = accounting(messages, window = 10_000)
        assertTrue(budget.needsCompaction(acc))

        val plan = budget.planCompaction(messages, acc, keepSeqs = setOf(ask.seq))

        assertTrue(budget.isWorthCompacting(plan, acc))
        assertTrue("the user's message stays", ask.seq in plan.keepSeqs)
        assertFalse("and is not archived", plan.archiveRuns.any { ask.seq in it })
        val last = messages.last { it.role == Role.ASSISTANT }
        assertTrue("the latest round stays", plan.archiveToSeq < last.seq)
        // Cut before an assistant, never between a call and its result.
        val firstKept = messages.first { it.seq > plan.archiveToSeq }
        assertEquals(Role.ASSISTANT, firstKept.role)
        assertTrue(
            "down to the target",
            acc.promptTokens - plan.estimatedTokensFreed <= (acc.promptBudget * ContextBudget.TARGET_FRACTION).toInt(),
        )
    }

    @Test
    fun `older turns are archived whole first, as before, when that is enough`() {
        val old = (1..9).flatMap { listOf(user(), reply(tokens = 1_000)) }
        val recent = (1..6).flatMap { listOf(user(), reply()) }
        val messages = old + recent
        val acc = accounting(messages, window = 10_000)

        val plan = budget.planCompaction(messages, acc)

        assertTrue(plan.keepSeqs.isEmpty())
        assertEquals("the last six turns stay", recent.first().seq - 1, plan.archiveToSeq)
    }

    @Test
    fun `refused on its first request, a turn keeps its message and archives older turns`() {
        val older = (1..3).flatMap { listOf(user(), reply(tokens = 3_000)) }
        val ask = user(tokens = 200)
        val messages = older + ask
        val acc = accounting(messages, window = 9_000)

        val plan = budget.planCompaction(messages, acc, keepSeqs = setOf(ask.seq))

        assertTrue(budget.isWorthCompacting(plan, acc))
        assertTrue("never the message just sent", plan.archiveToSeq < ask.seq)
    }

    @Test
    fun `a single round that is all there is leaves nothing to archive`() {
        val ask = user()
        val messages = listOf(ask) + round(resultTokens = 20_000)
        val acc = accounting(messages, window = 10_000)

        val plan = budget.planCompaction(messages, acc, keepSeqs = setOf(ask.seq))

        assertFalse(budget.isWorthCompacting(plan, acc))
    }

    @Test
    fun `the runs to archive go around the kept messages`() {
        val plan = ContextBudget.Plan(
            archiveFromSeq = 0,
            archiveToSeq = 10,
            archivedCount = 8,
            keptCount = 3,
            estimatedTokensFreed = 1,
            reason = "",
            keepSeqs = setOf(0, 4, 5, 12),
        )
        assertEquals(listOf(1L..3L, 6L..10L), plan.archiveRuns)
    }
}
