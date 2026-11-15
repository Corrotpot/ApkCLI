package dev.apkcli.core.llm

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt's per-request part goes after the conversation
 * ([SystemSplit]), and Anthropic's models get cache breakpoints before it:
 * what lets a provider's prompt cache reach the conversation.
 */
class PromptCachingTest {

    private val stable = "You are the agent."
    private val volatile = "Window: 91% full."

    private fun request(scheme: ConnectionScheme, model: String) = CompletionRequest(
        endpoint = scheme.newEndpoint("e"),
        model = ModelSpec(id = model),
        apiKey = null,
        system = "$stable\n\n$volatile",
        systemStableLength = stable.length,
        messages = listOf(
            Message(id = "u", sessionId = "s", role = Role.USER, content = listOf(Block.Text("Fix the build"))),
            Message(id = "a", sessionId = "s", role = Role.ASSISTANT, content = listOf(Block.ToolUse("c1", "fs_read", """{"path":"a"}"""))),
            Message(id = "t", sessionId = "s", role = Role.TOOL, content = listOf(Block.ToolResult("c1", "fs_read", "contents"))),
        ),
        tools = emptyList(),
        thinking = ThinkingMode.OFF,
        maxOutputTokens = 1_000,
    )

    private fun JsonObject.messages() = getValue("messages").jsonArray.map { it.jsonObject }
    private fun JsonObject.cached() = this["cache_control"] != null

    @Test
    fun `OpenAI-format endpoints get the stable prompt first and the rest after the newest message`() {
        val body = OpenAiChatAdapter().buildBody(request(ConnectionScheme.OPENAI, "gpt-6"), stream = true)
        val messages = body.messages()

        assertEquals(JsonPrimitive(stable), messages.first().getValue("content"))
        val last = messages.last()
        assertEquals("tool", last.getValue("role").jsonPrimitive.content)
        val text = last.getValue("content").jsonPrimitive.content
        assertTrue(text.startsWith("contents\n\n<app-context>"))
        assertTrue(text.contains(volatile))
        // No breakpoints: these caches match the prefix by themselves.
        assertFalse(body.toString().contains("cache_control"))
    }

    @Test
    fun `Claude behind OpenRouter gets breakpoints on the prompt and the newest message, before the app's part`() {
        val messages = OpenAiChatAdapter().buildBody(request(ConnectionScheme.OPENROUTER, "anthropic/claude-sonnet-5"), stream = true).messages()

        assertTrue(messages.first().getValue("content").jsonArray.single().jsonObject.cached())
        val parts = messages.last().getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(2, parts.size)
        assertTrue(parts[0].cached())
        assertEquals("contents", parts[0].getValue("text").jsonPrimitive.content)
        assertFalse(parts[1].cached())
        assertTrue(parts[1].getValue("text").jsonPrimitive.content.contains(volatile))
        // The message the request before this one ended with, right before the reply.
        val user = messages[1]
        assertTrue(user.getValue("content").jsonArray.single().jsonObject.cached())
        assertNull(messages[2]["cache_control"])
    }

    @Test
    fun `Anthropic's API gets the stable prompt cached and the app's part after the last breakpoint`() {
        val body = AnthropicAdapter().buildBody(request(ConnectionScheme.ANTHROPIC, "claude-sonnet-5"), streaming = true)

        val system = body.getValue("system").jsonArray.single().jsonObject
        assertEquals(stable, system.getValue("text").jsonPrimitive.content)
        assertTrue(system.cached())
        val messages = body.messages()
        assertTrue((messages.first().getValue("content") as JsonArray).last().jsonObject.cached())
        val blocks = messages.last().getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("tool_result", blocks[0].getValue("type").jsonPrimitive.content)
        assertTrue(blocks[0].cached())
        assertEquals("text", blocks[1].getValue("type").jsonPrimitive.content)
        assertFalse(blocks[1].cached())
        assertTrue(blocks[1].getValue("text").jsonPrimitive.content.contains(volatile))
        assertEquals(3, body.toString().split("cache_control").size - 1)
    }

    @Test
    fun `OpenRouter is told which chat a request is, so the chat stays where its cache is`() {
        val adapter = OpenAiChatAdapter()
        val chat = request(ConnectionScheme.OPENROUTER, "deepseek/deepseek-v4.1-flash").copy(sessionId = "s-42")
        assertEquals("s-42", adapter.buildBody(chat, stream = true).getValue("session_id").jsonPrimitive.content)
        // A request of no chat says none, and no other endpoint gets the field.
        assertNull(adapter.buildBody(chat.copy(sessionId = null), stream = true)["session_id"])
        assertNull(adapter.buildBody(request(ConnectionScheme.OPENAI, "gpt-6").copy(sessionId = "s-42"), stream = true)["session_id"])
    }
}
