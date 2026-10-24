package dev.apkcli.core.agent.tools

import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.boolean
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.objectArray
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.nativebridge.NativeException
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.intOrNull
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.long
import dev.apkcli.core.util.objects
import dev.apkcli.core.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * File tools.
 *
 * Shared behaviour worth knowing:
 *
 *  * Every path goes through [ToolContext.resolve], which canonicalises it and
 *    refuses anything outside the three sandbox roots. Symlink escapes and
 *    `../../..` are both caught there, not here.
 *  * Every mutation snapshots the file's before and after contents into the
 *    blob store and records a snapshot row, which is what makes "revert this
 *    change" work from the UI hours later, after the session has been reopened.
 *  * Diffs and search/replace run in the native core: the forgiving matcher
 *    (exact, then trailing-whitespace-insensitive, then indentation-relative)
 *    turns the single most common model mistake from a failed call into a
 *    successful one that reports how it matched.
 */

private const val MAX_WRITE_BYTES = 4 * 1024 * 1024

/**
 * Largest file we snapshot for revert.
 *
 * Every edit stores the full before *and* after contents, so the cost is twice
 * the file size per call. Editing a 5 MB generated file twenty times would add
 * 200 MB to the database for an undo nobody will use — and on a phone that
 * storage is not free. Above this limit the edit still happens; it is just
 * not recorded, so it cannot be reverted.
 */
private const val MAX_SNAPSHOT_BYTES = 512 * 1024

/**
 * A file's contents as text, if they are text.
 *
 * `readText()` decodes leniently: a binary file comes back full of
 * replacement characters, and writing that back — as an edit, or as a revert
 * restoring a snapshot — corrupts the file. Strict UTF-8 is the honest test.
 * And a file that could not be read is its own answer: treated as "no
 * previous content", it was recorded as created, and reverting deleted it.
 */
private sealed interface FileText {
    data class Text(val value: String) : FileText
    data object NotText : FileText
    data class Unreadable(val reason: String) : FileText
}

private fun readFileText(file: File): FileText {
    val bytes = try {
        file.readBytes()
    } catch (t: Throwable) {
        return FileText.Unreadable(t.message ?: t.javaClass.simpleName)
    }
    return try {
        FileText.Text(
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString(),
        )
    } catch (_: CharacterCodingException) {
        FileText.NotText
    }
}

/** Why a file whose contents are not [FileText.Text] cannot be edited as text. */
private fun refuseEdit(ctx: ToolContext, file: File, read: FileText): ToolResult = when (read) {
    is FileText.Unreadable -> ToolResult.error("Could not read ${ctx.display(file)}: ${read.reason}")
    else -> ToolResult.error(
        "${ctx.display(file)} is not UTF-8 text. Editing it as text would corrupt it; " +
            "change it with a program made for its format, through sh_exec.",
    )
}

/**
 * Record before/after for revert.
 *
 * @return the snapshot id, or null when the file was too large to snapshot or
 *   the store rejected it. Callers surface null as "not revertible" rather than
 *   failing the edit.
 */
private suspend fun snapshot(
    ctx: ToolContext,
    file: File,
    before: String?,
    after: String?,
): String? {
    val cost = (before?.length ?: 0) + (after?.length ?: 0)
    if (cost > MAX_SNAPSHOT_BYTES * 2) return null

    return runCatching {
        val beforeBlob = before?.let {
            ctx.store.putBlob(
                it,
                kind = "file_snapshot",
                label = "before:${file.name}",
                sessionId = ctx.changesSessionId,
            )
        }
        val afterBlob = after?.let {
            ctx.store.putBlob(
                it,
                kind = "file_snapshot",
                label = "after:${file.name}",
                sessionId = ctx.changesSessionId,
            )
        }
        ctx.store.recordSnapshot(
            sessionId = ctx.changesSessionId,
            path = file.absolutePath,
            beforeBlobId = beforeBlob,
            afterBlobId = afterBlob,
            toolCallId = ctx.toolCallId,
        )
    }.getOrNull()
}

/** Shared refusal text; every tool in this package resolves paths the same way. */
internal fun outsideSandbox(ctx: ToolContext, path: String) = ToolResult.refused(
    "'$path' is outside the project sandbox. Paths must stay within " +
        "${ctx.layout.home.absolutePath}, ${ctx.layout.prefix.absolutePath} or " +
        "${ctx.layout.state.absolutePath}. Relative paths resolve against " +
        "${ctx.workspace.absolutePath}.",
)

