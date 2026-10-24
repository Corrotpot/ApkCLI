package dev.apkcli.core.llm

import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ThinkingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * One request, expressed once, translated per protocol by the adapters.
 *
 * The app's own [Message] model is the source of truth; nothing upstream of
 * here knows whether the endpoint wants `tool_calls` or `tool_use`, or whether
 * reasoning is an effort string or a token budget.
 */
data class CompletionRequest(
    val endpoint: Endpoint,
    val model: ModelSpec,
    val apiKey: String?,
    /** Assembled by `SystemPrompt`; may be several thousand tokens. */
    val system: String,
    /**
     * Where the part of [system] that changes from request to request begins
     * (`SystemPrompt.Parts`); -1 when the caller did not say. An adapter that
     * caches prompts keeps everything before it as the cached prefix and sends
     * the rest after the conversation. Others ignore it.
     */
    val systemStableLength: Int = -1,
    /** The live window, oldest first, already compacted to fit. */
    val messages: List<Message>,
    val tools: List<ToolDeclaration>,
    val thinking: ThinkingMode,
    /**
     * A level the model published (`none`, `high`, `xhigh`…), sent as is;
     * null sends no level and leaves the model's default. See
     * [dev.apkcli.core.model.ReasoningLevels].
     */
    val reasoningEffort: String? = null,
    val maxOutputTokens: Int,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val stream: Boolean = true,
    val toolChoice: ToolChoice = ToolChoice.Auto,
    /** Opaque per-request id used in logs and in the UI's error reporting. */
    val requestId: String = "",
) {
    sealed interface ToolChoice {
        data object Auto : ToolChoice
        data object None : ToolChoice
        /** Force exactly this tool. Used by the compaction pass. */
        data class Required(val name: String) : ToolChoice
    }
}

/**
 * A tool as the model sees it: name, description, JSON Schema.
 *
 * Separate from the executable `Tool` so that the declaration can be filtered
 * by permission without touching the implementation, and so a subagent can be
 * handed a subset.
 */
data class ToolDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * Incremental output.
 *
 * Streaming is not a nicety here. A reasoning model can spend 30 seconds before
 * its first visible token; without streaming the UI would be frozen for that
 * whole time with nothing to show, and there would be no way to interrupt.
 */
sealed interface StreamEvent {

    data class TextDelta(val text: String) : StreamEvent

    data class ThinkingDelta(val text: String) : StreamEvent

    /**
     * Everything sent as [TextDelta] so far in this response was reasoning: the
     * model closed a `</think>` it never opened. See [ThinkTagSplitter].
     */
    data object TextWasThinking : StreamEvent

    /**
     * The provider's opaque attestation of a reasoning block.
     *
     * Must be echoed back verbatim on the next turn or the provider rejects the
     * request. Dropping it is a subtle failure: the first turn works, the
     * second returns a 400 about an invalid thinking block.
     */
    data class ThinkingSignature(val signature: String, val redacted: Boolean = false) : StreamEvent

    data class ToolCallStart(val callId: String, val name: String) : StreamEvent

    /** Arguments arrive as JSON text fragments and are only valid once joined. */
    data class ToolCallArgsDelta(val callId: String, val fragment: String) : StreamEvent

    data class ToolCallEnd(val callId: String) : StreamEvent

    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val reasoningTokens: Int = 0,
        /** Of [promptTokens], how many were read from the provider's prompt cache. */
        val cachedPromptTokens: Int = 0,
        /** Of [promptTokens], how many were written to it for the next request. */
        val cacheWriteTokens: Int = 0,
    ) : StreamEvent

    /**
     * @param finishReason provider-specific; `"tool_calls"`, `"stop"`,
     *   `"length"`, `"max_tokens"`. The agent loop only distinguishes
     *   "there are tool calls to run" from "the turn is over".
     */
    data class Done(val finishReason: String?) : StreamEvent

    data class Failed(val error: LlmError) : StreamEvent
}

/**
 * A request failure, classified.
 *
 * The classification drives behaviour, so it is not cosmetic: [retryable]
 * decides whether the client re-sends, and [userActionable] decides whether the
 * UI shows a "fix settings" affordance instead of a retry button.
 */
