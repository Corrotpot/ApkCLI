package dev.apkcli.core.llm

import android.util.Log
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.store.SecretStore
import dev.apkcli.core.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Kiro, by its account token: Amazon Q Developer's `generateAssistantResponse`.
 *
 * Kiro has no public API. This follows Kiro Account Manager's proxy
 * (github.com/chaogei/Kiro-account-manager; its facts, not its code, since
 * it is AGPL; docs/PROVIDERS.md), which speaks to the service the way Kiro
 * IDE does:
 *
 *  * Two surfaces, the CodeWhisperer one first, then Amazon Q's, both in
 *    N. Virginia and both routed by path. A surface that refuses for any
 *    reason but the token hands the request to the next; a refused token
 *    stops there, after one more exchange of a refresh token.
 *  * Kiro IDE's headers: its user agent with this connection's device id,
 *    `x-amzn-kiro-agent-mode: vibe`, the SDK's invocation and retry headers.
 *    One addition: `x-amzn-codewhisperer-optout`, which Kiro IDE sends for a
 *    user who keeps their content out of service improvement.
 *
 * The answer is an AWS event-stream, not SSE ([AwsEventStream]), carrying
 * text, reasoning, tool calls in fragments, and usage; [KiroWire] shapes the
 * request and reads the events. Credentials are [KiroAuth]'s.
 *
 * Nothing here is endorsed by AWS; it is the user's own subscription used
 * from another client, and the connection form says so.
 */
class KiroAdapter(secrets: SecretStore) : LlmProtocolAdapter {

    override val protocolName = "kiro"

    private val auth = KiroAuth(secrets)

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = callbackFlow {
        val current = AtomicReference<Call?>()
        // Blocking, so a busy collector slows the read instead of losing
        // events; see OpenAiChatAdapter.
        val emit: (StreamEvent) -> Unit = { event -> trySendBlocking(event) }
        val worker = Thread({
            try {
                run(request, current, emit)
            } catch (io: IOException) {
                if (current.get()?.isCanceled() == true) {
                    emit(StreamEvent.Failed(LlmError(LlmError.Kind.CANCELLED, "Stopped")))
                } else {
                    emit(StreamEvent.Failed(Http.classify(io)))
                }
            } catch (t: Throwable) {
                emit(StreamEvent.Failed(Http.classify(t)))
            } finally {
                close()
            }
        }, "apkcli-llm-kiro")
        worker.isDaemon = true
        worker.start()
        awaitClose { current.get()?.takeUnless { it.isCanceled() }?.cancel() }
    }

    private fun run(request: CompletionRequest, current: AtomicReference<Call?>, emit: (StreamEvent) -> Unit) {
        val credential = request.apiKey?.trim().orEmpty()
        if (credential.isEmpty()) {
            emit(StreamEvent.Failed(noToken()))
            return
        }
        val conversationId = conversationId(request)
        var session = auth.session(request.endpoint, credential)
        var exchangedAgain = false

        while (true) {
            val body = KiroWire.body(request, session.profileArn, conversationId)
            val bytes = body.json.toString().toByteArray(Charsets.UTF_8)
            var last: LlmError? = null
            var tokenRefused = false

            for (surface in SURFACES) {
                val call = streamingClient(request.endpoint).newCall(generateRequest(surface, bytes, session))
                current.set(call)
                val refusal: LlmError = call.execute().use { response ->
                    if (response.isSuccessful) {
                        consume(response, body.toolNames, emit)
                        return
                    }
                    val text = runCatching { response.body.string() }.getOrNull()
                    if (response.code == 401 || response.code == 403) tokenRefused = true
                    KiroWire.error(response.code, text, bytes.size, estimatedTokens(request), request.maxOutputTokens)
                }
                last = refusal
                // A refused token is refused everywhere.
                if (tokenRefused) break
                Log.i(TAG, "${surface.name} answered HTTP ${refusal.httpStatus}; trying the next surface")
            }

            // The token was refused: a refresh token gets one more exchange, in
            // case the cached access token was revoked early.
            if (tokenRefused && !session.apiKey && !exchangedAgain) {
                exchangedAgain = true
                session = auth.session(request.endpoint, credential, forceRefresh = true)
                continue
            }
            emit(StreamEvent.Failed(last ?: noToken()))
            return
        }
    }

    private fun consume(
        response: okhttp3.Response,
        toolNames: Map<String, String>,
        emit: (StreamEvent) -> Unit,
    ) {
        val decoder = KiroWire.Decoder(toolNames)
        val source = response.body.source()
        while (true) {
            val frame = AwsEventStream.read(source) ?: break
            val events = decoder.accept(frame)
            events.forEach(emit)
            if (events.any { it is StreamEvent.Failed }) return
        }
        val finished = decoder.finish()
        // What a request cost and how full the window is: the stream has no
        // token counts, cached or not, so this is all there is to see what the
        // prompt cache saves (KiroPromptCache).
        Log.i(TAG, "request: %.3f credits, window %.2f%% full".format(Locale.ROOT, decoder.credits, decoder.contextPercent ?: 0.0))
        finished.forEach(emit)
    }