/* ---------------------------------------------------------------- fs_read */

class FsReadTool : Tool {
    override val name = "fs_read"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Read a text file and get its lines back numbered.

        When to use it: you know the file and roughly the part you need. To locate
        something, use grep or symbol_search; for a large file, get its outline and
        read only the lines you need.

        `offset` is 0-based but printed numbers start at 1: to begin at line N, pass
        N-1. Binary files and files over 8 MB are refused, as are paths outside
        ${'$'}HOME, ${'$'}PREFIX and the app's state directory. A result over the
        per-call context budget (often a few hundred lines) loses its middle, with the
        full text kept for conversation_read, so read large files in smaller ranges.

        Output: a header giving the path, the range shown and the total line count,
        with "(more follows)" when the file continues; then each line prefixed by its
        number.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path")) {
        string("path", "File path, absolute or relative to the project root.")
        integer("offset", "First line to return, 0-based.", default = 0, minimum = 0)
        integer("limit", "Maximum lines to return.", default = 2000, minimum = 1, maximum = 5000)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.exists()) {
            return ToolResult.error(
                "${ctx.display(file)} does not exist. Use fs_glob or fs_list to find the " +
                    "right path.",
            )
        }

        return try {
            val result = NativeCore.search(
                "read",
                jsonOf(
                    "path" to file.absolutePath,
                    "offset" to args.int("offset", 0),
                    "limit" to args.int("limit", 2000),
                ),
            )
            if (result.bool("binary")) {
                return ToolResult.error(
                    "${ctx.display(file)} is binary (${result.int("size")} bytes). " +
                        "Nothing useful to read as text.",
                )
            }

            val lines = result.objects("lines")
            val total = result.int("totalLines")
            val from = result.int("from", 1)
            val to = result.int("to", total)
            val body = lines.joinToString("\n") { line ->
                "${line.int("n").toString().padStart(5)}  ${line.str("text", "")}"
            }

            val header = buildString {
                append(ctx.display(file))
                append("  lines $from-$to of $total")
                if (!result.bool("eof")) append("  (more follows)")
            }

            ToolResult.ok(
                content = "$header\n$body",
                display = ToolDisplay.FileContent(
                    path = ctx.display(file),
                    text = lines.joinToString("\n") { it.str("text", "") },
                    firstLine = from,
                    totalLines = total,
                    truncated = !result.bool("eof"),
                ),
            )
        } catch (e: NativeException) {
            ToolResult.error("Could not read ${ctx.display(file)}: ${e.detail}")
        }
    }
}

/* --------------------------------------------------------------- fs_write */

class FsWriteTool : Tool {
    override val name = "fs_write"

    // Interactive, like every file mutation here: milliseconds of work that
    // the user is watching a spinner for. The background class runs on
    // threads Android parks in its background cgroup, little cores and a
    // small CPU share, and with a build going on the phone a two-millisecond
    // write waited there for seconds.
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Create a file, or replace a file's entire contents.

        When to use it: new files, and rewrites where most of the file changes. To
        change part of an existing file use fs_edit or fs_multi_edit instead: a
        rewrite drops anything you did not reproduce exactly.

        Missing parent directories are created. Content over 4 MB and paths that are
        directories are refused. `content` is written exactly as given, so end it
        with a newline if the file should have one. Replacing a file that was not
        text cannot be reverted; the result says so.

