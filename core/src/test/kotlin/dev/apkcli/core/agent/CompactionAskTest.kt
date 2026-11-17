package dev.apkcli.core.agent

import dev.apkcli.core.llm.CompletionRequest
import dev.apkcli.core.llm.KiroWire
import dev.apkcli.core.llm.ToolDeclaration
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a summary is asked for, and read back: [CompactionAsk]. */
class CompactionAskTest {

    private val endpoint = Endpoint(id = "kiro", name = "Kiro", baseUrl = "https://q.us-east-1.amazonaws.com")
    private val model = ModelSpec(id = "claude-sonnet-5", contextWindow = 200_000, maxOutputTokens = 32_000)
    private val readTool = ToolDeclaration(
        name = "fs_read",
        description = "Read a file.",
        parameters = buildJsonObject { put("type", "object") },
    )
    private var seq = 0L

    private fun message(role: Role, vararg content: Block) =
        Message(id = "m$seq", sessionId = "s1", seq = seq++, role = role, content = content.toList(), tokens = 100)

    /** A turn one round of calls in: what the agent is about to send. */
    private fun agentRequest(): CompletionRequest = CompletionRequest(
        endpoint = endpoint,
        model = model,
        apiKey = null,
        system = "You are the agent.\n\nWindow: 91% full.",
        systemStableLength = "You are the agent.".length,
        messages = listOf(
            message(Role.USER, Block.Text("Fix the build")),
            message(Role.ASSISTANT, Block.Text("Reading it."), Block.ToolUse("c1", "fs_read", """{"path":"build.gradle"}""")),
            message(Role.TOOL, Block.ToolResult("c1", "fs_read", "plugins { id 'android' }")),
        ),
        tools = listOf(readTool),
        thinking = ThinkingMode.DEEP,
        reasoningEffort = "high",
        maxOutputTokens = model.maxOutputTokens,
        temperature = 0.3,
        sessionId = "s1",
    )

    @Test
    fun `the summary is asked at the end of the agent's own request, which is otherwise unchanged`() {
        val base = agentRequest()

        val asked = checkNotNull(CompactionAsk.within(base, promptTokens = 150_000))

        // To the provider that served the agent's requests, whose cache holds them.
        assertEquals(base.sessionId, asked.sessionId)
        assertEquals(base.system, asked.system)
        assertEquals(base.systemStableLength, asked.systemStableLength)
        assertEquals(base.tools, asked.tools)
        assertEquals(base.messages, asked.messages.dropLast(1))
        val ask = asked.messages.last()
        assertEquals(Role.USER, ask.role)
        assertTrue(ask.text.startsWith("# Compaction"))
        assertTrue("the format is in the request", ask.text.contains("\"failedApproaches\""))
        // Nothing a current model refuses, and quick to answer.
        assertNull(asked.temperature)
        assertNull(asked.topP)
        assertEquals(CompletionRequest.ToolChoice.Auto, asked.toolChoice)
        assertEquals(ThinkingMode.OFF, asked.thinking)
        assertNull(asked.reasoningEffort)
        assertEquals(CompactionAsk.OUTPUT_TOKENS, asked.maxOutputTokens)
    }

    @Test
    fun `on Kiro the request with the summary has the same history as the one it stands in for`() {
        val base = agentRequest()
        val asked = checkNotNull(CompactionAsk.within(base, promptTokens = 150_000))

        val sent = KiroWire.body(base, profileArn = null, conversationId = "c", continuationId = "k").json
        val summary = KiroWire.body(asked, profileArn = null, conversationId = "c", continuationId = "k").json

        fun JsonObject.state() = getValue("conversationState").jsonObject
        fun JsonObject.current() = state().getValue("currentMessage").jsonObject.getValue("userInputMessage").jsonObject
        fun JsonObject.results() = current().getValue("userInputMessageContext").jsonObject.getValue("toolResults")
        assertEquals(sent.state().getValue("history"), summary.state().getValue("history"))
        assertTrue(summary.current().getValue("content").jsonPrimitive.content.contains("# Compaction"))
        // The tool result is still answered in the same turn.
        assertEquals(sent.results(), summary.results())
    }

    @Test
    fun `a conversation that leaves no room for the reply is not asked within`() {
        val base = agentRequest()

        assertNull(CompactionAsk.within(base, promptTokens = model.inputWindow - CompactionAsk.OUTPUT_TOKENS))
    }

