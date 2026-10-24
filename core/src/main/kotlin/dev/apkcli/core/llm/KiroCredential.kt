package dev.apkcli.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What the one Kiro field can hold, read the way Kiro Account Manager
 * (github.com/chaogei/Kiro-account-manager) imports an account. Its import
 * takes three shapes, and so does this:
 *
 *  * The refresh token alone, `aorAAAAAG…`: a Google or GitHub sign-in,
 *    exchanged at Kiro's own auth service.
 *  * JSON: `{refreshToken, clientId, clientSecret, region, authMethod,
 *    provider, profileArn}`, as the manager exports it, as an array of
 *    those, or nested under `credentials`. With a client id and secret it
 *    is an AWS Builder ID or IAM Identity Center sign-in, refreshed at that
 *    region's OIDC service. Kiro IDE's own `kiro-auth-token.json` pastes as
 *    it is for a social sign-in.
 *  * Its line format, `email----password----refreshToken----clientId----clientSecret[----provider]`,
 *    split on `----`, a tab or a run of spaces.
 *
 * Anything else is taken as a Kiro API key and sent as it is.
 *
 * Pure: no network, no store. [encode] is what goes back into the store, so
 * a rotated refresh token keeps the client it belongs to.
 */
sealed interface KiroCredential {

    val refreshToken: String?

    /** Google or GitHub: the refresh token alone. */
    data class Social(override val refreshToken: String, val profileArn: String? = null) : KiroCredential

    /** AWS Builder ID or IAM Identity Center: the token and the OIDC client it was issued to. */
    data class Oidc(
        override val refreshToken: String,
        val clientId: String,
        val clientSecret: String,
        val region: String = DEFAULT_REGION,
        /** `BuilderId`, `Enterprise`, `ExternalIdp`… as the manager records it; empty when unknown. */
        val provider: String = "",
        /** `IdC`, `external_idp`… as the manager records it; empty when unknown. */
        val authMethod: String = "",
        val profileArn: String? = null,
    ) : KiroCredential {
        /** An organisation's Identity Center: its profile has to be looked up. */
        val enterprise: Boolean
            get() = provider.equals("Enterprise", ignoreCase = true) || externalIdp

        /** Signed in through an external identity provider, which requests say with `TokenType`. */
        val externalIdp: Boolean
            get() = authMethod.equals("external_idp", ignoreCase = true) || provider.equals("ExternalIdp", ignoreCase = true)
    }

    /** A Builder ID or Identity Center token without the client that can refresh it. */
    data class MissingClient(override val refreshToken: String) : KiroCredential

    /** Not a token: a Kiro API key, sent as the bearer with `TokenType: API_KEY`. */
    data class ApiKey(val key: String) : KiroCredential {
        override val refreshToken: String? get() = null
    }

    /** The same credential with the refresh token an exchange handed back. */
    fun withRefreshToken(token: String): KiroCredential = when (this) {
        is Social -> copy(refreshToken = token)
        is Oidc -> copy(refreshToken = token)
        is MissingClient -> copy(refreshToken = token)
        is ApiKey -> this
    }