        Output: "Created <path> (N lines)." or "Rewrote <path> (N lines,
        +added/-removed).", or a note that the file already had exactly this content
        and nothing was written.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path", "content")) {
        string("path", "File path, absolute or relative to the project root.")
        string("content", "Complete file contents.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val content = args.str("content")
            ?: return ToolResult.error("'content' is required (pass \"\" for an empty file).")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)

        if (content.toByteArray().size > MAX_WRITE_BYTES) {
            return ToolResult.error(
                "Refusing to write ${content.length} characters in one call " +
                    "(limit ${MAX_WRITE_BYTES / 1024 / 1024} MB).",
            )
        }
        if (file.isDirectory) {
            return ToolResult.error("${ctx.display(file)} is a directory.")
        }

        val existed = file.exists()
        val previous = if (existed) readFileText(file) else null
        val before = (previous as? FileText.Text)?.value

        if (existed && before == content) {
            return ToolResult.ok("${ctx.display(file)} already has exactly this content; nothing written.")
        }

        return withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(content)

                // A file that existed but is not text, or could not be read, has
                // no faithful "before". Recording null would say it never
                // existed, and reverting would delete it: record nothing.
                val snapshotId = if (existed && before == null) null else snapshot(ctx, file, before, content)
                val diff = runCatching {
                    NativeCore.diff(
                        "unified",
                        jsonOf(
                            "path" to ctx.display(file),
                            "old" to (before ?: ""),
                            "new" to content,
                            "context" to 3,
                        ),
                    ).str("diff", "")
                }.getOrDefault("")

                val lineCount = content.lines().size - if (content.endsWith('\n')) 1 else 0
                val (added, removed) = lineStats(before ?: "", content)
                ToolResult(
                    content = when {
                        !existed -> "Created ${ctx.display(file)} ($lineCount lines)."
                        before == null ->
                            "Replaced ${ctx.display(file)} ($lineCount lines). Its previous content " +
                                (if (previous == FileText.NotText) "was not text" else "could not be read") +
                                ", so this cannot be reverted."
                        else -> "Rewrote ${ctx.display(file)} ($lineCount lines, +$added/-$removed)."
                    },
                    display = ToolDisplay.Diff(
                        path = ctx.display(file),
                        unified = diff,
                        addedLines = added,
                        removedLines = removed,
                        snapshotId = snapshotId,
                    ),
                    touchedPaths = listOf(file.absolutePath),
                )
            } catch (t: Throwable) {
                ToolResult.error("Could not write ${ctx.display(file)}: ${t.message}")
            }
        }
    }
}

/* ---------------------------------------------------------------- fs_edit */

class FsEditTool : Tool {
    override val name = "fs_edit"
    override val taskClass = TaskClass.INTERACTIVE // see FsWriteTool
    override val parallelSafe = false

    override val description = """
        Replace text in an existing file.

        When to use it: a change in one place, or to every occurrence of a string
        with `replaceAll`. For several changes to one file use fs_multi_edit, which
        applies all or none; to create a file or rewrite most of one use fs_write.

        Without `replaceAll`, `search` must occur exactly `expectedCount` times
        (default 1). If the count is wrong nothing changes and the error says how
        many were found, so add surrounding lines until `search` is unique. If the
        exact text is absent, matching retries ignoring trailing whitespace, then
        ignoring indentation, re-indenting `replace` to fit; only the matched text
        changes, and `replace` gets the file's line endings (CRLF stays CRLF). Copy
        `search` from a recent fs_read, without the line-number prefix. Files that
        are not UTF-8 text are refused.

        Output: "Edited <path>: N replacement(s), +added/-removed lines.", plus a
        note when a whitespace-tolerant match was used. On "not found", read the
        file again before retrying.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path", "search", "replace")) {
        string("path", "File path, absolute or relative to the project root.")
        string("search", "Exact text to find, including indentation.")
        string("replace", "Replacement text. Pass \"\" to delete the matched text.")
        integer("expectedCount", "How many occurrences you expect.", default = 1, minimum = 1)
        boolean("replaceAll", "Replace every occurrence, ignoring expectedCount.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val search = args.str("search") ?: return ToolResult.error("'search' is required.")
        // Required, not defaulted: a missing key deleted the matched text.
        val replacement = args.str("replace")
            ?: return ToolResult.error("'replace' is required; pass \"\" to delete the matched text.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)

        if (!file.isFile) {
            return ToolResult.error("${ctx.display(file)} is not an existing file.")
        }

        return withContext(Dispatchers.IO) {
            val before = when (val read = readFileText(file)) {
                is FileText.Text -> read.value
                else -> return@withContext refuseEdit(ctx, file, read)
            }

            try {
                val result = NativeCore.diff(
                    "replace",
                    jsonOf(
                        "path" to ctx.display(file),
                        "content" to before,
                        "search" to search,
                        "replace" to replacement,
                        "replaceAll" to args.bool("replaceAll", false),
                        "expectedCount" to args.int("expectedCount", 1),
                    ),
                )
                val after = result.str("content") ?: return@withContext ToolResult.error(
                    "The native edit returned no content.",
                )
                file.writeText(after)
                val snapshotId = snapshot(ctx, file, before, after)

                val strategy = result.str("strategy", "exact")
                val replacements = result.int("replacements", 1)
                // Older native builds sent snake_case keys; count it here then
                // rather than report "+0/-0".
                val (added, removed) = result.intOrNull("addedLines")
                    ?.let { it to result.int("removedLines") }
                    ?: lineStats(before, after)

                val note = when (strategy) {
                    "trailing_whitespace" ->
                        "\nMatched after normalising trailing whitespace — your `search` " +
                            "had whitespace the file does not."
                    "reindented" ->
                        "\nMatched ignoring indentation; the replacement was re-indented to " +
                            "the file's level. Your `search` had the wrong leading whitespace."
                    else -> ""
                }

                ToolResult(
                    content = "Edited ${ctx.display(file)}: $replacements replacement(s), " +
                        "+$added/-$removed lines.$note",
                    display = ToolDisplay.Diff(
                        path = ctx.display(file),
                        unified = result.str("diff", ""),
                        addedLines = added,
                        removedLines = removed,
                        snapshotId = snapshotId,
                    ),
                    touchedPaths = listOf(file.absolutePath),
                )
            } catch (e: NativeException) {
                // `refused` means found-but-wrong-count; `not_found` means absent.
                // Both are the model's to fix, and the native message already
                // says how.
                ToolResult.error("fs_edit on ${ctx.display(file)} failed: ${e.detail}")
            }
        }
    }
}

/* ---------------------------------------------------------- fs_multi_edit */

class FsMultiEditTool : Tool {
    override val name = "fs_multi_edit"
    override val taskClass = TaskClass.INTERACTIVE // see FsWriteTool
    override val parallelSafe = false

    override val description = """
        Apply several search-and-replace edits to one file, all or nothing.