    @Test
    fun `a conversation at the compaction point is asked within, with room for the summary`() {
        // OpenRouter's listing for DeepSeek V4.1 Flash, whose cap kept whole
        // left room for 104,858 tokens here.
        val roomy = ModelSpec(id = "deepseek-v4.1-flash", contextWindow = 1_048_576, maxOutputTokens = 943_718)
        val base = agentRequest().copy(model = roomy)

        val asked = checkNotNull(CompactionAsk.within(base, promptTokens = roomy.promptBudget))
        assertEquals(CompactionAsk.OUTPUT_TOKENS, asked.maxOutputTokens)
        assertNull(CompactionAsk.within(base, promptTokens = roomy.inputWindow - CompactionAsk.OUTPUT_TOKENS))
    }

    @Test
    fun `the least reasoning is a named level only where the request names one`() {
        val effort = ModelSpec(id = "gpt", reasoning = ReasoningSupport.EFFORT, reasoningEfforts = listOf("minimal", "low", "high"))
        val adaptive = ModelSpec(id = "claude", reasoning = ReasoningSupport.ADAPTIVE, reasoningEfforts = listOf("low", "high"))

        assertEquals("minimal", CompactionAsk.quickest(effort))
        assertNull(CompactionAsk.quickest(adaptive))
    }

    @Test
    fun `the summary is read out of words and a fence around it`() {
        val reply = "Here is the summary.\n```json\n" +
            """{"objective": "Fix the build", "currentState": "Gradle fails on {android}", "decisions": [{"what": "keep AGP 9",},],}""" +
            "\n```\nDone."

        val digest = checkNotNull(CompactionAsk.parse(reply))

        assertEquals("Fix the build", digest.objective)
        assertEquals("Gradle fails on {android}", digest.currentState)
        assertEquals("keep AGP 9", digest.decisions.single().what)
    }

    @Test
    fun `what only the app sets cannot come from the reply`() {
        val digest = checkNotNull(
            CompactionAsk.parse("""{"objective":"x","version":7,"trigger":"command","focus":"f","tokensFreed":99,"coversToSeq":5}"""),
        )

        assertEquals(1, digest.version)
        assertNull(digest.trigger)
        assertNull(digest.focus)
        assertEquals(0L, digest.tokensFreed)
        assertEquals(0L, digest.coversToSeq)
    }

    @Test
    fun `an object that is no summary is passed over, and a cut-off one is none`() {
        assertEquals("y", CompactionAsk.parse("""Used {"path": "a"} then: {"objective": "y"}""")?.objective)
        assertNull(CompactionAsk.parse("""{"objective": "Fix the build", "currentState": "Gradle fai"""))
        assertNull(CompactionAsk.parse("No summary here."))
    }

    @Test
    fun `a transcript keeps a result's start and end, cut to the level`() {
        val log = "A".repeat(3_000) + "\nBUILD FAILED: missing sdk"
        val span = listOf(
            message(Role.ASSISTANT, Block.ToolUse("c1", "sh_exec", """{"command":"./gradlew build"}""")),
            message(Role.TOOL, Block.ToolResult("c1", "sh_exec", log, isError = true)),
        )

        val whole = CompactionAsk.transcript(span, CompactionAsk.LEVELS.first())
        val bare = CompactionAsk.transcript(span, CompactionAsk.LEVELS.last())

        assertTrue(whole.contains("BUILD FAILED: missing sdk"))
        assertTrue(whole.contains(" … "))
        assertTrue(whole.contains("RESULT sh_exec [ERROR]"))
        assertFalse(bare.contains("AAAA"))
        assertTrue(bare.contains("RESULT sh_exec [ERROR]\n"))
    }

    @Test
    fun `a transcript too long for the room goes down a level, and past the last keeps the newest messages`() {
        val span = (1..40).flatMap { i ->
            listOf(
                message(Role.ASSISTANT, Block.ToolUse("c$i", "fs_read", """{"path":"src/file$i.kt"}""")),
                message(Role.TOOL, Block.ToolResult("c$i", "fs_read", "x".repeat(2_500))),
            )
        }
        val full = checkNotNull(CompactionAsk.fitted(span, room = 1_000_000))
        assertEquals(0, full.level)
        assertEquals(0, full.leftOut)

        val cut = checkNotNull(CompactionAsk.fitted(span, room = 6_000))
        assertTrue(cut.level > 0)

        val newest = checkNotNull(CompactionAsk.fitted(span, room = 300))
        assertEquals(CompactionAsk.LEVELS.size, newest.level)
        assertTrue(newest.leftOut > 0)
        assertTrue(newest.text.contains("src/file40.kt"))
        assertFalse(newest.text.contains("src/file1.kt\""))
        assertTrue(newest.text.startsWith("(The ${newest.leftOut} oldest message(s)"))

        assertNull(CompactionAsk.fitted(span, room = 0))
    }
}
