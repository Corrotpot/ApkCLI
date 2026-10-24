package dev.apkcli.core.agent.tools

import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.boolean
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.runtime.TerminalSessions
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.str
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Shell and process tools.
 *
 * The device-specific constraint, repeated here because it determines what these
 * tools can and cannot do: on the Play-compliant build, SELinux forbids
 * executing any file inside the app's data directory. Only binaries shipped in
 * the APK and those in `/system/bin` can run. Shell *scripts* always work,
 * since the interpreter is what is executed.
 */

private fun Long.asSeconds(): String = "%.1fs".format(this / 1000.0)

/* ----------------------------------------------------------------- sh_exec */

class ShExecTool : Tool {
    override val name = "sh_exec"
    override val taskClass = TaskClass.BACKGROUND
    override val parallelSafe = false

    /**
     * A little past the longest `timeoutMs` the schema allows, so the shell's
     * own timeout fires first and the result carries the output so far,
     * rather than the registry cancelling the call with nothing to show.
     */
    override val timeoutMs: Long? = 310_000L

    override val description = """
        Run a shell command, wait for it, and capture what it printed.

        When to use it: anything that ends by itself — builds, tests, package
        managers, one-off scripts. For a server, a watcher, or work longer than a few
        minutes use sh_background instead. When `timeoutMs` runs out (default 120s,
        at most 300s) the command and everything it started are killed.

        The shell is POSIX sh, not bash: no `[[ ]]`, no arrays, no process substitution.
        Pipes, redirection, `&&`, `||` and `$(...)` work. There is no `sudo`, no root and
        no system package manager.

        Pass `cwd` instead of writing `cd`: each call starts from the project root, so a
        `cd` is forgotten by the next one. Quote paths that contain spaces.

        The call returns when the command exits. Something it started with `&` that
        still holds its output is stopped then and named in the result; a server
        belongs in sh_background.

        Output goes to a terminal 120 columns wide, as in a terminal window: programs
        print progress and colour, and stderr appears where it was written. You get
        the screen as it ended — a progress bar as its last state, colour removed.
        Pagers are off (PAGER=cat). Programs that fit their output to the width (`ps`,
        `ls` in columns) do so; pipe through `cat` for their plain output. stdin is
        empty and not the terminal, so a REPL, an editor or a prompt with no flag to
        skip it fails or waits out the timeout: use its non-interactive flag (`-y`,
        `--yes`, `--batch`), or ask the user to run it in their terminal.

        Output: what the terminal showed, then the exit code. Over 2 MB its start and
        its end are kept; output too long for the context is stored whole, and the
        result says so.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("command")) {
        string("command", "Shell command line. Run through sh -c.")
        string("cwd", "Working directory. Defaults to the project root.")
        integer(
            "timeoutMs",
            "Cancel the command after this long. The call itself is cancelled at 300000.",
            default = 120_000,
            maximum = 300_000,
        )
        string("stdin", "Text to feed to the command's standard input.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.str("command") ?: return ToolResult.error("'command' is required.")
        val cwdArg = args.str("cwd") ?: "."
        val cwd = ctx.resolve(cwdArg) ?: return outsideSandbox(ctx, cwdArg)
        if (!cwd.isDirectory) {
            return ToolResult.error("${ctx.display(cwd)} is not a directory.")
        }

        // Catch the commands that cannot work here before spending a process on
        // them, and say why — otherwise the model sees "not found" and tries a
        // variation.
        impossibleCommandHint(command, ctx)?.let { return ToolResult.refused(it) }

        val result = ctx.shell.exec(
            command = command,
            workingDir = cwd,
            timeoutMs = args.int("timeoutMs", 120_000).toLong(),
            stdin = args.str("stdin") ?: "",
            taskClass = TaskClass.BACKGROUND,
            terminal = true,
            onOutput = ctx.onOutput,
            echo = ctx.echo,
        )

        val hint = ShellHints.afterRun(
            command = command,
            exitCode = result.exitCode,
            stdout = result.stdout,
            // On the terminal stderr is part of the screen.
            stderr = result.stderr.ifBlank { result.stdout },
            canInstall = ctx.packages != null,
        )
        val text = result.forModel() + (hint?.let { "\n\n$it" } ?: "")
        return ToolResult(
            content = text,
            isError = !result.succeeded,
            exitCode = result.exitCode,
            display = ToolDisplay.Command(
                command = command,
                exitCode = result.exitCode,
                output = result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "",
                durationMs = result.durationMs,
                timedOut = result.timedOut,
            ),
        )
    }

    /**
     * Pre-empt the handful of commands a model reliably reaches for that cannot
     * exist on Android.
     */
    private fun impossibleCommandHint(command: String, ctx: ToolContext): String? {
        val head = command.trimStart().substringBefore(' ').substringAfterLast('/')
        return when (head) {
            "sudo", "su", "doas" ->
                "There is no root on this device and no `$head`. Everything runs as the " +
                    "app's own unprivileged user. If a command needs elevated access, it " +
                    "cannot be done here."

            "apt", "apt-get", "dpkg", "pkg", "yum", "dnf", "apk", "pacman", "brew", "zypper" ->
                "`$head` does not exist here — there is no system package manager and no " +
                    "root to install into. " +
                    if (ctx.packages != null) {
                        "Termux packages are installed with the pkg_install tool (find names " +
                            "with pkg_search, remove with pkg_remove); `pip install` and " +
                            "`npm install` work once python or nodejs is installed."
                    } else {
                        "On this build new binaries cannot be executed at all (SELinux W^X). " +
                            "Work with the tools listed in the environment section."
                    }

            "systemctl", "service", "journalctl" ->
                "There is no systemd on Android. `$head` does not exist. Use sh_background " +
                    "to run a process and poll it."

            else -> null
        }
    }
}

/* ----------------------------------------------------------- sh_background */

class ShBackgroundTool : Tool {
    override val name = "sh_background"
    override val taskClass = TaskClass.BACKGROUND

    override val description = """
        Start a command detached and get a job id back immediately.

