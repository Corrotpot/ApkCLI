package dev.apkcli.core.llm

import android.util.Log
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.store.SecretStore
import dev.apkcli.core.util.long
import dev.apkcli.core.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Kiro credentials: what the user pasted ([KiroCredential]), turned into what
 * a request carries. The scheme is Kiro Account Manager's
 * (github.com/chaogei/Kiro-account-manager, AGPL: its facts, not its code;
 * docs/PROVIDERS.md):
 *
 *  * A Google or GitHub sign-in's refresh token, `aorAAAAAG…`, as Kiro IDE
 *    keeps it in `~/.aws/sso/cache/kiro-auth-token.json`, is exchanged at
 *    Kiro's own auth service.
 *  * An AWS Builder ID or IAM Identity Center sign-in's refresh token is
 *    exchanged at its region's OIDC service, with the client id and secret
 *    it was issued to.
 *  * Anything else is a Kiro API key, sent as it is with `TokenType: API_KEY`.
 *
 * Either exchange may hand back a new refresh token, which replaces the
 * stored one: the old one stops working. The sharp edge: the IDE holds the
 * same token, so an exchange here that rotates it leaves the IDE with a dead
 * one and the IDE signs out, and an exchange in the IDE does the same to this
 * app. The form says so before a token is pasted.
 *
 * Every request names a profile. Social sign-ins share one fixed profile and
 * Builder ID another, the values Kiro IDE itself carries; an organisation's
 * Identity Center profile is looked up.
 *
 * Blocking: called from the streaming worker thread and from listings on
 * Dispatchers.IO. One exchange at a time per connection, because two at once
 * would spend the same refresh token twice.
 */
internal class KiroAuth(private val secrets: SecretStore) {