data class LlmError(
    val kind: Kind,
    /**
     * English, complete: the provider's words plus advice. For logs and for
     * the model (a sub-agent's failure is reported to its parent in this
     * form). Screens build their own text from [kind], [detail] and [hint].
     */
    val message: String,
    val httpStatus: Int? = null,
    /** Verbatim provider payload; invaluable with third-party gateways. */
    val body: String? = null,
    /**
     * The model's real context limit, when the provider revealed it in an
     * overflow error.
     *
     * This is how a stored context window gets corrected without a table of
     * model names: an endpoint that does not report its limits in `/models`
     * will state them precisely the first time one is exceeded
     * ("prompt is too long: 214331 tokens > 200000 maximum"). The client
     * surfaces the number, settings persists it, and the mistake is not
     * repeated.
     */
    val discoveredContextLimit: Int? = null,
    val cause: Throwable? = null,
    /**
     * What the provider (or the exception) said, verbatim and without advice
     * mixed in: "The selected model is unavailable." Shown as is, since it
     * cannot be translated; null when there was nothing beyond the status.
     */
    val detail: String? = null,
    /** What to do about it, for the UI to say in its own language. */
    val hint: Hint? = null,
) {
    enum class Kind {
        /** No network, DNS failure, connection reset. */
        NETWORK,
        TIMEOUT,
        /** 401/403. */
        AUTH,
        /** 429, or a provider-specific quota message. */
        RATE_LIMIT,
        /** 400 — malformed request. Usually ours to fix. */
        BAD_REQUEST,
        /**
         * The endpoint answered, but not for this model: unknown id, retired,
         * or not on this key's plan. A 404 that says so is this, not a wrong
         * URL — the advice differs.
         */
        MODEL_UNAVAILABLE,
        /** The prompt exceeded the model's window despite our budgeting. */
        CONTEXT_OVERFLOW,
        /** 5xx. */
        SERVER,
        /** The response did not match the protocol we expected. */
        PROTOCOL,
        /** The user pressed stop. */
        CANCELLED,
        /**
         * Refused before sending: the API key would have crossed the internet
         * over plain HTTP. Never retried — a retry cannot add TLS.
         */
        INSECURE_TRANSPORT,
        /** 402: the balance or credits behind the key ran out. */
        PAYMENT_REQUIRED,
        /** Nothing was sent: there is no endpoint, or no model to send to. */
        CONFIGURATION,
        UNKNOWN,
    }

    /** Advice that goes with an error. [message] carries the same advice in English. */
    enum class Hint {
        /** 401/403: the key or the auth scheme. */
        CHECK_KEY,
        /** A 404 not about the model: the base URL with or without `/v1`. */
        CHECK_BASE_PATH,
        /** Pick another model or refresh the list. */
        PICK_ANOTHER_MODEL,
        /** 402: top up the account. */
        TOP_UP,
        /** No first token in time: a reasoning model may need a longer idle timeout. */
        RAISE_IDLE_TIMEOUT,
        /** DNS: the host name does not resolve. */
        CHECK_HOST,
        /** Nothing listens at that address and port. */
        CHECK_SERVER_RUNNING,
        /** TLS handshake or certificate failure. */
        TLS,
        /** The key would cross the internet over plain HTTP. */
        USE_HTTPS,
        /** The models route answered with a web page: the base URL is a website. */
        MODELS_ROUTE_IS_HTML,
        /** The models route answered with something that is not JSON. */
        MODELS_ROUTE_NOT_JSON,
    }

    val retryable: Boolean
        get() = kind in setOf(Kind.NETWORK, Kind.TIMEOUT, Kind.RATE_LIMIT, Kind.SERVER)

    val userActionable: Boolean
        get() = kind in setOf(
            Kind.AUTH, Kind.CONTEXT_OVERFLOW, Kind.BAD_REQUEST, Kind.MODEL_UNAVAILABLE,
            Kind.INSECURE_TRANSPORT, Kind.PAYMENT_REQUIRED, Kind.CONFIGURATION,
        )

    /** One line for the transcript; [body] goes in the expandable detail. */
    fun headline(): String = when (kind) {
        Kind.NETWORK -> "Network unreachable"
        Kind.TIMEOUT -> "Request timed out"
        Kind.AUTH -> "Endpoint rejected the API key"
        Kind.RATE_LIMIT -> "Rate limited"
        Kind.BAD_REQUEST -> "Endpoint rejected the request"
        Kind.MODEL_UNAVAILABLE -> "The model is unavailable on this endpoint"
        Kind.CONTEXT_OVERFLOW -> "Prompt exceeded the model's context window"
        Kind.SERVER -> "Endpoint returned a server error"
        Kind.PROTOCOL -> "Unexpected response format"
        Kind.CANCELLED -> "Stopped"
        Kind.INSECURE_TRANSPORT -> "Refused to send the key over plain HTTP"
        Kind.PAYMENT_REQUIRED -> "The account behind the key is out of credit"
        Kind.CONFIGURATION -> "Setup is incomplete"
        Kind.UNKNOWN -> "Request failed"
    } + (httpStatus?.let { " (HTTP $it)" } ?: "")
}

/**
 * An [LlmError] where a value has to be returned instead (model listing), so
 * the caller can still word it per kind rather than show [message] raw.
 */
class LlmException(val error: LlmError) : IOException("${error.headline()}: ${error.message}", error.cause)

/** Implemented once per wire protocol. */
interface LlmProtocolAdapter {

    val protocolName: String

    fun stream(request: CompletionRequest): Flow<StreamEvent>

    /**
     * Query the endpoint's model list.
     *
     * Returns an empty list (rather than throwing) when the endpoint has no
     * listing route — plenty of gateways do not, and that is not an error the
     * user needs to see.
     */
    suspend fun listModels(endpoint: Endpoint, apiKey: String?): List<ModelSpec>