        When to use it: two or more separate changes in the same file. For a single
        change use fs_edit; for changes in several files, make one call per file.

        Edits run in order, each on the result of the previous one, so a later
        `search` must match the text as earlier edits left it. Each `search` must
        occur exactly once unless that edit sets `replaceAll`, and matching
        tolerates whitespace the way fs_edit does. If any edit fails, nothing is
        written and the error names the edit by number.

        Output: "Applied N edit(s) to <path>." and one line per edit with its
        replacement count and match strategy (exact, trailing_whitespace or
        reindented), or "No net change" if the edits cancel out.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path", "edits")) {
        string("path", "File path, absolute or relative to the project root.")
        objectArray(
            "edits",
            "Replacements, applied in order.",
            required = listOf("search", "replace"),
        ) {
            string("search", "Exact text to find.")
            string("replace", "Replacement text.")
            boolean("replaceAll", "Replace every occurrence of this search.", default = false)
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val edits = args.objects("edits")
        if (edits.isEmpty()) return ToolResult.error("'edits' is empty.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.isFile) return ToolResult.error("${ctx.display(file)} is not an existing file.")

        return withContext(Dispatchers.IO) {
            val before = when (val read = readFileText(file)) {
                is FileText.Text -> read.value
                else -> return@withContext refuseEdit(ctx, file, read)
            }

            var current = before
            val applied = mutableListOf<String>()
            for ((index, edit) in edits.withIndex()) {
                val search = edit.str("search")
                    ?: return@withContext ToolResult.error("Edit ${index + 1} has no 'search'.")
                val replacement = edit.str("replace") ?: return@withContext ToolResult.error(
                    "Edit ${index + 1} has no 'replace'; pass \"\" to delete the matched text. Nothing was written.",
                )
                try {
                    val result = NativeCore.diff(
                        "replace",
                        jsonOf(
                            "path" to ctx.display(file),
                            "content" to current,
                            "search" to search,
                            "replace" to replacement,
                            "replaceAll" to edit.bool("replaceAll", false),
                            "expectedCount" to 1,
                        ),
                    )
                    current = result.str("content") ?: current
                    applied += "#${index + 1}: ${result.int("replacements")} replacement(s) " +
                        "(${result.str("strategy", "exact")})"
                } catch (e: NativeException) {
                    // Nothing has been written yet, so bailing out here is the
                    // atomicity guarantee.
                    return@withContext ToolResult.error(
                        "Edit ${index + 1} of ${edits.size} failed, so no changes were " +
                            "written to ${ctx.display(file)}: ${e.detail}",
                    )
                }
            }

            if (current == before) {
                return@withContext ToolResult.ok("No net change to ${ctx.display(file)}.")
            }

            try {
                file.writeText(current)
            } catch (t: Throwable) {
                return@withContext ToolResult.error("Could not write ${ctx.display(file)}: ${t.message}")
            }

            val snapshotId = snapshot(ctx, file, before, current)
            val diff = runCatching {
                NativeCore.diff(
                    "unified",
                    jsonOf("path" to ctx.display(file), "old" to before, "new" to current, "context" to 3),
                ).str("diff", "")
            }.getOrDefault("")
            val stats = runCatching {
                NativeCore.diff("stats", jsonOf("old" to before, "new" to current))
            }.getOrNull()

            ToolResult(
                content = "Applied ${edits.size} edit(s) to ${ctx.display(file)}.\n" +
                    applied.joinToString("\n"),
                display = ToolDisplay.Diff(
                    path = ctx.display(file),
                    unified = diff,
                    addedLines = stats?.int("added") ?: 0,
                    removedLines = stats?.int("removed") ?: 0,
                    snapshotId = snapshotId,
                ),
                touchedPaths = listOf(file.absolutePath),
            )
        }
    }
}

/* --------------------------------------------------------- fs_apply_patch */

class FsApplyPatchTool : Tool {
    override val name = "fs_apply_patch"
    override val taskClass = TaskClass.INTERACTIVE // see FsWriteTool
    override val parallelSafe = false