    companion object {
        const val DEFAULT_REGION = "us-east-1"

        private val REGION = Regex("^[a-z]{2}(-[a-z]+)+-\\d$")
        private val SEPARATORS = Regex("----|\\t| {2,}")

        /** Read whatever was pasted. Never throws. */
        fun parse(text: String): KiroCredential {
            val clean = text.trim()
            if (clean.startsWith("{") || clean.startsWith("[")) {
                // JSON without a refresh token in it is nothing a request can use.
                return Http.parseElement(clean)?.let(::account)?.let(::fromJson) ?: ApiKey(clean)
            }
            if (SEPARATORS.containsMatchIn(clean)) {
                val parts = clean.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?.split(SEPARATORS)?.map(String::trim)
                if (parts != null && parts.size >= 3 && parts[2].isNotEmpty()) return fromLine(parts)
            }
            return if (clean.startsWith("aor")) Social(clean) else ApiKey(clean)
        }

        /** True when [text] is a structured credential rather than a bare token or key. */
        fun isStructured(text: String): Boolean {
            val clean = text.trim()
            return when (parse(clean)) {
                is Oidc, is MissingClient -> true
                is Social -> clean.startsWith("{") || clean.startsWith("[") || SEPARATORS.containsMatchIn(clean)
                is ApiKey -> false
            }
        }

        /** [text] as it should be stored: compact, one line, nothing dropped that a refresh needs. */
        fun normalize(text: String): String = encode(parse(text))

        fun encode(credential: KiroCredential): String = when (credential) {
            is Social -> credential.profileArn?.let { arn ->
                buildJsonObject {
                    put("refreshToken", credential.refreshToken)
                    put("profileArn", arn)
                }.toString()
            } ?: credential.refreshToken
            is Oidc -> buildJsonObject {
                put("refreshToken", credential.refreshToken)
                put("clientId", credential.clientId)
                put("clientSecret", credential.clientSecret)
                put("region", credential.region)
                if (credential.provider.isNotEmpty()) put("provider", credential.provider)
                if (credential.authMethod.isNotEmpty()) put("authMethod", credential.authMethod)
                credential.profileArn?.let { put("profileArn", it) }
            }.toString()
            is MissingClient -> buildJsonObject {
                put("refreshToken", credential.refreshToken)
                put("authMethod", "IdC")
            }.toString()
            is ApiKey -> credential.key
        }

        /** The account object in a paste: itself, the first of a list, `credentials`, or the first of `accounts`. */
        private fun account(element: JsonElement): JsonObject? = when (element) {
            is JsonArray -> element.firstNotNullOfOrNull(::account)
            is JsonObject -> when {
                element.string("refreshToken") != null -> element
                element["credentials"] is JsonObject -> account(element["credentials"] as JsonObject)?.let { inner ->
                    // The account around the credentials may carry the profile.
                    val arn = element.string("profileArn")
                    if (arn != null && inner.string("profileArn") == null) JsonObject(inner + ("profileArn" to JsonPrimitive(arn))) else inner
                }
                element["accounts"] is JsonObject -> (element["accounts"] as JsonObject).values.firstNotNullOfOrNull(::account)
                else -> null
            }
            else -> null
        }

        private fun fromJson(account: JsonObject): KiroCredential? {
            val token = account.string("refreshToken")?.trim().orEmpty()
            if (token.isEmpty()) return null
            val clientId = account.string("clientId")?.trim().orEmpty()
            val clientSecret = account.string("clientSecret")?.trim().orEmpty()
            val authMethod = account.string("authMethod")?.trim().orEmpty()
            val provider = account.string("provider")?.trim().orEmpty()
            val arn = account.string("profileArn")?.trim()?.takeIf { it.isNotEmpty() }
            val region = account.string("region")?.trim()?.takeIf { REGION.matches(it) } ?: DEFAULT_REGION
            return when {
                clientId.isNotEmpty() && clientSecret.isNotEmpty() ->
                    Oidc(token, clientId, clientSecret, region, provider, authMethod, arn)
                authMethod.equals("social", ignoreCase = true) || provider in SOCIAL_PROVIDERS || token.startsWith("aor") ->
                    Social(token, arn)
                else -> MissingClient(token)
            }
        }

        private fun fromLine(parts: List<String>): KiroCredential {
            val token = parts[2]
            val clientId = parts.getOrNull(3).orEmpty()
            val clientSecret = parts.getOrNull(4).orEmpty()
            val provider = parts.getOrNull(5).orEmpty()
            return if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                Oidc(token, clientId, clientSecret, provider = provider.ifEmpty { "BuilderId" })
            } else {
                Social(token)
            }
        }

        private val SOCIAL_PROVIDERS = setOf("Google", "Github", "GitHub")

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