        When to use it:
        - A dev server, a file watcher, a REPL fed from a script: anything that does not
          end on its own.
        - Work longer than a couple of minutes, which sh_exec would cancel.

        The command gets a real terminal, so build tools line-buffer and colour their
        output instead of emitting nothing for minutes. The job also appears in the
        user's terminal panel while it runs, so they can watch it.

        Output: the job id, which sh_job_output reads and sh_job_kill stops. Nothing
        tells you when the job finishes — read it when you need the result, and prefer
        a check (`curl` the port, grep the log) over sleeping.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("command")) {
        string("command", "Shell command line.")
        string("cwd", "Working directory. Defaults to the project root.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.str("command") ?: return ToolResult.error("'command' is required.")
        val terminals = ctx.terminals
            ?: return ToolResult.refused("Background jobs need the native core, which is not loaded.")
        val cwdArg = args.str("cwd") ?: "."
        val cwd = ctx.resolve(cwdArg) ?: return outsideSandbox(ctx, cwdArg)

        return try {
            val terminal = terminals.startBackground(command, cwd)
            echoDetached(ctx, command, cwd, terminal.id)
            ToolResult.ok(
                "Started `$command` as ${terminal.id} in ${ctx.display(cwd)}.\n" +
                    "Read it with sh_job_output(id: \"${terminal.id}\"), stop it with " +
                    "sh_job_kill(id: \"${terminal.id}\").",
            )
        } catch (unavailable: dev.apkcli.core.runtime.PtyManager.NotAvailable) {
            ToolResult.refused(unavailable.message ?: "Cannot start a background job.")
        } catch (t: Throwable) {
            ToolResult.error("Could not start the job: ${t.message}")
        }
    }
}

class ShJobOutputTool : Tool {
    override val name = "sh_job_output"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Read what a session has printed, and whether it is still running.

        When to use it: to see where a sh_background job got to, or its result once
        it has ended; and to see what the user ran in their terminal panel (a term-…
        id) and what it said, instead of asking them to paste it. A command that ends
        by itself belongs in sh_exec, which returns its output directly.

