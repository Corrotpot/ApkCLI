package dev.apkcli.core.agent

import dev.apkcli.core.llm.LlmError
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.SubagentRun
import dev.apkcli.core.model.SubagentRun.Step.Kind
import dev.apkcli.core.model.SubagentRun.Step.Status
import dev.apkcli.core.model.ToolCallStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A subagent's card, built from its events: what it did, in order, and bounded. */
class SubagentTrackerTest {

    private var clock = 1_000L

    private fun tracker() = SubagentTracker(
        agentId = "builtin-explorer",
        agentName = "Explorer",
        readOnly = true,
        sessionId = "s-child",
        maxIterations = 20,
        now = { clock },
    )

    private fun finished(callId: String, content: String, isError: Boolean = false, display: ToolDisplay? = null) =
        AgentEvent.ToolCallFinished(
            callId = callId,
            name = "fs_read",
            status = if (isError) ToolCallStatus.ERROR else ToolCallStatus.OK,
            result = ToolResult(content, isError = isError, display = display),
            durationMs = 12,
        )

    private fun completed(vararg uses: Block.ToolUse) =
        AgentEvent.MessageCompleted(Message(id = "m", sessionId = "s-child", role = Role.ASSISTANT, content = uses.toList()))

    @Test
    fun `a round's words lead to its calls, the last round's words are the answer`() {
        val t = tracker()
        t.on(AgentEvent.AssistantStarted("m1", 1))
        t.on(AgentEvent.ThinkingDelta("m1", "where is "))
        t.on(AgentEvent.ThinkingDelta("m1", "the router?"))
        clock += 2_100
        t.on(AgentEvent.ThinkingFinished("m1", 0))
        t.on(AgentEvent.TextDelta("m1", "Reading main.py."))
        t.on(AgentEvent.ToolCallStarted("m1", "c1", "fs_read", ""))
        t.on(AgentEvent.ToolCallProgress("m1", "c1", 9, "{\"path\":"))
        t.on(completed(Block.ToolUse("c1", "fs_read", "{\"path\":\"bot/main.py\"}")))
        t.on(AgentEvent.ToolCallRunning("c1"))
        t.on(finished("c1", "import asyncio"))
        t.on(AgentEvent.AssistantStarted("m2", 2))
        t.on(AgentEvent.TextDelta("m2", "The router is included twice."))
        t.on(AgentEvent.TurnFinished(2, AgentEvent.StopReason.COMPLETED))

        val run = t.snapshot()
        assertEquals(listOf(Kind.THINKING, Kind.TEXT, Kind.TOOL), run.steps.map { it.kind })
        assertEquals("where is the router?", run.steps[0].text)
        assertEquals(2_100L, run.steps[0].durationMs)
        assertEquals("Reading main.py.", run.steps[1].text)
        val read = run.steps[2]
        assertEquals("fs_read", read.tool)
        assertEquals("{\"path\":\"bot/main.py\"}", read.argsHead)
        assertEquals(Status.OK, read.status)
        assertEquals("import asyncio", read.text)
        assertEquals("The router is included twice.", run.answer)
        assertEquals(SubagentRun.State.DONE, run.state)
        assertEquals(2, run.iterations)
        assertEquals("s-child", run.sessionId)
        assertFalse(run.answerCut)
    }

    @Test
    fun `nothing is cut without saying so`() {
        val t = tracker()
        t.on(AgentEvent.AssistantStarted("m1", 1))
        t.on(AgentEvent.ToolCallStarted("m1", "c1", "grep", ""))
        t.on(AgentEvent.ToolCallRunning("c1"))
        t.on(finished("c1", "x".repeat(SubagentRun.MAX_STEP_TEXT * 3)))
        repeat(SubagentRun.MAX_STEPS + 7) { i ->
            t.on(AgentEvent.ToolCallStarted("m1", "k$i", "fs_read", ""))
        }
        t.on(AgentEvent.AssistantStarted("m2", 2))
        t.on(AgentEvent.TextDelta("m2", "y".repeat(SubagentRun.MAX_ANSWER + 5)))
        t.on(AgentEvent.TurnFinished(2, AgentEvent.StopReason.COMPLETED))

        val run = t.snapshot()
        assertEquals(SubagentRun.MAX_STEPS, run.steps.size)
        assertEquals(8, run.stepsDropped)
        assertEquals(SubagentRun.MAX_ANSWER, run.answer.length)
        assertEquals(SubagentRun.MAX_ANSWER + 5, run.answerLength)
        assertTrue(run.answerCut)

        val long = tracker()
        long.on(AgentEvent.ToolCallStarted("m1", "c1", "grep", ""))
        long.on(finished("c1", "x".repeat(SubagentRun.MAX_STEP_TEXT * 3)))
        val step = long.snapshot().steps.single()
        assertEquals(SubagentRun.MAX_STEP_TEXT, step.text.length)
        assertEquals(SubagentRun.MAX_STEP_TEXT * 3, step.textLength)
        assertTrue(step.textCut)
    }

