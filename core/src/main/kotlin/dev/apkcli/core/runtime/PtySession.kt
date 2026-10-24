package dev.apkcli.core.runtime

import android.util.Log
import dev.apkcli.core.nativebridge.Native
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.util.int
import dev.apkcli.core.util.intOrNull
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.long
import dev.apkcli.core.util.objects
import dev.apkcli.core.util.str
import dev.apkcli.core.util.bool
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * An interactive shell on a real pseudo-terminal.
 *
 * Needed because a pipe is not a terminal: with pipes, `isatty()` is false, so
 * `vi` refuses to start, `less` turns into `cat`, python's REPL drops its
 * prompt, progress bars print one line per update, and nothing emits colour.
 *
 * Reading is done on a dedicated platform thread rather than a coroutine. The
 * native read is a blocking `read(2)` on the master fd with no timeout, which
 * would occupy a dispatcher thread indefinitely — and cancelling a coroutine
 * cannot interrupt a thread parked in a syscall. One thread per session, ended
 * by closing the fd.
 */
class PtySession internal constructor(
    val id: Long,
    val pid: Int,
    val program: String,
    private val onClosed: (Long) -> Unit,
) {

    /**
     * A Channel rather than a SharedFlow, because order is not negotiable here.
     *
     * Emitting into a SharedFlow from a blocking reader thread means either
     * suspending the reader (which stalls the PTY and eventually the child's
     * writes) or launching a coroutine per chunk — and separate coroutines can
     * complete out of order, which scrambles escape sequences and produces
     * corrupted terminal output. `trySend` on a buffered channel is non-blocking
     * and strictly ordered.
     *
     * On overflow the oldest chunk is dropped. For a terminal that is the right
     * failure: a visual glitch during a 10 MB/s log flood, instead of a stalled
     * session.
     */
    private val channel = Channel<ByteArray>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Raw bytes from the terminal. Feed into an ANSI parser, not into text. */
    val output: Flow<ByteArray> = channel.receiveAsFlow()

    private val _state = MutableStateFlow(State.RUNNING)
    val state: StateFlow<State> = _state.asStateFlow()

    enum class State { RUNNING, EXITED, CLOSED }

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var released = false

    internal fun startReading() {
        val thread = Thread({
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = Native.ptyRead(id, buffer)
                    if (n <= 0) break // 0 == EOF: the child closed the slave
                    channel.trySend(buffer.copyOf(n))
                }
            } catch (t: Throwable) {
                Log.d(TAG, "pty $id read ended: ${t.message}")
            } finally {
                exitCode = runCatching {
                    NativeCore.pty("pty.wait", jsonOf("id" to id)).intOrNull("exitCode")
                }.getOrNull()
                _state.value = State.EXITED
                channel.close()
                // Release the kernel resources now that the child is gone.
                //
                // This is not optional housekeeping. Without it the PTY master
                // fd stays open and the native session map keeps its entry
                // forever — one leaked fd per terminal tab and per background
                // job, against a per-process limit of about 1024. A long
                // session that runs a few hundred commands would start failing
                // to open files for reasons that look nothing like the cause.
                //
                // Deliberately not `close()`: that would overwrite the state
                // with CLOSED and hide the fact that the child exited on its
                // own, which is what the UI distinguishes.
                release()
            }
        }, "apkcli-pty-$id")
        thread.isDaemon = true
        reader = thread
        thread.start()
    }

    /**
     * Free the native session without changing [state].
     *
     * Idempotent — the native side tolerates closing an unknown id, and the
     * flag stops a later [close] from reaping twice.
     */
    private fun release() {
        if (released) return
        released = true
        runCatching { NativeCore.pty("pty.close", jsonOf("id" to id)) }
        reader = null
        onClosed(id)
    }

    fun write(data: ByteArray) {
        if (_state.value != State.RUNNING) return
        runCatching { Native.ptyWrite(id, data) }
            .onFailure { Log.w(TAG, "pty $id write failed: ${it.message}") }
    }

    fun write(text: String) = write(text.toByteArray())

    /**
     * Resize the terminal. Also sends SIGWINCH to the foreground process group,
     * without which a running full-screen program keeps drawing at the old
     * geometry after a rotation.
     */
    fun resize(cols: Int, rows: Int) {
        runCatching {
            NativeCore.pty(
                "pty.resize",
                jsonOf("id" to id, "cols" to cols.coerceIn(10, 1000), "rows" to rows.coerceIn(4, 400)),
            )
        }
    }

    /** Ctrl-C. Delivered through the terminal, so line discipline turns it into SIGINT. */
    fun interrupt() = write(byteArrayOf(0x03))

    /** Ctrl-D. */
    fun sendEof() = write(byteArrayOf(0x04))

    fun kill(signal: Int = 1 /* SIGHUP */) {
        runCatching { NativeCore.pty("pty.kill", jsonOf("id" to id, "signal" to signal)) }
    }

    /**
     * Terminate the session from this side.
     *
     * Closing the master makes the child's next write fail with SIGHUP, which
     * unblocks the reader thread's `read(2)` with EIO — that is what ends the
     * thread. Interrupting it would not: a thread parked in a syscall ignores
     * Java interruption.
     */
    fun close() {
        if (_state.value == State.CLOSED) return
        val wasRunning = _state.value == State.RUNNING
        if (wasRunning) _state.value = State.CLOSED
        release()
        if (!wasRunning) _state.value = State.CLOSED
    }

    companion object {
        private const val TAG = "PtySession"
    }
}

