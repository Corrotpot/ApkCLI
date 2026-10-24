package dev.apkcli.core.nativebridge

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads the native library and exposes it as JSON-in/JSON-out calls.
 *
 * The library may legitimately be absent: `apkcli.buildRust=false` in
 * `gradle.properties` skips the cargo step so the UI can be iterated on without
 * an NDK and a Rust toolchain installed. Everything downstream therefore has to
 * tolerate [available] being false, and the call sites that can degrade
 * (token estimation, search) have Kotlin fallbacks. The ones that cannot
 * (the conversation store) fail loudly at startup instead of halfway through a
 * session.
 */
object NativeCore {

    private const val TAG = "NativeCore"
    private const val LIB = "apkcli_core"

    @Volatile
    private var loadError: Throwable? = null

    @Volatile
    private var loaded = false

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded
        }

    val failure: Throwable?
        get() {
            ensureLoaded()
            return loadError
        }

    val version: String
        get() = if (available) runCatching { Native.nativeVersion() }.getOrElse { "?" } else "absent"

    @Synchronized
    private fun ensureLoaded() {
        if (loaded || loadError != null) return
        try {
            System.loadLibrary(LIB)
            Native.nativeInit(verbose = false)
            loaded = true
            Log.i(TAG, "loaded lib$LIB.so (${Native.nativeVersion()})")
        } catch (t: Throwable) {
            // UnsatisfiedLinkError when the .so is missing or built for another
            // ABI; anything else is a genuine init failure.
            loadError = t
            Log.w(TAG, "native core unavailable: ${t.message}")
        }
    }

    /** Force a load attempt early so startup can surface the problem. */
    fun preload(verbose: Boolean = false) {
        if (loaded) {
            runCatching { Native.nativeInit(verbose) }
            return
        }
        loadError = null
        ensureLoaded()
    }

    private fun requireLoaded() {
        ensureLoaded()
        if (!loaded) {
            throw NativeException(
                NativeException.Kind.INTERNAL,
                "native core not loaded: ${loadError?.message ?: "unknown"}. " +
                    "Build with apkcli.buildRust=true and a configured NDK.",
            )
        }
    }

    /* --------------------------------------------------------- dispatchers */

    fun search(op: String, payload: JsonObject): JsonObject = callJson(op, payload) { o, p ->
        Native.searchCall(o, p)
    }

    fun diff(op: String, payload: JsonObject): JsonObject = callJson(op, payload) { o, p ->
        Native.diffCall(o, p)
    }

    fun sched(op: String, payload: JsonObject): JsonObject = callJson(op, payload) { o, p ->
        Native.schedCall(o, p)
    }

    fun pty(op: String, payload: JsonObject): JsonObject = callJson(op, payload) { o, p ->
        Native.ptyCall(o, p)
    }

    private inline fun callJson(
        op: String,
        payload: JsonObject,
        crossinline invoke: (String, String) -> String,
    ): JsonObject {
        requireLoaded()
        val raw = invoke(op, payload.toString())
        return replyObject(op, raw)
    }

    /**
     * Parse a native reply.
     *
     * Every op is meant to answer with an object, but "nothing found" has been
     * answered with a bare `null` — `digest.latest` for any session that was
     * never compacted, which is every new session. Forcing that to an object
     * threw on the first turn of every conversation. `null` now becomes an
     * empty object, which every caller already reads as "field absent"; any
     * other non-object is a contract violation and names the op.
     */
    internal fun replyObject(op: String, raw: String): JsonObject =
        when (val element = json.parseToJsonElement(raw)) {
            is JsonObject -> element
            is JsonNull -> JsonObject(emptyMap())
            is JsonArray -> throw NativeException(NativeException.Kind.JSON, "'$op' answered with an array, not an object")
            is JsonPrimitive -> throw NativeException(NativeException.Kind.JSON, "'$op' answered with a value, not an object")
        }

    /* --------------------------------------------------------------- store */

    fun openStore(path: String): Long {
        requireLoaded()
        val handle = Native.storeOpen(path)
        if (handle == 0L) {
            throw NativeException(NativeException.Kind.SQLITE, "could not open store at $path")
        }
        return handle
    }

    fun storeCall(handle: Long, op: String, payload: JsonObject): JsonObject {
        requireLoaded()
        val raw = Native.storeCall(handle, op, payload.toString())
        return replyObject(op, raw)
    }

    fun closeStore(handle: Long) {
        if (loaded && handle != 0L) Native.storeClose(handle)
    }

    /* -------------------------------------------------------------- tokens */

    /**
     * Token estimate, with a Kotlin fallback so the context meter still shows
     * something sane when the native core is absent.
     *
     * The fallback is deliberately crude; it exists to avoid a null in the UI,
     * not to be accurate.
     */
    fun estimateTokens(text: String): Int {
        ensureLoaded()
        if (!loaded) return fallbackEstimate(text)
        return runCatching { Native.tokensEstimate(text) }.getOrElse { fallbackEstimate(text) }
    }

    fun calibrateTokens(estimated: Int, reported: Int) {
        ensureLoaded()
        if (loaded && estimated > 0 && reported > 0) {
            runCatching { Native.tokensCalibrate(estimated, reported) }
        }
    }

    fun clampTokens(text: String, budget: Int): ClampResult {
        ensureLoaded()
        if (!loaded) {
            val limit = budget * 4
            return if (text.length <= limit) {
                ClampResult(text, false)
            } else {
                ClampResult(text.take(limit), true)
            }
        }
        val obj = json.parseToJsonElement(Native.tokensClamp(text, budget)).jsonObject
        return ClampResult(
            text = obj["text"]?.jsonPrimitive?.contentOrNull ?: text,
            truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    data class ClampResult(val text: String, val truncated: Boolean)

    private fun fallbackEstimate(text: String): Int {
        if (text.isEmpty()) return 0
        // Cyrillic costs about twice as much per character as Latin; ignoring
        // that underestimates Russian conversations by nearly half.
        var latin = 0
        var wide = 0
        for (ch in text) {
            if (ch.code in 0x0400..0x052F || ch.code in 0x3000..0x9FFF) wide++ else latin++
        }
        return (latin / 4) + (wide / 2) + 1
    }

    /* ---------------------------------------------------------------- misc */

    fun realpath(path: String): String {
        ensureLoaded()
        if (!loaded) return path
        return runCatching { Native.realpath(path) }.getOrElse { path }
    }
}
