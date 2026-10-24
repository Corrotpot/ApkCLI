package dev.apkcli.core.llm

import android.util.Log
import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.Protocol
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import dev.apkcli.core.store.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Front door to the model.
 *
 * Owns three things the adapters deliberately do not: choosing the adapter,
 * retrying, and feeding the provider's authoritative token counts back into the
 * estimator.
 */
class LlmClient(private val secrets: SecretStore) {

    private val adapters: Map<Protocol, LlmProtocolAdapter> = mapOf(
        Protocol.OPENAI_CHAT to OpenAiChatAdapter(),
        Protocol.ANTHROPIC_MESSAGES to AnthropicAdapter(),
        // Holds the store: a token exchange can rotate the stored credential.
        Protocol.KIRO to KiroAdapter(secrets),
    )

    private val requestCounter = AtomicLong(1)

    fun adapterFor(endpoint: Endpoint): LlmProtocolAdapter =
        adapters[endpoint.protocol]
            ?: error("No adapter for ${endpoint.protocol}; this is a build error.")

    private fun keyFor(endpoint: Endpoint): String? = secrets.apiKey(endpoint.id)

    /**
     * Stream a completion, retrying transient failures.
     *
     * Retry is restricted to failures that are actually transient — network,
     * timeout, 429, 5xx — and only while **nothing has been emitted yet**. Once
     * tokens have arrived, retrying would duplicate a partial answer and bill
     * for both, so a mid-stream failure is reported as-is and the agent decides.
     */
    fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        val adapter = adapterFor(request.endpoint)
        val tagged = request.copy(
            apiKey = request.apiKey ?: keyFor(request.endpoint),
            requestId = "req-${requestCounter.getAndIncrement()}",
        )

        // Before any attempt, and not retryable: retrying cannot make an
        // http:// URL encrypted.
        Http.transportRefusal(tagged.endpoint, tagged.apiKey)?.let { reason ->
            emit(
                StreamEvent.Failed(
                    LlmError(LlmError.Kind.INSECURE_TRANSPORT, reason, hint = LlmError.Hint.USE_HTTPS),
                ),
            )
            return@flow
        }

        var attempt = 0
        val maxAttempts = (request.endpoint.maxRetries + 1).coerceIn(1, 5)

