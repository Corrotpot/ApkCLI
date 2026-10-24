package dev.apkcli.core.runtime

import android.util.Log
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.intOrNull
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Every live terminal, whoever opened it.
 *
 * One registry for two things that used to be separate and are in fact
 * identical: the terminals the user opens in the terminal panel, and the
 * commands the agent leaves running in the background. Both are a shell on a
 * PTY ([PtyManager]); both need their output buffered, their state watched and
 * their exit reported. Keeping two implementations meant the user could not see
 * what the agent had started, and the agent could not read what the user was
 * looking at — which is exactly the question either one asks first.
 *
 * Output is consumed here, once per session, into a [TerminalBuffer]. That is
 * not a detail: `PtySession.output` is a channel, so two collectors would
 * *split* the chunks between them rather than each seeing all of them, and a
 * collector attaching later would see nothing of what came before. The buffer
 * is the shared, re-readable copy — the UI renders from it and the agent reads
 * its tail.
 *
 * Sessions live as long as the process, not as long as a screen: a build the
 * user started must survive leaving the terminal panel, and one the agent
 * started must survive the end of its turn.
 */
class TerminalSessions(
    private val ptyManager: PtyManager,
    private val scope: CoroutineScope,
) {

    /** Who opened a session, which decides who is expected to close it. */
    enum class Owner {
        /** Opened from the terminal panel. */
        USER,

        /** Started by the agent with `sh_background` or a backgrounded build. */
        AGENT,
    }

    enum class Kind {
        /** A shell or a command on a PTY of its own. */
        SHELL,

        /**
         * The commands the agent runs in a chat, one after another, with
         * what they print: the terminal a desktop agent's commands would be
         * seen in. Fed by [ExecEcho]; there is no shell behind it to type into.
         */
        TRANSCRIPT,
    }

    enum class State {
        RUNNING,

        /** The program ended on its own; [Terminal.exitCode] says how. */
        EXITED,

        /** Stopped from this side, by the user or by `sh_job_kill`. */
        KILLED,

        /** Never started, or died before its first byte. */
        FAILED,
    }

    /**
     * What a session is doing, as the kernel sees it.
     *
     * @param foreground the name of the process group that owns the terminal,
     *   or null when that is the shell itself — i.e. a prompt with nothing
     *   running. This is the honest answer to "what is running in there": the
     *   foreground group is what Ctrl-C would reach.
     * @param cwd where the shell is now, which a `cd` moves.
     */
    data class Status(
        val running: Boolean,
        val exitCode: Int?,
        val foreground: String?,
        val foregroundPid: Int?,
        val cwd: String?,
    )

    /** A session as a tool or the system prompt sees it. */
    data class Snapshot(
        val id: String,
        val owner: Owner,
        val title: String,
        /** Null for an interactive shell the user is typing into. */
        val command: String?,
        val workingDir: String,
        val cwd: String?,
        val foreground: String?,
        val state: State,
        val exitCode: Int?,
        val runningForMs: Long,
        val idleForMs: Long,
        /** Tail of the output, as much as was asked for. */
        val output: String,
        val outputTruncated: Boolean,
        /** The last line with anything on it: one line of "where it is". */
        val lastLine: String?,
        /** A program took over the screen; the buffer holds no usable text. */
        val fullScreenProgram: Boolean,
    )

    /**
     * One terminal: its PTY, its scrollback, and the state the UI binds to.
     *
     * Constructed only by the registry, which owns the reader.
     */
    class Terminal internal constructor(
        val id: String,
        val owner: Owner,
        val command: String?,
        val workingDir: String,
        val startedAt: Long,
        /** Null for a [Kind.TRANSCRIPT], which has no PTY of its own. */
        internal val session: PtySession?,
        private val scope: CoroutineScope,
        /**
         * Called once the program has ended on its own or been stopped, so the
         * registry can tell its listeners. Without it the session list went on
         * calling a finished build "running" until something else changed.
         */
        private val onEnded: () -> Unit,
        val kind: Kind = Kind.SHELL,
        /** For a transcript: the chat it belongs to, as the session list names it. */
        val title: String? = null,
        /** For a transcript: tells the registry a command started or ended. */
        private val onBusyChanged: () -> Unit = {},
    ) {
        // Bounded by characters as well as lines: 2 000 lines of a minified
        // bundle at 4 000 characters each, with a style reference per
        // character, was some 50 MB for one terminal.
        private val buffer = TerminalBuffer(maxChars = SCROLLBACK_CHARS)

        val isTranscript: Boolean get() = kind == Kind.TRANSCRIPT

        /* ------------------------------------------------------ transcript */

        private val _busy = MutableStateFlow(false)

        /** A transcript's command is running now. Always false for a shell. */
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        /**
         * Commands running in a transcript at once: the agent's, and a line
         * the user typed into it. Busy until the last of them ends, not the
         * first.
         */
        private val runningCommands = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        private var interruptCurrent: (() -> Unit)? = null

        /**
         * The number the terminal panel calls this session by, "Shell #3";
         * 0 until the panel has given it one. Kept here rather than in the
         * panel, so the names outlive the screen that gave them.
         */
        @Volatile
        var number: Int = 0

        /** The command a transcript is running, for the session list. */
        @Volatile
        var currentCommand: String? = null
            private set

        @Volatile
        var currentCwd: String? = null
            private set

        /** Append output to a transcript, as the program printed it. */
        internal fun feed(text: String) {
            if (text.isEmpty()) return
            synchronized(buffer) { buffer.feed(text) }
            lastOutputAt = System.currentTimeMillis()
            _revision.value = synchronized(buffer) { buffer.revision }
        }

        internal fun commandStarted(command: String, cwd: String, interrupt: () -> Unit) {
            currentCommand = command
            currentCwd = cwd
            interruptCurrent = interrupt
            runningCommands.incrementAndGet()
            _busy.value = true
            onBusyChanged()
        }

        internal fun commandEnded() {
            if (runningCommands.decrementAndGet() <= 0) {
                runningCommands.set(0)
                interruptCurrent = null
                currentCommand = null
                _busy.value = false
            }
            onBusyChanged()
        }

        /** Writes the agent's commands into this transcript. */
        internal val echo: TranscriptEcho by lazy { TranscriptEcho(this) }

        /**
         * Input and geometry go through one channel consumed by one coroutine.
         *
         * Both are blocking JNI calls, so they must not run on the caller's
         * thread — a UI thread writing to a PTY whose child has stopped reading
         * would block on a full kernel buffer. A channel also keeps order,
         * which `launch` per keystroke would not: two coroutines on a
         * multi-threaded dispatcher can swap, and scrambled input is worse than
         * slow input.
         */
        private sealed interface Cmd {
            class Write(val bytes: ByteArray) : Cmd
            class Resize(val cols: Int, val rows: Int) : Cmd
            class Signal(val signal: Int) : Cmd
        }

        private val commands = Channel<Cmd>(capacity = 256)

        private val _state = MutableStateFlow(State.RUNNING)
        val state: StateFlow<State> = _state.asStateFlow()

        /** Bumped whenever the scrollback changed, for the UI to recompose on. */
        private val _revision = MutableStateFlow(0)
        val revision: StateFlow<Int> = _revision.asStateFlow()

        @Volatile
        var exitCode: Int? = null
            private set

        @Volatile
        var lastOutputAt: Long = startedAt
            private set

        @Volatile
        var cols: Int = 80
            private set

        @Volatile
        var rows: Int = 24
            private set

        /** Last result of [status], so the UI and the prompt do not each poll. */
        @Volatile
        internal var cachedStatus: Status? = null

        @Volatile
        internal var cachedStatusAt: Long = 0

        internal var reader: Job? = null
        internal var writer: Job? = null

        internal fun start(cols: Int, rows: Int) {
            this.cols = cols
            this.rows = rows
            synchronized(buffer) { buffer.columns = cols }
            // A transcript is written to directly; nothing to read or write.
            val session = session ?: return
            writer = scope.launch(Dispatchers.IO) {
                for (cmd in commands) {
                    when (cmd) {
                        is Cmd.Write -> session.write(cmd.bytes)
                        is Cmd.Resize -> session.resize(cmd.cols, cmd.rows)
                        is Cmd.Signal -> session.kill(cmd.signal)
                    }
                }
            }
            reader = session.output
                .onEach { bytes ->
                    synchronized(buffer) { buffer.feed(bytes) }
                    lastOutputAt = System.currentTimeMillis()
                    _revision.value = synchronized(buffer) { buffer.revision }
                }
                .onCompletion {
                    exitCode = session.exitCode
                    commands.close()
                    _state.update { current ->
                        when {
                            current == State.KILLED -> State.KILLED
                            session.exitCode == null -> State.FAILED
                            else -> State.EXITED
                        }
                    }
                    // Nudge the UI: the last line and the status badge change
                    // even though no output arrived.
                    _revision.value = _revision.value + 1
                    Log.i(TAG, "$id ended: exit ${session.exitCode}")
                    onEnded()
                }
                .launchIn(scope)
        }

        /* ------------------------------------------------------------ input */

        /** Send text as typed. The shell echoes it back; nothing is echoed locally. A transcript takes no input. */
        fun write(text: String) {
            if (text.isEmpty() || isTranscript) return
            commands.trySend(Cmd.Write(text.toByteArray()))
        }

        fun write(bytes: ByteArray) {
            if (isTranscript) return
            commands.trySend(Cmd.Write(bytes))
        }

        /** A line of input, as pressing Enter does. */
        fun writeLine(text: String) = write(text + "\n")

        /**
         * Ctrl-C, through the terminal, so line discipline turns it into
         * SIGINT. In a transcript, SIGINT to the agent's running command: it
         * ends as it would at a keyboard, and the agent reads how.
         */
        fun interrupt() {
            if (isTranscript) {
                val stop = interruptCurrent ?: return
                // A JNI call that may wait for the command's step lock.
                scope.launch(Dispatchers.IO) { runCatching { stop() } }
                return
            }
            write(byteArrayOf(0x03))
        }

        /** Ctrl-D. At a prompt this ends the shell. */
        fun eof() = write(byteArrayOf(0x04))

        fun resize(cols: Int, rows: Int) {
            // A transcript keeps the width its commands printed at; the panel
            // splits long lines for display.
            if (isTranscript) return
            val c = cols.coerceIn(10, 1000)
            val r = rows.coerceIn(4, 400)
            if (c == this.cols && r == this.rows) return
            this.cols = c
            this.rows = r
            // Before the program hears of it: its redraw for the new width
            // has to be read at the new width.
            synchronized(buffer) { buffer.columns = c }
            commands.trySend(Cmd.Resize(c, r))
        }

        internal fun signal(signal: Int) {
            commands.trySend(Cmd.Signal(signal))
        }

        internal fun markKilled() {
            _state.value = State.KILLED
        }

        /* ----------------------------------------------------------- output */

        /** The last [maxRows] lines, ready to render. */
        fun rows(maxRows: Int): List<TerminalBuffer.Row> = synchronized(buffer) { buffer.rows(maxRows) }

        fun cursor(maxRows: Int): Pair<Int, Int> = synchronized(buffer) { buffer.cursor(maxRows) }

        fun plainTail(maxChars: Int): String = synchronized(buffer) { buffer.plainTail(maxChars) }

        fun lastLine(): String? = synchronized(buffer) { buffer.lastNonBlankLine() }

        val totalChars: Long get() = synchronized(buffer) { buffer.totalChars }

        val fullScreenProgram: Boolean get() = synchronized(buffer) { buffer.fullScreenProgram }

        /** What the program calls itself, from an OSC title sequence. */
        val reportedTitle: String? get() = synchronized(buffer) { buffer.title }

        /** Where an `OSC 7` said the shell was since the last call, then forgotten. */
        internal fun takeReportedCwd(): String? = synchronized(buffer) { buffer.takeCwd() }

        fun clearScrollback() {
            synchronized(buffer) { buffer.clear() }
            _revision.value = _revision.value + 1
        }

        /**
         * A short name for the session list.
         *
         * What is running now, if anything, then the command it was started
         * with, then the shell's own title — the same order of preference a
         * desktop terminal uses for a tab label.
         */
        fun displayName(): String {
            if (isTranscript) return title?.take(32) ?: id
            // What it ran last is not what it is running once it has ended.
            if (_state.value == State.RUNNING) cachedStatus?.foreground?.let { return it }
            command?.let { return it.trim().substringBefore(' ').substringAfterLast('/').take(24) }
            reportedTitle?.let { return it.take(24) }
            return session?.program?.let { File(it).name } ?: id
        }

        private companion object {
            const val TAG = "Terminal"

            /** Scrollback kept per terminal, in characters, besides its 2 000 lines. */
            const val SCROLLBACK_CHARS = 1_000_000L
        }
    }

    private val terminals = ConcurrentHashMap<String, Terminal>()
    private val userCounter = AtomicLong(1)
    private val agentCounter = AtomicLong(1)

    private val _sessions = MutableStateFlow<List<Terminal>>(emptyList())

    /** Every session, oldest first. The terminal panel binds to this. */
    val sessions: StateFlow<List<Terminal>> = _sessions.asStateFlow()

    /**
     * Bumped on every change: a session opened, closed, stopped, or ended on
     * its own.
     *
     * Separate from [sessions] because a state flow only emits when its value
     * stops being equal to the last one, and a session ending changes the
     * session, not the list that holds it.
     */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    val active: List<Terminal> get() = _sessions.value.filter { it.state.value == State.RUNNING }

    /**
     * Open a session.
     *
     * Blocking: this forks and execs. Call it off the main thread.
     *
     * @param command null for an interactive shell the user types into; a
     *   command runs through `sh -lc` and the session ends when it does.
     * @throws PtyManager.NotAvailable when the native core is missing or this
     *   build may not execute anything.
     */
    fun open(
        workingDir: File,
        command: String? = null,
        owner: Owner = Owner.USER,
        cols: Int = 80,
        rows: Int = 24,
        env: Map<String, String> = emptyMap(),
    ): Terminal {
        val session = ptyManager.open(
            workingDir = workingDir,
            command = command,
            cols = cols,
            rows = rows,
            env = env,
        )
        val id = when (owner) {
            Owner.USER -> "term-${userCounter.getAndIncrement()}"
            Owner.AGENT -> "job-${agentCounter.getAndIncrement()}"
        }
        val terminal = Terminal(
            id = id,
            owner = owner,
            command = command,
            workingDir = workingDir.absolutePath,
            startedAt = System.currentTimeMillis(),
            session = session,
            scope = scope,
            onEnded = {
                prune()
                publish()
            },
        )
        terminal.start(cols, rows)
        terminals[id] = terminal
        publish()
        // Finished sessions are kept for inspection, but not forever.
        prune()
        return terminal
    }

    fun get(id: String): Terminal? = terminals[id]

    private fun publish() {
        _sessions.value = terminals.values.sortedBy { it.startedAt }
        _changes.update { it + 1 }
    }

    /**
     * Ask the kernel what a session is doing.
     *
     * Cached for [STATUS_TTL_MS]: the terminal panel wants this a few times a
     * second to keep its badges current, and the system prompt wants it once
     * per request, and neither needs its own syscalls.
     */
    fun status(id: String, maxAgeMs: Long = STATUS_TTL_MS): Status? {
        val terminal = terminals[id] ?: return null
        if (terminal.isTranscript) {
            // Nothing to ask the kernel: the transcript knows what it runs.
            return Status(
                running = terminal.busy.value,
                exitCode = null,
                foreground = terminal.currentCommand?.trim()?.substringBefore(' ')?.substringAfterLast('/'),
                foregroundPid = null,
                cwd = terminal.currentCwd ?: terminal.workingDir,
            ).also { terminal.cachedStatus = it }
        }
        val session = terminal.session ?: return null
        // An ended session cannot change again, and its native side has
        // already been released, so asking the kernel would only fail — and
        // fall back to a cache that still says "running `sleep`".
        if (terminal.state.value != State.RUNNING) {
            val ended = Status(
                running = false,
                exitCode = terminal.exitCode,
                foreground = null,
                foregroundPid = null,
                cwd = terminal.cachedStatus?.cwd,
            )
            terminal.cachedStatus = ended
            return ended
        }
        val now = System.currentTimeMillis()
        terminal.cachedStatus?.let { cached ->
            if (now - terminal.cachedStatusAt < maxAgeMs) return cached
        }
        val fresh = runCatching {
            val reply = NativeCore.pty("pty.status", jsonOf("id" to session.id))
            Status(
                running = reply.bool("running", true),
                exitCode = reply.intOrNull("exitCode"),
                foreground = reply.str("foregroundName")?.takeIf { it.isNotBlank() },
                foregroundPid = reply.intOrNull("foregroundPid"),
                cwd = reply.str("cwd")?.takeIf { it.isNotBlank() },
            )
        }.getOrNull() ?: return terminal.cachedStatus
        terminal.cachedStatus = fresh
        terminal.cachedStatusAt = now
        return fresh
    }

    /**
     * The last status [status] fetched, without asking the kernel again.
     *
     * For the main thread: the lookup itself is a JNI call into `/proc`, which
     * the UI does on a background thread and then only reads.
     */
    fun lastStatus(id: String): Status? = terminals[id]?.cachedStatus

    /* ------------------------------------------------------------- the agent */

    /**
     * Start a command detached, for `sh_background` and backgrounded builds.
     *
     * Wider and taller than a phone's terminal on purpose: build tools wrap
     * their output to the reported width, and a 40-column wrap is unreadable
     * when the agent reads it back.
     */
    fun startBackground(command: String, workingDir: File, env: Map<String, String> = emptyMap()): Terminal =
        open(
            workingDir = workingDir,
            command = command,
            owner = Owner.AGENT,
            cols = 120,
            rows = 40,
            env = env,
        )

    /**
     * One session's state and the tail of its output.
     *
     * @param tailChars 0 for state only, which is what a listing wants.
     */
    fun poll(id: String, tailChars: Int = 8_000): Snapshot? {
        val terminal = terminals[id] ?: return null
        // The agent's own commands, shown back to the user: not a session for
        // the agent to read, kill or list. It already has their results.
        if (terminal.isTranscript) return null
        val status = status(id)
        val now = System.currentTimeMillis()
        val output = if (tailChars > 0) terminal.plainTail(tailChars) else ""
        return Snapshot(
            id = terminal.id,
            owner = terminal.owner,
            title = terminal.displayName(),
            command = terminal.command,
            workingDir = terminal.workingDir,
            cwd = status?.cwd,
            foreground = status?.foreground,
            state = terminal.state.value,
            exitCode = terminal.exitCode,
            runningForMs = now - terminal.startedAt,
            idleForMs = now - terminal.lastOutputAt,
            output = output,
            outputTruncated = tailChars in 1 until terminal.totalChars.toInt().coerceAtLeast(0),
            lastLine = terminal.lastLine(),
            fullScreenProgram = terminal.fullScreenProgram,
        )
    }

    /** Every session but the transcripts, state only. */
    fun list(): List<Snapshot> = _sessions.value.mapNotNull { poll(it.id, tailChars = 0) }

    /* ------------------------------------------------------- transcripts */

    private val transcriptsByKey = ConcurrentHashMap<String, String>()
    private val transcriptCounter = AtomicLong(1)

    /**
     * The transcript of one chat's commands, made on first use.
     *
     * One per chat rather than one for the app: two chats' commands in one
     * scrollback would read as one story. The few most recent are kept;
     * see [pruneTranscripts].
     */
    fun transcript(key: String, title: String, workingDir: File): Terminal {
        transcriptsByKey[key]?.let { id -> terminals[id]?.let { return it } }
        val id = "agent-${transcriptCounter.getAndIncrement()}"
        val terminal = Terminal(
            id = id,
            owner = Owner.AGENT,
            command = null,
            workingDir = workingDir.absolutePath,
            startedAt = System.currentTimeMillis(),
            session = null,
            scope = scope,
            onEnded = {},
            kind = Kind.TRANSCRIPT,
            title = title,
            onBusyChanged = { _changes.update { it + 1 } },
        )
        terminal.start(TRANSCRIPT_COLUMNS, TRANSCRIPT_ROWS)
        terminals[id] = terminal
        transcriptsByKey[key] = id
        pruneTranscripts()
        publish()
        return terminal
    }

    /**
     * An [ExecEcho] into the transcript of chat [key]. The transcript is made
     * when the first command starts, not before: a chat that runs nothing
     * adds nothing to the session list.
     */
    fun transcriptEcho(key: String, title: String, workingDir: File): ExecEcho = object : ExecEcho {
        // The transcript a command began in gets the rest of it, even if a
        // new one is made meanwhile (the user closed it).
        @Volatile
        private var current: TranscriptEcho? = null

        override fun begin(command: String, cwd: String, interrupt: (() -> Unit)?) {
            current = transcript(key, title, workingDir).echo.also { it.begin(command, cwd, interrupt) }
        }

        override fun output(text: String) {
            current?.output(text)
        }

        override fun end(exitCode: Int?, signal: Int?, timedOut: Boolean, durationMs: Long) {
            current?.end(exitCode, signal, timedOut, durationMs)
            current = null
        }

        override fun detached(id: String) {
            current?.detached(id)
            current = null
        }
    }

    /**
     * Where a line the user typed into transcript [id] is shown as it runs:
     * the same prompt, output and ending as the agent's commands get. Null
     * for anything but a transcript, which has a shell of its own to type to.
     */
    fun echoInto(id: String): ExecEcho? = terminals[id]?.takeIf { it.isTranscript }?.echo

    /** Keep the [MAX_TRANSCRIPTS] newest transcripts; one with a command running stays. */
    private fun pruneTranscripts() {
        val transcripts = terminals.values.filter { it.isTranscript }.sortedBy { it.startedAt }
        val excess = transcripts.size - MAX_TRANSCRIPTS
        if (excess <= 0) return
        transcripts.filter { !it.busy.value }.take(excess).forEach { old ->
            terminals.remove(old.id)
            transcriptsByKey.entries.removeIf { it.value == old.id }
        }
    }

    /**
     * Stop a session: SIGTERM first so it can flush and remove lock files,
     * then hang up the terminal, which SIGHUPs the whole process group and so
     * catches anything it spawned.
     */
    fun kill(id: String): Boolean {
        val terminal = terminals[id] ?: return false
        if (terminal.isTranscript) {
            terminal.interrupt()
            return true
        }
        val session = terminal.session ?: return false
        terminal.markKilled()
        terminal.signal(SIGTERM)
        // Closing is a blocking JNI call that also waits to reap the child.
        scope.launch(Dispatchers.IO) {
            delay(500)
            session.close()
        }
        publish()
        return true
    }

    /** Close a session and forget it. What the X on a terminal tab does. */
    fun close(id: String) {
        val terminal = terminals.remove(id) ?: return
        transcriptsByKey.entries.removeIf { it.value == id }
        terminal.markKilled()
        terminal.session?.let { session -> scope.launch(Dispatchers.IO) { session.close() } }
        publish()
    }

    /**
     * Drop finished sessions, keeping the most recent few.
     *
     * Each one holds its scrollback, and a session the user can no longer see
     * the result of is just memory. Running sessions are never pruned.
     */
    fun prune(keepFinished: Int = 8) {
        val finished = terminals.values
            .filter { it.state.value != State.RUNNING }
            .sortedBy { it.startedAt }
        if (finished.size <= keepFinished) return
        finished.take(finished.size - keepFinished).forEach { terminals.remove(it.id) }
        publish()
    }

    fun closeAll() {
        terminals.values.toList().forEach { it.markKilled() }
        terminals.values.toList().forEach { runCatching { it.session?.close() } }
        terminals.clear()
        transcriptsByKey.clear()
        publish()
    }

    private companion object {
        const val SIGTERM = 15

        /** The width the agent's commands print at; see ShellRunner's terminal. */
        const val TRANSCRIPT_COLUMNS = 120
        const val TRANSCRIPT_ROWS = 40
        const val MAX_TRANSCRIPTS = 4

        /**
         * How long a kernel-sourced status is reused. Short enough that the
         * panel's "running" badge follows a command starting, long enough that
         * sixty frames a second do not each make three syscalls.
         */
        const val STATUS_TTL_MS = 700L
    }
}