    override val description = """
        Apply a unified diff to one existing file.

        When to use it: you already have a diff, or the change is many hunks spread
        through one file. Otherwise prefer fs_edit or fs_multi_edit; to create a
        file use fs_write. File headers are ignored and every hunk is applied to
        `path`, so send one file's hunks per call.

        Each hunk needs an `@@ -N` header. Its context and `-` lines, as one block,
        must be found within `drift` lines of N and match the file apart from
        trailing whitespace. Context lines keep the file's own text, and added lines
        get its line endings. If any hunk fails to match, nothing is written.

        Output: "Applied patch to <path>: +added/-removed lines.", or an error
        naming the hunk's old line number and the range searched.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path", "patch")) {
        string("path", "File the patch applies to.")
        string("patch", "Unified diff. @@ headers required; ---/+++ headers optional.")
        integer("drift", "How far to search for each hunk, in lines.", default = 40, maximum = 400)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val patch = args.str("patch") ?: return ToolResult.error("'patch' is required.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.isFile) return ToolResult.error("${ctx.display(file)} is not an existing file.")

        return withContext(Dispatchers.IO) {
            val before = when (val read = readFileText(file)) {
                is FileText.Text -> read.value
                else -> return@withContext refuseEdit(ctx, file, read)
            }
            try {
                val result = NativeCore.diff(
                    "apply",
                    jsonOf(
                        "content" to before,
                        "patch" to patch,
                        "drift" to args.int("drift", 40),
                    ),
                )
                val after = result.str("content")
                    ?: return@withContext ToolResult.error("Patch produced no content.")
                file.writeText(after)
                val snapshotId = snapshot(ctx, file, before, after)
                // The file's own diff, not the model's patch: a hunk applied
                // within `drift` of its header sits at other lines than the
                // header says, so the patch's numbers would be wrong.
                val diff = runCatching {
                    NativeCore.diff(
                        "unified",
                        jsonOf("path" to ctx.display(file), "old" to before, "new" to after, "context" to 3),
                    ).str("diff", "")
                }.getOrDefault(patch)
                ToolResult(
                    content = "Applied patch to ${ctx.display(file)}: " +
                        "+${result.int("added")}/-${result.int("removed")} lines.",
                    display = ToolDisplay.Diff(
                        path = ctx.display(file),
                        unified = diff,
                        addedLines = result.int("added"),
                        removedLines = result.int("removed"),
                        snapshotId = snapshotId,
                    ),
                    touchedPaths = listOf(file.absolutePath),
                )
            } catch (e: NativeException) {
                ToolResult.error("Patch did not apply to ${ctx.display(file)}: ${e.detail}")
            }
        }
    }
}

/* ---------------------------------------------------------------- fs_list */

class FsListTool : Tool {
    override val name = "fs_list"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        List the entries of a directory, optionally a few levels deep.

        When to use it: to see what one directory holds, with file sizes. To find
        files by name or extension anywhere, use fs_glob; for the shape of the
        whole project, project_tree; for a single path, fs_stat.

        Dotfiles are skipped unless `includeHidden`, as are paths matched by .ignore
        files, or by .gitignore where a .git directory exists. Build output and
        dependency directories (build, node_modules, target, dist, .gradle, .venv
        and the like) are always skipped; to look inside one, pass it as `path`.
        Listings stop at 2000 entries.

        Output: the directory, the entry count and "truncated" if cut, then one line
        per entry relative to it, each under its own directory, directories first
        with a trailing "/", files with their size. "is empty" can also mean
        everything was filtered out.
    """.trimIndent()

