package dev.apkcli.core.llm

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An assistant message's own reasoning goes back with it on OpenAI-format
 * endpoints, so a model that thinks between tool calls goes on from where
 * it was rather than starting over from the user's request.
 */
class ReasoningReplayTest {

    private val flash = "deepseek/deepseek-v4.1-flash-1017"

    private fun assistant(id: String, model: String?, vararg blocks: Block) =
        Message(id = id, sessionId = "s", role = Role.ASSISTANT, content = blocks.toList(), model = model)

    /** A user's request, a step that read a file, its result, and a second step. */
    private fun request(endpoint: Endpoint, model: String, first: Message, second: Message) = CompletionRequest(
        endpoint = endpoint,
        model = ModelSpec(id = model, reasoning = ReasoningSupport.OPENROUTER_UNIFIED),
        apiKey = null,
        system = "You are the agent.",
        messages = listOf(
            Message(id = "u", sessionId = "s", role = Role.USER, content = listOf(Block.Text("Make a React page"))),
            first,
            Message(id = "t", sessionId = "s", role = Role.TOOL, content = listOf(Block.ToolResult("c1", "fs_read", "contents"))),
            second,
            Message(id = "t2", sessionId = "s", role = Role.TOOL, content = listOf(Block.ToolResult("c2", "sh_exec", "ok"))),
        ),
        tools = emptyList(),
        thinking = ThinkingMode.DEEP,
        maxOutputTokens = 1_000,
    )

    private val read = Block.ToolUse("c1", "fs_read", "{}")
    private val run = Block.ToolUse("c2", "sh_exec", "{}")

    private fun assistants(request: CompletionRequest): List<JsonObject> =
        OpenAiChatAdapter().buildBody(request, stream = true).getValue("messages").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("role").jsonPrimitive.content == "assistant" }

    private fun JsonObject.text(field: String): String? = this[field]?.jsonPrimitive?.content

    @Test
    fun `OpenRouter gets each step's reasoning back as reasoning, the model's own only`() {
        val sent = assistants(
            request(
                ConnectionScheme.OPENROUTER.newEndpoint("or"),
                flash,
                assistant("a1", flash, Block.Thinking("The user wants a page. Read the app first."), read),
                assistant("a2", "another/model", Block.Thinking("Someone else's thought."), run),
            ),
        )
        assertEquals("The user wants a page. Read the app first.", sent[0].text("reasoning"))
        assertNull(sent[0]["reasoning_content"])
        assertNull(sent[1]["reasoning"])
    }

    @Test
    fun `DeepSeek's own API gets reasoning_content on every assistant message, empty where there was none`() {
        val deepSeek = Endpoint(id = "ds", name = "DeepSeek", baseUrl = "https://api.deepseek.com")
        val sent = assistants(
            request(
                deepSeek,
                "deepseek-flash",
                assistant("a1", "deepseek-flash", Block.Thinking("Part one."), Block.Thinking("hidden", redacted = true), read),
                assistant("a2", null, run),
            ),
        )
        assertEquals("Part one.", sent[0].text("reasoning_content"))
        assertEquals("", sent[1].text("reasoning_content"))
    }

    @Test
    fun `OpenAI's own endpoint never gets the field`() {
        val sent = assistants(
            request(
                ConnectionScheme.OPENAI.newEndpoint("oa"),
                "gpt-6",
                assistant("a1", "gpt-6", Block.Thinking("A thought."), read),
                assistant("a2", "gpt-6", run),
            ),
        )
        sent.forEach { message ->
            assertNull(message["reasoning"])
            assertNull(message["reasoning_content"])
        }
    }

    @Test
    fun `signed reasoning still goes back as reasoning_details alone`() {
        val claude = "anthropic/claude-sonnet-5"
        val sent = assistants(
            request(
                ConnectionScheme.OPENROUTER.newEndpoint("or"),
                claude,
                assistant("a1", claude, Block.Thinking("Signed thought.", signature = "sig"), read),
                assistant("a2", claude, run),
            ),
        )
        val details = sent[0].getValue("reasoning_details").jsonArray.single().jsonObject
        assertEquals("sig", details.text("signature"))
        assertNull(sent[0]["reasoning"])
    }
}
