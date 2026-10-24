package dev.apkcli.core.agent.tools

import dev.apkcli.core.agent.MemoryScopes
import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.boolean
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.str
import kotlinx.serialization.json.JsonObject

/**
 * Memory and history tools.
 *
 * These are what make a compacted session recoverable rather than amnesiac.
 * When the window fills, older turns are summarised into a digest and removed
 * from the prompt — but they are never deleted, and they stay indexed for
 * full-text search. `conversation_search` and `conversation_read` are how the
 * agent gets a specific detail back.
 */

/** Memory scope key for the current project. */
private fun workspaceScope(ctx: ToolContext): String = MemoryScopes.project(ctx.workspace)

private fun scopeOf(args: JsonObject, ctx: ToolContext): String =
    if (args.bool("global", false)) MemoryScopes.GLOBAL else workspaceScope(ctx)

/* ----------------------------------------------------- conversation_search */

class ConversationSearchTool : Tool {
    override val name = "conversation_search"
    override val readOnly = true
    override val parallelSafe = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Full-text search over this session's history, including turns compacted out of
        your context.

        When to use it: before asking the user to repeat something, and before
        concluding a topic never came up. After a compaction the exact wording, paths
        and errors from earlier turns are still here. It does not search remembered
        facts; memory_read has those.

        Any query word can match, best matches first, so pick distinctive words; there
        is no phrase search. Indexed: what was said, reasoning, tool arguments, and only
        the first 400 characters of each tool result. `allSessions` searches every saved
        session in every project, but conversation_read opens only this session's turns.

        Output: per hit, its turn number, role, whether it was compacted, and a snippet
        with matches marked <<like this>>. Open a hit with
        conversation_read(fromSeq: <turn>).
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("query")) {
        string("query", "Words to search for. Terms are OR-ed; exact phrases are not supported.")
        integer("limit", "Maximum hits.", default = 10, maximum = 50)
        boolean(
            "archivedOnly",
            "Only search turns that were compacted away, skipping what you can already see.",
            default = false,
        )
        boolean("allSessions", "Search every saved session, in all projects, not just this one.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.str("query")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.error("'query' is required.")

        val hits = ctx.store.search(
            query = query,
            sessionId = if (args.bool("allSessions", false)) null else ctx.sessionId,
            limit = args.int("limit", 10),
            archivedOnly = args.bool("archivedOnly", false),
        )

        if (hits.isEmpty()) {
            return ToolResult.ok(
                "Nothing in the conversation history matches \"$query\". It genuinely has " +
                    "not come up — you can ask the user about it.",
            )
        }

        return ToolResult.ok(
            "${hits.size} match(es) for \"$query\":\n\n" +
                hits.joinToString("\n\n") { hit ->
                    buildString {
                        append("[turn ${hit.seq}, ${hit.role}")
                        if (hit.archived) append(", compacted")
                        appendLine("]")
                        append("  ")
                        append(hit.snippet.replace("\n", "\n  "))
                    }
                } +
                "\n\nRead the turns around one with conversation_read(fromSeq: <turn>).",
        )
    }
}

/* ------------------------------------------------------- conversation_read */

class ConversationReadTool : Tool {
    override val name = "conversation_read"
    override val readOnly = true
    override val parallelSafe = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Read stored history back: this session's turns, compacted ones included, or a
        blob holding output too large to show in full.

        When to use it: a result says its full output is stored under a blob id — page
        through it here instead of re-running the command or re-fetching the page; or
        you need the turns around a conversation_search hit.

        `fromSeq` reads from that turn number to `toSeq`, default fromSeq + 4, in this
        session only. Turns come back as plain text with their tool calls and results
        whole; a range too long for one result is stored as a blob like any long output,
        and the result says so. `blobId` reads `length` bytes (default 20000) from
        `offset`.

        Output: each turn headed with its number and role; for a blob, a header with
        the byte range, the total size and where to continue, then the text.
    """.trimIndent()

    override val parameters = Schema.obj {
        string("blobId", "Blob id from a truncated tool result.")
        integer("fromSeq", "First turn of a range to read, e.g. the turn of a conversation_search hit.")
        integer("toSeq", "Last turn of a range to read.")
        integer("offset", "Byte offset into a blob.", default = 0)
        integer("length", "Bytes to read from a blob.", default = 20_000, maximum = 100_000)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        args.str("blobId")?.let { blobId ->
            return runCatching {
                val page = ctx.store.readBlob(
                    id = blobId,
                    offset = args.int("offset", 0),
                    length = args.int("length", 20_000),
                )
                val position = "bytes ${page.offset}-${page.offset + page.text.length} of ${page.size}"
                ToolResult.ok(
                    buildString {
                        appendLine("$blobId — $position${if (page.eof) " (end)" else ""}")
                        if (!page.eof) {
                            appendLine(
                                "Continue with offset: ${page.offset + page.text.length}.",
                            )
                        }
                        appendLine()
                        append(page.text)
                    },
                )
            }.getOrElse {
                ToolResult.error(
                    "No blob '$blobId'. Blobs are pruned when storage is reclaimed; re-run " +
                        "the command if you still need its output.",
                )
            }
        }

        val fromSeq = args.int("fromSeq", -1)
        val toSeq = args.int("toSeq", -1)

        // There used to be a messageId mode. It looked the id up through the
        // full-text index, which holds message text, not ids, so it returned
        // whichever turn happened to mention the id. Turns are addressed by
        // number, and a search hit gives the number.
        if (fromSeq < 0) {
            return ToolResult.error(
                if (args.str("messageId") != null) {
                    "conversation_read reads turns by number: pass fromSeq, the turn shown " +
                        "in the conversation_search hit."
                } else {
                    "Pass blobId, or fromSeq (and optionally toSeq)."
                },
            )
        }

        val messages = ctx.store.range(
            ctx.sessionId,
            fromSeq.toLong(),
            (if (toSeq >= 0) toSeq else fromSeq + 4).toLong(),
        )

        if (messages.isEmpty()) return ToolResult.ok("No turns in that range.")

        // Whole: flatten() is the search index's text, which keeps 400
        // characters of each tool result, and handed out here that cut every
        // archived output short without saying so. Too long a range is the
        // loop's to store as a blob, with a note, like any long result.
        return ToolResult.ok(
            messages.joinToString("\n\n---\n\n") { message ->
                buildString {
                    appendLine("[turn ${message.seq}, ${message.role.name.lowercase()}]")
                    append(message.promptText())
                }
            },
        )
    }
}

/* ------------------------------------------------------------ memory_read */

class MemoryReadTool : Tool {
    override val name = "memory_read"
    override val readOnly = true
    override val parallelSafe = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Read facts saved with memory_write: this project's, or with `global: true` the
        ones shared by every project.

        When to use it: rarely. Both scopes are already in your system prompt on every
        request, under "Project instructions and memory", so reading them again is
        usually wasted — except when the prompt says some were left out for length, or
        to check one exact key. For what was said in a conversation, use
        conversation_search.

        Output: `key: value` for one key, every entry in the scope sorted by key, or a
        line saying nothing is remembered.
    """.trimIndent()

