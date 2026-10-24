package dev.apkcli.core.llm

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.PromptCaching
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KiroWireTest {

    private val endpoint = Endpoint(id = "kiro", name = "Kiro", baseUrl = "https://q.us-east-1.amazonaws.com")
    private val readTool = ToolDeclaration(
        name = "fs_read",
        description = "Read a file.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }
            put("required", JsonArray(emptyList()))
            put("additionalProperties", false)
        },
    )

    private fun message(role: Role, vararg content: Block) =
        Message(id = "m${content.hashCode()}", sessionId = "s1", role = role, content = content.toList())

    private fun request(
        messages: List<Message>,
        tools: List<ToolDeclaration> = listOf(readTool),
        model: ModelSpec = ModelSpec(id = "claude-sonnet-5"),
        effort: String? = null,
        system: String = "You are the agent.",
    ) = CompletionRequest(
        endpoint = endpoint,
        model = model,
        apiKey = "aorAAAAAGtoken",
        system = system,
        messages = messages,
        tools = tools,
        thinking = ThinkingMode.OFF,
        reasoningEffort = effort,
        maxOutputTokens = 64_000,
    )

    private fun JsonObject.state() = this["conversationState"]!!.jsonObject
    private fun JsonObject.history() = state()["history"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.current() = state()["currentMessage"]!!.jsonObject["userInputMessage"]!!.jsonObject
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `the system prompt is a turn of its own, answered, and the last turn is the current message`() {
        val body = KiroWire.body(
            request(
                listOf(
                    message(Role.USER, Block.Text("Hi")),
                    message(Role.ASSISTANT, Block.Text("Hello")),
                    message(Role.USER, Block.Text("Read main.py")),
                ),
            ),
            profileArn = "arn:aws:codewhisperer:us-east-1:1:profile/P",
            conversationId = "c1",
            continuationId = "k1",
        ).json

        assertNull("no top-level system prompt: it is refused", body["systemPrompt"])
        val state = body.state()
        assertEquals("k1", state.text("agentContinuationId"))
        assertEquals("vibe", state.text("agentTaskType"))
        assertEquals("MANUAL", state.text("chatTriggerType"))
        assertEquals("c1", state.text("conversationId"))

        val history = body.history()
        assertEquals(4, history.size)
        val system = history[0]["userInputMessage"]!!.jsonObject
        assertEquals("You are the agent.", system.text("content"))
        assertEquals("AI_EDITOR", system.text("origin"))
        assertTrue(system["userInputMessageContext"]!!.jsonObject.isEmpty())
        assertEquals("I will follow these instructions.", history[1]["assistantResponseMessage"]!!.jsonObject.text("content"))

        val first = history[2]["userInputMessage"]!!.jsonObject
        assertEquals("Hi", first.text("content"))
        assertEquals("claude-sonnet-5", first.text("modelId"))
        assertEquals("every user turn names its origin", "AI_EDITOR", first.text("origin"))
        assertEquals("Hello", history[3]["assistantResponseMessage"]!!.jsonObject.text("content"))

        val current = body.current()
        assertEquals("Read main.py", current.text("content"))
        assertEquals("AI_EDITOR", current.text("origin"))
        val tools = current["userInputMessageContext"]!!.jsonObject["tools"]!!.jsonArray
        val spec = tools[0].jsonObject["toolSpecification"]!!.jsonObject
        assertEquals("fs_read", spec.text("name"))
        val schema = spec["inputSchema"]!!.jsonObject["json"]!!.jsonObject
        assertNull("additionalProperties is refused", schema["additionalProperties"])
        assertNull("an empty required list is refused", schema["required"])
        assertEquals("arn:aws:codewhisperer:us-east-1:1:profile/P", body.text("profileArn"))
        assertEquals(KiroWire.MAX_OUTPUT_TOKENS, body["inferenceConfig"]!!.jsonObject["maxTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a call and its result pair up by id across the assistant and user turns`() {
        val body = KiroWire.body(
            request(
                listOf(
                    message(Role.USER, Block.Text("Read it")),
                    message(Role.ASSISTANT, Block.Text("Reading."), Block.ToolUse("toolu.01", "fs_read", """{"path":"a.py"}""")),
                    message(Role.TOOL, Block.ToolResult("toolu.01", "fs_read", "print(1)")),
                ),
            ),
            profileArn = null,
            conversationId = "c1",
        ).json

        val history = body.history()
        val use = history[3]["assistantResponseMessage"]!!.jsonObject["toolUses"]!!.jsonArray[0].jsonObject
        assertEquals("ids keep only letters, digits, _ and -", "toolu01", use.text("toolUseId"))
        assertEquals("a.py", use["input"]!!.jsonObject.text("path"))

        // The tool turn is the current message: its result answers the call.
        val current = body.current()
        assertEquals("Tool results provided.", current.text("content"))
        val result = current["userInputMessageContext"]!!.jsonObject["toolResults"]!!.jsonArray[0].jsonObject
        assertEquals("toolu01", result.text("toolUseId"))
        assertEquals("success", result.text("status"))
        assertEquals("print(1)", result["content"]!!.jsonArray[0].jsonObject.text("text"))
    }

    @Test
    fun `a failed tool says so in its text, with the status Kiro takes`() {
        val body = KiroWire.body(
            request(
                listOf(
                    message(Role.USER, Block.Text("Read it")),
                    message(Role.ASSISTANT, Block.ToolUse("t1", "fs_read", """{"path":"a.py"}""")),
                    message(Role.TOOL, Block.ToolResult("t1", "fs_read", "No such file", isError = true)),
                ),
            ),
            profileArn = null,
            conversationId = "c1",
        ).json
        val assistant = body.history()[3]["assistantResponseMessage"]!!.jsonObject
        assertEquals("a turn of calls alone is never empty", " ", assistant.text("content"))
        val result = body.current()["userInputMessageContext"]!!.jsonObject["toolResults"]!!.jsonArray[0].jsonObject
        assertEquals("success", result.text("status"))
        assertEquals("Error: No such file", result["content"]!!.jsonArray[0].jsonObject.text("text"))
    }

    @Test
    fun `calls that cannot be paired go in as text instead of breaking the request`() {
        val body = KiroWire.body(
            request(
                listOf(
                    message(Role.USER, Block.Text("Go")),
                    // Never answered: the turn was interrupted.
                    message(Role.ASSISTANT, Block.ToolUse("a", "fs_read", """{"path":"x"}""")),
                    message(Role.USER, Block.Text("Never mind")),
                    // A tool this request does not declare.
                    message(Role.ASSISTANT, Block.ToolUse("b", "web_fetch", """{"url":"u"}""")),
                    message(Role.TOOL, Block.ToolResult("b", "web_fetch", "page")),
                ),
            ),
            profileArn = null,
            conversationId = "c1",
        ).json

        val history = body.history()
        val interrupted = history[3]["assistantResponseMessage"]!!.jsonObject
        assertNull(interrupted["toolUses"])
        assertTrue(interrupted.text("content").contains("[Tool call: fs_read("))
        val undeclared = history[5]["assistantResponseMessage"]!!.jsonObject
        assertNull(undeclared["toolUses"])

        val current = body.current()
        assertTrue(current.text("content").contains("[Tool result for web_fetch: page]"))
        assertNull(current["userInputMessageContext"]!!.jsonObject["toolResults"])
    }

    @Test
    fun `a transcript that ends on the assistant gets a user turn to answer`() {
        val body = KiroWire.body(
            request(listOf(message(Role.USER, Block.Text("Hi")), message(Role.ASSISTANT, Block.Text("Half an answer")))),
            profileArn = null,
            conversationId = "c1",
        ).json
        assertEquals("Continue.", body.current().text("content"))
        assertEquals(4, body.history().size)
    }

    @Test
    fun `images go with the user turn in Kiro's shape, and no history is sent when there is none`() {
        val body = KiroWire.body(
            request(listOf(message(Role.USER, Block.Image(mimeType = "image/jpeg", data = "QUJD"))), system = ""),
            profileArn = null,
            conversationId = "c1",
        ).json
        val current = body.current()
        val image = current["images"]!!.jsonArray[0].jsonObject
        assertEquals("jpeg", image.text("format"))
        assertEquals("QUJD", image["source"]!!.jsonObject.text("bytes"))
        assertEquals("Continue", current.text("content"))
        assertNull(body.state()["history"])
        assertNull(body["profileArn"])
    }

    @Test
    fun `reasoning fields follow the model's schema shape`() {
        val (claudeShape, claudeLevels) = KiroWire.reasoningOf("claude-sonnet-5")
        val claude = KiroWire.effortFields(ModelSpec(id = "claude-sonnet-5", reasoning = claudeShape, reasoningEfforts = claudeLevels), "high")!!
        assertEquals("adaptive", claude["thinking"]!!.jsonObject.text("type"))
        assertEquals("summarized", claude["thinking"]!!.jsonObject.text("display"))
        assertEquals("high", claude["output_config"]!!.jsonObject.text("effort"))

        val (gptShape, gptLevels) = KiroWire.reasoningOf("gpt-5.6-sol")
        val gpt = ModelSpec(id = "gpt-5.6-sol", reasoning = gptShape, reasoningEfforts = gptLevels)
        assertEquals("a level the model lacks becomes its highest", "xhigh", KiroWire.effortFields(gpt, "max")!!["reasoning"]!!.jsonObject.text("effort"))

        assertNull("4.5 takes no fields", KiroWire.effortFields(ModelSpec(id = "claude-sonnet-4.5"), "high"))
        assertNull("none is sending nothing", KiroWire.effortFields(ModelSpec(id = "claude-sonnet-5", reasoning = claudeShape), "none"))
        assertNull(KiroWire.effortFields(ModelSpec(id = "deepseek-3.2"), "high"))

        // Listed by an earlier version, which said EFFORT for Claude too.
        val saved = ModelSpec(id = "claude-opus-5.5", reasoning = ReasoningSupport.EFFORT, reasoningEfforts = listOf("none", "high"))
        assertNotNull(KiroWire.effortFields(saved, "high")!!["output_config"])

        assertFalse("4.6 has no xhigh", "xhigh" in KiroWire.reasoningOf("claude-opus-4.6").second)
        assertTrue("max" in KiroWire.reasoningOf("claude-opus-4.6").second)
        assertTrue("xhigh" in KiroWire.reasoningOf("claude-opus-5.5").second)
        assertEquals(ReasoningSupport.NONE, KiroWire.reasoningOf("glm-5").first)
    }

    @Test
    fun `the request-fields schema decides the shape whatever the family`() {
        fun entry(field: String) = Http.parseObject(
            """{"additionalModelRequestFieldsSchema":{"properties":{"$field":{"properties":{"effort":{"enum":["low","high"]}}}}}}""",
        )!!
        val (shape, levels) = KiroWire.reasoningOf("deepseek-4", entry("reasoning"))
        assertEquals(ReasoningSupport.EFFORT, shape)
        assertEquals(listOf("none", "low", "high"), levels)
        assertEquals(ReasoningSupport.ADAPTIVE, KiroWire.reasoningOf("qwen-4", entry("output_config")).first)
        val deepseek = ModelSpec(id = "deepseek-4", reasoning = shape, reasoningEfforts = levels)
        assertEquals("low", KiroWire.effortFields(deepseek, "low")!!["reasoning"]!!.jsonObject.text("effort"))
    }

    @Test
    fun `the model list carries the window, the credit rate and the reasoning schema`() {
        val models = KiroWire.models(
            Http.parseObject(
                """
                {"models":[
                  {"modelId":"claude-opus-5.5","modelName":"Claude Opus 5.5","rateMultiplier":2.0,
                   "tokenLimits":{"maxInputTokens":1000000,"maxOutputTokens":128000}},
                  {"modelId":"deepseek-3.2","modelName":"DeepSeek 3.2","supportedInputTypes":["TEXT"],
                   "tokenLimits":{"maxInputTokens":128000}},
                  {"modelId":"minimax-m3","supportedInputTypes":["TEXT","IMAGE"],
                   "additionalModelRequestFieldsSchema":{"properties":{"reasoning":{"properties":{"effort":{"enum":["low","medium","high"]}}}}}},
                  {"modelName":"no id"}
                ]}
                """.trimIndent(),
            )!!,
        )
        assertEquals(3, models.size)
        val opus = models[0]
        assertEquals("Claude Opus 5.5", opus.displayName)
        assertEquals("the answer cap is what a request may ask", KiroWire.MAX_OUTPUT_TOKENS, opus.maxOutputTokens)
        assertEquals(1_000_000, opus.promptBudget)
        assertTrue(opus.metadataFromEndpoint)
        assertTrue(opus.supportsImages)
        assertTrue(opus.notes.contains("×2 credits"))
        assertTrue("xhigh" in opus.reasoningEfforts)
        assertEquals(ReasoningSupport.ADAPTIVE, opus.reasoning)
        assertFalse(models[1].supportsImages)
        assertTrue(models[1].reasoningEfforts.isEmpty())
        val minimax = models[2]
        assertTrue(minimax.supportsImages)
        assertEquals(ReasoningSupport.EFFORT, minimax.reasoning)
        assertEquals(listOf("none", "low", "medium", "high"), minimax.reasoningEfforts)
    }

    @Test
    fun `refusals are told apart by Kiro's reason`() {
        val tooLong = KiroWire.error(400, """{"message":"Input is too long.","reason":"CONTENT_LENGTH_EXCEEDS_THRESHOLD"}""", 700_000, 175_000, 32_000)
        assertEquals(LlmError.Kind.CONTEXT_OVERFLOW, tooLong.kind)
        assertNotNull(tooLong.discoveredContextLimit)
        assertTrue(tooLong.discoveredContextLimit!! in 150_000..175_000)

        val oversized = KiroWire.error(400, """{"message":"Improperly formed request.","reason":"REQUEST_BODY_INVALID"}""", 650_000, 160_000, 32_000)
        assertEquals("a refused request past the size limit is a long one", LlmError.Kind.CONTEXT_OVERFLOW, oversized.kind)

        val malformed = KiroWire.error(400, """{"message":"Improperly formed request.","reason":"REQUEST_BODY_INVALID"}""", 20_000, 5_000, 32_000)
        assertEquals(LlmError.Kind.BAD_REQUEST, malformed.kind)

        assertEquals(LlmError.Kind.PAYMENT_REQUIRED, KiroWire.error(400, """{"reason":"MONTHLY_REQUEST_COUNT"}""", 1, 1, 1).kind)
        assertEquals(LlmError.Kind.MODEL_UNAVAILABLE, KiroWire.error(400, """{"reason":"INVALID_MODEL_ID"}""", 1, 1, 1).kind)
        assertEquals(LlmError.Kind.AUTH, KiroWire.error(403, """{"message":"bearer token invalid"}""", 1, 1, 1).kind)
    }

    private fun event(type: String, payload: String) = AwsEventStream.decode(
        AwsEventStream.encode(mapOf(":message-type" to "event", ":event-type" to type), payload.toByteArray()),
    )

    private fun decode(decoder: KiroWire.Decoder, vararg frames: AwsEventStream.Frame): List<StreamEvent> =
        frames.flatMap(decoder::accept) + decoder.finish()

    private fun List<StreamEvent>.shownText() = filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun `the stream turns into text, reasoning, a streamed tool call and usage`() {
        val events = decode(
            KiroWire.Decoder(toolNames = mapOf("fs_read" to "fs_read")),
            event("reasoningContentEvent", """{"text":"Thinking it over"}"""),
            event("assistantResponseEvent", """{"content":"Let me "}"""),
            event("assistantResponseEvent", """{"content":"look."}"""),
            event("toolUseEvent", """{"toolUseId":"t1","name":"fs_read","input":""}"""),
            event("toolUseEvent", """{"toolUseId":"t1","name":"fs_read","input":"{\"path\":"}"""),
            event("toolUseEvent", """{"toolUseId":"t1","name":"fs_read","input":"\"a.py\"}"}"""),
            event("toolUseEvent", """{"toolUseId":"t1","name":"fs_read","stop":true}"""),
            event("metricsEvent", """{"inputTokens":1200,"outputTokens":80}"""),
            event("messageStopEvent", """{"stopReason":"TOOL_USE"}"""),
        )

        assertEquals(StreamEvent.ThinkingDelta("Thinking it over"), events[0])
        assertEquals("Let me look.", events.shownText())
        assertEquals(StreamEvent.ToolCallStart("t1", "fs_read"), events.first { it is StreamEvent.ToolCallStart })
        assertEquals(
            """{"path":"a.py"}""",
            events.filterIsInstance<StreamEvent.ToolCallArgsDelta>().joinToString("") { it.fragment },
        )
        assertEquals(1, events.count { it is StreamEvent.ToolCallEnd })
        assertEquals(StreamEvent.Usage(promptTokens = 1200, completionTokens = 80), events.first { it is StreamEvent.Usage })
        assertEquals(StreamEvent.Done("TOOL_USE"), events.last())
    }

    @Test
    fun `message metadata counts uncached and cached input together`() {
        val events = decode(
            KiroWire.Decoder(emptyMap()),
            event("assistantResponseEvent", """{"content":"ok"}"""),
            event(
                "messageMetadataEvent",
                """{"messageMetadataEvent":{"tokenUsage":{"uncachedInputTokens":300,"cacheReadInputTokens":900,"cacheWriteInputTokens":100,"outputTokens":12}}}""",
            ),
        )
        assertEquals(
            StreamEvent.Usage(promptTokens = 1300, completionTokens = 12, cachedPromptTokens = 900, cacheWriteTokens = 100),
            events.first { it is StreamEvent.Usage },
        )
    }

    @Test
    fun `the end of a live stream gives the stop reason, the window's fill and the credits, but no token counts`() {
        // As the service sent it, frame for frame, at the end of a tool call.
        val decoder = KiroWire.Decoder(emptyMap())
        val events = decode(
            decoder,
            event("assistantResponseEvent", """{"content":"ok"}"""),
            event("metadataEvent", """{"stopReason":"TOOL_USE"}"""),
            event("contextUsageEvent", """{"contextUsagePercentage":3.2014000415802}"""),
            event("meteringEvent", """{"unit":"credit","unitPlural":"credits","usage":0.2358526319402985}"""),
        )
        assertTrue("a percentage is not a prompt count", events.none { it is StreamEvent.Usage })
        assertEquals(StreamEvent.Done("TOOL_USE"), events.last())
        assertEquals(0.2358526319402985, decoder.credits, 1e-12)
        assertEquals(3.2014000415802, decoder.contextPercent!!, 1e-12)
    }

    private val cachingModel = ModelSpec(id = "claude-sonnet-5", caching = PromptCaching(checkpoints = 4, minimumTokens = 1_024))

    private fun conversation() = listOf(
        message(Role.USER, Block.Text("Read it")),
        message(Role.ASSISTANT, Block.ToolUse("t1", "fs_read", """{"path":"a.py"}""")),
        message(Role.TOOL, Block.ToolResult("t1", "fs_read", "print(1)")),
        message(Role.ASSISTANT, Block.Text("It prints 1.")),
        message(Role.USER, Block.Text("Now change it")),
    )

    private fun JsonObject.hasCachePoint() = this["cachePoint"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "default"

    @Test
    fun `the per-request part of the prompt goes with the current message, the rest is the system turn`() {
        val stable = "You are the agent. " + "Rule. ".repeat(50)
        val volatile = "# This request\n\n- Context window: 1200 of 200000 tokens used (0%)."
        val body = KiroWire.body(
            request(listOf(message(Role.USER, Block.Text("Hi")))).copy(
                system = "$stable\n\n$volatile",
                systemStableLength = stable.length,
            ),
            profileArn = null,
            conversationId = "c1",
        ).json

        val system = body.history()[0]["userInputMessage"]!!.jsonObject.text("content")
        assertEquals(stable.trim(), system)
        assertFalse("the changing line is not in the cached prefix", "Context window" in system)

        val current = body.current().text("content")
        assertTrue(current.startsWith("<app-context>"))
        assertTrue("Context window: 1200 of 200000" in current)
        assertTrue(current.endsWith("</app-context>\n\nHi"))
    }

    @Test
    fun `cache points go on the system turn and the last history turn, where the model caches`() {
        val longSystem = "You are the agent. " + "Follow the rules carefully. ".repeat(400)
        val body = KiroWire.body(
            request(conversation(), model = cachingModel).copy(system = longSystem),
            profileArn = null,
            conversationId = "c1",
        ).json
        val history = body.history()
        assertTrue("the system turn", history[0]["userInputMessage"]!!.jsonObject.hasCachePoint())
        val userTurns = history.mapNotNull { it["userInputMessage"]?.jsonObject }
        // The system turn and the last user turn before the current one; no other.
        assertEquals(2, userTurns.count { it.hasCachePoint() })
        assertTrue(userTurns.last().hasCachePoint())
        assertFalse("never the current message, which changes", body.current().hasCachePoint())
    }

    @Test
    fun `no cache points for a model that does not cache, or a prompt too short to be worth it`() {
        val longSystem = "Follow the rules carefully. ".repeat(400)
        val plain = KiroWire.body(request(conversation()).copy(system = longSystem), null, "c1").json
        assertTrue(plain.history().none { it["userInputMessage"]?.jsonObject?.hasCachePoint() == true })

        val short = KiroWire.body(request(listOf(message(Role.USER, Block.Text("Hi"))), model = cachingModel), null, "c1").json
        assertTrue("1 024 tokens is the minimum", short.history().none { it["userInputMessage"]?.jsonObject?.hasCachePoint() == true })
    }

    @Test
    fun `the listing says which models cache prompts`() {
        val models = KiroWire.models(
            Http.parseObject(
                """
                {"models":[
                  {"modelId":"claude-sonnet-5","promptCaching":{"supportsPromptCaching":true,
                    "maximumCacheCheckpointsPerRequest":4,"minimumTokensPerCacheCheckpoint":2048}},
                  {"modelId":"deepseek-3.2","promptCaching":{"supportsPromptCaching":false}},
                  {"modelId":"glm-5"}
                ]}
                """.trimIndent(),
            )!!,
        )
        assertEquals(PromptCaching(checkpoints = 4, minimumTokens = 2_048), models[0].caching)
        assertNull(models[1].caching)
        assertNull(models[2].caching)
    }

    @Test
    fun `an exception frame fails the turn with the service's words`() {
        val decoder = KiroWire.Decoder(emptyMap())
        val frame = AwsEventStream.decode(
            AwsEventStream.encode(
                mapOf(":message-type" to "exception", ":exception-type" to "ThrottlingException"),
                """{"message":"Rate exceeded"}""".toByteArray(),
            ),
        )
        val failed = decoder.accept(frame).single() as StreamEvent.Failed
        assertEquals(LlmError.Kind.RATE_LIMIT, failed.error.kind)
        assertEquals("Rate exceeded", failed.error.detail)
        assertTrue("no Done after a failure", decoder.finish().none { it is StreamEvent.Done })
    }

    @Test
    fun `an invalid state is said in the reply, an error inside an event fails it`() {
        val warned = decode(
            KiroWire.Decoder(emptyMap()),
            event("invalidStateEvent", """{"reason":"INVALID_TASK_ASSIST_PLAN","message":"Plan was rejected"}"""),
        )
        assertTrue(warned.shownText().contains("Plan was rejected (reason: INVALID_TASK_ASSIST_PLAN)"))

        val decoder = KiroWire.Decoder(emptyMap())
        val failed = decoder.accept(event("assistantResponseEvent", """{"error":{"message":"Internal error"}}"""))
        assertEquals("Internal error", (failed.single() as StreamEvent.Failed).error.detail)
    }

    @Test
    fun `a tool call written out as text is made after all, and only once`() {
        val decoder = KiroWire.Decoder(toolNames = mapOf("fs_read" to "fs_read"))
        val events = decode(
            decoder,
            event("assistantResponseEvent", """{"content":"Reading it.\n<function_calls>\n<invoke name=\"fs_read\"><parameter name=\"path\">a."}"""),
            event("assistantResponseEvent", """{"content":"py</parameter><parameter name=\"limit\">20</parameter></invoke>\n</function_calls>"}"""),
        )
        assertEquals("Reading it.\n", events.shownText())
        val start = events.filterIsInstance<StreamEvent.ToolCallStart>().single()
        assertEquals("fs_read", start.name)
        assertEquals(
            """{"path":"a.py","limit":20}""",
            events.filterIsInstance<StreamEvent.ToolCallArgsDelta>().single { it.callId == start.callId }.fragment,
        )
        assertEquals("tool_use", (events.last() as StreamEvent.Done).finishReason)

        // The same call made properly as well: not made twice.
        val twice = decode(
            KiroWire.Decoder(toolNames = mapOf("fs_read" to "fs_read")),
            event("toolUseEvent", """{"toolUseId":"t1","name":"fs_read","input":{"path":"a.py"},"stop":true}"""),
            event("assistantResponseEvent", """{"content":"count<invoke name=\"fs_read\"><parameter name=\"path\">a.py</parameter></invoke>"}"""),
        )
        assertEquals(1, twice.count { it is StreamEvent.ToolCallStart })
        assertEquals("", twice.shownText())
    }

    @Test
    fun `call markup for a tool the request does not have stays text`() {
        val events = decode(
            KiroWire.Decoder(toolNames = mapOf("fs_read" to "fs_read")),
            event("assistantResponseEvent", """{"content":"Example: <invoke name=\"rm_rf\"><parameter name=\"p\">/</parameter></invoke> done"}"""),
        )
        assertTrue(events.none { it is StreamEvent.ToolCallStart })
        assertEquals("""Example: <invoke name="rm_rf"><parameter name="p">/</parameter></invoke> done""", events.shownText())
    }

    @Test
    fun `tool names the wire could not carry come back as the app's own`() {
        val dotted = ToolDeclaration("mcp.github.search", "Search.", buildJsonObject { put("type", "object") })
        val body = KiroWire.body(request(listOf(message(Role.USER, Block.Text("x"))), tools = listOf(dotted)), null, "c")
        assertEquals("mcp.github.search", body.toolNames["mcp_github_search"])

        val decoder = KiroWire.Decoder(body.toolNames)
        val start = decoder.accept(event("toolUseEvent", """{"toolUseId":"t","name":"mcp_github_search","input":{"q":"k"}}"""))
        assertEquals(StreamEvent.ToolCallStart("t", "mcp.github.search"), start[0])
        assertEquals(StreamEvent.ToolCallArgsDelta("t", """{"q":"k"}"""), start[1])
    }
}