    override val parameters = Schema.obj {
        string("path", "Directory to list. Defaults to the project root.")
        integer("maxDepth", "How deep to recurse. 1 lists only this directory.", default = 1, maximum = 8)
        boolean("includeHidden", "Include dotfiles.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: "."
        val dir = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!dir.isDirectory) return ToolResult.error("${ctx.display(dir)} is not a directory.")

        return try {
            val result = NativeCore.search(
                "tree",
                jsonOf(
                    "path" to dir.absolutePath,
                    "maxDepth" to args.int("maxDepth", 1),
                    "includeHidden" to args.bool("includeHidden", false),
                    "maxEntries" to 2000,
                ),
            )
            val entries = result.objects("entries")
            if (entries.isEmpty()) return ToolResult.ok("${ctx.display(dir)} is empty.")

            val body = entries.joinToString("\n") { entry ->
                val isDir = entry.bool("dir")
                val marker = if (isDir) "/" else ""
                val size = if (isDir) "" else "  ${formatBytes(entry.long("size"))}"
                "  ${entry.str("path", "")}$marker$size"
            }
            ToolResult.ok(
                content = "${ctx.display(dir)} (${entries.size} entries" +
                    (if (result.bool("truncated")) ", truncated" else "") + ")\n$body",
                display = ToolDisplay.Listing(
                    root = ctx.display(dir),
                    entries = entries.map {
                        ToolDisplay.Listing.Entry(
                            it.str("path", ""),
                            it.bool("dir"),
                            it.long("size"),
                        )
                    },
                ),
            )
        } catch (e: NativeException) {
            ToolResult.error("Could not list ${ctx.display(dir)}: ${e.detail}")
        }
    }
}

/* ---------------------------------------------------------------- fs_glob */

class FsGlobTool : Tool {
    override val name = "fs_glob"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Find files by glob pattern, newest first.

        When to use it: you know part of a file name or extension. To search inside
        files use grep; for where a symbol is defined, symbol_search; for what one
        directory holds, fs_list.

        The pattern is tried against each file's path relative to `path` and against
        its bare name, and `*` crosses `/`, so `*.kt` or `Foo.kt` finds files at any
        depth. Only files are returned. Dotfiles, ignore-file matches and build or
        dependency directories (build, node_modules, .gradle, etc.) are skipped;
        pass such a directory as `path` to search inside it. At most `limit` files
        are collected, in directory order, before sorting, so a truncated result is
        not the newest overall: narrow the pattern.

        Output: the match count, "(truncated)" if cut, then one path per line,
        relative to `path`.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("pattern")) {
        string("pattern", "Glob pattern, matched against paths relative to the search root.")
        string("path", "Directory to search under. Defaults to the project root.")
        integer("limit", "Maximum results.", default = 200, maximum = 1000)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult.error("'pattern' is required.")
        val root = ctx.resolve(args.str("path") ?: ".") ?: return outsideSandbox(ctx, args.str("path") ?: ".")

        return try {
            val result = NativeCore.search(
                "glob",
                jsonOf(
                    "path" to root.absolutePath,
                    "pattern" to pattern,
                    "limit" to args.int("limit", 200),
                ),
            )
            val files = result.objects("files")
            if (files.isEmpty()) {
                return ToolResult.ok(
                    "No files match '$pattern' under ${ctx.display(root)}. Dotfiles, ignore-file " +
                        "matches and build or dependency directories are skipped; pass such a " +
                        "directory as `path` to search inside it.",
                )
            }
            ToolResult.ok(
                "${files.size} file(s) matching '$pattern'" +
                    (if (result.bool("truncated")) " (truncated)" else "") + ":\n" +
                    files.joinToString("\n") { "  ${it.str("path", "")}" },
            )
        } catch (e: NativeException) {
            ToolResult.error("fs_glob failed: ${e.detail}")
        }
    }
}

/* ---------------------------------------------------------------- fs_stat */

