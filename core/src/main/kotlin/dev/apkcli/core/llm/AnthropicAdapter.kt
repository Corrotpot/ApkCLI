package dev.apkcli.core.llm

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningLevels
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import dev.apkcli.core.util.int
import dev.apkcli.core.util.obj
import dev.apkcli.core.util.str
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * `POST {base}/messages` — the Anthropic Messages API.
 *
 * Worth a separate adapter rather than a translation layer because of how
 * reasoning is handled. Anthropic returns `thinking` blocks carrying an opaque
 * `signature`, and that signature **must be echoed back verbatim** in the
 * assistant message on the following turn. Drop it and the first turn succeeds
 * while the second fails with a complaint about an invalid thinking block — a
 * failure that is very hard to attribute if the signature was never stored.
 * That requirement is why [Block.Thinking] has a `signature` field at all.
 *
 * Two more structural differences from the chat protocol:
 *   * The system prompt is a top-level field, not a message.
 *   * Tool results are blocks inside a **user** message, not messages with a
 *     `tool` role.
 */
class AnthropicAdapter : LlmProtocolAdapter {

    override val protocolName = "anthropic_messages"

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = callbackFlow {
        val streaming = request.stream && request.endpoint.streaming
        val body = buildBody(request, streaming)
        val call = Http.postJson(
            request.endpoint,
            request.apiKey,
            "messages",
            body,
            streaming = streaming,
        )

        // Blocking, so a busy collector slows the read instead of losing
        // events; see OpenAiChatAdapter.
        val emit: (StreamEvent) -> Unit = { event -> trySendBlocking(event) }

        val worker = Thread({
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val text = runCatching { response.body.string() }.getOrNull()
                        emit(StreamEvent.Failed(Http.classify(response, text)))
                        return@use
                    }
                    if (streaming) consumeStream(response, emit) else consumeWhole(response, emit)
                }
            } catch (io: IOException) {
                if (call.isCanceled()) {
                    emit(StreamEvent.Failed(LlmError(LlmError.Kind.CANCELLED, "Stopped")))
                } else {
                    emit(StreamEvent.Failed(Http.classify(io)))
                }
            } catch (t: Throwable) {
                emit(StreamEvent.Failed(Http.classify(t)))
            } finally {
                close()
            }
        }, "apkcli-llm-anthropic")
        worker.isDaemon = true
        worker.start()

        awaitClose { if (!call.isCanceled()) call.cancel() }
    }

    /* ------------------------------------------------------------ streaming */

    private fun consumeStream(response: okhttp3.Response, emit: (StreamEvent) -> Unit) {
        val source = response.body.source()

        // Content blocks are addressed by index across start/delta/stop events.
        val toolCallIdByIndex = HashMap<Int, String>()
        var stopReason: String? = null
        var promptTokens = 0
        var cachedTokens = 0

        Sse.read(source) { event ->
            if (Sse.isDone(event.data)) return@read false
            val obj = Http.parseObject(event.data) ?: return@read true
            // The event name is duplicated in the payload's `type`; trust the
            // payload, since some proxies drop the SSE `event:` line.
            val type = obj.str("type") ?: event.type ?: return@read true

            when (type) {
                "message_start" -> {
                    obj.obj("message")?.obj("usage")?.let { usage ->
                        promptTokens = usage.int("input_tokens", 0)
                        cachedTokens = usage.int("cache_read_input_tokens", 0)
                    }
                }

                "content_block_start" -> {
                    val index = obj.int("index", 0)
                    val block = obj.obj("content_block") ?: return@read true
                    when (block.str("type")) {
                        "tool_use" -> {
                            val id = block.str("id", "call_$index")
                            toolCallIdByIndex[index] = id
                            emit(StreamEvent.ToolCallStart(id, block.str("name", "")))
                        }
                        "redacted_thinking" -> {
                            // Encrypted reasoning: nothing to display, but the
                            // payload must round-trip.
                            emit(
                                StreamEvent.ThinkingSignature(
                                    block.str("data", ""),
                                    redacted = true,
                                ),
                            )
                        }
                    }
                }

                "content_block_delta" -> {
                    val index = obj.int("index", 0)
                    val delta = obj.obj("delta") ?: return@read true
                    when (delta.str("type")) {
                        "text_delta" ->
                            delta.str("text")?.let { emit(StreamEvent.TextDelta(it)) }
                        "thinking_delta" ->
                            delta.str("thinking")?.let { emit(StreamEvent.ThinkingDelta(it)) }
                        "signature_delta" ->
                            delta.str("signature")?.let { emit(StreamEvent.ThinkingSignature(it)) }
                        "input_json_delta" -> {
                            val id = toolCallIdByIndex[index] ?: "call_$index"
                            delta.str("partial_json")?.let {
                                emit(StreamEvent.ToolCallArgsDelta(id, it))
                            }
                        }
                    }
                }

                "content_block_stop" -> {
                    toolCallIdByIndex[obj.int("index", 0)]?.let {
                        emit(StreamEvent.ToolCallEnd(it))
                    }
                }

                "message_delta" -> {
                    obj.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                    obj.obj("usage")?.let { usage ->
                        emit(
                            StreamEvent.Usage(
                                promptTokens = promptTokens,
                                completionTokens = usage.int("output_tokens", 0),
                                cachedPromptTokens = cachedTokens,
                            ),
                        )
                    }
                }

                "message_stop" -> return@read false

                "error" -> {
                    val err = obj.obj("error")
                    emit(
                        StreamEvent.Failed(
                            LlmError(
                                LlmError.Kind.SERVER,
                                err?.str("message", "stream error") ?: "stream error",
                                body = event.data.take(2_000),
                            ),
                        ),
                    )
                    return@read false
                }

                "ping" -> Unit
            }
            true
        }

        emit(StreamEvent.Done(stopReason))
    }

    private fun consumeWhole(response: okhttp3.Response, emit: (StreamEvent) -> Unit) {
        val text = response.body.string()
        val obj = Http.parseObject(text)
            ?: throw IOException("response was not JSON: ${text.take(200)}")

        obj.obj("usage")?.let { usage ->
            emit(
                StreamEvent.Usage(
                    promptTokens = usage.int("input_tokens", 0),
                    completionTokens = usage.int("output_tokens", 0),
                    cachedPromptTokens = usage.int("cache_read_input_tokens", 0),
                ),
            )
        }
        for (block in (obj["content"] as? JsonArray).orEmptyObjects()) {
            when (block.str("type")) {
                "text" -> block.str("text")?.let { emit(StreamEvent.TextDelta(it)) }
                "thinking" -> {
                    block.str("thinking")?.let { emit(StreamEvent.ThinkingDelta(it)) }
                    block.str("signature")?.let { emit(StreamEvent.ThinkingSignature(it)) }
                }
                "redacted_thinking" ->
                    emit(StreamEvent.ThinkingSignature(block.str("data", ""), redacted = true))
                "tool_use" -> {
                    val id = block.str("id", "call")
                    emit(StreamEvent.ToolCallStart(id, block.str("name", "")))
                    block["input"]?.let { emit(StreamEvent.ToolCallArgsDelta(id, it.toString())) }
                    emit(StreamEvent.ToolCallEnd(id))
                }
            }
        }
        emit(StreamEvent.Done(obj.str("stop_reason")))
    }

    private fun JsonArray?.orEmptyObjects(): List<JsonObject> =
        this?.mapNotNull { it as? JsonObject } ?: emptyList()

    /* ------------------------------------------------------- request shaping */

    private fun buildBody(request: CompletionRequest, streaming: Boolean): JsonObject =
        buildJsonObject {
            put("model", request.model.id)
            // Required by the API, and it must leave room for the thinking
            // budget as well as the visible answer.
            put("max_tokens", request.maxOutputTokens)
            put("stream", streaming)
            if (request.system.isNotBlank()) put("system", request.system)
            put("messages", encodeMessages(request.messages))

            if (request.stopSequences.isNotEmpty()) {
                put("stop_sequences", buildJsonArray { request.stopSequences.forEach { add(it) } })
            }

            if (request.tools.isNotEmpty() && request.model.supportsTools) {
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("input_schema", tool.parameters)
                                },
                            )
                        }
                    },
                )
                when (val choice = request.toolChoice) {
                    CompletionRequest.ToolChoice.Auto ->
                        putJsonObject("tool_choice") { put("type", "auto") }
                    CompletionRequest.ToolChoice.None ->
                        putJsonObject("tool_choice") { put("type", "none") }
                    is CompletionRequest.ToolChoice.Required -> putJsonObject("tool_choice") {
                        put("type", "tool")
                        put("name", choice.name)
                    }
                }
            }

            // Reasoning. "Off" is the level `none`, or a pass that asks for
            // none at all (compaction, the endpoint test).
            val effort = request.reasoningEffort?.takeIf { it != ReasoningLevels.NONE }
            val off = request.reasoningEffort == ReasoningLevels.NONE ||
                (request.thinking == ThinkingMode.OFF && request.reasoningEffort == null)
            val types = request.model.thinkingTypes
            var thinkingOn = false
            when (request.model.reasoning) {
                // Claude 4.6 on: the model decides how much to think, the
                // level steers it. `display` asks for the summary back — on
                // the newest models it is omitted unless asked for.
                ReasoningSupport.ADAPTIVE -> when {
                    !off -> {
                        putJsonObject("thinking") {
                            put("type", "adaptive")
                            put("display", "summarized")
                        }
                        thinkingOn = true
                    }
                    // Only where the model takes it: several refuse
                    // `disabled` with a 400 and simply keep thinking.
                    THINKING_DISABLED in types -> putJsonObject("thinking") { put("type", "disabled") }
                    // A model that cannot stop thinking thinks.
                    types.isNotEmpty() -> thinkingOn = true
                }

                // Up to Claude 4.5: a token budget.
                ReasoningSupport.BUDGET -> if (!off) {
                    val budget = request.thinking.budgetTokens(request.maxOutputTokens)
                    // The budget has to be strictly less than max_tokens,
                    // otherwise the request is rejected outright.
                    if (budget != null && budget < request.maxOutputTokens) {
                        putJsonObject("thinking") {
                            put("type", "enabled")
                            put("budget_tokens", budget)
                        }
                        thinkingOn = true
                    }
                }

                else -> Unit
            }
            // The level, where the model publishes levels at all. With a budget
            // too: Opus 4.5 takes both.
            if (effort != null && effort in request.model.reasoningEfforts) {
                putJsonObject("output_config") { put("effort", effort) }
            }

            // Thinking takes no sampling changes; sent anyway, they are a 400.
            if (!thinkingOn) {
                request.temperature?.let { put("temperature", it) }
                request.topP?.let { put("top_p", it) }
            }
        }

    /**
     * Encode the transcript.
     *
     * Rules that differ from the chat protocol and matter:
     *  * Consecutive messages of the same role are not allowed, so tool results
     *    are gathered into one user message.
     *  * Thinking blocks are emitted **before** text in an assistant message,
     *    with their signatures, or the follow-up request is rejected.
     *  * A `digest` becomes a user message prefixed as context, since there is
     *    no additional system slot in the transcript.
     */
    private fun encodeMessages(messages: List<Message>): JsonArray {
        // Built as (role, blocks) pairs first, then adjacent same-role entries
        // are merged, because this API is strict about the shape of the
        // transcript in two ways the chat protocol is not:
        //
        //   * Roles must alternate. A prompt here routinely produces three
        //     consecutive `user` entries — the rendered digest, the FTS recall
        //     block, and what the user actually typed — and emitting them
        //     separately is a 400.
        //   * Tool results are `user` blocks, not messages with a `tool` role,
        //     so a batch of results has to land inside one entry.
        val entries = mutableListOf<Pair<String, MutableList<JsonObject>>>()

        fun append(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            val last = entries.lastOrNull()
            if (last != null && last.first == role) {
                last.second.addAll(blocks)
            } else {
                entries += role to blocks.toMutableList()
            }
        }

        for (message in messages) {
            when (message.role) {
                Role.TOOL -> append(
                    "user",
                    message.toolResults.map { result ->
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", result.callId)
                            put("content", result.content.ifBlank { "(no output)" })
                            if (result.isError) put("is_error", true)
                        }
                    },
                )

                Role.ASSISTANT -> {
                    val blocks = mutableListOf<JsonObject>()
                    for (thinking in message.thinking) {
                        when {
                            thinking.redacted -> blocks += buildJsonObject {
                                put("type", "redacted_thinking")
                                put("data", thinking.signature.orEmpty())
                            }
                            // Without a signature the block cannot be replayed,
                            // so it is dropped rather than sent and rejected.
                            thinking.signature != null -> blocks += buildJsonObject {
                                put("type", "thinking")
                                put("thinking", thinking.text)
                                put("signature", thinking.signature)
                            }
                        }
                    }
                    if (message.text.isNotBlank()) {
                        blocks += buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                    }
                    for (use in message.toolUses) {
                        blocks += buildJsonObject {
                            put("type", "tool_use")
                            put("id", use.callId)
                            put("name", use.name)
                            put(
                                "input",
                                Http.parseObject(use.argumentsJson.ifBlank { "{}" })
                                    ?: buildJsonObject {},
                            )
                        }
                    }
                    append("assistant", blocks)
                }

                else -> {
                    val prefix = if (message.role == Role.DIGEST || message.role == Role.SYSTEM) {
                        "[context]\n"
                    } else {
                        ""
                    }
                    val blocks = mutableListOf<JsonObject>()
                    for (image in message.content.filterIsInstance<Block.Image>()) {
                        blocks += buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", image.mimeType)
                                put("data", image.data)
                            }
                        }
                    }
                    // An image sent without words needs no text beside it; an
                    // empty text block is rejected, so a message with neither
                    // still says so.
                    if (message.text.isNotBlank() || blocks.isEmpty()) {
                        blocks += buildJsonObject {
                            put("type", "text")
                            put("text", prefix + message.text.ifBlank { "(empty)" })
                        }
                    }
                    append("user", blocks)
                }
            }
        }

        // The transcript must open with `user`. It normally does, but a session
        // restored mid-turn can start on an assistant message, and the API
        // rejects that outright rather than ignoring it.
        if (entries.firstOrNull()?.first == "assistant") {
            entries.add(
                0,
                "user" to mutableListOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "(continuing a previous session)")
                    },
                ),
            )
        }

        return buildJsonArray {
            entries.forEach { (role, blocks) ->
                add(
                    buildJsonObject {
                        put("role", role)
                        put("content", buildJsonArray { blocks.forEach { add(it) } })
                    },
                )
            }
        }
    }

    /**
     * Anthropic's `/models` reports each model's limits (`max_input_tokens`,
     * `max_tokens`) and capabilities; [ModelDiscovery] reads them like any
     * listing's. Anthropic-compatible gateways often report ids only: those
     * specs come back with a conservative window, which the first overflow
     * error corrects ([Http.extractContextLimit]).
     *
     * Reasoning follows what the listing says the model takes
     * (`capabilities.thinking.types`, read into [ModelSpec.thinkingTypes]):
     * adaptive thinking from Claude 4.6 on — 4.7 and later answer a budget
     * with a 400 — and a budget before that. A gateway whose listing says
     * nothing gets the budget, which is what the Messages API took longest.
     */
    override suspend fun listModels(endpoint: Endpoint, apiKey: String?): List<ModelSpec> =
        // Anthropic pages 20 at a time unless asked for more; 1000 is its
        // maximum. Anthropic-compatible gateways often implement only
        // /messages, which ModelRoutes reports as an empty list.
        ModelRoutes.list(endpoint, apiKey, firstQuery = "limit=1000").map(::messagesSpec)

    override suspend fun describeModel(endpoint: Endpoint, apiKey: String?, modelId: String): ModelSpec? =
        runCatching { ModelRoutes.describe(endpoint, apiKey, modelId) }.getOrNull()?.let(::messagesSpec)

    /** A listed model as the Messages API takes it. */
    private fun messagesSpec(model: ModelSpec): ModelSpec {
        val types = model.thinkingTypes
        val dialect = when {
            "adaptive" in types -> ReasoningSupport.ADAPTIVE
            "enabled" in types || types.isEmpty() -> ReasoningSupport.BUDGET
            else -> ReasoningSupport.NONE
        }
        // "Off" is a level of its own where the model can be told to stop.
        val efforts = if (dialect == ReasoningSupport.ADAPTIVE && THINKING_DISABLED in types && model.reasoningEfforts.isNotEmpty()) {
            listOf(ReasoningLevels.NONE) + model.reasoningEfforts.filter { it != ReasoningLevels.NONE }
        } else {
            model.reasoningEfforts
        }
        return model.copy(
            reasoning = dialect,
            reasoningEfforts = efforts,
            reasoningMandatory = dialect == ReasoningSupport.ADAPTIVE && THINKING_DISABLED !in types,
            // Every Claude since 3 reads images. A listing with capabilities
            // says per model (`image_input`); one without is taken as yes.
            supportsImages = if (types.isEmpty()) true else model.supportsImages,
        )
    }

    private companion object {
        const val THINKING_DISABLED = "disabled"
    }
}
