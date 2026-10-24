package dev.apkcli.core.agent.tools

import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.boolean
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Schema.stringArray
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.str
import dev.apkcli.core.util.strings
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Git tools.
 *
 * **Not registered.** The app has no version control for now, so
 * [dev.apkcli.core.agent.Tools.registry] leaves these out and the system prompt
 * says nothing about git. Kept so that bringing them back is one block in
 * `Tools.kt` plus their labels in `ToolLabels.kt`.
 *
 * Thin wrappers over the `git` binary rather than a reimplementation. The only
 * thing worth adding is guard rails: commits are never created unless the user
 * asked for one, and the destructive operations are not exposed at all.
 */

private suspend fun requireGit(ctx: ToolContext): String? =
    if (ctx.shell.which("git") == null) {
        "`git` is not installed on this device. It is a ~5 MB binary that has to be " +
            "shipped in the app package to be executable at all — see env_info for what " +
            "is available. Version control operations are not possible without it."
    } else {
        null
    }

private fun notARepo(ctx: ToolContext, dir: File): String? =
    if (!File(dir, ".git").exists() && !File(dir.parentFile ?: dir, ".git").exists()) {
        "${ctx.display(dir)} is not a git repository. Run `git init` with sh_exec first " +
            "if that is what you want."
    } else {
        null
    }

/**
 * Shared base for the git tools. Public only because Kotlin requires a
 * supertype to be at least as visible as its subclasses, and the concrete tools
 * are public so [dev.apkcli.core.agent.Tools.registry] can assemble them.
 * Abstract, so there is nothing to construct.
 */
abstract class GitTool : Tool {
    override val taskClass = TaskClass.BACKGROUND

    protected suspend fun run(
        ctx: ToolContext,
        command: String,
        timeoutMs: Long = 60_000,
    ): ToolResult {
        requireGit(ctx)?.let { return ToolResult.refused(it) }
        notARepo(ctx, ctx.workspace)?.let { return ToolResult.error(it) }
        val result = ctx.shell.exec(
            command = command,
            workingDir = ctx.workspace,
            timeoutMs = timeoutMs,
            // Keep git non-interactive: without this it can block forever on a
            // credential or editor prompt with no terminal to answer it.
            env = mapOf(
                "GIT_TERMINAL_PROMPT" to "0",
                "GIT_EDITOR" to "true",
                "GIT_PAGER" to "cat",
            ),
        )
        return ToolResult(
            content = result.forModel(),
            isError = !result.succeeded,
            exitCode = result.exitCode,
        )
    }
}

class GitStatusTool : GitTool() {
    override val name = "git_status"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description =
        "Show the working tree status: branch, staged, unstaged and untracked files. " +
            "Call this before committing to see exactly what would go in."

    override val parameters = Schema.empty

    override suspend fun execute(args: JsonObject, ctx: ToolContext) =
        run(ctx, "git status --short --branch")
}

class GitDiffTool : GitTool() {
    override val name = "git_diff"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Show a diff of the working tree, the index, or between two refs.