        Nothing tells you when a job finishes: read it when you need the result, and
        check for the result itself (a port answering, a line in the log) rather than
        sleeping. A session running a full-screen program (an editor, a pager, htop)
        has no readable text, and the result says so.

        Output: a header with the id, whose session it is, the command, its state
        (running for how long, or the exit code), the foreground program, the
        directory and how long it has been silent; then the last `tailChars`
        characters with colour codes stripped. The header says when earlier output
        was dropped.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("id")) {
        string("id", "Session id: job-N from sh_background, or term-N from sh_jobs.")
        integer("tailChars", "How much of the end of the output to return.", default = 6000, maximum = 40_000)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.str("id") ?: return ToolResult.error("'id' is required.")
        val terminals = ctx.terminals ?: return ToolResult.error("Terminals unavailable: no native core.")
        val snapshot = terminals.poll(id, args.int("tailChars", 6000))
            ?: return ToolResult.error(
                "No session '$id'. Open sessions: " +
                    terminals.list().filter { it.state == TerminalSessions.State.RUNNING }
                        .joinToString(", ") { it.id }.ifEmpty { "none" },
            )

        return ToolResult(
            content = buildString {
                appendLine(describe(snapshot, ctx))
                if (snapshot.fullScreenProgram) {
                    appendLine(
                        "This session is running a full-screen program (an editor, a pager, " +
                            "htop). Its drawing is not captured as text, so there is nothing " +
                            "useful to read here.",
                    )
                }
                appendLine()
                append(snapshot.output.ifBlank { "(no output yet)" })
            },
            isError = snapshot.state == TerminalSessions.State.FAILED,
            exitCode = snapshot.exitCode,
        )
    }
}

/** One line of "what this session is and where it got to", shared by the two read tools. */
private fun describe(snapshot: TerminalSessions.Snapshot, ctx: ToolContext): String = buildString {
    append(snapshot.id)
    append(if (snapshot.owner == TerminalSessions.Owner.USER) " (the user's terminal)" else "")
    snapshot.command?.let { append(" `$it`") }
    append(" — ")
    append(
        when (snapshot.state) {
            TerminalSessions.State.RUNNING -> "running for ${snapshot.runningForMs.asSeconds()}"
            TerminalSessions.State.EXITED -> "exited with ${snapshot.exitCode}"
            TerminalSessions.State.KILLED -> "stopped"
            TerminalSessions.State.FAILED -> "failed to start"
        },
    )
    snapshot.foreground?.let { append(", running `$it`") }
    snapshot.cwd?.let { append(", in ${ctx.display(java.io.File(it))}") }
    if (snapshot.state == TerminalSessions.State.RUNNING && snapshot.idleForMs > 5_000) {
        append(", silent for ${snapshot.idleForMs.asSeconds()}")
    }
    if (snapshot.outputTruncated) append("\n[earlier output dropped from the window]")
}

class ShJobKillTool : Tool {
    override val name = "sh_job_kill"
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Stop a background job you started.

        When to use it: a server or watcher you started is no longer needed, a job is
        stuck, or you need its port back before starting it again. Terminals the user
        opened (term-…) are refused: they are theirs to close, so ask them instead.

        Sends SIGTERM so the program can clean up, then hangs up its terminal, which
        also stops anything it spawned.

        Output: "Stopped job-N.", or an error when there is no such session.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("id")) {
        string("id", "Job id from sh_background.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.str("id") ?: return ToolResult.error("'id' is required.")
        val terminals = ctx.terminals ?: return ToolResult.error("Terminals unavailable: no native core.")
        val snapshot = terminals.poll(id, tailChars = 0)
            ?: return ToolResult.error("No session '$id'.")
        // The user's terminal is their workspace; killing what they are doing
        // in it is not a decision for the agent to make silently.
        if (snapshot.owner == TerminalSessions.Owner.USER) {
            return ToolResult.refused(
                "$id is a terminal the user opened. Ask them to stop it, or say what " +
                    "needs stopping and why.",
            )
        }
        return if (terminals.kill(id)) ToolResult.ok("Stopped $id.") else ToolResult.error("No session '$id'.")
    }
}

class ShJobsTool : Tool {
    override val name = "sh_jobs"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        List the terminal sessions: jobs you started (job-…), terminals the user has
        open (term-…), and the last few that ended.

