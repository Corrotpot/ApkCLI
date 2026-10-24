package dev.apkcli.core.runtime

import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.nativebridge.NativeException
import dev.apkcli.core.sched.LoadGovernor
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.intOrNull
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.long
import dev.apkcli.core.util.objects
import dev.apkcli.core.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * One-shot command execution with captured output.
 *
 * This backs the `sh_exec` tool, which is the workhorse: builds, tests, git,
 * package managers, everything that has a beginning and an end.
 *
 * Runs through the native implementation when available, because it handles
 * what is awkward on the JVM: draining both pipes without blocking, a process
 * group of its own so a timeout or a stop kills everything the command
 * started, SIGTERM-then-SIGKILL escalation, the terminating signal apart from
 * the exit code, and a terminal to print on (`terminal`). The JVM fallback
 * exists so the app is usable without the native core and is honest about
 * being less precise.
 */
class ShellRunner(
    private val layout: EnvLayout,
    private val policy: ExecPolicy,
    private val governor: LoadGovernor,
) {

    data class Result(
        val exitCode: Int?,
        val signal: Int?,
        val timedOut: Boolean,
        val durationMs: Long,
        val stdout: String,
        val stderr: String,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        val command: String,
        val workingDir: String,
        /** Something worth knowing that is neither output nor exit status. */
        val note: String? = null,
        /**
         * Run on a terminal: [stdout] is the screen, stderr included, and
         * marks itself where it was cut.
         */
        val onTerminal: Boolean = false,
    ) {
        val succeeded: Boolean get() = exitCode == 0 && !timedOut

        /**
         * Combined view for the model. Interleaving is not preserved — the
         * streams are captured separately — so they are labelled rather than
         * silently merged, which otherwise makes error messages look like they
         * came from stdout.
         */
        fun forModel(): String = buildString {
            if (stdout.isNotBlank()) {
                append(stdout.trimEnd())
                if (stdoutTruncated && !onTerminal) append("\n[stdout truncated]")
            }
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("--- stderr ---\n")
                append(stderr.trimEnd())
                if (stderrTruncated) append("\n[stderr truncated]")
            }
            if (isEmpty()) append("(no output)")
            note?.let { append("\n\n").append(it) }
            append("\n\n")
            when {
                timedOut -> append(
                    "Timed out after ${durationMs}ms; it and everything it started were killed.",
                )
                signal != null -> append("Killed by signal $signal after ${durationMs}ms.")
                else -> append("Exit code $exitCode in ${durationMs}ms.")
            }
        }
    }

    /**
     * @param command a shell command line, run via `sh -c`. Pipelines,
     *   redirections and `&&` all work; this is not an exec vector.
     * @param maxOutputBytes per stream, not combined. Past it the start and
     *   the end are kept — a build's error is at the end. On a [terminal],
     *   the one stream's.
     * @param terminal stdout and stderr on a pseudo-terminal
     *   ([TERMINAL_COLUMNS] x [TERMINAL_ROWS]) rather than two pipes. Tools
     *   then print as they would for a person: progress, colour, a line at a
     *   time — on a pipe, `npm install` prints nothing until it is done. The
     *   result's [Result.stdout] is the screen it left, stderr in it where it
     *   was printed, progress bars in their final state and colour dropped;
     *   [Result.stderr] is empty. stdin is never the terminal, so nothing can
     *   wait for keys. Pipes, as before, without the native core.
     * @param onOutput the last lines it printed, with their colours, a few
     *   times a second while it runs, for showing progress. Called on the
     *   caller's coroutine; keep it cheap.
     * @param echo also shows the command, everything it prints and how it
     *   ends, as it happens: the agent's commands in the terminal panel.
     */
    suspend fun exec(
        command: String,
        workingDir: File = layout.projects,
        timeoutMs: Long = 120_000,
        env: Map<String, String> = emptyMap(),
        stdin: String = "",
        maxOutputBytes: Int = 2 * 1024 * 1024,
        taskClass: TaskClass = TaskClass.BACKGROUND,
        terminal: Boolean = false,
        onOutput: ((List<TerminalBuffer.Row>) -> Unit)? = null,
        echo: ExecEcho? = null,
    ): Result {
        val shell = policy.resolveShell()
        if (!policy.canExecute(shell)) {
            return refusal(command, workingDir, policy.explainRefusal(shell))
        }
        val dir = if (workingDir.isDirectory) workingDir else layout.projects.also { it.mkdirs() }
        // Python buffers stdout in blocks when it is not a terminal, so a
        // script's output would arrive only when it ended.
        val unbuffered = mapOf("PYTHONUNBUFFERED" to "1")
        val tty = terminal && NativeCore.available
        val environment = if (tty) {
            policy.baseEnvironment(dir, term = TERMINAL_TYPE, extra = unbuffered + TERMINAL_ENV + env)
        } else {
            policy.baseEnvironment(dir, term = "dumb", extra = unbuffered + env)
        }

        return if (NativeCore.available) {
            execNative(shell, command, dir, timeoutMs, environment, stdin, maxOutputBytes, taskClass, tty, onOutput, echo)
        } else {
            withContext(governor.dispatcherFor(taskClass)) {
                execJvm(shell, command, dir, timeoutMs, environment, stdin, maxOutputBytes)
            }
        }
    }

    /**
     * Run through the native process API (`proc.*`), one step at a time.
     *
     * Stepping is what makes this cancellable and live: between steps the
     * coroutine can be cancelled — the `finally` then closes the process,
     * which stops its whole process group — and each step hands over the
     * output printed since the last one. No thread is held while the command
     * runs; the old single blocking call held one for as long as the command
     * (or anything it left behind) kept the pipes open, which could be
     * forever.
     */
    private suspend fun execNative(
        shell: File,
        command: String,
        dir: File,
        timeoutMs: Long,
        environment: Map<String, String>,
        stdin: String,
        maxOutputBytes: Int,
        taskClass: TaskClass,
        terminal: Boolean,
        onOutput: ((List<TerminalBuffer.Row>) -> Unit)?,
        echo: ExecEcho?,
    ): Result {
        val started = System.currentTimeMillis()
        val id = try {
            // Spawned from the governor's pool so the child inherits that
            // thread's nice value and CPU affinity: a build runs below the UI.
            withContext(governor.dispatcherFor(taskClass)) {
                NativeCore.pty(
                    "proc.spawn",
                    jsonOf(
                        "command" to command,
                        "shell" to shell.absolutePath,
                        "cwd" to dir.absolutePath,
                        "timeoutMs" to timeoutMs,
                        "stdin" to stdin,
                        "clearEnv" to true,
                        "env" to environment,
                        "tty" to terminal,
                        "cols" to TERMINAL_COLUMNS,
                        "rows" to TERMINAL_ROWS,
                    ),
                ).long("id")
            }
        } catch (e: NativeException) {
            val why = if (e.kind == NativeException.Kind.ERRNO) "${e.detail}\n\n${policy.explainRefusal(shell)}" else e.detail
            echo?.let {
                it.begin(command, layout.shorten(dir), null)
                it.output(why.replace("\n", "\r\n") + "\r\n")
                it.end(null, null, false, System.currentTimeMillis() - started)
            }
            return refusal(command, dir, why)
        }

        // Ctrl-C from the terminal panel: SIGINT to the command's group, as
        // at a keyboard. The step loop then reads how it ended, as for any end.
        echo?.begin(command, layout.shorten(dir)) {
            runCatching { NativeCore.pty("proc.signal", jsonOf("id" to id, "signal" to SIGINT)) }
        }
        var echoEnded = false

        // On a terminal there is one stream, kept as the screen it draws.
        // Through pipes there are two, kept as they came, and the live view
        // reads them through a small terminal of its own for `\r` and colour.
        val screen = if (terminal) TerminalCapture(TERMINAL_COLUMNS, maxOutputBytes) else null
        val stdout = OutputCapture(maxOutputBytes)
        val stderr = OutputCapture(maxOutputBytes)
        val view = if (screen == null && onOutput != null) {
            TerminalBuffer(maxLines = LIVE_ROWS * 4).also { it.columns = TERMINAL_COLUMNS }
        } else {
            null
        }
        var shownRevision = 0
        var lastShown = 0L
        try {
            while (true) {
                val step = withContext(Dispatchers.IO) {
                    NativeCore.pty("proc.step", jsonOf("id" to id, "waitMs" to STEP_WAIT_MS))
                }
                val out = step.str("stdout", "")
                val err = step.str("stderr", "")
                if (echo != null) {
                    if (out.isNotEmpty()) echo.output(out)
                    if (err.isNotEmpty()) echo.output(err)
                }
                if (screen != null) {
                    screen.append(out)
                    screen.append(err)
                } else {
                    stdout.append(out)
                    stderr.append(err)
                    view?.feed(out)
                    view?.feed(err)
                }

                val now = System.currentTimeMillis()
                if (onOutput != null && now - lastShown >= LIVE_INTERVAL_MS) {
                    val revision = screen?.revision ?: view?.revision ?: 0
                    if (revision != shownRevision) {
                        onOutput(screen?.rows(LIVE_ROWS, LIVE_ROW_CHARS) ?: view?.rows(LIVE_ROWS, LIVE_ROW_CHARS).orEmpty())
                        shownRevision = revision
                        lastShown = now
                    }
                }

                if (step.bool("done")) {
                    echo?.end(step.intOrNull("exitCode"), step.intOrNull("signal"), step.bool("timedOut"), now - started)
                    echoEnded = true
                    return Result(
                        exitCode = step.intOrNull("exitCode"),
                        signal = step.intOrNull("signal"),
                        timedOut = step.bool("timedOut"),
                        durationMs = now - started,
                        stdout = screen?.text() ?: stdout.text(),
                        stderr = if (screen != null) "" else stderr.text(),
                        stdoutTruncated = screen?.truncated ?: stdout.truncated,
                        stderrTruncated = screen == null && stderr.truncated,
                        command = command,
                        workingDir = dir.absolutePath,
                        note = leftBehind(step),
                        onTerminal = screen != null,
                    )
                }
            }
        } finally {
            // Also on cancellation, so NonCancellable: a stopped tool call must
            // not leave its command running.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { NativeCore.pty("proc.close", jsonOf("id" to id)) }
            }
            // Stopped from this side: the turn was stopped, or the tool's
            // deadline passed.
            if (!echoEnded) runCatching { echo?.end(null, null, false, System.currentTimeMillis() - started) }
        }
    }

    /** What the command left running when it exited, in words for the model. */
    private fun leftBehind(step: kotlinx.serialization.json.JsonObject): String? {
        val lingering = step.objects("lingering").map { "${it.str("name", "?")} (pid ${it.int("pid")})" }
        return when {
            lingering.isNotEmpty() ->
                "The command exited, but ${lingering.joinToString(", ")} it started was still running " +
                    "and holding its output, so it was stopped: sh_exec waits for everything a command " +
                    "starts. Run servers and watchers with sh_background instead."
            step.bool("outputHeld") ->
                "The command exited, but a process it started detached itself (as a daemon does) and " +
                    "still holds the output. It keeps running; anything more it prints is not captured. " +
                    "Redirect a daemon's output to a log file to read it."
            else -> null
        }
    }

    /**
     * JVM fallback. Two reader threads for the same deadlock reason as the
     * native path. `ProcessBuilder` cannot report the terminating signal, so
     * [Result.signal] is always null here and a killed process shows up as
     * exit code 128+n or null.
     */
    private suspend fun execJvm(
        shell: File,
        command: String,
        dir: File,
        timeoutMs: Long,
        environment: Map<String, String>,
        stdin: String,
        maxOutputBytes: Int,
    ): Result {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(shell.absolutePath, "-c", command)
            .directory(dir)
        builder.environment().apply {
            clear()
            putAll(environment)
        }

        val process = try {
            builder.start()
        } catch (t: Throwable) {
            return refusal(command, dir, "${t.javaClass.simpleName}: ${t.message}")
        }

        if (stdin.isNotEmpty()) {
            runCatching { process.outputStream.use { it.write(stdin.toByteArray()) } }
        } else {
            runCatching { process.outputStream.close() }
        }

        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = drain(process.inputStream, out, maxOutputBytes)
        val errThread = drain(process.errorStream, err, maxOutputBytes)

        // Waited in short steps, so a cancelled call stops the process too.
        var finished = false
        try {
            val deadline = started + timeoutMs
            while (!finished && System.currentTimeMillis() < deadline) {
                finished = process.waitFor(100, TimeUnit.MILLISECONDS)
                coroutineContext.ensureActive()
            }
        } finally {
            if (!finished) {
                process.destroy()
                if (!process.waitFor(400, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
        outThread.join(1_000)
        errThread.join(1_000)

        return Result(
            exitCode = if (finished) process.exitValue() else null,
            signal = null,
            timedOut = !finished,
            durationMs = System.currentTimeMillis() - started,
            stdout = out.toString(),
            stderr = err.toString(),
            stdoutTruncated = out.length >= maxOutputBytes,
            stderrTruncated = err.length >= maxOutputBytes,
            command = command,
            workingDir = dir.absolutePath,
        )
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder, cap: Int): Thread =
        Thread {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n <= 0) break
                        if (into.length < cap) {
                            into.appendRange(buffer, 0, minOf(n, cap - into.length))
                        }
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }

    private fun refusal(command: String, dir: File, message: String) = Result(
        exitCode = null,
        signal = null,
        timedOut = false,
        durationMs = 0,
        stdout = "",
        stderr = message,
        stdoutTruncated = false,
        stderrTruncated = false,
        command = command,
        workingDir = dir.absolutePath,
    )

    /** `which`, resolved through the exec policy rather than the shell. */
    suspend fun which(program: String): String? = withContext(governor.dispatcherFor(TaskClass.INTERACTIVE)) {
        if (!NativeCore.available) {
            return@withContext listOf(layout.bin, File("/system/bin"), File("/system/xbin"))
                .map { File(it, program) }
                .firstOrNull { it.exists() && it.canExecute() }
                ?.absolutePath
        }
        runCatching {
            NativeCore.pty(
                "which",
                jsonOf(
                    "program" to program,
                    "path" to "${layout.bin.absolutePath}:/system/bin:/system/xbin",
                ),
            ).str("resolved")
        }.getOrNull()
    }

    /**
     * Snapshot of what the environment can actually do, for the settings screen
     * and for the `env_info` tool. The model benefits from knowing that `python`
     * is missing *before* it writes a six-step plan that depends on it.
     *
     * No `git`: the app has no version control for now, and listing it would
     * suggest a workflow the agent has no tools for.
     */
    suspend fun capabilities(): Map<String, String?> {
        val probes = listOf(
            "sh", "bash", "grep", "sed", "awk", "find", "tar",
            "python", "python3", "pip", "pip3", "node", "npm", "cargo", "rustc", "gcc", "clang",
            "make", "cmake", "java", "kotlinc", "gradle", "rg", "fd", "jq", "curl", "wget",
        )
        return probes.associateWith { which(it) }
    }

    private companion object {
        /** How long one native step may wait for output. */
        const val STEP_WAIT_MS = 100

        const val SIGINT = 2

        /** How often running output is handed to `onOutput`. */
        const val LIVE_INTERVAL_MS = 250L

        /**
         * Lines handed to `onOutput`, and the longest part of one it gets.
         * More than the live view shows at once: it scrolls back through them.
         */
        const val LIVE_ROWS = 200
        const val LIVE_ROW_CHARS = 500

        /**
         * The terminal a [terminal] command prints on. The size agent jobs
         * (`sh_background`) get too: wide enough that little wraps or is cut
         * to fit, which matters more to the model than to the narrow live view.
         */
        const val TERMINAL_COLUMNS = 120
        const val TERMINAL_ROWS = 40
        const val TERMINAL_TYPE = "xterm-256color"

        /**
         * What a terminal changes besides the output. Programs that page what
         * they print (`git log`, `man`, psql) start a pager on one and wait
         * for keys that never come; git would prompt for credentials. None of
         * that happens on a pipe, and none of it should here.
         */
        val TERMINAL_ENV = mapOf(
            "PAGER" to "cat",
            "GIT_PAGER" to "cat",
            "MANPAGER" to "cat",
            "SYSTEMD_PAGER" to "cat",
            "GIT_TERMINAL_PROMPT" to "0",
        )
    }
}

/**
 * One output stream, kept within [limit] characters by dropping the middle.
 *
 * The start says what the command was doing; the end is where a build's error
 * and a test run's summary are. Keeping only the start, as this used to, kept
 * the part least likely to matter — and stopped reading at the limit, so a
 * chatty command died of SIGPIPE.
 */
internal class OutputCapture(private val limit: Int) {
    private val headLimit = limit / 2
    private val tailLimit = limit - headLimit
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var dropped = 0L

    fun append(text: String) {
        if (text.isEmpty()) return
        var rest = text
        if (head.length < headLimit) {
            val take = minOf(headLimit - head.length, rest.length)
            head.append(rest, 0, take)
            rest = rest.substring(take)
        }
        if (rest.isEmpty()) return
        tail.append(rest)
        // Trimmed in batches: deleting from the front of a StringBuilder on
        // every append would be quadratic for a command that prints a lot.
        if (tail.length > tailLimit * 2) {
            val excess = tail.length - tailLimit
            tail.delete(0, excess)
            dropped += excess
        }
    }

    private val omitted: Long get() = dropped + (tail.length - tailLimit).coerceAtLeast(0)

    val truncated: Boolean get() = omitted > 0

    fun text(): String {
        val omitted = omitted
        if (omitted == 0L) return head.toString() + tail
        val kept = tail.substring((tail.length - tailLimit).coerceAtLeast(0))
        return "$head\n[… $omitted characters omitted …]\n$kept"
    }
}
