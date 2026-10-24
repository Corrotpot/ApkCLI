package dev.apkcli.core.llm

import dev.apkcli.core.model.AuthScheme
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Protocol
import dev.apkcli.core.util.NetworkHosts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The helpers the custom form uses, and the ids of connections earlier
 * versions shipped.
 *
 * Connections are made from [ConnectionScheme]s: a known service needs only
 * its key, and everything else about it is the scheme's. This replaces the
 * earlier rule that endpoints are entered by hand with no provider
 * directory — a blank form turned "paste a key" into "know the URL, the wire
 * format and where the key goes", which is the part nobody should have to
 * know. Any OpenAI- or Anthropic-compatible server still works, through the
 * custom scheme.
 *
 * A new install has no connection: every one in the list is one the user
 * added. Earlier versions created OpenRouter on first run, waiting for a
 * key, and as the only connection it was listed as active while nothing
 * could be sent through it.
 */
object EndpointDefaults {

    /**
     * Shipped by earlier versions and no longer: an AnyModel endpoint still
     * without models was never used and is removed once (settings version 3).
     */
    const val RETIRED_ANYMODEL_ID = "anymodel"

    /**
     * The OpenRouter connection versions before settings version 4 created
     * on first run. One that never got a key was never used and is removed
     * once; one with a key is the user's and stays.
     */
    const val RETIRED_FIRST_RUN_ID = "openrouter"

    /** An empty custom form. Not persisted until saved. */
    fun blank(id: String): Endpoint = Endpoint(
        id = id,
        name = "",
        baseUrl = "",
        protocol = Protocol.OPENAI_CHAT,
        authScheme = AuthScheme.BEARER,
        scheme = ConnectionScheme.CUSTOM.id,
        createdAt = System.currentTimeMillis(),
    )

    /**
     * Turn what the user typed into a base URL, or null if it cannot be one.
     *
     * Forgiving about the parts people leave off: a missing scheme becomes
     * `http://` for a LAN address (local servers almost never have TLS) and
     * `https://` for everything else; trailing slashes go. It does not guess
     * at `/v1` — [Http.joinUrl] already copes with it being present or absent.
     */
    fun normaliseBaseUrl(input: String): String? {
        val trimmed = input.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) {
            trimmed
        } else {
            val host = trimmed.substringBefore('/').substringBefore(':')
            val scheme = if (NetworkHosts.isPrivateOrLocal(host)) "http" else "https"
            "$scheme://$trimmed"
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        return withScheme.removeSuffix("/")
    }

    /** A display name when the user left it blank: the host. */
    fun suggestedName(baseUrl: String): String =
        baseUrl.toHttpUrlOrNull()?.host?.removePrefix("api.")?.ifBlank { null } ?: "Endpoint"

    /** Where a base URL points, for the editor's transport hints. */
    data class Target(val https: Boolean, val host: String, val privateHost: Boolean)

    /** Null when [baseUrl] does not parse. Keeps OkHttp's URL type out of the UI module. */
    fun target(baseUrl: String): Target? = baseUrl.toHttpUrlOrNull()?.let {
        Target(https = it.isHttps, host = it.host, privateHost = NetworkHosts.isPrivateOrLocal(it.host))
    }
}
