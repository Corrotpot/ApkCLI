package dev.apkcli.core.llm

import dev.apkcli.core.model.AuthScheme
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Protocol
import dev.apkcli.core.util.NetworkHosts
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol as OkProtocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * HTTP plumbing shared by every adapter.
 *
 * One client per process, with per-call timeout overrides. Creating an
 * `OkHttpClient` per request is a classic way to exhaust connections and lose
 * TLS session reuse, which on a phone over mobile data costs a full handshake
 * per turn.
 */
object Http {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Messages API version; the current stable one since its release. */
    const val ANTHROPIC_VERSION = "2023-06-01"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // No global read timeout: streaming reads are bounded per-call
            // instead, because a reasoning model's first token can take a long
            // time and a fixed read timeout would kill valid requests.
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            // Some corporate proxies and older gateways mis-handle h2 streaming
            // and stall until the response completes, which defeats the whole
            // point. HTTP/1.1 is the safe default for SSE.
            .protocols(listOf(OkProtocol.HTTP_1_1))
            .build()
    }

    fun postJson(
        endpoint: Endpoint,
        apiKey: String?,
        path: String,
        body: JsonObject,
        streaming: Boolean,
    ): Call {
        val url = joinUrl(endpoint.displayUrl, path)
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .header("Content-Type", "application/json")
        applyAuth(builder, endpoint, apiKey, url)

        val timeoutMs = if (streaming) {
            endpoint.streamIdleTimeoutSeconds * 1000L
        } else {
            endpoint.timeoutSeconds * 1000L
        }
        val perCall = client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return perCall.newCall(builder.build())
    }

    /** @param readTimeoutSeconds overrides the endpoint's request timeout for this call. */
    fun get(endpoint: Endpoint, apiKey: String?, path: String, readTimeoutSeconds: Long? = null): Call {
        val url = joinUrl(endpoint.displayUrl, path)
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
        applyAuth(builder, endpoint, apiKey, url)
        val perCall = client.newBuilder()
            .readTimeout(readTimeoutSeconds ?: endpoint.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        return perCall.newCall(builder.build())
    }

    /**
     * Why a credentialed request to this endpoint must not be sent, or null.
     *
     * Plain HTTP is allowed — a model server on the LAN almost never has TLS —
     * but not when it would carry the API key across the internet in clear
     * text. Checked before the request exists, so the refusal is a clear
     * message rather than a leak.
     */
    fun transportRefusal(endpoint: Endpoint, apiKey: String?, url: String = endpoint.displayUrl): String? {
        if (apiKey.isNullOrBlank() || endpoint.authScheme == AuthScheme.NONE) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.isHttps || NetworkHosts.isPrivateOrLocal(parsed.host)) return null
        return "Refusing to send the API key for '${endpoint.name}' over unencrypted HTTP to " +
            "${parsed.host}, which is not a private-network address. Use an https:// URL, or " +
            "a LAN/VPN address for a local server."
    }

    /** Thrown by [applyAuth]; [transportRefusal] is the polite pre-check. */
    class InsecureTransportException(message: String) : IOException(message)

    private fun applyAuth(builder: Request.Builder, endpoint: Endpoint, apiKey: String?, url: String) {
        transportRefusal(endpoint, apiKey, url)?.let { throw InsecureTransportException(it) }

        // The Messages API rejects requests without a version header. It is
        // supplied here for every endpoint that speaks it, the Anthropic
        // scheme and custom ones alike. A value in extraHeaders still wins,
        // below.
        if (endpoint.protocol == Protocol.ANTHROPIC_MESSAGES) {
            builder.header("anthropic-version", ANTHROPIC_VERSION)
        }

        if (!apiKey.isNullOrBlank()) {
            when (endpoint.authScheme) {
                AuthScheme.BEARER -> builder.header("Authorization", "Bearer $apiKey")
                AuthScheme.X_API_KEY -> builder.header("x-api-key", apiKey)
                AuthScheme.CUSTOM_HEADER -> builder.header(
                    endpoint.authHeaderName.ifBlank { "Authorization" },
                    apiKey,
                )
                AuthScheme.NONE -> Unit
            }
        }
        endpoint.extraHeaders.forEach { (name, value) ->
            if (name.isNotBlank()) builder.header(name, value)
        }
    }

    /**
     * Join base and path tolerantly.
     *
     * Users paste base URLs with and without `/v1`, with and without a trailing
     * slash. Getting this wrong produces a 404 that looks like an auth problem,
     * so the duplicate-segment case is handled explicitly.
     */
    fun joinUrl(base: String, path: String): String {
        val b = base.removeSuffix("/")
        val p = path.removePrefix("/")
        // Avoid ".../v1/v1/chat/completions" when the user already included it.
        val firstSegment = p.substringBefore('/')
        if (firstSegment.isNotEmpty() && b.endsWith("/$firstSegment")) {
            val rest = p.removePrefix(firstSegment).removePrefix("/")
            return if (rest.isEmpty()) b else "$b/$rest"
        }
        return "$b/$p"
    }

    /** Parse a response body, tolerating empty and non-JSON payloads. */
    fun parseObject(text: String): JsonObject? = runCatching {
        json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    /** Any JSON value — some servers answer with a bare array. Null for empty or non-JSON bodies. */
    fun parseElement(text: String): JsonElement? =
        if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()

    /**
     * Turn a transport or HTTP failure into a classified [LlmError].
     *
     * The body is captured because with third-party gateways the useful
     * information is almost always in there and almost never in the status
     * code — a 400 might mean "context too long", "unknown model", or
     * "this proxy does not support tools", and the handling differs.
     */
    fun classify(response: Response, bodyText: String?): LlmError =
        classify(response.code, response.message, bodyText)

    /** [classify] without an OkHttp response; the status line's text is [reason]. */
    fun classify(status: Int, reason: String, bodyText: String?): LlmError {
        val lowered = bodyText?.lowercase() ?: ""
        val kind = when {
            status == 401 || status == 403 -> LlmError.Kind.AUTH
            // Out of balance or credits: OpenRouter and AnyModel both answer
            // 402. Not retryable and not the key's fault.
            status == 402 -> LlmError.Kind.PAYMENT_REQUIRED
            status == 429 -> LlmError.Kind.RATE_LIMIT
            status in 500..599 -> LlmError.Kind.SERVER
            status == 400 && (
                lowered.contains("context length") ||
                    lowered.contains("context_length") ||
                    lowered.contains("maximum context") ||
                    lowered.contains("too many tokens") ||
                    lowered.contains("prompt is too long")
                ) -> LlmError.Kind.CONTEXT_OVERFLOW
            status == 413 -> LlmError.Kind.CONTEXT_OVERFLOW
            // Before the generic 404: "The selected model is unavailable" as a
            // 404 used to be reported as a wrong URL, sending people to fiddle
            // with /v1 on an endpoint that was fine.
            status in MODEL_STATUSES && mentionsMissingModel(lowered) -> LlmError.Kind.MODEL_UNAVAILABLE
            status in 400..499 -> LlmError.Kind.BAD_REQUEST
            else -> LlmError.Kind.UNKNOWN
        }
        val detail = extractMessage(bodyText)
        val hint = when {
            kind == LlmError.Kind.AUTH -> LlmError.Hint.CHECK_KEY
            kind == LlmError.Kind.PAYMENT_REQUIRED -> LlmError.Hint.TOP_UP
            kind == LlmError.Kind.MODEL_UNAVAILABLE -> LlmError.Hint.PICK_ANOTHER_MODEL
            status == 404 -> LlmError.Hint.CHECK_BASE_PATH
            else -> null
        }
        val said = detail ?: reason.ifBlank { "HTTP $status" }
        val message = when (hint) {
            LlmError.Hint.CHECK_KEY -> "$said — check the API key and the auth scheme for this endpoint."
            LlmError.Hint.PICK_ANOTHER_MODEL -> "$said — pick another model, or refresh the model list."
            LlmError.Hint.CHECK_BASE_PATH ->
                "$said — the path was not found. Check whether the base URL should include or omit /v1."
            else -> said
        }
        return LlmError(
            kind = kind,
            message = message,
            httpStatus = status,
            body = bodyText?.take(4_000),
            discoveredContextLimit = if (kind == LlmError.Kind.CONTEXT_OVERFLOW) {
                extractContextLimit(bodyText)
            } else {
                null
            },
            detail = detail,
            hint = hint,
        )
    }

    /** Statuses a gateway uses for "no such model": OpenAI 404, OpenRouter 400, some proxies 422. */
    private val MODEL_STATUSES = setOf(400, 404, 422)

    private val MISSING_MODEL = listOf(
        // Error codes: OpenAI `model_not_found`, others `model_not_available`, `invalid_model`.
        Regex("""model[_ ]not[_ ](found|available|exist)"""),
        Regex("""invalid[_ ]model"""),
        // "The selected model is unavailable", "The model `x` does not exist",
        // "model 'x' not found", "the model x is not available on your plan".
        Regex("""\bmodel\b[^.\n]{0,80}?\b(not found|does not exist|doesn't exist|unavailable|not available|no longer available|is not a valid)"""),
        // "x is not a valid model ID", "unknown model x", "no such model".
        // Adjacent only: "unknown parameter 'reasoning_effort' for model x"
        // is about the parameter.
        Regex("""\b(not a valid|unknown|no such|nonexistent|non-existent|unsupported) model\b"""),
    )

    /**
     * Whether an error body says the *model* is the problem. Kept to explicit
     * phrasing: "this model does not support tools" is a 400 about the model
     * too, but not a missing one.
     */
    fun mentionsMissingModel(loweredBody: String): Boolean =
        loweredBody.isNotEmpty() && MISSING_MODEL.any { it.containsMatchIn(loweredBody) }

    /**
     * Pull the real context limit out of an overflow error.
     *
     * Providers are specific when you exceed the window, even when their
     * `/models` route says nothing about it:
     *
     *   Anthropic: "prompt is too long: 214331 tokens > 200000 maximum"
     *   OpenAI:    "maximum context length is 128000 tokens, however you
     *               requested 131204 tokens"
     *   vLLM:      "This model's maximum context length is 32768 tokens"
     *
     * All three state the limit as the number followed by, or preceded by,
     * context-length wording. Taking the *smallest* plausible candidate is the
     * safe reading: in the Anthropic phrasing the larger number is what we
     * asked for, not what is allowed.
     */
    fun extractContextLimit(bodyText: String?): Int? {
        if (bodyText.isNullOrBlank()) return null
        val lowered = bodyText.lowercase()
        if (!lowered.contains("context") && !lowered.contains("too long") &&
            !lowered.contains("maximum")
        ) {
            return null
        }
        val candidates = Regex("""\d{4,8}""")
            .findAll(bodyText)
            .mapNotNull { it.value.toIntOrNull() }
            // Below 1024 is not a window; above 16M is a byte count or an id.
            .filter { it in 1_024..16_000_000 }
            .toList()
        return candidates.minOrNull()
    }

    fun classify(throwable: Throwable): LlmError = when (throwable) {
        is LlmException -> throwable.error
        is InsecureTransportException -> LlmError(
            LlmError.Kind.INSECURE_TRANSPORT,
            throwable.message ?: "Refusing to send the API key over unencrypted HTTP.",
            cause = throwable,
            hint = LlmError.Hint.USE_HTTPS,
        )
        is SocketTimeoutException -> LlmError(
            LlmError.Kind.TIMEOUT,
            "The endpoint stopped responding. Raise the stream idle timeout if the " +
                "model spends a long time reasoning before its first token.",
            cause = throwable,
            hint = LlmError.Hint.RAISE_IDLE_TIMEOUT,
        )
        is UnknownHostException -> LlmError(
            LlmError.Kind.NETWORK,
            "Could not resolve the host. Check the base URL and the network.",
            cause = throwable,
            detail = throwable.message,
            hint = LlmError.Hint.CHECK_HOST,
        )
        is ConnectException -> LlmError(
            LlmError.Kind.NETWORK,
            "${throwable.message ?: "Connection refused"} — check the address and port, and that " +
                "the server is running.",
            cause = throwable,
            detail = throwable.message,
            hint = LlmError.Hint.CHECK_SERVER_RUNNING,
        )
        is SSLException -> LlmError(
            LlmError.Kind.NETWORK,
            "TLS failure: ${throwable.message}",
            cause = throwable,
            detail = throwable.message,
            hint = LlmError.Hint.TLS,
        )
        is IOException -> LlmError(
            LlmError.Kind.NETWORK,
            throwable.message ?: "Network error",
            cause = throwable,
            detail = throwable.message,
        )
        is kotlinx.serialization.SerializationException -> LlmError(
            LlmError.Kind.PROTOCOL,
            "Response did not match the selected protocol: ${throwable.message}",
            cause = throwable,
            detail = throwable.message,
        )
        else -> LlmError(
            LlmError.Kind.UNKNOWN,
            throwable.message ?: throwable.javaClass.simpleName,
            cause = throwable,
            detail = throwable.message ?: throwable.javaClass.simpleName,
        )
    }

    /**
     * Dig the human-readable message out of the several shapes providers use.
     * A web page (a proxy's or a website's error page) contributes its title
     * at most: markup is not a message.
     */
    private fun extractMessage(bodyText: String?): String? {
        if (bodyText.isNullOrBlank()) return null
        val obj = parseObject(bodyText)
        if (obj == null) {
            val trimmed = bodyText.trim()
            if (trimmed.startsWith("<")) {
                return HTML_TITLE.find(trimmed)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            }
            return trimmed.take(300)
        }
        (obj["error"] as? JsonObject)?.let { err ->
            (err["message"] as? kotlinx.serialization.json.JsonPrimitive)?.let { return it.content }
        }
        (obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.let { return it.content }
        (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.let { return it.content }
        (obj["detail"] as? kotlinx.serialization.json.JsonPrimitive)?.let { return it.content }
        return bodyText.take(300)
    }

    private val HTML_TITLE = Regex("""<title[^>]*>([^<]{1,200})</title>""", RegexOption.IGNORE_CASE)
}

/**
 * Execute this call so that cancelling the coroutine cancels the request.
 *
 * `execute()` blocks in socket reads, and coroutine cancellation cannot
 * interrupt a thread parked there: a stopped `web_search` went on waiting for
 * DuckDuckGo regardless. A watcher in the same scope cancels the [Call] when
 * the scope is cancelled, which closes the socket and makes the blocked read
 * throw. [block] reads the response on an IO thread and must consume it fully;
 * the response is closed afterwards.
 */
suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
    val call = this@executeCancellable
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { call.execute().use(block) }
    } finally {
        watcher.cancel()
    }
}