        When to use it: when the answer has to be current, such as after starting
        something or before reporting whether a server is still up. The environment
        context in your prompt already has this list as of when the request was
        built, so do not call it just to see what exists.

        Output: one line per session with its id, whose it is, the command, its state
        (running for how long, or the exit code), the foreground program, the
        directory and how long it has been silent. Read a session's text with
        sh_job_output.
    """.trimIndent()

    override val parameters = Schema.empty

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val terminals = ctx.terminals ?: return ToolResult.error("Terminals unavailable: no native core.")
        val all = terminals.list()
        if (all.isEmpty()) return ToolResult.ok("Nothing running: no background jobs and no open terminals.")
        return ToolResult.ok(all.joinToString("\n") { "  " + describe(it, ctx) })
    }
}

/* ------------------------------------------------------------- build / test */

/**
 * Build-system detection.
 *
 * Deliberately marker-file based and reported honestly: guessing `npm test` for
 * a project that uses `pnpm` wastes a turn, so the detected command is stated
 * and the model can override it.
 */
private data class ProjectKind(
    val label: String,
    val marker: String,
    val build: String?,
    val test: String?,
    val requires: String,
)

private val PROJECT_KINDS = listOf(
    ProjectKind("Cargo", "Cargo.toml", "cargo build", "cargo test", "cargo"),
    ProjectKind("Gradle (wrapper)", "gradlew", "./gradlew assemble", "./gradlew test", "java"),
    ProjectKind("Gradle", "build.gradle.kts", "gradle assemble", "gradle test", "gradle"),
    ProjectKind("Gradle", "build.gradle", "gradle assemble", "gradle test", "gradle"),
    ProjectKind("Maven", "pom.xml", "mvn -q compile", "mvn -q test", "mvn"),
    ProjectKind("pnpm", "pnpm-lock.yaml", "pnpm build", "pnpm test", "pnpm"),
    ProjectKind("Yarn", "yarn.lock", "yarn build", "yarn test", "yarn"),
    ProjectKind("npm", "package.json", "npm run build", "npm test", "npm"),
    ProjectKind("Make", "Makefile", "make", "make test", "make"),
    ProjectKind("CMake", "CMakeLists.txt", "cmake -B build && cmake --build build", null, "cmake"),
    ProjectKind("Go", "go.mod", "go build ./...", "go test ./...", "go"),
    ProjectKind("Python (pyproject)", "pyproject.toml", null, "python -m pytest", "python"),
    ProjectKind("Python", "setup.py", null, "python -m pytest", "python"),
    ProjectKind("Dart/Flutter", "pubspec.yaml", "dart compile exe .", "dart test", "dart"),
)

/** A background start in the chat's "Agent" terminal: the command, and which session it went on in. */
private fun echoDetached(ctx: ToolContext, command: String, cwd: File, id: String) {
    val echo = ctx.echo ?: return
    echo.begin("$command &", ctx.layout.shorten(cwd), null)
    echo.detached(id)
}

private fun detect(workspace: File): ProjectKind? =
    PROJECT_KINDS.firstOrNull { File(workspace, it.marker).exists() }

/**
 * Shared base for `build_run` and `test_run`. Public for the same reason as
 * [GitTool]: a supertype cannot be less visible than its subclasses.
 */
abstract class BuildLikeTool(
    private val isTest: Boolean,
) : Tool {
    override val taskClass = TaskClass.BACKGROUND
    override val parallelSafe = false

    /**
     * Past the longest `timeoutMs` the schema allows, as for sh_exec. Without
     * it the registry's 300s default cancelled every longer build with no
     * output at all, whatever `timeoutMs` the call asked for.
     */
    override val timeoutMs: Long? = 1_810_000L

    override val parameters = Schema.obj {
        string("command", "Override the detected command.")
        string("cwd", "Where to run. Defaults to the project root.")
        integer(
            "timeoutMs",
            "Kill a foreground run after this many ms and return its output so far. " +
                "Ignored with background.",
            default = 600_000,
            maximum = 1_800_000,
        )
        boolean(
            "background",
            "Start it as a background job and return immediately instead of waiting.",
            default = false,
        )
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        // The governor declines heavy work under thermal or battery pressure.
        // Declining is the right answer rather than queueing: a compile on a
        // throttled phone is slower *and* makes the editor unusable.
        if (!ctx.governor.allowBuild()) {
            return ToolResult.refused(ctx.governor.buildRefusalReason())
        }

        val cwdArg = args.str("cwd") ?: "."
        val cwd = ctx.resolve(cwdArg) ?: return outsideSandbox(ctx, cwdArg)

        val detected = detect(cwd)
        val command = args.str("command")
            ?: (if (isTest) detected?.test else detected?.build)
            ?: return ToolResult.error(
                if (detected == null) {
                    "No recognised build system in ${ctx.display(cwd)} (looked for " +
                        PROJECT_KINDS.map { it.marker }.distinct().joinToString(", ") +
                        "). Pass an explicit command."
                } else {
                    "${detected.label} was detected but has no default " +
                        (if (isTest) "test" else "build") + " command. Pass one explicitly."
                },
            )

        // Verify the tool exists before running, so the failure is a clear
        // message rather than a shell "not found".
        detected?.requires?.let { required ->
            if (args.str("command") == null && ctx.shell.which(required) == null) {
                return ToolResult.refused(
                    "${detected.label} project detected, but `$required` is not installed on " +
                        "this device, so `$command` cannot run. " +
                        when {
                            ctx.packages != null -> "Install it with pkg_install, then retry."
                            ctx.policy.dataDirExecAllowed ->
                                "No package manager is available here. Tell the user."
                            else ->
                                "This build cannot execute newly installed binaries, so the " +
                                    "project cannot be built on this device. Tell the user."
                        },
                )
            }
        }

        if (args.bool("background", false)) {
            val jobs = ctx.terminals
                ?: return ToolResult.error("Background execution unavailable.")
            val handle = jobs.startBackground(command, cwd)
            echoDetached(ctx, command, cwd, handle.id)
            return ToolResult.ok(
                "Started `$command` as ${handle.id}. Read it with sh_job_output.",
            )
        }

        val result = ctx.shell.exec(
            command = command,
            workingDir = cwd,
            timeoutMs = args.int("timeoutMs", 600_000).toLong(),
            taskClass = TaskClass.BACKGROUND,
            terminal = true,
            onOutput = ctx.onOutput,
            echo = ctx.echo,
        )
        return ToolResult(
            content = "`$command` (${detected?.label ?: "custom"})\n\n${result.forModel()}",
            isError = !result.succeeded,
            exitCode = result.exitCode,
            display = ToolDisplay.Command(
                command = command,
                exitCode = result.exitCode,
                output = result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "",
                durationMs = result.durationMs,
                timedOut = result.timedOut,
            ),
        )
    }
}

class BuildRunTool : BuildLikeTool(isTest = false) {
    override val name = "build_run"
    override val description = """
        Build the project with the command detected from marker files in `cwd`.