        while (true) {
            attempt++
            var emittedContent = false
            var failure: LlmError? = null

            adapter.stream(tagged).collect { event ->
                when (event) {
                    is StreamEvent.Failed -> failure = event.error
                    is StreamEvent.TextDelta,
                    is StreamEvent.ThinkingDelta,
                    is StreamEvent.ToolCallStart,
                    -> {
                        emittedContent = true
                        emit(event)
                    }
                    else -> emit(event)
                }
            }

            val error = failure
            if (error == null) return@flow

            val canRetry = error.retryable && !emittedContent && attempt < maxAttempts
            if (!canRetry) {
                emit(StreamEvent.Failed(error))
                return@flow
            }

            // Exponential backoff, capped. A 429 with no Retry-After gets the
            // same treatment; being slower than necessary is better than being
            // rate-limited again immediately.
            val backoffMs = (500L shl (attempt - 1)).coerceAtMost(8_000L)
            Log.i(
                TAG,
                "${tagged.requestId} attempt $attempt failed (${error.kind}), " +
                    "retrying in ${backoffMs}ms",
            )
            delay(backoffMs)
        }
    }

    // Token calibration is not done here.
    //
    // It needs both the provider's reported count and *our* estimate for the
    // identical payload, and only the agent loop has the latter — it built the
    // prompt. An earlier version cached the reported count on this client and
    // exposed a `calibrate(estimated)` method; nothing called it, because the
    // loop already had the usage event in hand. See `AgentLoop`.

    /**
     * The endpoint's models, or an empty list if it has no listing route.
     *
     * Runs on [Dispatchers.IO]. The adapters call OkHttp's blocking `execute()`
     * and the key lookup is a Keystore decrypt, and every caller so far was a
     * `viewModelScope` — the main thread — where the network call throws
     * `NetworkOnMainThreadException`. That used to be swallowed into "this
     * endpoint does not expose /models", so the Models button could never work.
     *
     * Real failures (unreachable host, 401, a refused cleartext key) throw, so
     * the caller can show what actually went wrong.
     */
    suspend fun listModels(endpoint: Endpoint): List<ModelSpec> = withContext(Dispatchers.IO) {
        val key = keyFor(endpoint)
        Http.transportRefusal(endpoint, key)?.let { throw Http.InsecureTransportException(it) }
        adapterFor(endpoint).listModels(endpoint, key)
    }

    /**
     * Whether the stored credential is accepted, where listing the models
     * would not tell ([ConnectionScheme.checkPath]: OpenRouter lists without
     * a key). Elsewhere a no-op: the listing that follows is the check.
     * Throws as [listModels] does.
     */
    suspend fun checkCredential(endpoint: Endpoint): Unit = withContext(Dispatchers.IO) {
        val path = ConnectionScheme.of(endpoint).checkPath ?: return@withContext
        val key = keyFor(endpoint)
        Http.transportRefusal(endpoint, key)?.let { throw Http.InsecureTransportException(it) }
        ModelRoutes.checkKey(endpoint, key, path)
    }

    /**
     * One model's metadata from the endpoint's per-model route, when its
     * listing did not carry a context window. Null on any failure — this is a
     * lookup worth trying, never one worth failing over.
     */
    suspend fun describeModel(endpoint: Endpoint, modelId: String): ModelSpec? = withContext(Dispatchers.IO) {
        val key = keyFor(endpoint)
        if (Http.transportRefusal(endpoint, key) != null) return@withContext null
        adapterFor(endpoint).describeModel(endpoint, key, modelId)
    }

    /* --------------------------------------------------------------- testing */

    /**
     * Exercise an endpoint end to end, reporting each step separately.
     *
     * Separate steps because "it doesn't work" is useless: a key that
     * authenticates but a model id that does not exist, a server that answers
     * but whose SSE never flushes, a gateway that accepts tools but silently
     * ignores them — these need different fixes, and a single pass/fail hides
     * which one you have.
     */
    suspend fun test(endpoint: Endpoint, modelId: String?): EndpointTestReport {
        val steps = mutableListOf<EndpointTestReport.Step>()
        val overallStart = System.currentTimeMillis()
        val apiKey = keyFor(endpoint)

        // 1. Credential present?
        val keyNeeded = endpoint.authScheme != dev.apkcli.core.model.AuthScheme.NONE
        steps += EndpointTestReport.Step(
            kind = EndpointTestReport.StepKind.API_KEY,
            ok = !keyNeeded || !apiKey.isNullOrBlank(),
            outcome = when {
                !keyNeeded -> EndpointTestReport.Outcome.KeyNotRequired
                apiKey.isNullOrBlank() -> EndpointTestReport.Outcome.KeyMissing
                else -> EndpointTestReport.Outcome.KeyPresent(apiKey.length)
            },
            durationMs = 0,
        )

        // 2. Model listing. A missing route is not fatal — plenty of gateways
        // have no /models — but a listing that *failed* is reported as failed:
        // it is usually the first sign of a wrong URL or a bad key.
        var discovered = emptyList<ModelSpec>()
        var listingError: LlmError? = null
        val listStart = System.currentTimeMillis()
        runCatching { listModels(endpoint) }
            .onSuccess { discovered = it }
            .onFailure {
                listingError = Http.classify(it)
                Log.d(TAG, "listing failed: ${it.message}")
            }
        val listFailure = listingError
        steps += EndpointTestReport.Step(
            kind = EndpointTestReport.StepKind.MODEL_LIST,
            ok = listFailure == null,
            outcome = when {
                listFailure != null -> EndpointTestReport.Outcome.Failed(listFailure)
                discovered.isEmpty() -> EndpointTestReport.Outcome.NoListingRoute
                else -> EndpointTestReport.Outcome.Listed(
                    count = discovered.size,
                    withContextWindow = discovered.count { it.metadataFromEndpoint },
                )
            },
            durationMs = System.currentTimeMillis() - listStart,
        )

        // 3. A real streaming completion, with one tool offered — to a chat
        // model: an image generator answering 404 says nothing about the
        // endpoint, and it was what the first listed model often was.
        val chosen = modelId?.let { id -> discovered.firstOrNull { it.id == id } ?: endpoint.modelById(id) }
        val model = chosen?.takeIf { !ModelDiscovery.looksNonChat(it.id) }
            ?: endpoint.defaultModel?.takeIf { !ModelDiscovery.looksNonChat(it.id) }
            ?: ModelDiscovery.pickDefault(discovered)
            ?: chosen
            ?: endpoint.defaultModel

        if (model == null) {
            steps += EndpointTestReport.Step(
                kind = EndpointTestReport.StepKind.COMPLETION,
                ok = false,
                outcome = EndpointTestReport.Outcome.NoModel,
                durationMs = 0,
            )
            return report(steps, discovered.size, overallStart, null, false, false, false, false)
        }

        val probe = probeCompletion(endpoint, model, apiKey)
        steps += probe.steps
        return report(
            steps = steps,
            modelsDiscovered = discovered.size,
            overallStart = overallStart,
            modelTested = model.id,
            reachable = probe.reachable,
            authOk = probe.authOk,
            streamingOk = probe.streamingOk,
            toolsOk = probe.toolsOk,
            firstTokenMs = probe.firstTokenMs,
        )
    }

    private data class Probe(
        val steps: List<EndpointTestReport.Step>,
        val reachable: Boolean,
        val authOk: Boolean,
        val streamingOk: Boolean,
        val toolsOk: Boolean,
        val firstTokenMs: Long?,
    )

    private suspend fun probeCompletion(
        endpoint: Endpoint,
        model: ModelSpec,
        apiKey: String?,
    ): Probe {
        val steps = mutableListOf<EndpointTestReport.Step>()
        val start = System.currentTimeMillis()
        var firstTokenAt: Long? = null
        var sawText = false
        var sawToolCall = false
        var sawUsage = false
        var deltaCount = 0
        var error: LlmError? = null

        // A prompt that forces a tool call: the only reliable way to find out
        // whether a gateway actually supports tools, as opposed to accepting the
        // field and dropping it.
        val probeTool = ToolDeclaration(
            name = "report_ok",
            description = "Report that the connection works.",
            parameters = Schema.obj(required = listOf("status")) {
                string("status", "Always the literal string 'ok'.")
            },
        )

        val request = CompletionRequest(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            system = "You are a connection test. Call report_ok with status \"ok\". Say nothing else.",
            messages = listOf(
                Message(
                    id = "probe",
                    sessionId = "probe",
                    role = Role.USER,
                    content = listOf(Block.Text("Run the connection test.")),
                ),
            ),
            tools = listOf(probeTool),
            thinking = ThinkingMode.OFF,
            maxOutputTokens = 256,
            stream = endpoint.streaming,
            toolChoice = CompletionRequest.ToolChoice.Auto,
        )

        runCatching {
            adapterFor(endpoint).stream(request).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                        sawText = true
                        deltaCount++
                    }
                    is StreamEvent.ThinkingDelta -> {
                        if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                        deltaCount++
                    }
                    is StreamEvent.ToolCallStart -> {
                        if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                        sawToolCall = true
                    }
                    is StreamEvent.Usage -> sawUsage = true
                    is StreamEvent.Failed -> error = event.error
                    else -> Unit
                }
            }
        }.onFailure { error = Http.classify(it) }

        val failure = error
        val elapsed = System.currentTimeMillis() - start

        steps += EndpointTestReport.Step(
            kind = EndpointTestReport.StepKind.COMPLETION,
            ok = failure == null && (sawText || sawToolCall),
            outcome = when {
                failure != null -> EndpointTestReport.Outcome.Failed(failure)
                sawToolCall || sawText -> EndpointTestReport.Outcome.Responded(elapsed, model.id)
                else -> EndpointTestReport.Outcome.NoOutput
            },
            durationMs = elapsed,
        )

        if (failure == null) {
            steps += EndpointTestReport.Step(
                kind = EndpointTestReport.StepKind.STREAMING,
                ok = !endpoint.streaming || deltaCount > 1 || sawToolCall,
                outcome = when {
                    !endpoint.streaming -> EndpointTestReport.Outcome.StreamingDisabled
                    deltaCount > 1 -> EndpointTestReport.Outcome.Incremental(
                        chunks = deltaCount,
                        firstChunkMs = firstTokenAt?.minus(start) ?: 0,
                    )
                    else -> EndpointTestReport.Outcome.Buffered
                },
                durationMs = 0,
            )

            steps += EndpointTestReport.Step(
                kind = EndpointTestReport.StepKind.TOOLS,
                ok = sawToolCall,
                outcome = if (sawToolCall) {
                    EndpointTestReport.Outcome.ToolCalled
                } else {
                    EndpointTestReport.Outcome.ToolIgnored
                },
                durationMs = 0,
            )

            steps += EndpointTestReport.Step(
                kind = EndpointTestReport.StepKind.USAGE,
                ok = sawUsage,
                outcome = if (sawUsage) {
                    EndpointTestReport.Outcome.UsageReported
                } else {
                    EndpointTestReport.Outcome.UsageMissing
                },
                durationMs = 0,
            )
        }

        return Probe(
            steps = steps,
            reachable = failure == null || failure.kind != LlmError.Kind.NETWORK,
            authOk = failure?.kind != LlmError.Kind.AUTH,
            streamingOk = deltaCount > 1 || sawToolCall,
            toolsOk = sawToolCall,
            firstTokenMs = firstTokenAt?.minus(start),
        )
    }

    private fun report(
        steps: List<EndpointTestReport.Step>,
        modelsDiscovered: Int,
        overallStart: Long,
        modelTested: String?,
        reachable: Boolean,
        authOk: Boolean,
        streamingOk: Boolean,
        toolsOk: Boolean,
        firstTokenMs: Long? = null,
    ): EndpointTestReport = EndpointTestReport(
        reachable = reachable,
        authOk = authOk,
        streamingOk = streamingOk,
        toolsOk = toolsOk,
        modelsDiscovered = modelsDiscovered,
        latencyMs = System.currentTimeMillis() - overallStart,
        firstTokenMs = firstTokenMs,
        modelTested = modelTested,
        steps = steps,
    )

    companion object {
        private const val TAG = "LlmClient"
    }
}