        Use this to review your own changes before reporting done, and to understand
        what someone else changed.
    """.trimIndent()

    override val parameters = Schema.obj {
        boolean("staged", "Diff the index against HEAD instead of the working tree.", default = false)
        string("ref", "Diff against this ref, e.g. main or HEAD~3.")
        stringArray("paths", "Limit the diff to these paths.")
        integer("contextLines", "Context lines per hunk.", default = 3, maximum = 20)
        boolean("statOnly", "Summary of changed files only, no content.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val parts = mutableListOf("git", "diff", "--no-color")
        if (args.bool("staged", false)) parts += "--cached"
        if (args.bool("statOnly", false)) parts += "--stat"
        parts += "-U${args.int("contextLines", 3)}"
        args.str("ref")?.let { parts += shellQuote(it) }
        val paths = args.strings("paths")
        if (paths.isNotEmpty()) {
            parts += "--"
            paths.forEach { parts += shellQuote(it) }
        }
        return run(ctx, parts.joinToString(" "))
    }
}

class GitLogTool : GitTool() {
    override val name = "git_log"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description =
        "Recent commits, one per line. Pass a path to see the history of a single file — " +
            "useful for working out why code is the way it is before changing it."

    override val parameters = Schema.obj {
        integer("limit", "How many commits.", default = 20, maximum = 200)
        string("path", "Restrict to commits touching this path.")
        boolean("showStats", "Include changed-file counts.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val parts = mutableListOf(
            "git", "log", "--no-color",
            "--pretty=format:%h %ad %an: %s", "--date=short",
            "-n${args.int("limit", 20)}",
        )
        if (args.bool("showStats", false)) parts += "--shortstat"
        args.str("path")?.let { parts += "-- ${shellQuote(it)}" }
        return run(ctx, parts.joinToString(" "))
    }
}

class GitBranchTool : GitTool() {
    override val name = "git_branch"
    override val taskClass = TaskClass.INTERACTIVE

    // Not read-only: `create` and `switchTo` change the working tree. Listing
    // is harmless, but the permission system gates per tool, not per argument,
    // and claiming read-only here would let a read-only agent switch branches.
    override val readOnly = false
    override val parallelSafe = false

    override val description = "List branches, or create and switch to one."

    override val parameters = Schema.obj {
        string("create", "Create this branch and switch to it.")
        string("switchTo", "Switch to an existing branch.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        args.str("create")?.let { return run(ctx, "git checkout -b ${shellQuote(it)}") }
        args.str("switchTo")?.let { return run(ctx, "git checkout ${shellQuote(it)}") }
        return run(ctx, "git branch -vv --no-color")
    }
}

class GitCommitTool : GitTool() {
    override val name = "git_commit"
    override val destructive = true
    override val parallelSafe = false

    override val description = """
        Stage files and create a commit.

        Only commit when the user asked for one. An agent that commits on its own
        initiative scatters half-finished work through the history.

        Stage named paths rather than everything: `git add .` routinely picks up unrelated
        edits, build output and, occasionally, a credentials file. If `paths` is omitted
        the already-staged changes are committed and nothing new is added.

        Does not push. Never amends.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("message")) {
        string("message", "Commit message. First line under ~70 characters.")
        stringArray("paths", "Files to stage. Omit to commit what is already staged.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val message = args.str("message")?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("'message' is required and must not be empty.")

        requireGit(ctx)?.let { return ToolResult.refused(it) }

        val paths = args.strings("paths")
        if (paths.isNotEmpty()) {
            // Refuse the blanket forms rather than silently staging everything.
            val blanket = paths.firstOrNull { it == "." || it == "-A" || it == "--all" || it == "*" }
            if (blanket != null) {
                return ToolResult.refused(
                    "Refusing to stage '$blanket'. Name the files you changed — a blanket " +
                        "add commits unrelated edits and build output, and is how secrets " +
                        "end up in a repository. Use git_status to see the list.",
                )
            }
            for (path in paths) {
                if (ctx.resolve(path) == null) return outsideSandbox(ctx, path)
            }
            val staged = run(ctx, "git add -- ${paths.joinToString(" ") { shellQuote(it) }}")
            if (staged.isError) return staged
        }

        // A commit with nothing staged exits non-zero with a confusing message;
        // check first so the explanation is clear.
        val diffCheck = ctx.shell.exec(
            command = "git diff --cached --quiet; echo \$?",
            workingDir = ctx.workspace,
            timeoutMs = 20_000,
        )
        if (diffCheck.stdout.trim() == "0") {
            return ToolResult.error(
                "Nothing is staged, so there is nothing to commit. Pass `paths` with the " +
                    "files you want included.",
            )
        }

        return run(ctx, "git commit -m ${shellQuote(message)}")
    }
}
