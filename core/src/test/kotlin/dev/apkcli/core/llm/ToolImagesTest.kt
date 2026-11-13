package dev.apkcli.core.llm

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A picture a call returned (`fs_read` of a screenshot) reaches each protocol where it takes one. */
class ToolImagesTest {

    private val image = Block.Image(mimeType = "image/jpeg", data = "QUJD", label = "shot.png", width = 10, height = 20)

    private val messages = listOf(
        Message(id = "u", sessionId = "s", role = Role.USER, content = listOf(Block.Text("Look at the screenshot"))),
        Message(
            id = "a",
            sessionId = "s",
            role = Role.ASSISTANT,
            content = listOf(
                Block.ToolUse("c1", "fs_read", """{"path":"shot.png"}"""),
                Block.ToolUse("c2", "fs_read", """{"path":"a.txt"}"""),
            ),
        ),
        Message(
            id = "t",
            sessionId = "s",
            role = Role.TOOL,
            content = listOf(
                Block.ToolResult("c1", "fs_read", "shot.png: an image, 10x20 px.", images = listOf(image)),
                Block.ToolResult("c2", "fs_read", "a.txt  lines 1-1 of 1"),
            ),
        ),
    )

    private fun request(scheme: ConnectionScheme, model: String) = CompletionRequest(
        endpoint = scheme.newEndpoint("e"),
        model = ModelSpec(id = model),
        apiKey = null,
        system = "You are the agent.",
        systemStableLength = "You are the agent.".length,
        messages = messages,
        tools = emptyList(),
        thinking = ThinkingMode.OFF,
        maxOutputTokens = 1_000,
    )

    private fun JsonObject.messages() = getValue("messages").jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `Anthropic takes the picture inside its result, after the result's text`() {
        val body = AnthropicAdapter().buildBody(request(ConnectionScheme.ANTHROPIC, "claude-sonnet-5"), streaming = true)
        val results = body.messages().last().getValue("content").jsonArray.map { it.jsonObject }
        val withImage = results.first { it.text("tool_use_id") == "c1" }.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image"), withImage.map { it.text("type") })
        assertEquals("QUJD", withImage[1].getValue("source").jsonObject.text("data"))
        assertEquals(JsonPrimitive("a.txt  lines 1-1 of 1"), results.first { it.text("tool_use_id") == "c2" }["content"])
    }

    @Test
    fun `Chat Completions gets it in a user message after all the batch's results`() {
        val sent = OpenAiChatAdapter().buildBody(request(ConnectionScheme.OPENAI, "gpt-6"), stream = true).messages()
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user"), sent.map { it.text("role") })
        val parts = sent.last().getValue("content").jsonArray.map { it.jsonObject }
        assertTrue(parts[0].text("text").contains("fs_read returned for call c1"))
        assertEquals("image_url", parts[1].text("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts[1].getValue("image_url").jsonObject.text("url"))
    }

    @Test
    fun `Kiro carries it with the turn that holds the results`() {
        val turns = KiroWire.turns(messages)
        val results = turns.last()
        assertTrue(results.user)
        assertEquals(listOf("c1", "c2"), results.results.map { it.id })
        assertEquals(listOf(image), results.images)
    }
}
