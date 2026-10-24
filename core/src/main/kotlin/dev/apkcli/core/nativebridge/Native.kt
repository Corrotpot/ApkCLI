package dev.apkcli.core.nativebridge

/**
 * The entire JNI surface of the app.
 *
 * Every method except the PTY byte pumps follows the same shape:
 * `(op, payloadJson) -> resultJson`. That keeps this file small and means a new
 * native capability is one Rust match arm, not a new external declaration plus
 * a ProGuard rule plus a signature mismatch waiting to happen.
 *
 * Threading: all of these block. None may be called from the main thread —
 * [dev.apkcli.core.sched.LoadGovernor] owns the dispatchers that are allowed to
 * call in.
 *
 * Failures arrive as [NativeException].
 *
 * **Public, not internal, and that is deliberate.** The JNI symbol the Rust
 * side exports is derived from the class and method names
 * (`Java_dev_apkcli_core_nativebridge_Native_ptyRead`). Kotlin appends a
 * `$module` suffix to the JVM names of declarations marked `internal`, and
 * relying on the exact rules for members of an internal container is a bet with
 * no upside — if it went the wrong way, *every* native call would fail with
 * `UnsatisfiedLinkError` at runtime rather than at build time. Visibility is
 * enforced by convention here: nothing outside `dev.apkcli.core.nativebridge`
 * should call this; use [NativeCore].
 */
object Native {

    external fun nativeInit(verbose: Boolean)

    external fun nativeVersion(): String

    /* ------------------------------------------------------------- store */

    /** @return an opaque handle, or 0 on failure. */
    external fun storeOpen(path: String): Long

    external fun storeCall(handle: Long, op: String, payload: String): String

    external fun storeClose(handle: Long)

    /* ----------------------------------------------- search / diff / sched */

    external fun searchCall(op: String, payload: String): String

    external fun diffCall(op: String, payload: String): String

    external fun schedCall(op: String, payload: String): String

    /* --------------------------------------------------------------- pty */

    external fun ptyCall(op: String, payload: String): String

    /**
     * Blocking read from a PTY master into [out].
     *
     * @return byte count, or 0 for EOF (the child exited). Must be called from
     *   a dedicated thread; there is one such thread per open session.
     */
    external fun ptyRead(id: Long, out: ByteArray): Int

    external fun ptyWrite(id: Long, data: ByteArray): Int

    /* ------------------------------------------------------------ tokens */

    external fun tokensEstimate(text: String): Int

    /**
     * Correct the estimator against a provider's authoritative count.
     * @see dev.apkcli.core.agent.ContextBudget
     */
    external fun tokensCalibrate(estimated: Int, reported: Int)

    /** @return `{"text": "...", "truncated": bool}` */
    external fun tokensClamp(text: String, budget: Int): String

    /* -------------------------------------------------------------- misc */

    external fun realpath(path: String): String
}
