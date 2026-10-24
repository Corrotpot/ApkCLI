package dev.apkcli.core.llm

import android.util.Log
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
import dev.apkcli.core.util.objects
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
 * `POST {base}/chat/completions`.
 *
 * The default adapter, because this shape is what almost everything speaks:
 * vLLM, llama.cpp's server, LM Studio, Ollama, OpenRouter, Together, Groq,
 * DeepSeek, Mistral, and essentially every proxy.
 *
 * "OpenAI-compatible" is a family, not a spec, so this adapter is written to be
 * permissive about the differences that actually occur:
 *
 *   * Reasoning text arrives as `delta.reasoning_content` (DeepSeek and most
 *     OSS servers), `delta.reasoning` (OpenRouter), or not at all. All three are
 *     accepted.
 *   * `max_tokens` vs `max_completion_tokens`: newer OpenAI models reject the
 *     former, older gateways do not recognise the latter. Both are sent, and
 *     gateways ignore the one they do not know — except to OpenAI itself,
 *     which gets only `max_completion_tokens`.
 *   * Tool call fragments are keyed by `index`, not by `id`, because the id
 *     only appears on the first fragment of each call.
 */
class OpenAiChatAdapter : LlmProtocolAdapter {

    override val protocolName = "openai_chat"

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = callbackFlow {
        val streaming = request.stream && request.endpoint.streaming && request.model.supportsStreaming
        val body = buildBody(request, stream = streaming)
        val call = Http.postJson(
            request.endpoint,
            request.apiKey,
            "chat/completions",
            body,
            streaming = streaming,
        )

        // Blocking, on this worker thread: when the agent falls behind, the
        // network read waits for it. `trySend` used to drop whatever did not
        // fit the 64-event buffer, so a busy phone lost pieces of the answer
        // (broken markdown) and of tool arguments (a file written with a hole
        // in it, or JSON that no longer parsed). Fails only once the
        // collector is gone, which is the end anyway.
        val emit: (StreamEvent) -> Unit = { event -> trySendBlocking(event) }

        // A platform thread, not a coroutine: `call.execute()` and the Okio
        // reads beneath it block, and a cancelled coroutine cannot interrupt a
        // thread parked in a socket read. Cancellation is handled by cancelling
        // the Call in awaitClose, which closes the socket and unblocks it.
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
                // A cancelled call also surfaces as an IOException. Reporting
                // that as a network failure would be wrong and would trigger a
                // retry of something the user just stopped.
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
        }, "apkcli-llm-openai")
        worker.isDaemon = true
        worker.start()

        awaitClose { if (!call.isCanceled()) call.cancel() }
    }

    /* ------------------------------------------------------------ streaming */

    private fun consumeStream(
        response: okhttp3.Response,
        emit: (StreamEvent) -> Unit,
    ) {
        val source = response.body.source()

        // Tool calls stream in fragments keyed by array index. The id and name
        // appear once, on the first fragment; everything after is arguments.
        val callIdByIndex = HashMap<Int, String>()
        val nameByIndex = HashMap<Int, String>()
        val announced = HashSet<Int>()
        var finishReason: String? = null
        var sawUsage = false
        val thinkTags = ThinkTagSplitter()

        Sse.read(source) { event ->
            if (Sse.isDone(event.data)) return@read false
            val obj = Http.parseObject(event.data) ?: return@read true

            // Some gateways deliver errors mid-stream as a data frame instead of
            // an HTTP status.
            obj.obj("error")?.let { err ->
                emit(
                    StreamEvent.Failed(
                        LlmError(
                            LlmError.Kind.SERVER,
                            err.str("message", "stream error"),
                            body = event.data.take(2_000),
                        ),
                    ),
                )
                return@read false
            }

            obj.obj("usage")?.let { usage ->
                sawUsage = true
                emit(usage.toUsageEvent())
            }

            val choice = obj.objects("choices").firstOrNull() ?: return@read true
            choice.str("finish_reason")?.let { finishReason = it }

            val delta = choice.obj("delta") ?: choice.obj("message")
            if (delta != null) {
                // Reasoning, under whichever key this server uses.
                (delta.str("reasoning_content") ?: delta.str("reasoning"))
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(StreamEvent.ThinkingDelta(it)) }

                // OpenRouter wraps upstream reasoning in `reasoning_details`,
                // which is where Anthropic's signature ends up when a Claude
                // model is reached through it. That signature has to survive the
                // round trip or the *next* request fails, so it is captured here
                // even though there is nothing to display.
                for (detail in delta.objects("reasoning_details")) {
                    when (detail.str("type")) {
                        "reasoning.encrypted" -> emit(
                            StreamEvent.ThinkingSignature(
                                detail.str("data", ""),
                                redacted = true,
                            ),
                        )
                        else -> {
                            detail.str("signature")?.takeIf { it.isNotBlank() }
                                ?.let { emit(StreamEvent.ThinkingSignature(it)) }
                            // Some providers deliver the reasoning body only
                            // inside the details array.
                            detail.str("text")?.takeIf { it.isNotEmpty() }
                                ?.let { emit(StreamEvent.ThinkingDelta(it)) }
                        }
                    }
                }

                // Content may carry reasoning in <think> tags; see ThinkTagSplitter.
                delta.str("content")?.takeIf { it.isNotEmpty() }
                    ?.let { content -> thinkTags.accept(content).forEach { emit(it.toStreamEvent()) } }

                for (fragment in delta.objects("tool_calls")) {
                    val index = fragment.int("index", 0)
                    val fn = fragment.obj("function")

                    fragment.str("id")?.takeIf { it.isNotBlank() }
                        ?.let { callIdByIndex[index] = it }
                    fn?.str("name")?.takeIf { it.isNotBlank() }
                        ?.let { nameByIndex[index] = it }

                    val callId = callIdByIndex.getOrPut(index) { "call_$index" }
                    val name = nameByIndex[index]

                    if (name != null && announced.add(index)) {
                        emit(StreamEvent.ToolCallStart(callId, name))
                    }
                    fn?.str("arguments")?.takeIf { it.isNotEmpty() }?.let {
                        emit(StreamEvent.ToolCallArgsDelta(callId, it))
                    }
                }
            }
            true
        }

        thinkTags.finish().forEach { emit(it.toStreamEvent()) }
        callIdByIndex.keys.sorted().forEach { index ->
            callIdByIndex[index]?.let { emit(StreamEvent.ToolCallEnd(it)) }
        }
        if (!sawUsage) {
            // Not every server honours stream_options.include_usage. The agent
            // falls back to its own estimate, and the estimator stays
            // uncalibrated for this endpoint.
            Log.d(TAG, "stream finished without a usage frame")
        }
        emit(StreamEvent.Done(finishReason))
    }

    /** Non-streaming path, for endpoints with broken or absent SSE. */
    private fun consumeWhole(response: okhttp3.Response, emit: (StreamEvent) -> Unit) {
        val text = response.body.string()
        val obj = Http.parseObject(text)
            ?: throw IOException("response was not JSON: ${text.take(200)}")

        obj.obj("usage")?.let { emit(it.toUsageEvent()) }

        val choice = obj.objects("choices").firstOrNull()
        val message = choice?.obj("message")
        if (message != null) {
            (message.str("reasoning_content") ?: message.str("reasoning"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { emit(StreamEvent.ThinkingDelta(it)) }
            message.str("content")?.takeIf { it.isNotEmpty() }?.let { content ->
                val thinkTags = ThinkTagSplitter()
                (thinkTags.accept(content) + thinkTags.finish()).forEach { emit(it.toStreamEvent()) }
            }
            for (toolCall in message.objects("tool_calls")) {
                val id = toolCall.str("id", "call_${toolCall.hashCode()}")
                val fn = toolCall.obj("function")
                emit(StreamEvent.ToolCallStart(id, fn?.str("name", "") ?: ""))
                fn?.str("arguments")?.let { emit(StreamEvent.ToolCallArgsDelta(id, it)) }
                emit(StreamEvent.ToolCallEnd(id))
            }
        }
        emit(StreamEvent.Done(choice?.str("finish_reason")))
    }

    private fun ThinkTagSplitter.Piece.toStreamEvent(): StreamEvent = when (this) {
        is ThinkTagSplitter.Piece.Text -> StreamEvent.TextDelta(text)
        is ThinkTagSplitter.Piece.Thinking -> StreamEvent.ThinkingDelta(text)
        ThinkTagSplitter.Piece.EarlierTextWasThinking -> StreamEvent.TextWasThinking
    }

    private fun JsonObject.toUsageEvent(): StreamEvent.Usage {
        val details = obj("completion_tokens_details")
        val promptDetails = obj("prompt_tokens_details")
        return StreamEvent.Usage(
            promptTokens = int("prompt_tokens", int("input_tokens", 0)),
            completionTokens = int("completion_tokens", int("output_tokens", 0)),
            reasoningTokens = details?.int("reasoning_tokens", 0) ?: 0,
            cachedPromptTokens = promptDetails?.int("cached_tokens", 0) ?: 0,
        )
    }

    /* ------------------------------------------------------- request shaping */

    private fun buildBody(request: CompletionRequest, stream: Boolean): JsonObject =
        buildJsonObject {
            put("model", request.model.id)
            put("messages", encodeMessages(request))
            put("stream", stream)
            if (stream) {
                // Opt in to the final usage frame. Harmlessly ignored by
                // servers that do not implement it.
                putJsonObject("stream_options") { put("include_usage", true) }
            }

            // Both spellings elsewhere: older gateways do not know
            // max_completion_tokens. OpenAI itself gets only that one — its
            // reasoning models answer max_tokens with a 400 even when the
            // other is there too.
            if (ConnectionScheme.of(request.endpoint) != ConnectionScheme.OPENAI) {
                put("max_tokens", request.maxOutputTokens)
            }
            put("max_completion_tokens", request.maxOutputTokens)

            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            if (request.stopSequences.isNotEmpty()) {
                put("stop", buildJsonArray { request.stopSequences.forEach { add(it) } })
            }

            if (request.tools.isNotEmpty() && request.model.supportsTools) {
                put("tools", encodeTools(request.tools))
                when (val choice = request.toolChoice) {
                    CompletionRequest.ToolChoice.Auto -> put("tool_choice", "auto")
                    CompletionRequest.ToolChoice.None -> put("tool_choice", "none")
                    is CompletionRequest.ToolChoice.Required -> putJsonObject("tool_choice") {
                        put("type", "function")
                        putJsonObject("function") { put("name", choice.name) }
                    }
                }
                // Serial tool calls keep the approval UI comprehensible and
                // avoid two concurrent edits to the same file.
                put("parallel_tool_calls", true)
            }

            // Reasoning controls. Only a level the model itself published is
            // ever sent (see ReasoningLevels): reasoning_effort with a value a
            // model does not take is a hard 400 on several gateways, and a
            // made-up scale would map onto nothing real. No published levels
            // means nothing is sent and the model's default applies.
            val effort = request.reasoningEffort
            // The compaction pass and the endpoint test ask for none at all.
            val off = effort == ReasoningLevels.NONE || (request.thinking == ThinkingMode.OFF && effort == null)
            when (request.model.reasoning) {
                ReasoningSupport.EFFORT -> if (effort != null) put("reasoning_effort", effort)

                ReasoningSupport.OPENROUTER_UNIFIED -> when {
                    // "Off" has to be stated. Several models carry
                    // `reasoning.default_enabled = true`, so omitting the
                    // field leaves reasoning on — and billed — while the UI
                    // claims it is disabled. Models with
                    // `reasoning.mandatory = true` ignore this, which is
                    // correct: they cannot run without it.
                    off -> putJsonObject("reasoning") {
                        put("enabled", false)
                        put("exclude", true)
                    }
                    effort != null -> putJsonObject("reasoning") { put("effort", effort) }
                    else -> Unit
                }

                else -> Unit
            }

            // Ask for the reasoning body back. Without it the reasoning is
            // produced and charged for but not returned, so the thinking panel
            // stays empty and the signatures needed to replay the turn are lost.
            if (request.model.reasoning == ReasoningSupport.OPENROUTER_UNIFIED && !off) {
                put("include_reasoning", true)
            }
        }

    private fun encodeTools(tools: List<ToolDeclaration>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    }
                },
            )
        }
    }

    /**
     * Translate the app's message model into the chat wire format.
     *
     * Two things here are easy to get wrong and expensive to debug:
     *
     *  * A tool result must be its own message with `role: "tool"` and the
     *    matching `tool_call_id`. Several results from one assistant turn become
     *    several messages, in the same order as the calls.
     *  * An assistant message that made tool calls must still be present even
     *    if it had no text, otherwise the `tool` messages reference a call the
     *    server never saw and the request is rejected.
     */
    private fun encodeMessages(request: CompletionRequest): JsonArray = buildJsonArray {
        if (request.system.isNotBlank()) {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                },
            )
        }

        for (message in request.messages) {
            when (message.role) {
                Role.SYSTEM -> add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", message.text)
                    },
                )

                Role.DIGEST -> add(
                    // Presented as a system note: it is instruction-like
                    // content about the conversation, not something the user
                    // said.
                    buildJsonObject {
                        put("role", "system")
                        put("content", message.text)
                    },
                )

                Role.USER -> add(encodeUser(message))

                Role.ASSISTANT -> {
                    val toolUses = message.toolUses
                    val text = message.text
                    if (text.isNotBlank() || toolUses.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text)

                                // Echo reasoning back when the provider gave us
                                // a signature for it. Required when a
                                // signature-bearing model (Claude, directly or
                                // via OpenRouter) is used with thinking
                                // enabled: omit it and this turn succeeds while
                                // the next is rejected for an unverifiable
                                // thinking block.
                                val signed = message.thinking.mapNotNull { block ->
                                    val signature = block.signature ?: return@mapNotNull null
                                    buildJsonObject {
                                        if (block.redacted) {
                                            put("type", "reasoning.encrypted")
                                            put("data", signature)
                                        } else {
                                            put("type", "reasoning.text")
                                            put("text", block.text)
                                            put("signature", signature)
                                        }
                                    }
                                }
                                if (signed.isNotEmpty()) {
                                    put(
                                        "reasoning_details",
                                        buildJsonArray { signed.forEach { add(it) } },
                                    )
                                }

                                if (toolUses.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            toolUses.forEach { use ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", use.callId)
                                                        put("type", "function")
                                                        putJsonObject("function") {
                                                            put("name", use.name)
                                                            put(
                                                                "arguments",
                                                                use.argumentsJson.ifBlank { "{}" },
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }

                Role.TOOL -> {
                    for (result in message.toolResults) {
                        add(
                            buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", result.callId)
                                put("content", result.content.ifBlank { "(no output)" })
                            },
                        )
                    }
                }
            }
        }
    }

    private fun encodeUser(message: Message): JsonObject {
        val images = message.content.filterIsInstance<Block.Image>()
        if (images.isEmpty()) {
            return buildJsonObject {
                put("role", "user")
                put("content", message.text)
            }
        }
        return buildJsonObject {
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    if (message.text.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            },
                        )
                    }
                    images.forEach { image ->
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${image.mimeType};base64,${image.data}")
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    /* --------------------------------------------------------------- models */

    /**
     * Read the endpoint's own model metadata.
     *
     * No inference from model names: [ModelDiscovery] reads the fields the
     * server actually publishes. OpenRouter reports the context window, the
     * provider's max completion size, input modalities and which reasoning
     * parameters are accepted, which is everything needed. Servers that report
     * less produce specs flagged as unverified rather than confidently wrong
     * ones.
     */
    override suspend fun listModels(endpoint: Endpoint, apiKey: String?): List<ModelSpec> =
        ModelRoutes.list(endpoint, apiKey).also { models ->
            if (models.isEmpty()) Log.d(TAG, "no models listed by ${endpoint.displayUrl}")
        }

    override suspend fun describeModel(endpoint: Endpoint, apiKey: String?, modelId: String): ModelSpec? =
        runCatching { ModelRoutes.describe(endpoint, apiKey, modelId) }.getOrNull()

    companion object {
        private const val TAG = "OpenAiChatAdapter"
    }
}