    private class Surface(val name: String, val url: String)

    private fun generateRequest(surface: Surface, body: ByteArray, session: KiroAuth.Session): Request =
        Request.Builder()
            .url(surface.url)
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("x-amzn-kiro-agent-mode", AGENT_MODE)
            .apply { identify(this, session, attempts = 3) }
            .build()

    /** What Kiro's clients send with every request: the token, the client marker, the SDK headers. */
    private fun identify(builder: Request.Builder, session: KiroAuth.Session, attempts: Int) {
        builder
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("User-Agent", KiroIdentity.userAgent(session.machineId))
            .header("x-amz-user-agent", KiroIdentity.amzUserAgent(session.machineId))
            .header("amz-sdk-invocation-id", UUID.randomUUID().toString())
            .header("amz-sdk-request", "attempt=1; max=$attempts")
            // Do not let the conversation be used to improve the service.
            .header("x-amzn-codewhisperer-optout", "true")
        session.tokenType?.let { builder.header("TokenType", it) }
    }

    private fun streamingClient(endpoint: Endpoint) = Http.client.newBuilder()
        .readTimeout(endpoint.streamIdleTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    /**
     * One id per chat, stable across its requests, the way Kiro's own client
     * keeps one per conversation. The history is sent whole every time, so
     * nothing depends on the service remembering it.
     */
    private fun conversationId(request: CompletionRequest): String {
        val session = request.messages.firstOrNull()?.sessionId?.takeIf { it.isNotBlank() }
            ?: return UUID.randomUUID().toString()
        return UUID.nameUUIDFromBytes("apkcli:$session".toByteArray()).toString()
    }

    private fun estimatedTokens(request: CompletionRequest): Int =
        request.messages.sumOf { it.tokens } + request.system.length / 4

    private fun noToken() = LlmError(
        LlmError.Kind.AUTH,
        "No Kiro account token. Paste the refreshToken from the Kiro IDE in Settings › Connections.",
        hint = LlmError.Hint.CHECK_KEY,
    )

    /* ------------------------------------------------------------ models */

    /**
     * `ListAvailableModels` on the Amazon Q surface: the account's own list,
     * with each model's limits and reasoning schema, paged by `nextToken`.
     */
    override suspend fun listModels(endpoint: Endpoint, apiKey: String?): List<ModelSpec> = withContext(Dispatchers.IO) {
        val credential = apiKey?.trim().orEmpty()
        if (credential.isEmpty()) throw LlmException(noToken())
        var session = auth.session(endpoint, credential)
        var listed = runCatching { listAll(session) }
        val refused = (listed.exceptionOrNull() as? LlmException)?.error?.kind == LlmError.Kind.AUTH
        if (refused && !session.apiKey) {
            session = auth.session(endpoint, credential, forceRefresh = true)
            listed = runCatching { listAll(session) }
        }
        listed.getOrThrow()
    }

    private fun listAll(session: KiroAuth.Session): List<ModelSpec> {
        val models = mutableListOf<ModelSpec>()
        var next: String? = null
        var pages = 0
        do {
            val url = "https://q.${session.serviceRegion}.amazonaws.com/ListAvailableModels".toHttpUrl().newBuilder()
                .addQueryParameter("origin", "AI_EDITOR")
                .addQueryParameter("maxResults", PAGE_SIZE.toString())
                .apply {
                    session.profileArn?.let { addQueryParameter("profileArn", it) }
                    next?.let { addQueryParameter("nextToken", it) }
                }
                .build()
            val request = Request.Builder().url(url).get()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .apply { identify(this, session, attempts = 1) }
                .build()
            val page = listingClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw LlmException(KiroWire.error(response.code, text, 0, 0, 0))
                Http.parseObject(text)
                    ?: throw LlmException(LlmError(LlmError.Kind.PROTOCOL, "Kiro's model list was not JSON", body = text.take(500)))
            }
            models += KiroWire.models(page)
            next = page.str("nextToken")?.takeIf { it.isNotBlank() }
        } while (next != null && ++pages < MAX_PAGES)
        val listed = models.distinctBy { it.id }
        Log.i(TAG, "listed ${listed.size} models, ${listed.count { it.caching != null }} with prompt caching")
        return listed
    }

    private val listingClient by lazy { Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    companion object {
        private const val TAG = "KiroAdapter"

        /** In the order Kiro Account Manager tries them; both are in N. Virginia whatever the account's region. */
        private val SURFACES = listOf(
            Surface("CodeWhisperer", "https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse"),
            Surface("Amazon Q", "https://q.us-east-1.amazonaws.com/generateAssistantResponse"),
        )

        /** Kiro IDE's chat mode; `spec` is its planning mode. */
        private const val AGENT_MODE = "vibe"

        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 10

        private val JSON = "application/json".toMediaType()
    }
}