    @Test
    fun `a step waits for the user and keeps a refusal`() {
        val t = tracker()
        t.on(AgentEvent.ToolCallStarted("m1", "c1", "sh_exec", "{\"command\":\"pytest\"}"))
        t.on(AgentEvent.ToolCallRunning("c1"))
        t.asking("c1")
        assertEquals(Status.ASKING, t.snapshot().steps.single().status)
        t.answered("c1", allowed = false)
        t.on(finished("c1", "The user declined this call.", isError = true))
        assertEquals(Status.DENIED, t.snapshot().steps.single().status)
    }

    @Test
    fun `an edit keeps its line counts`() {
        val t = tracker()
        t.on(AgentEvent.ToolCallStarted("m1", "c1", "fs_edit", ""))
        t.on(finished("c1", "Edited", display = ToolDisplay.Diff("bot/main.py", "@@", addedLines = 3, removedLines = 1)))
        val step = t.snapshot().steps.single()
        assertEquals(3, step.added)
        assertEquals(1, step.removed)
    }

    @Test
    fun `the round cap and a failure end the run as they are`() {
        val limited = tracker()
        limited.on(AgentEvent.ToolCallStarted("m1", "c1", "fs_read", ""))
        limited.on(AgentEvent.ToolCallRunning("c1"))
        limited.on(AgentEvent.TurnFinished(20, AgentEvent.StopReason.ITERATION_LIMIT))
        val cut = limited.snapshot()
        assertEquals(SubagentRun.State.LIMIT, cut.state)
        assertEquals(Status.ERROR, cut.steps.single().status)

        val failing = tracker()
        failing.on(AgentEvent.Failed(LlmError(LlmError.Kind.CONTEXT_OVERFLOW, "window full"), recoverable = false))
        failing.on(AgentEvent.TurnFinished(1, AgentEvent.StopReason.ERROR))
        val failed = failing.snapshot()
        assertEquals(SubagentRun.State.FAILED, failed.state)
        assertEquals("window full", failed.error)
    }

    @Test
    fun `a last reply cut at the output limit is said, and a later full one clears it`() {
        val t = tracker()
        val cut = Block.Notice(Block.Notice.OUTPUT_LIMIT, limit = 8_192)
        t.on(AgentEvent.MessageCompleted(Message(id = "m1", sessionId = "s-child", role = Role.ASSISTANT, content = listOf(Block.Text("a"), cut))))
        assertTrue(t.snapshot().outputLimited)
        t.on(AgentEvent.MessageCompleted(Message(id = "m2", sessionId = "s-child", role = Role.ASSISTANT, content = listOf(Block.Text("b")))))
        assertFalse(t.snapshot().outputLimited)
    }

    @Test
    fun `a snapshot is taken only when something changed`() {
        val t = tracker()
        assertNotNull(t.takeIfChanged())
        assertNull(t.takeIfChanged())
        t.on(AgentEvent.TextDelta("m1", "a"))
        assertNotNull(t.takeIfChanged())
        // Events it has no use for change nothing.
        t.on(AgentEvent.FilesChanged(listOf("a")))
        assertNull(t.takeIfChanged())
    }

    @Test
    fun `the run is stored with the result and read back, and old rows have none`() {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
        val t = tracker()
        t.on(AgentEvent.ToolCallStarted("m1", "c1", "fs_read", "{\"path\":\"a\"}"))
        t.on(finished("c1", "text"))
        t.on(AgentEvent.TextDelta("m2", "answer"))
        t.on(AgentEvent.TurnFinished(2, AgentEvent.StopReason.COMPLETED))
        val block: Block = Block.ToolResult("d1", "subagent_dispatch", "Subagent reported", subagent = t.snapshot())

        val text = json.encodeToString(Block.serializer(), block)
        assertEquals(block, json.decodeFromString(Block.serializer(), text))

        val old = """{"type":"tool_result","callId":"d1","name":"subagent_dispatch","content":"x"}"""
        assertNull((json.decodeFromString(Block.serializer(), old) as Block.ToolResult).subagent)
    }
}