class FsStatTool : Tool {
    override val name = "fs_stat"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Check whether a path exists, and get its type, size, modification time and
        permissions.

        When to use it: to check a path before acting on it, such as whether a file
        exists or how big it is before reading it; this is cheaper than fs_read or
        fs_list. For what a directory contains use fs_list.

        A missing path is a normal result, not an error. For a directory, the size
        shown is not the total of its contents, and the entry count covers only its
        immediate children, hidden ones included.

        Output: one line: path, kind (file, directory or special), size, entry count
        for a directory, modification time in UTC, and readable/writable/executable
        flags.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path")) {
        string("path", "Path to inspect.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.exists()) return ToolResult.ok("${ctx.display(file)}: does not exist")

        val kind = when {
            file.isDirectory -> "directory"
            file.isFile -> "file"
            else -> "special"
        }
        val children = if (file.isDirectory) ", ${file.list()?.size ?: 0} entries" else ""
        return ToolResult.ok(
            "${ctx.display(file)}: $kind, ${formatBytes(file.length())}$children, " +
                "modified ${java.time.Instant.ofEpochMilli(file.lastModified())}, " +
                "readable=${file.canRead()} writable=${file.canWrite()} executable=${file.canExecute()}",
        )
    }
}

/* ---------------------------------------------------------------- fs_move */

class FsMoveTool : Tool {
    override val name = "fs_move"
    override val taskClass = TaskClass.INTERACTIVE // see FsWriteTool
    override val parallelSafe = false

    override val description = """
        Move or rename a file or directory.

        When to use it: to rename or relocate something, rather than writing a copy
        with fs_write and deleting the original.

        `to` is the complete new path, not a directory to move into; missing parent
        directories are created. If `to` exists the call fails unless `overwrite` is
        true, which deletes the existing destination first, recursively if it is a
        directory. Nothing is snapshotted, so revert_edit cannot undo a move or
        restore what it overwrote. References are not updated: afterwards, grep for
        the old path or name and fix what still points at it.

        Output: "Moved <from> -> <to>", with "(copied)" when the rename had to fall
        back to copying and deleting.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("from", "to")) {
        string("from", "Existing path.")
        string("to", "Complete new path, including the name; not a directory to move into.")
        boolean("overwrite", "Replace the destination if it exists.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val fromPath = args.str("from") ?: return ToolResult.error("'from' is required.")
        val toPath = args.str("to") ?: return ToolResult.error("'to' is required.")
        val from = ctx.resolve(fromPath) ?: return outsideSandbox(ctx, fromPath)
        val to = ctx.resolve(toPath) ?: return outsideSandbox(ctx, toPath)

        if (!from.exists()) return ToolResult.error("${ctx.display(from)} does not exist.")
        // Both paths are canonical here. Without these checks, overwrite:true
        // on the same path, or onto a directory that contains the source,
        // deleted the source before anything was moved.
        if (from == to) {
            return ToolResult.error("'from' and 'to' are the same path: ${ctx.display(from)}.")
        }
        if (from.path.startsWith(to.path + File.separator)) {
            return ToolResult.error(
                "${ctx.display(to)} contains ${ctx.display(from)}; replacing it would delete " +
                    "what you are moving.",
            )
        }
        if (from.isDirectory && to.path.startsWith(from.path + File.separator)) {
            return ToolResult.error("Cannot move ${ctx.display(from)} into itself.")
        }
        if (to.exists() && !args.bool("overwrite", false)) {
            return ToolResult.error(
                "${ctx.display(to)} already exists. Pass overwrite: true to replace it.",
            )
        }

        return withContext(Dispatchers.IO) {
            to.parentFile?.mkdirs()
            if (to.exists()) to.deleteRecursively()
            if (from.renameTo(to)) {
                ToolResult(
                    content = "Moved ${ctx.display(from)} -> ${ctx.display(to)}",
                    touchedPaths = listOf(from.absolutePath, to.absolutePath),
                )
            } else {
                // renameTo fails across filesystems; copy and delete instead.
                runCatching {
                    from.copyRecursively(to, overwrite = true)
                    check(from.deleteRecursively()) {
                        "copied to ${ctx.display(to)}, but the original could not be fully removed"
                    }
                }.fold(
                    onSuccess = {
                        ToolResult(
                            content = "Moved ${ctx.display(from)} -> ${ctx.display(to)} (copied)",
                            touchedPaths = listOf(from.absolutePath, to.absolutePath),
                        )
                    },
                    onFailure = { ToolResult.error("Move failed: ${it.message}") },
                )
            }
        }
    }
}

/* -------------------------------------------------------------- fs_delete */

class FsDeleteTool : Tool {
    override val name = "fs_delete"
    override val destructive = true
    override val taskClass = TaskClass.INTERACTIVE // see FsWriteTool
    override val parallelSafe = false

    override val description = """
        Delete a file, or a directory with `recursive: true`.

