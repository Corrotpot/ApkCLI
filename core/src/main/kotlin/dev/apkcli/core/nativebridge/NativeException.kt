package dev.apkcli.core.nativebridge

/**
 * Thrown from the Rust core. Constructed by name from `ffi.rs`, so the fully
 * qualified name is load-bearing — see `consumer-rules.pro`.
 *
 * The native side encodes `"<kind>|<message>"` into the single String a JNI
 * `throw_new` can carry. [kind] is a stable machine-readable token, which
 * matters because callers branch on it: an `errno` failure during exec gets a
 * targeted explanation about the W^X rule, a `refused` failure from the edit
 * tool is reported to the model as retryable, and a `panic` is a bug report.
 */
class NativeException internal constructor(
    val kind: Kind,
    val detail: String,
) : RuntimeException("$kind: $detail") {

    enum class Kind {
        INVALID,
        NOT_FOUND,
        REFUSED,
        IO,
        SQLITE,
        JSON,
        ERRNO,
        INTERNAL,
        PANIC,
        UNKNOWN,
        ;

        companion object {
            fun parse(token: String): Kind = when (token.trim().lowercase()) {
                "invalid" -> INVALID
                "not_found" -> NOT_FOUND
                "refused" -> REFUSED
                "io" -> IO
                "sqlite" -> SQLITE
                "json" -> JSON
                "errno" -> ERRNO
                "internal" -> INTERNAL
                "panic" -> PANIC
                else -> UNKNOWN
            }
        }
    }

    /**
     * Whether retrying the same call could plausibly succeed. Reported to the
     * model so it does not loop on a hard refusal.
     */
    val retryable: Boolean
        get() = when (kind) {
            Kind.IO, Kind.SQLITE, Kind.ERRNO -> true
            else -> false
        }

    /** The JVM constructs instances through this path when Rust throws. */
    @Suppress("unused")
    constructor(encoded: String) : this(
        kind = Kind.parse(encoded.substringBefore('|', missingDelimiterValue = "unknown")),
        detail = encoded.substringAfter('|', missingDelimiterValue = encoded),
    )
}