    override val parameters = Schema.obj {
        string("key", "Read one specific key. Omit to list everything.")
        boolean("global", "Read the cross-project scope instead of this project's.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val global = args.bool("global", false)
        val scope = scopeOf(args, ctx)
        val where = if (global) "for all projects" else "about this project"

        args.str("key")?.let { key ->
            val value = ctx.store.memoryGet(scope, key)
            return ToolResult.ok(
                if (value == null) "Nothing remembered under '$key'." else "$key: $value",
            )
        }

        val entries = ctx.store.memoryList(scope)
        return ToolResult.ok(
            if (entries.isEmpty()) {
                "Nothing remembered $where yet."
            } else {
                "Remembered $where:\n" +
                    entries.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
            },
        )
    }
}

/* ----------------------------------------------------------- memory_write */

class MemoryWriteTool : Tool {
    override val name = "memory_write"
    // One row written: not work for the throttled background pool (FsWriteTool).
    override val taskClass = TaskClass.INTERACTIVE
    override val supervised = false

    override val description = """
        Save a fact for every later request and session in this project, or with
        `global: true` for all projects.

        When to use it: what will still be true next week — how to build the project, a
        convention the user insists on, a non-obvious constraint, where something
        surprising lives, a gotcha that took real effort to find, credentials' location
        (never the secret itself). Use `global: true` for what holds everywhere: the
        user's language, how they like to work, their device setup. Also when the user
        asks you to remember something. Not task state, which belongs in task_list, and
        not what the code already says plainly; conversation_search finds details of
        past conversations.

        Both scopes go into your system prompt on every request, so keep keys short and
        values to a sentence or two; values over 500 characters are refused. Longer
        project notes belong in AGENTS.md at the project root, which is loaded into the
        prompt too (its first 32 KB). Writing an existing key replaces it. No approval is
        asked; the user sees and edits memory in the app.

        Output: a one-line confirmation.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("key", "value")) {
        string("key", "Short identifier, e.g. build-command, test-convention.")
        string("value", "The fact. One or two sentences.")
        boolean("global", "Remember across all projects instead of just this one.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val key = args.str("key")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.error("'key' is required.")
        val value = args.str("value")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.error("'value' is required. To forget a key, use memory_forget.")

        if (value.length > MAX_VALUE_CHARS) {
            return ToolResult.error(
                "That value is ${value.length} characters. Memory is injected into every " +
                    "future prompt for this project, so keep entries under $MAX_VALUE_CHARS. " +
                    "Put long content in a file instead and remember the path.",
            )
        }

        ctx.store.memorySet(scopeOf(args, ctx), key, value)
        return ToolResult.ok(
            if (args.bool("global", false)) "Remembered '$key' for all projects." else "Remembered '$key' for this project.",
        )
    }

    private companion object {
        const val MAX_VALUE_CHARS = 500
    }
}

class MemoryForgetTool : Tool {
    override val name = "memory_forget"
    override val taskClass = TaskClass.INTERACTIVE
    override val supervised = false

    override val description =
        "Delete a remembered fact.\n\n" +
            "When to use it: a remembered fact has become wrong or no longer applies. A " +
            "stale entry in this project's memory is put in your prompt on every request " +
            "and reads as true. To correct a fact rather than drop it, memory_write the " +
            "same key; that replaces the value.\n\n" +
            "Pass the exact key; `global: true` targets the cross-project scope.\n\n" +
            "Output: a one-line confirmation, the same whether or not the key existed, " +
            "so check the exact key with memory_read when unsure."

    override val parameters = Schema.obj(required = listOf("key")) {
        string("key", "Key to forget.")
        boolean("global", "Target the cross-project scope.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val key = args.str("key") ?: return ToolResult.error("'key' is required.")
        ctx.store.memoryDelete(scopeOf(args, ctx), key)
        return ToolResult.ok("Forgot '$key'.")
    }
}