    class Session(
        /** The stored credential this session came from. */
        val credential: String,
        val accessToken: String,
        val expiresAt: Long,
        val profileArn: String?,
        /** Where the account lives: its OIDC region, or its profile's. */
        val region: String,
        /** What requests say the bearer is (`API_KEY`, `EXTERNAL_IDP`); null for a Kiro access token. */
        val tokenType: String?,
        /** The device id requests carry in their user agent; one per connection. */
        val machineId: String,
    ) {
        val apiKey: Boolean get() = tokenType == TOKEN_TYPE_API_KEY

        /** Amazon Q's listing host for this account: Frankfurt for the EU, otherwise N. Virginia. */
        val serviceRegion: String get() = serviceRegion(region)

        fun fresh(now: Long): Boolean = apiKey || expiresAt - now > EXPIRY_MARGIN_MS
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun session(endpoint: Endpoint, credential: String, forceRefresh: Boolean = false): Session {
        val pasted = credential.trim()
        val machineId = machineId(endpoint.id)
        val first = KiroCredential.parse(pasted)
        if (first is KiroCredential.ApiKey) {
            return Session(pasted, first.key, Long.MAX_VALUE, null, DEFAULT_REGION, TOKEN_TYPE_API_KEY, machineId)
        }
        synchronized(locks.getOrPut(endpoint.id) { Any() }) {
            val now = System.currentTimeMillis()
            // Another request may have rotated the token while this one waited.
            val stored = secrets.apiKey(endpoint.id)?.trim()
                ?.takeIf { KiroCredential.parse(it).refreshToken != null }
                ?: pasted
            val cached = sessions[endpoint.id]
            if (!forceRefresh && cached != null && cached.credential == stored && cached.fresh(now)) return cached

            val (next, kept) = exchange(KiroCredential.parse(stored), cached?.profileArn, machineId)
            val encoded = KiroCredential.encode(kept)
            if (encoded != stored) {
                if (kept.refreshToken != KiroCredential.parse(stored).refreshToken) {
                    Log.i(TAG, "Kiro rotated the refresh token of ${endpoint.id}; storing the new one")
                }
                secrets.putApiKey(endpoint.id, encoded)
            }
            val session = Session(encoded, next.accessToken, next.expiresAt, next.profileArn, next.region, next.tokenType, machineId)
            sessions[endpoint.id] = session
            return session
        }
    }

    /** Drop the cached access token, after a credential change. */
    fun forget(endpointId: String) {
        sessions.remove(endpointId)
    }

    /** A fresh session for [credential], and the credential to store after it. */
    private fun exchange(credential: KiroCredential, knownArn: String?, machineId: String): Pair<Session, KiroCredential> =
        when (credential) {
            is KiroCredential.Social -> social(credential, knownArn, machineId)
            is KiroCredential.Oidc -> oidc(credential, knownArn, machineId)
            is KiroCredential.MissingClient -> throw LlmException(
                LlmError(
                    LlmError.Kind.AUTH,
                    "This is an AWS Builder ID or Identity Center token, which refreshes only with the client it was " +
                        "issued to. Paste it as JSON with its clientId and clientSecret (Kiro IDE keeps them in " +
                        "~/.aws/sso/cache/<clientIdHash>.json), or in Kiro Account Manager's export format.",
                    hint = LlmError.Hint.CHECK_KEY,
                ),
            )
            is KiroCredential.ApiKey -> error("an API key is not exchanged")
        }

    /** Google or GitHub: Kiro's own auth service. */
    private fun social(credential: KiroCredential.Social, knownArn: String?, machineId: String): Pair<Session, KiroCredential> {
        val request = Request.Builder()
            .url(REFRESH_URL)
            .post(buildJsonObject { put("refreshToken", credential.refreshToken) }.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", KiroIdentity.userAgent(machineId))
            .build()
        val obj = post(request, social = true)
        val access = accessToken(obj)
        val arn = obj.str("profileArn")?.takeIf { it.isNotBlank() } ?: credential.profileArn ?: knownArn ?: SOCIAL_PROFILE_ARN
        val rotated = obj.str("refreshToken")?.takeIf { it.isNotBlank() } ?: credential.refreshToken
        val session = Session(
            credential = "",
            accessToken = access,
            expiresAt = expiry(obj),
            profileArn = arn,
            region = regionOf(arn) ?: DEFAULT_REGION,
            tokenType = null,
            machineId = machineId,
        )
        return session to credential.copy(refreshToken = rotated)
    }

    /** Builder ID or Identity Center: the region's OIDC service, with the client the token belongs to. */
    private fun oidc(credential: KiroCredential.Oidc, knownArn: String?, machineId: String): Pair<Session, KiroCredential> {
        val request = Request.Builder()
            .url("https://oidc.${credential.region}.amazonaws.com/token")
            .post(
                buildJsonObject {
                    put("clientId", credential.clientId)
                    put("clientSecret", credential.clientSecret)
                    put("refreshToken", credential.refreshToken)
                    put("grantType", "refresh_token")
                }.toString().toRequestBody(JSON),
            )
            .header("Content-Type", "application/json")
            .build()
        val obj = post(request, social = false)
        val access = accessToken(obj)
        var kept = credential.copy(refreshToken = obj.str("refreshToken")?.takeIf { it.isNotBlank() } ?: credential.refreshToken)
        val arn = credential.profileArn ?: knownArn ?: if (credential.enterprise) {
            // An organisation's profile is its own; found once, it is kept with the credential.
            profileOf(access, credential.region, machineId)?.also { kept = kept.copy(profileArn = it) }
        } else {
            BUILDER_ID_PROFILE_ARN
        }
        val session = Session(
            credential = "",
            accessToken = access,
            expiresAt = expiry(obj),
            profileArn = arn,
            region = credential.region,
            tokenType = if (credential.externalIdp) TOKEN_TYPE_EXTERNAL_IDP else null,
            machineId = machineId,
        )
        return session to kept
    }

    private fun post(request: Request, social: Boolean): JsonObject {
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw LlmException(Http.classify(it)) }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                val base = Http.classify(it.code, it.message, text)
                throw LlmException(
                    if (it.code in 400..403) {
                        base.copy(
                            kind = LlmError.Kind.AUTH,
                            message = if (social) {
                                "Kiro did not accept the account token. It may have been used up by the Kiro IDE " +
                                    "signing in again; copy the current refreshToken and paste it."
                            } else {
                                "AWS did not accept the Builder ID token or its client. The token may have been used " +
                                    "up by the Kiro IDE signing in again; copy the current refreshToken, clientId and " +
                                    "clientSecret and paste them."
                            },
                            hint = LlmError.Hint.CHECK_KEY,
                        )
                    } else {
                        base
                    },
                )
            }
            return Http.parseObject(text)
                ?: throw LlmException(LlmError(LlmError.Kind.PROTOCOL, "Kiro's token exchange did not answer JSON", body = text.take(500)))
        }
    }

    private fun accessToken(obj: JsonObject): String = obj.str("accessToken")?.takeIf { it.isNotBlank() }
        ?: throw LlmException(LlmError(LlmError.Kind.PROTOCOL, "Kiro's token exchange returned no access token", body = obj.toString().take(500)))

    private fun expiry(obj: JsonObject): Long {
        val seconds = obj.long("expiresIn").takeIf { it > 0 } ?: DEFAULT_LIFETIME_S
        return System.currentTimeMillis() + seconds * 1000
    }

    /**
     * An Identity Center account's CodeWhisperer profile: the first of
     * `ListAvailableProfiles`, as Kiro IDE offers them after sign-in. Null
     * when there is none to be had.
     */
    private fun profileOf(accessToken: String, region: String, machineId: String): String? = runCatching {
        val request = Request.Builder()
            .url("https://codewhisperer.${serviceRegion(region)}.amazonaws.com/ListAvailableProfiles")
            .post("{}".toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .header("x-amz-user-agent", KiroIdentity.amzUserAgent(machineId))
            .header("User-Agent", KiroIdentity.userAgent(machineId))
            .header("amz-sdk-invocation-id", UUID.randomUUID().toString())
            .header("amz-sdk-request", "attempt=1; max=1")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "ListAvailableProfiles answered ${response.code}")
                return@use null
            }
            val profiles = Http.parseObject(response.body.string())?.get("profiles") as? JsonArray
            profiles?.firstNotNullOfOrNull { (it as? JsonObject)?.str("arn")?.takeIf(String::isNotBlank) }
        }
    }.onFailure { Log.d(TAG, "profile lookup failed: ${it.message}") }.getOrNull()

    companion object {
        private const val TAG = "KiroAuth"

        /** Kiro's own token service, for Google and GitHub sign-ins. */
        const val REFRESH_URL = "https://prod.us-east-1.auth.desktop.kiro.dev/refreshToken"

        const val DEFAULT_REGION = KiroCredential.DEFAULT_REGION

        /** The profile every Google or GitHub sign-in shares, as Kiro IDE carries it. */
        const val SOCIAL_PROFILE_ARN = "arn:aws:codewhisperer:us-east-1:699475941385:profile/EHGA3GRVQMUK"

        /** The profile Kiro IDE gives every AWS Builder ID sign-in. */
        const val BUILDER_ID_PROFILE_ARN = "arn:aws:codewhisperer:us-east-1:638616132270:profile/AAAACCCCXXXX"

        const val TOKEN_TYPE_API_KEY = "API_KEY"
        const val TOKEN_TYPE_EXTERNAL_IDP = "EXTERNAL_IDP"

        private val REGION = Regex("^[a-z]{2}(-[a-z]+)+-\\d$")
        private const val EXPIRY_MARGIN_MS = 2 * 60 * 1000L
        private const val DEFAULT_LIFETIME_S = 3600L

        private val JSON = "application/json".toMediaType()

        private val client by lazy {
            Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        }

        /** The region in an ARN (`arn:aws:codewhisperer:<region>:…`), when it is one. */
        fun regionOf(arn: String?): String? = arn?.split(':')?.getOrNull(3)?.takeIf { REGION.matches(it) }

        /** Where an account's Amazon Q and CodeWhisperer calls go: Frankfurt for the EU, otherwise N. Virginia. */
        fun serviceRegion(region: String): String = if (region.startsWith("eu-")) "eu-central-1" else DEFAULT_REGION

        /**
         * The device id a connection's requests carry, as Kiro Account
         * Manager derives one for an account without a bound id: the SHA-256
         * of `kiro-device-<id>`, in hex. Stable, and different per connection.
         */
        fun machineId(endpointId: String): String =
            MessageDigest.getInstance("SHA-256").digest("kiro-device-$endpointId".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * How Kiro IDE introduces itself to AWS, in the shape Kiro Account Manager
 * sends: the AWS SDK for JavaScript of the streaming client, the operating
 * system, and `KiroIDE-<version>-<machineId>`. A request without a Kiro
 * client marker is refused.
 */
internal object KiroIdentity {
    const val IDE_VERSION = "0.12.155"
    const val SDK_VERSION = "1.0.34"
    const val NODE_VERSION = "22.22.0"

    /** `os/linux#<kernel>`: Android is Linux, and the kernel release is what Node reports there. */
    private val os: String by lazy {
        val release = System.getProperty("os.version").orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "-").take(64)
        "linux#${release.ifEmpty { "6.1.0" }}"
    }

    fun userAgent(machineId: String): String =
        "aws-sdk-js/$SDK_VERSION ua/2.1 os/$os lang/js md/nodejs#$NODE_VERSION " +
            "api/codewhispererstreaming#$SDK_VERSION m/E KiroIDE-$IDE_VERSION-$machineId"

    fun amzUserAgent(machineId: String): String = "aws-sdk-js/$SDK_VERSION KiroIDE $IDE_VERSION $machineId"
}
