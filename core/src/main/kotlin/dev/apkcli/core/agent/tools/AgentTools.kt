package dev.apkcli.core.agent.tools

import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.boolean
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.objectArray
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Schema.stringArray
import dev.apkcli.core.agent.SubagentRequest
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.agent.UiBridge
import dev.apkcli.core.model.SubagentRun
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.store.BuiltInAgents
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.objects
import dev.apkcli.core.util.str
import dev.apkcli.core.util.strings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/* --------------------------------------------------------------- task_list */

class TaskListTool : Tool {
    override val name = "task_list"
    override val taskClass = TaskClass.INTERACTIVE
    override val parallelSafe = false
    override val supervised = false

    override val description = """
        Replace the task list the user sees with the steps of your current work.

        When to use it: work with three or more steps, so the user can follow your
        progress. Skip it for a single edit; a one-item list is noise. Facts worth
        keeping after this session belong in memory_write instead.

        Each call replaces the whole list and nothing is merged, so send every item,
        in order, every time. Items without text are dropped. At most one item is
        active: the first marked active that is not done. Mark each step done as you
        finish it, not in a batch at the end. Inside a subagent there is no task
        list and the call does nothing.

        Output: "Task list updated: D/N done."
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("tasks")) {
        objectArray("tasks", "The complete list, in order.", required = listOf("text")) {
            string("text", "What the step is. Short and concrete.")
            boolean("done", "Finished.", default = false)
            boolean("active", "Currently being worked on. At most one.", default = false)
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val ui = ctx.ui
            ?: return ToolResult.ok("No task list in this context (running as a subagent).")

        val items = args.objects("tasks").mapNotNull { entry ->
            entry.str("text")?.takeIf { it.isNotBlank() }?.let { text ->
                UiBridge.Task(
                    text = text,
                    done = entry.bool("done", false),
                    active = entry.bool("active", false),
                )
            }
        }
        if (items.isEmpty()) return ToolResult.error("'tasks' is empty.")

        // Keep the invariant the UI relies on: at most one active item.
        var seenActive = false
        val normalised = items.map { task ->
            when {
                task.done -> task.copy(active = false)
                task.active && !seenActive -> task.also { seenActive = true }
                else -> task.copy(active = false)
            }
        }

        ui.setTasks(normalised)
        val done = normalised.count { it.done }
        return ToolResult.ok(
            content = "Task list updated: $done/${normalised.size} done.",
            display = ToolDisplay.Tasks(
                normalised.map { ToolDisplay.Tasks.Item(it.text, it.done, it.active) },
            ),
        )
    }
}

/* -------------------------------------------------------- subagent_dispatch */

class SubagentDispatchTool : Tool {
    override val name = "subagent_dispatch"
    override val taskClass = TaskClass.BACKGROUND
    override val parallelSafe = true

    /**
     * Explorers dispatched together run together: they only read. Any other
     * agent runs on its own, like any change. Two workers side by side could
     * edit one file at the same time, or run two builds in one directory. An
     * agent of the user's own is not looked up here, so it runs alone even
     * when it is read-only: slower, never wrong.
     */
    override fun parallelSafe(args: JsonObject): Boolean = args.str("agent")?.trim() == BuiltInAgents.EXPLORER

    /**
     * Its own rounds are capped (60 at most) and each of its calls has its
     * own deadline; the registry's default five minutes cut a long
     * investigation off midway, with everything it had found lost.
     */
    override val timeoutMs: Long = 45 * 60_000L

    override val description = """
        Hand a self-contained task to another agent and get back its written answer.

        When to use it: an investigation needing many reads, which then fill its
        context, not yours. Explorers dispatched in one reply run concurrently; a
        worker runs on its own, after the calls before it. Read inline instead for a
        read or two, or for files you will edit, whose exact text you need.

        It sees none of this conversation, only `prompt`, so state what you need and
        why. It cannot ask you questions, and cannot dispatch subagents of its own.
        'builtin-explorer' only reads and never needs approval. 'builtin-worker' can
        change files and run commands under your approval mode: what would ask the
        user if you did it asks them in this conversation's dialog, under the
        subagent's name. After `maxIterations` rounds it stops, keeping only what it
        had written. Only its text comes back, none of its tool output.

        Output: "Subagent '<id>' reported:" then its answer (flagged if the iteration
        limit cut it short) or why there is none, such as a failure or unknown agent
        id.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("agent", "prompt")) {
        string(
            "agent",
            "'builtin-explorer': read-only, for investigation. 'builtin-worker': changes " +
                "files and runs commands, asking the user as your approval mode does.",
        )
        string("prompt", "The task. Include your intent, not just the question.")
        stringArray(
            "contextFiles",
            "Paths listed in its prompt as places to start. Only the paths are sent, " +
                "not the contents.",
        )
        integer("maxIterations", "Cap on the subagent's tool-call rounds.", default = 20, maximum = 60)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val dispatch = ctx.dispatchSubagent
            ?: return ToolResult.refused(
                "The ${ctx.agent.name} agent cannot dispatch subagents. Do the work " +
                    "directly, or ask the user to enable it.",
            )
        val agentId = args.str("agent") ?: return ToolResult.error("'agent' is required.")
        val prompt = args.str("prompt")?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("'prompt' is required.")
        // No loop to guard against: a subagent is given no dispatch function,
        // so it cannot hand the task on again, whichever agent it runs as.

        return try {
            val outcome = dispatch(
                SubagentRequest(
                    agentId = agentId,
                    prompt = prompt,
                    contextFiles = args.strings("contextFiles"),
                    maxIterations = args.int("maxIterations", 20),
                    onProgress = ctx.onSubagent,
                ),
            )
            ToolResult(
                content = "Subagent '$agentId' reported:\n\n${outcome.text}",
                // A subagent that failed is a failed call, for the model and
                // on its card, not a green tick over "The subagent failed".
                isError = outcome.run?.state == SubagentRun.State.FAILED,
                display = outcome.run?.let { ToolDisplay.Subagent(it) },
            )
        } catch (cancelled: CancellationException) {
            // Stopping the turn stops the subagent too; that is not its failure.
            throw cancelled
        } catch (t: Throwable) {
            ToolResult.error("Subagent '$agentId' failed: ${t.message}")
        }
    }
}

