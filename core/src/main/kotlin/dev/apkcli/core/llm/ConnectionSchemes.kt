package dev.apkcli.core.llm

import dev.apkcli.core.model.AuthScheme
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Protocol

/**
 * The services ApkCLI knows how to reach, each carrying everything a
 * connection to it needs except the credential.
 *
 * The URL, the wire format, the header the credential goes in and the
 * attribution headers are facts about a service, not decisions, so the form
 * for a scheme is a single field. Only [CUSTOM] asks for more, because there
 * nothing is known. Every value is checked against the service's own
 * documentation; docs/PROVIDERS.md has the sources.
 *
 * The facts are re-applied to a scheme's endpoints every time settings are
 * read ([applyTo]), so a correction here — a renamed header, a moved route —
 * reaches connections created by an earlier version without anyone editing
 * them.
 *
 * There is no model list in here. Every endpoint is asked for its own;
 * [ModelSource] says how much the answer carried.
 */
enum class ConnectionScheme(
    /** Stored in [Endpoint.scheme]. */
    val id: String,
    /** The service's own name; brand names are not translated. Empty for [CUSTOM]. */
    val displayName: String,
    val baseUrl: String,
    val protocol: Protocol,
    val authScheme: AuthScheme,
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * What a credential for this service starts with, so a paste of the wrong
     * thing is caught before a request is spent on it. A mismatch is a
     * warning, not a refusal: the prefixes are observed, not all documented.
     */
    val keyPrefix: String = "",
    /**
     * A route, relative to [baseUrl], that says whether the credential is
     * accepted, for a service whose model listing does not need one. Null
     * where listing the models is that check already.
     */
    val checkPath: String? = null,
) {
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        protocol = Protocol.ANTHROPIC_MESSAGES,
        // anthropic-version is added by Http for every Messages request.
        authScheme = AuthScheme.X_API_KEY,
        keyPrefix = "sk-ant-",
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = Protocol.OPENAI_CHAT,
        authScheme = AuthScheme.BEARER,
        keyPrefix = "sk-",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        protocol = Protocol.OPENAI_CHAT,
        authScheme = AuthScheme.BEARER,
        // Attribution, optional: it labels the traffic in OpenRouter's
        // dashboard. X-OpenRouter-Title is the current name, X-Title the one
        // it still accepts. HTTP-Referer is left out: it would have to be a
        // site this app owns.
        extraHeaders = mapOf("X-OpenRouter-Title" to "ApkCLI", "X-Title" to "ApkCLI"),
        keyPrefix = "sk-or-v1-",
        // The model list is public; GET /api/v1/key is what needs the key,
        // and it costs nothing.
        checkPath = "key",
    ),
    KIRO(
        id = "kiro",
        displayName = "Kiro",
        // Amazon Q, where the model list comes from. KiroAdapter sends
        // requests to the CodeWhisperer surface first and Amazon Q's second,
        // as Kiro Account Manager does.
        baseUrl = "https://q.us-east-1.amazonaws.com",
        protocol = Protocol.KIRO,
        // The credential is an account refresh token the adapter exchanges
        // for an access token (or a Kiro API key); Http never sends it as
        // it is. BEARER only says that one is needed.
        authScheme = AuthScheme.BEARER,
        keyPrefix = "aorAAAAAG",
    ) {
        /** A Builder ID sign-in pastes as JSON or as Kiro Account Manager's line format. */
        override fun looksLikeKey(key: String): Boolean = super.looksLikeKey(key) || KiroCredential.isStructured(key)
    },

    /** vLLM, llama.cpp, Ollama, LM Studio, a proxy: the user says where and how. */
    CUSTOM(
        id = "custom",
        displayName = "",
        baseUrl = "",
        protocol = Protocol.OPENAI_CHAT,
        authScheme = AuthScheme.BEARER,
    ),
    ;

    /** True for the services whose form is the credential alone. */
    val keyOnly: Boolean get() = this != CUSTOM

    /** Does [key] look like this service's credential? True when there is nothing to check. */
    open fun looksLikeKey(key: String): Boolean = keyPrefix.isEmpty() || key.trim().startsWith(keyPrefix)

    /** A new endpoint for this service, ready for its key. Not for [CUSTOM], which has no facts. */
    fun newEndpoint(id: String, now: Long = System.currentTimeMillis()): Endpoint {
        require(keyOnly) { "a custom endpoint is built from the user's form" }
        return applyTo(Endpoint(id = id, name = displayName, baseUrl = baseUrl, createdAt = now))
    }

    /**
     * [endpoint] with this service's facts, whatever it had stored. [CUSTOM]
     * only records itself: everything about a custom endpoint is the user's.
     */
    fun applyTo(endpoint: Endpoint): Endpoint = when (this) {
        CUSTOM -> if (endpoint.scheme == id) endpoint else endpoint.copy(scheme = id)
        else -> endpoint.copy(
            scheme = id,
            baseUrl = baseUrl,
            protocol = protocol,
            authScheme = authScheme,
            authHeaderName = "Authorization",
            extraHeaders = extraHeaders,
        )
    }

    companion object {
        fun byId(id: String?): ConnectionScheme? = entries.firstOrNull { it.id == id }

        /**
         * The scheme of [endpoint]: the one it records, or for an endpoint
         * saved before schemes existed, the service its URL points at.
         */
        fun of(endpoint: Endpoint): ConnectionScheme =
            byId(endpoint.scheme) ?: entries.firstOrNull { scheme ->
                scheme.keyOnly && scheme.baseUrl.equals(endpoint.baseUrl.trim().removeSuffix("/"), ignoreCase = true)
            } ?: CUSTOM
    }
}

/**
 * Where an endpoint's model list came from, which the model picker says.
 * Every scheme lists its models from the service — Kiro too, through
 * `ListAvailableModels` — so the only question is how much the list said.
 */
enum class ModelSource {
    /** The listing carried the limits: context window, output cap (OpenRouter, Kiro, vLLM-style servers). */
    FULL,

    /** The listing carried ids only, so the windows are cautious defaults until an overflow error tells the real one. */
    IDS,
    ;

    companion object {
        fun of(endpoint: Endpoint): ModelSource = when {
            // Most rather than all: one model added by hand, or one window
            // learned from an overflow error, does not change where the
            // list came from.
            endpoint.models.count { it.metadataFromEndpoint } * 2 > endpoint.models.size -> FULL
            else -> IDS
        }
    }
}