    /**
     * One model's own metadata, for when the listing left out its context
     * window. Null when the endpoint has no such route or it says nothing more.
     * Never throws: this is an optional lookup.
     */
    suspend fun describeModel(endpoint: Endpoint, apiKey: String?, modelId: String): ModelSpec? = null
}

/**
 * Result of the settings-screen endpoint test.
 *
 * Findings are data, not sentences: the screen words them in the UI language
 * ([Outcome], [Verdict]); [summary] and [Step.detail] are the English forms,
 * for logs and the stored last result.
 */
data class EndpointTestReport(
    val reachable: Boolean,
    val authOk: Boolean,
    val streamingOk: Boolean,
    val toolsOk: Boolean,
    val modelsDiscovered: Int,
    val latencyMs: Long,
    val firstTokenMs: Long?,
    val modelTested: String?,
    val steps: List<Step>,
) {
    enum class StepKind(val label: String) {
        API_KEY("API key"),
        MODEL_LIST("Model list"),
        COMPLETION("Completion"),
        STREAMING("Streaming"),
        TOOLS("Tool calling"),
        USAGE("Usage reporting"),
    }

    data class Step(
        val kind: StepKind,
        val ok: Boolean,
        val outcome: Outcome,
        val durationMs: Long,
    ) {
        val name: String get() = kind.label
        val detail: String get() = outcome.english
    }

    /** What one step found. */
    sealed interface Outcome {
        val english: String

        data object KeyNotRequired : Outcome {
            override val english get() = "not required for this endpoint"
        }

        data object KeyMissing : Outcome {
            override val english get() = "missing — add one in settings"
        }

        data class KeyPresent(val length: Int) : Outcome {
            override val english get() = "present ($length characters)"
        }

        /** A request failed; the error says how. */
        data class Failed(val error: LlmError) : Outcome {
            override val english get() = "${error.headline()}: ${error.message}"
        }

        data object NoListingRoute : Outcome {
            override val english get() = "endpoint does not expose /models — enter model ids manually"
        }

        data class Listed(val count: Int, val withContextWindow: Int) : Outcome {
            override val english get() = "$count model(s); $withContextWindow report a context window"
        }

        data object NoModel : Outcome {
            override val english get() = "no model to test with — add one first"
        }

        data class Responded(val elapsedMs: Long, val model: String) : Outcome {
            override val english get() = "responded in ${elapsedMs}ms using $model"
        }

        data object NoOutput : Outcome {
            override val english get() = "connected but produced no output — the model id may be wrong"
        }

        data object StreamingDisabled : Outcome {
            override val english get() = "disabled for this endpoint"
        }

        data class Incremental(val chunks: Int, val firstChunkMs: Long) : Outcome {
            override val english get() = "incremental ($chunks chunks, first after ${firstChunkMs}ms)"
        }

        data object Buffered : Outcome {
            override val english
                get() = "the response arrived in one piece — the server or a proxy in front of it is " +
                    "buffering SSE. Streaming will appear to hang, then complete all at once."
        }

        data object ToolCalled : Outcome {
            override val english get() = "the model called the test tool"
        }

        data object ToolIgnored : Outcome {
            override val english
                get() = "the model did not call the offered tool. Either it ignores tools, or the " +
                    "gateway drops them. The agent cannot do anything useful without tool calling."
        }

        data object UsageReported : Outcome {
            override val english get() = "token counts reported — the context meter will self-calibrate"
        }

        data object UsageMissing : Outcome {
            override val english
                get() = "no usage data; the context meter will rely on estimation only (typically within 8%)"
        }
    }

    /** The one-line conclusion over all steps. */
    sealed interface Verdict {
        data object Working : Verdict

        /** No usable answer; [cause] is the completion step, or the first failed one. */
        data class NotWorking(val cause: Step) : Verdict

        /** Answers, but never calls tools — useless to the agent. */
        data object NoTools : Verdict

        data class Caveats(val failed: List<StepKind>) : Verdict
    }

    val verdict: Verdict
        get() {
            val failed = steps.filterNot { it.ok }
            return when {
                failed.isEmpty() -> Verdict.Working
                failed.any { it.kind == StepKind.COMPLETION } ->
                    Verdict.NotWorking(failed.first { it.kind == StepKind.COMPLETION })
                failed.any { it.kind == StepKind.TOOLS } -> Verdict.NoTools
                else -> Verdict.Caveats(failed.map { it.kind })
            }
        }

    val summary: String
        get() = when (val v = verdict) {
            Verdict.Working -> "Working. Everything checked out."
            is Verdict.NotWorking -> "Not working: ${v.cause.detail}"
            Verdict.NoTools -> "Connects, but tool calling does not work — the agent needs it."
            is Verdict.Caveats -> "Usable with caveats: ${v.failed.joinToString("; ") { it.label }}."
        }

    val overallOk: Boolean get() = reachable && authOk
}