/**
 * Owns the live PTY sessions.
 *
 * Sessions outlive the UI: rotating the device or backgrounding the app must
 * not kill a running build. That is why this lives in the `:shell` process and
 * is reached over IPC rather than being held by a ViewModel.
 */
class PtyManager(
    private val layout: EnvLayout,
    private val policy: ExecPolicy,
) {

    private val sessions = ConcurrentHashMap<Long, PtySession>()

    val active: Collection<PtySession> get() = sessions.values

    class NotAvailable(message: String) : IllegalStateException(message)

    /**
     * Start a login shell (or [command]) on a new PTY.
     *
     * @param command null starts an interactive shell, which reads
     *   `$PREFIX/etc/profile`; a non-null value is run via `sh -lc` with the
     *   environment from [ExecPolicy.baseEnvironment], and no prompt or aliases.
     */
    fun open(
        workingDir: File = layout.projects,
        command: String? = null,
        cols: Int = 80,
        rows: Int = 24,
        env: Map<String, String> = emptyMap(),
    ): PtySession {
        if (!NativeCore.available) {
            throw NotAvailable(
                "PTY support needs the native core. Build with apkcli.buildRust=true.",
            )
        }
        val shell = policy.resolveShell()
        if (!policy.canExecute(shell)) {
            throw NotAvailable(policy.explainRefusal(shell))
        }
        val dir = if (workingDir.isDirectory) workingDir else layout.projects.also { it.mkdirs() }

        val argv = if (command == null) {
            // -i so the shell sets up job control and a prompt. The profile
            // comes in through ENV below, not -l: a login shell reads
            // /etc/profile and ~/.profile, never $PREFIX/etc/profile, and
            // Android's mksh then reads /system/etc/mkshrc, whose prompt is
            // the full /data/user/0/… path — wider than a phone.
            listOf(shell.absolutePath, "-i")
        } else {
            listOf(shell.absolutePath, "-l", "-c", command)
        }

        // mksh and busybox ash both read $ENV when they start interactive;
        // ours sources ~/.profile at its end, so the user's settings still win.
        val profile = File(layout.etc, "profile")
        val shellEnv = if (command == null && profile.isFile) {
            mapOf("ENV" to profile.absolutePath) + env
        } else {
            env
        }

        val result = NativeCore.pty(
            "pty.open",
            jsonOf(
                "argv" to argv,
                "cwd" to dir.absolutePath,
                "cols" to cols,
                "rows" to rows,
                "env" to policy.baseEnvironment(dir, extra = shellEnv),
            ),
        )

        val id = result.long("id")
        val session = PtySession(
            id = id,
            pid = result.int("pid"),
            program = result.str("program", shell.absolutePath),
            onClosed = { sessions.remove(it) },
        )
        sessions[id] = session
        session.startReading()
        return session
    }

    fun get(id: Long): PtySession? = sessions[id]

    /** Sessions the native layer knows about, including any we lost track of. */
    fun listNative(): List<NativeSessionInfo> {
        if (!NativeCore.available) return emptyList()
        return runCatching {
            NativeCore.pty("pty.list", jsonOf()).objects("sessions").map {
                NativeSessionInfo(
                    id = it.long("id"),
                    pid = it.int("pid"),
                    running = it.bool("running"),
                    exitCode = it.intOrNull("exitCode"),
                )
            }
        }.getOrDefault(emptyList())
    }

    data class NativeSessionInfo(
        val id: Long,
        val pid: Int,
        val running: Boolean,
        val exitCode: Int?,
    )

    fun closeAll() {
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
    }
}