        When to use it: removing files or directories that should no longer exist.
        To set something aside so it can be brought back, fs_move it instead.

        Whether the user is asked first is the approval mode's. A missing path is
        reported as nothing to do. A text file is snapshotted first when small enough, so
        revert_edit can restore it; binary or unreadable files are not. Directories
        are never snapshotted: a recursive delete cannot be undone. A non-empty
        directory without `recursive` is refused, with its entry count.

        Output: "Deleted <path>", marked "(snapshotted; revertible)" when it can be
        restored and otherwise saying why not, or "Deleted directory <path> (N
        entries)" with a reminder that it cannot be reverted.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path")) {
        string("path", "Path to delete.")
        boolean("recursive", "Required to delete a non-empty directory.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.exists()) return ToolResult.ok("${ctx.display(file)} does not exist; nothing to do.")

        // Guard the environment's own roots: deleting $HOME or $PREFIX would
        // destroy the toolchain or every project in one call.
        if (ctx.layout.allRoots.any { runCatching { it.canonicalPath == file.canonicalPath }.getOrDefault(false) }) {
            return ToolResult.refused(
                "${ctx.display(file)} is one of the environment's root directories. " +
                    "Refusing. Delete specific files or subdirectories instead.",
            )
        }

        return withContext(Dispatchers.IO) {
            if (file.isDirectory) {
                val entries = file.list()?.size ?: 0
                if (entries > 0 && !args.bool("recursive", false)) {
                    return@withContext ToolResult.error(
                        "${ctx.display(file)} is a directory with $entries entries. " +
                            "Pass recursive: true if you really mean to delete all of it.",
                    )
                }
                if (file.deleteRecursively()) {
                    ToolResult(
                        content = "Deleted directory ${ctx.display(file)} ($entries entries). " +
                            "This was not snapshotted and cannot be reverted.",
                        touchedPaths = listOf(file.absolutePath),
                    )
                } else {
                    ToolResult.error("Could not delete ${ctx.display(file)}.")
                }
            } else {
                // Only text read back exactly can be restored. A binary file
                // used to be snapshotted as lossy text, so reverting wrote back
                // a corrupted copy; an unreadable one as "absent", so reverting
                // had nothing to restore.
                val previous = if (file.length() <= MAX_SNAPSHOT_BYTES * 2L) readFileText(file) else null
                val before = (previous as? FileText.Text)?.value
                val snapshotId = before?.let { snapshot(ctx, file, it, null) }
                if (file.delete()) {
                    val note = when {
                        snapshotId != null -> " (snapshotted; revertible)."
                        previous == FileText.NotText -> ". It was not text, so it was not snapshotted and cannot be reverted."
                        previous is FileText.Unreadable -> ". It could not be read, so it was not snapshotted and cannot be reverted."
                        else -> ". It was too large to snapshot and cannot be reverted."
                    }
                    ToolResult(
                        content = "Deleted ${ctx.display(file)}$note",
                        touchedPaths = listOf(file.absolutePath),
                    )
                } else {
                    ToolResult.error("Could not delete ${ctx.display(file)}.")
                }
            }
        }
    }
}

/** Lines added and removed going from [before] to [after], as a diff counts them. */
internal fun lineStats(before: String, after: String): Pair<Int, Int> = runCatching {
    val stats = NativeCore.diff("stats", jsonOf("old" to before, "new" to after))
    stats.int("added") to stats.int("removed")
}.getOrElse {
    // No native core: a multiset difference of lines, which is what a diff
    // reports for everything but moved lines.
    val counts = HashMap<String, Int>()
    before.lines().forEach { counts.merge(it, 1, Int::plus) }
    var added = 0
    after.lines().forEach { line ->
        val left = counts[line] ?: 0
        if (left > 0) counts[line] = left - 1 else added++
    }
    added to counts.values.sum()
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