/* ------------------------------------------------------------- editor_open */

class EditorOpenTool : Tool {
    override val name = "editor_open"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Open a file in the app's editor for the user, optionally scrolled to a line.

        When to use it: you want the user to look at something, such as code you
        are about to change, the line a bug is on, or the result of an edit. It
        returns none of the file's contents; use fs_read to see them yourself.

        The editor is brought on screen, and when the screen is not split that takes
        the user away from the chat, so open files only when looking will help them.
        `path` must be an existing file. The editor refuses binary files and files
        over 4 MB; a subagent has no editor at all.

        Output: "Opened <path> for the user.", with "at line N" when a line was
        given — only once the editor has the file open. Otherwise an error saying
        why it is not, such as a refused file or an editor that did not answer.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path")) {
        string("path", "File to open.")
        integer("line", "Line to scroll to, 1-based.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val ui = ctx.ui ?: return ToolResult.error(
            "There is no editor here: a subagent cannot show files to the user. Name the " +
                "path and line in your answer instead.",
        )
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.isFile) return ToolResult.error("${ctx.display(file)} is not a file.")

        val line = args.int("line", 0).takeIf { it > 0 }
        ui.openInEditor(file.absolutePath, line)?.let { reason ->
            return ToolResult.error("The editor did not open ${ctx.display(file)}: $reason.")
        }
        return ToolResult.ok(
            "Opened ${ctx.display(file)}" + (line?.let { " at line $it" } ?: "") + " for the user.",
        )
    }
}

/*
 * There is deliberately no `ask_user` tool.
 *
 * It existed, and models used it for exactly the case it warned against: a
 * one-word message answered with a modal dialog asking what was meant —
 * sometimes in a language the user had never written in. A question belongs
 * in the reply. The user reads it in the transcript and answers in the
 * composer like any other message, and the next turn continues from there.
 */

/* ------------------------------------------------------------ revert_edit */

class RevertEditTool : Tool {
    override val name = "revert_edit"
    override val destructive = true
    // A file put back while the user waits; see FsWriteTool.
    override val taskClass = TaskClass.INTERACTIVE
    override val parallelSafe = false

    override val description = """
        Restore a file to its state before one of this session's changes, or list
        those changes.

        When to use it: an edit turned out wrong and the file should be exactly as
        before. It restores the whole file, so if later changes to it should stay,
        undo by hand with fs_edit instead. Moves, directory deletes, changes made
        through the shell, files that are not UTF-8 text and edits to files over
        about 512 KB are not recorded.

        fs_write, fs_edit, fs_multi_edit, fs_apply_patch and fs_delete record each
        file they change; call without `snapshotId` for the ids. Whether the user is
        asked first, even to list, is the approval mode's. A file the change created is deleted. The revert
        itself is not recorded.

        Output: up to 30 ids with file names, newest first, "(already reverted)"
        where so; or "Reverted <path> ...", noting when a file that had not existed
        was deleted.
    """.trimIndent()

    override val parameters = Schema.obj {
        string("snapshotId", "Snapshot to restore. Omit to list available snapshots.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val snapshots = ctx.store.snapshots(ctx.changesSessionId)

        val snapshotId = args.str("snapshotId")
            ?: return ToolResult.ok(
                if (snapshots.isEmpty()) {
                    "No file changes have been made in this session."
                } else {
                    "Revertible changes (most recent first):\n" +
                        snapshots.take(30).joinToString("\n") { snapshot ->
                            "  ${snapshot.id}  ${snapshot.path.substringAfterLast('/')}" +
                                if (snapshot.reverted) "  (already reverted)" else ""
                        }
                },
            )

        val snapshot = snapshots.firstOrNull { it.id == snapshotId }
            ?: return ToolResult.error("No snapshot '$snapshotId' in this session.")
        if (snapshot.reverted) {
            return ToolResult.ok("$snapshotId was already reverted.")
        }

        val file = java.io.File(snapshot.path)
        if (!ctx.layout.isInsideSandbox(file)) {
            return ToolResult.refused("${snapshot.path} is outside the sandbox.")
        }

        return if (snapshot.beforeBlobId == null) {
            // The file did not exist before the edit, so reverting means
            // deleting it again.
            if (file.delete()) {
                ctx.store.markReverted(snapshotId)
                ToolResult(
                    content = "Reverted: ${ctx.display(file)} did not exist before, so it was " +
                        "deleted.",
                    touchedPaths = listOf(file.absolutePath),
                )
            } else {
                ToolResult.error("Could not delete ${ctx.display(file)}.")
            }
        } else {
            runCatching {
                val before = ctx.store.readBlob(snapshot.beforeBlobId, 0, Int.MAX_VALUE / 4)
                file.parentFile?.mkdirs()
                file.writeText(before.text)
                ctx.store.markReverted(snapshotId)
                ToolResult(
                    content = "Reverted ${ctx.display(file)} to its state before that edit.",
                    touchedPaths = listOf(file.absolutePath),
                )
            }.getOrElse {
                ToolResult.error("Could not restore ${ctx.display(file)}: ${it.message}")
            }
        }
    }
}