        When to use it: building after a change. Pass `command` to build one module or
        target instead. Tests go to test_run; anything that is not a build, to sh_exec.

        Detection, first match wins: Cargo, Gradle (wrapper first), Maven, pnpm, Yarn,
        npm, Make, CMake, Go, Dart. Python projects have no default build command.

        Refused under thermal or power pressure: tell the user rather than retrying,
        here or through sh_exec. A foreground run is killed after `timeoutMs` (default
        10 minutes) and returns what it printed so far; for a build that may take
        longer, pass `background: true` and read the job with sh_job_output.

        Output: the command and detected build system, then what the terminal showed
        (as sh_exec: stderr where it was printed, progress in its last state, no
        colour), and the exit code; long output is cut with the rest kept. With
        `background`, the job id.
    """.trimIndent()
}

class TestRunTool : BuildLikeTool(isTest = true) {
    override val name = "test_run"
    override val description = """
        Run the project's tests with the runner detected from marker files in `cwd`.

        When to use it: checking a change. Pass `command` to run one test or module; a
        full suite on a phone is slow and hot. Linters and other checks go to sh_exec.

        Detection, first match wins: Cargo, Gradle (wrapper first), Maven, pnpm, Yarn,
        npm, Make, Go, Python (pytest), Dart. CMake has no default test command.

