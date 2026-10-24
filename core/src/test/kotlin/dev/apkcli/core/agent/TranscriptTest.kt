package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.Role
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window a request carries: every call answered, nothing answered twice, how a reply ended said. */
class TranscriptTest {

    private var seq = 0L

    private fun user(text: String) = Message(id = "u${seq}", sessionId = "s", seq = seq++, role = Role.USER, content = listOf(Block.Text(text)))

    private fun calls(vararg ids: String) = Message(
        id = "a${seq}",
        sessionId = "s",
        seq = seq++,
        role = Role.ASSISTANT,
        content = ids.map { Block.ToolUse(it, "fs_read", "{\"path\":\"$it\"}") },
    )

    private fun results(vararg ids: String) = Message(
        id = "t${seq}",
        sessionId = "s",
        seq = seq++,
        role = Role.TOOL,
        content = ids.map { Block.ToolResult(it, "fs_read", "content of $it") },
    )

    private fun answers(messages: List<Message>): Map<String, List<String>> =
        messages.filter { it.role == Role.TOOL }.associate { m -> m.id to m.toolResults.map { it.callId } }

    @Test
    fun `a whole window is passed through as it is`() {
        val window = listOf(user("read a and b"), calls("a", "b"), results("a", "b"), user("thanks"))
        assertSame(window, Transcript.answered(window))
    }

    @Test
    fun `calls stopped before their results are answered, in their place`() {
        val stopped = calls("a", "b")
        val window = listOf(user("read"), stopped, user("why did you stop?"))
        val paired = Transcript.answered(window)

        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), paired.map { it.role })
        val answer = paired[2]
        assertEquals(listOf("a", "b"), answer.toolResults.map { it.callId })
        assertTrue(answer.toolResults.all { it.isError && it.content == Transcript.STOPPED_RESULT })
        // Not a stored row: compaction plans by sequence and leaves it out.
        assertEquals(-1L, answer.seq)
    }

    @Test
    fun `a call left out of a batch is answered in the batch, in call order`() {
        val window = listOf(user("read"), calls("a", "b", "c"), results("a", "c"))
        val paired = Transcript.answered(window)

        assertEquals(3, paired.size)
        val batch = paired.last()
        assertEquals(listOf("a", "b", "c"), batch.toolResults.map { it.callId })
        assertEquals(Transcript.STOPPED_RESULT, batch.toolResults[1].content)
        assertEquals("content of c", batch.toolResults[2].content)
    }

    @Test
    fun `the last calls of the window, stopped, are answered at its end`() {
        val paired = Transcript.answered(listOf(user("read"), calls("a")))
        assertEquals(Role.TOOL, paired.last().role)
        assertEquals(listOf("a"), paired.last().toolResults.map { it.callId })
    }

    @Test
    fun `results without their call, or answering one twice, are left out`() {
        val window = listOf(results("x"), user("hi"), calls("a"), results("a"), results("a", "z"))
        val paired = Transcript.answered(window)

        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), paired.map { it.role })
        assertEquals(listOf(listOf("a")), answers(paired).values.toList())
    }

    @Test
    fun `how a reply ended is read from the provider's finish reason`() {
        for (reason in listOf("length", "max_tokens", "MAX_TOKENS", "model_context_window_exceeded")) {
            val notice = StopReasons.notice(reason, outputLimit = 8_192)
            assertEquals(reason, Block.Notice.OUTPUT_LIMIT, notice?.kind)
            assertEquals(8_192, notice?.limit)
        }
        for (reason in listOf("content_filter", "refusal", "SAFETY", "guardrail_intervened")) {
            assertEquals(reason, Block.Notice.FILTERED, StopReasons.notice(reason, 0)?.kind)
        }
        for (reason in listOf(null, "stop", "end_turn", "tool_use", "TOOL_USE", "tool_calls")) {
            assertNull(reason, StopReasons.notice(reason, 0))
        }
    }

    @Test
    fun `a request counts tool results whole, the index keeps their start`() {
        val output = "line\n".repeat(2_000)
        val message = Message(
            id = "t",
            sessionId = "s",
            role = Role.TOOL,
            content = listOf(Block.ToolResult("a", "sh_exec", output), Block.Notice(Block.Notice.FAILED, text = "never sent")),
        )
        assertTrue(message.promptText().contains(output.trim()))
        assertFalse(message.promptText().contains("never sent"))
        assertTrue(message.flatten().length < 500)
    }

    @Test
    fun `a notice is stored with its message and read back`() {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
        val block: Block = Block.Notice(Block.Notice.OUTPUT_LIMIT, limit = 32_000)
        val text = json.encodeToString(Block.serializer(), block)
        assertTrue(text, text.contains("\"type\":\"notice\""))
        assertEquals(block, json.decodeFromString(Block.serializer(), text))
    }
}
