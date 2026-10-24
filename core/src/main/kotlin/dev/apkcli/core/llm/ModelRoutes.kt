package dev.apkcli.core.llm

import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.ModelSpec
import java.io.IOException
import java.net.URLEncoder

/**
 * The HTTP side of model discovery, shared by both protocol adapters.
 *
 * [ModelDiscovery] reads bodies; this decides which routes to ask and what a
 * status code means. Blocking, like every OkHttp `execute()` here — callers
 * are already on an IO dispatcher (see `LlmClient.listModels`).
 */
internal object ModelRoutes {

    /** Anthropic pages 20 at a time by default; a gateway listing thousands is bounded here. */
    private const val MAX_PAGES = 20

    /**
     * A per-model lookup is a nicety, made right before a turn. It must not
     * hold that turn up for the endpoint's full request timeout.
     */
    private const val DETAIL_TIMEOUT_SECONDS = 15L

    private class Reply(val code: Int, val text: String, val error: LlmError?)

    private fun fetch(endpoint: Endpoint, apiKey: String?, path: String, readTimeoutSeconds: Long? = null): Reply =
        Http.get(endpoint, apiKey, path, readTimeoutSeconds).execute().use { response ->
            val text = response.body.string()
            Reply(
                code = response.code,
                text = text,
                error = if (response.isSuccessful) null else Http.classify(response, text),
            )
        }

    /**
     * Every chat model the endpoint lists, following pagination.
     *
     * Empty when the endpoint has no listing route (404/405) — normal for
     * plenty of gateways, whose models are then typed in. Anything else that
     * is not a model list throws, with the provider's own message: a wrong
     * URL, a bad key or a website answering with HTML need fixing, and an
     * empty list would hide which.
     *
     * @param firstQuery sent with the first request only, e.g. a page size.
     *   Dropped if the server rejects it.
     */
    fun list(endpoint: Endpoint, apiKey: String?, firstQuery: String? = null): List<ModelSpec> {
        val found = LinkedHashMap<String, ModelSpec>()
        var baseQuery = firstQuery
        var cursor: String? = null

        for (page in 0 until MAX_PAGES) {
            val query = listOfNotNull(baseQuery, cursor).joinToString("&")
            var reply = fetch(endpoint, apiKey, if (query.isEmpty()) "models" else "models?$query")

            // A strict server refusing the page-size parameter: ask plainly.
            if (page == 0 && baseQuery != null && reply.code in setOf(400, 422)) {
                baseQuery = null
                reply = fetch(endpoint, apiKey, "models")
            }

            if (reply.code == 404 || reply.code == 405) {
                if (page == 0) return emptyList() else break
            }
            reply.error?.let { error ->
                // Later pages failing still leaves the first ones usable.
                if (page > 0) return ModelDiscovery.order(found.values.toList())
                throw LlmException(error)
            }

            val body = Http.parseElement(reply.text)
            if (body == null) {
                if (page > 0) break
                throw LlmException(notAModelList(reply.text))
            }
            val parsed = ModelDiscovery.parsePage(body)
            parsed.models.forEach { found.putIfAbsent(it.id, it) }
            cursor = parsed.nextQuery ?: break
        }
        return ModelDiscovery.order(found.values.toList())
    }

    /**
     * One model's own metadata, when the listing left the context window out.
     *
     * Two routes, in order: `models/info?id=` (AnyModel's, where the window
     * lives for it) and `models/{id}` (the OpenAI-style retrieve route, which
     * Mistral and a few others fill in). Null when neither reports a window;
     * the caller then keeps the conservative fallback.
     */
    fun describe(endpoint: Endpoint, apiKey: String?, modelId: String): ModelSpec? {
        val encoded = URLEncoder.encode(modelId, "UTF-8")
        val paths = listOf("models/info?id=$encoded", "models/${encoded.replace("+", "%20")}")
        for (path in paths) {
            val reply = try {
                fetch(endpoint, apiKey, path, DETAIL_TIMEOUT_SECONDS)
            } catch (unreachable: IOException) {
                // Unreachable for one route is unreachable for the other.
                return null
            }
            if (reply.code !in 200..299) continue
            val body = Http.parseElement(reply.text) ?: continue
            val spec = ModelDiscovery.parseDetail(body, modelId) ?: continue
            // A window or published reasoning levels: either is worth keeping.
            if (spec.metadataFromEndpoint || spec.reasoningEfforts.isNotEmpty()) return spec
        }
        return null
    }

    /** Throws with the provider's reason when [path] does not accept [apiKey]. */
    fun checkKey(endpoint: Endpoint, apiKey: String?, path: String) {
        fetch(endpoint, apiKey, path).error?.let { throw LlmException(it) }
    }

    private fun notAModelList(text: String): LlmError {
        val looksLikeHtml = text.trimStart().startsWith("<")
        return if (looksLikeHtml) {
            LlmError(
                LlmError.Kind.PROTOCOL,
                "The models route answered with a web page, not a model list. The base URL " +
                    "probably points at a website; API base URLs usually end in /v1.",
                hint = LlmError.Hint.MODELS_ROUTE_IS_HTML,
            )
        } else {
            LlmError(
                LlmError.Kind.PROTOCOL,
                "The models route did not answer with JSON: ${text.take(200)}",
                detail = text.take(200).ifBlank { null },
                hint = LlmError.Hint.MODELS_ROUTE_NOT_JSON,
            )
        }
    }
}