        Refused under thermal or power pressure: tell the user rather than retrying,
        here or through sh_exec. A foreground run is killed after `timeoutMs` (default
        10 minutes) and returns what it printed so far; for a suite that may take
        longer, pass `background: true` and read it with sh_job_output.

        Output: the command and detected build system, then what the terminal showed
        (as sh_exec: stderr where it was printed, progress in its last state, no
        colour), and the exit code; long output is cut with the rest kept. With
        `background`, the job id.
    """.trimIndent()
}

/* ---------------------------------------------------------------- env_info */

class EnvInfoTool : Tool {
    override val name = "env_info"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Report the live environment: commands, directories, free space, exec policy,
        device load, and the project's detected build and test commands.

        When to use it: to see what build_run and test_run would run at the project
        root, or for a command's full path or the free space. Your prompt's Environment
        section already lists which of these commands exist (at most a minute old) and
        says when heavy work is declined; do not call this to re-read that. Only a fixed
        list of common commands is checked; try others with sh_exec.

        Output: the exec policy and whether new binaries can run; HOME, PREFIX, TMPDIR,
        project path and free space; the shell; each command found with its path, then
        the missing ones; device load and whether heavy work is allowed; the project
        type with its build and test commands.
    """.trimIndent()

    override val parameters = Schema.empty

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val capabilities = ctx.shell.capabilities()
        val present = capabilities.filterValues { it != null }
        val absent = capabilities.filterValues { it == null }.keys

        return ToolResult.ok(
            buildString {
                appendLine("Exec policy: ${ctx.policy.describe()}")
                appendLine(
                    when {
                        ctx.packages != null ->
                            "Termux packages install with pkg_install and run from \$PREFIX/bin."
                        ctx.policy.dataDirExecAllowed ->
                            "Binaries can be installed into \$PREFIX/bin and executed."
                        else ->
                            "Only binaries shipped in the app package and /system/bin can be " +
                                "executed. Installing a new binary is impossible on this build."
                    },
                )
                ctx.packages?.installed()?.let { installed ->
                    val named = installed.filter { it.explicit }.map { it.name }
                    appendLine(
                        "Installed packages: ${installed.size}" +
                            if (named.isEmpty()) "" else " (asked for by name: ${named.joinToString(", ")})",
                    )
                }
                appendLine()
                appendLine("Directories:")
                appendLine("  HOME    ${ctx.layout.home.absolutePath}")
                appendLine("  PREFIX  ${ctx.layout.prefix.absolutePath}")
                appendLine("  TMPDIR  ${ctx.layout.tmp.absolutePath}")
                appendLine("  project ${ctx.workspace.absolutePath}")
                appendLine("  free    ${formatBytes(ctx.layout.freeBytes())}")
                appendLine()
                appendLine("Shell: ${ctx.policy.resolveShell().absolutePath}")
                appendLine()
                appendLine("Available (${present.size}):")
                present.forEach { (command, path) -> appendLine("  $command -> $path") }
                appendLine()
                appendLine("Not available (${absent.size}): ${absent.joinToString(", ")}")
                appendLine()
                appendLine("Device load: ${ctx.governor.describe()}")
                appendLine("Heavy work allowed: ${ctx.governor.allowBuild()}")
                val detected = detect(ctx.workspace)
                appendLine()
                appendLine(
                    if (detected != null) {
                        "Project type: ${detected.label} (${detected.marker}). " +
                            "build=`${detected.build ?: "n/a"}` test=`${detected.test ?: "n/a"}`"
                    } else {
                        "Project type: not recognised from marker files."
                    },
                )
            },
        )
    }
}

// pkg_install, pkg_search and pkg_remove live in PkgTools.kt.

/**
 * Quote a value for `sh -c`.
 *
 * Single quotes with an escape for embedded single quotes: the only form that is
 * safe for arbitrary content. Interpolating a model-supplied string into a
 * command line unquoted is a command injection waiting to happen.
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
