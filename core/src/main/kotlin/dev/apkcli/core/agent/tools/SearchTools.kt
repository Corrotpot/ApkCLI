package dev.apkcli.core.agent.tools

import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.boolean
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Schema.stringArray
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.nativebridge.NativeException
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.objects
import dev.apkcli.core.util.str
import dev.apkcli.core.util.strings
import kotlinx.serialization.json.JsonObject

/**
 * Search and navigation.
 *
 * All of these run on ripgrep's engine linked into the native core, not on a
 * `rg` binary. That matters on the Play-compliant build, where a downloaded
 * binary could not be executed at all, and it means no process spawn per
 * search.
 */

/* -------------------------------------------------------------------- grep */

class GrepTool : Tool {
    override val name = "grep"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Search file contents by regex, with context around each match.

        When to use it: finding where text appears. For a definition by name use
        symbol_search; for files by name, fs_glob; for one file's declarations,
        outline. Prefer it to shell grep.

        Case is ignored unless `caseSensitive`. Rust regex: no lookaround or
        backreferences; pass `literal: true` for text with regex characters. A match
        stays on one line unless `multiline`, which allows `\n` in the pattern and
        lets `.` cross lines, at the cost of reading each file whole. Skipped:
        dotfiles unless `includeHidden`, ignore-file matches (.gitignore only where a
        .git directory exists), files over 8 MB, and build or dependency directories
        (build, node_modules, .gradle and the like); pass one as `path` to search
        it. At most 40 matches per file.

        Output: a count line (marked truncated if cut), then each file's path,
        relative to `path`, and its lines: "N: text" for matches, "N- text" for
        context.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("query")) {
        string("query", "Regex, or a literal string when literal is true.")
        string("path", "Directory or file to search. Defaults to the project root.")
        boolean("literal", "Treat query as a literal string, not a regex.", default = false)
        boolean("caseSensitive", "Force case sensitivity.", default = false)
        boolean("wholeWord", "Match whole words only.", default = false)
        boolean(
            "multiline",
            "Let a match span lines: \\n allowed in the pattern, and . matches it.",
            default = false,
        )
        stringArray("include", "Globs to restrict the search to, e.g. [\"*.kt\", \"src/**\"].")
        stringArray("exclude", "Globs to skip.")
        integer("contextLines", "Lines of context around each match.", default = 2, maximum = 20)
        integer("maxResults", "Total match limit.", default = 80, maximum = 1000)
        boolean("includeHidden", "Search dotfiles and dot-directories.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.str("query") ?: return ToolResult.error("'query' is required.")
        val path = args.str("path") ?: "."
        val root = ctx.resolve(path) ?: return outsideSandbox(ctx, path)

        return try {
            val result = NativeCore.search(
                "grep",
                jsonOf(
                    "query" to query,
                    "path" to root.absolutePath,
                    "literal" to args.bool("literal", false),
                    "caseSensitive" to args.bool("caseSensitive", false),
                    "wholeWord" to args.bool("wholeWord", false),
                    "multiline" to args.bool("multiline", false),
                    "include" to args.strings("include").takeIf { it.isNotEmpty() },
                    "exclude" to args.strings("exclude").takeIf { it.isNotEmpty() },
                    "contextLines" to args.int("contextLines", 2),
                    "maxResults" to args.int("maxResults", 80),
                    "includeHidden" to args.bool("includeHidden", false),
                ),
            )

            val matches = result.objects("matches")
            val scanned = result.int("filesScanned")
            if (matches.isEmpty()) {
                return ToolResult.ok(
                    "No matches for `$query` in ${ctx.display(root)} ($scanned files searched). " +
                        "If you expected hits: dotfiles need includeHidden, ignore files apply, " +
                        "and build or dependency directories (node_modules, target, build, …) " +
                        "are skipped unless you pass one as `path`.",
                )
            }

            val body = buildString {
                var lastPath: String? = null
                for (match in matches) {
                    val matchPath = match.str("path", "")
                    if (matchPath != lastPath) {
                        if (lastPath != null) appendLine()
                        appendLine("$matchPath:")
                        lastPath = matchPath
                    }
                    for (before in match.objects("before")) {
                        appendLine("  ${before.int("line").toString().padStart(5)}- ${before.str("text", "")}")
                    }
                    // A multiline match covers several lines: number each one.
                    match.str("text", "").split('\n').forEachIndexed { offset, text ->
                        appendLine("  ${(match.int("line") + offset).toString().padStart(5)}: $text")
                    }
                    for (after in match.objects("after")) {
                        appendLine("  ${after.int("line").toString().padStart(5)}- ${after.str("text", "")}")
                    }
                }
            }

            val header = "${matches.size} match(es) in ${result.int("filesWithMatches")} file(s), " +
                "$scanned searched" + if (result.bool("truncated")) " — truncated, narrow the query" else ""

            ToolResult.ok(
                content = "$header\n\n$body",
                display = ToolDisplay.Matches(
                    query = query,
                    hits = matches.map {
                        ToolDisplay.Matches.Hit(it.str("path", ""), it.int("line"), it.str("text", ""))
                    },
                    filesScanned = scanned,
                    truncated = result.bool("truncated"),
                ),
            )
        } catch (e: NativeException) {
            ToolResult.error(
                "grep failed: ${e.detail}" +
                    when {
                        e.kind != NativeException.Kind.INVALID -> ""
                        "\\n" in query && !args.bool("multiline", false) ->
                            "\nA pattern containing \\n needs multiline: true."
                        else ->
                            "\nRust regex has no lookaround or backreferences. Pass literal: true " +
                                "to search for the text as-is."
                    },
            )
        }
    }
}

/* ----------------------------------------------------------- symbol_search */

class SymbolSearchTool : Tool {
    override val name = "symbol_search"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Find where a name is declared: lines where it follows a declaration keyword.

        When to use it: you know a name and want its definition, not every use.
        For uses or other text use grep; for everything one file declares, outline;
        for files by name, fs_glob.

        The name must follow a keyword such as class, fun, def, fn, func, struct,
        interface, type, val, var, let, const, public, private or static on the
        same line. Matching is case-sensitive and a prefix counts: `parse` also
        finds `parseArgs`. Declarations with no such keyword are missed (a bare
        `function foo`, `int foo()` in C or Java, shell functions, SQL), and
        comments or lines like `val x: Foo` can match Foo too. It skips what grep
        skips.

        Output: "Definitions of `name`:" and one "path:line  text" per hit, paths
        relative to `path`.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("name")) {
        string("name", "Symbol name. Exact or a prefix.")
        string("path", "Directory to search. Defaults to the project root.")
        integer("limit", "Maximum definitions to return.", default = 40, maximum = 200)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val symbol = args.str("name") ?: return ToolResult.error("'name' is required.")
        val path = args.str("path") ?: "."
        val root = ctx.resolve(path) ?: return outsideSandbox(ctx, path)

        // Declaration keywords across the supported languages, followed by the
        // name. Narrower than a bare name search and far cheaper than parsing
        // every file: this is one regex pass over the tree.
        val declarationPattern =
            "(?:fun|class|object|interface|def|fn|struct|enum|trait|impl|type|func|" +
                "const|val|var|let|protocol|extension|module|record|public|private|" +
                "static|async)\\s+[\\w<>\\[\\],.\\s*&:]*\\b" + rustRegexEscape(symbol)

        return try {
            val result = NativeCore.search(
                "grep",
                jsonOf(
                    "query" to declarationPattern,
                    "path" to root.absolutePath,
                    "caseSensitive" to true,
                    "contextLines" to 0,
                    "maxResults" to args.int("limit", 40),
                ),
            )
            val matches = result.objects("matches")
            if (matches.isEmpty()) {
                return ToolResult.ok(
                    "No definition of `$symbol` found. It may be declared in a form this " +
                        "tool does not recognise, imported from a dependency, or generated. " +
                        "Try grep for the bare name.",
                )
            }
            ToolResult.ok(
                "Definitions of `$symbol`:\n" +
                    matches.joinToString("\n") {
                        "  ${it.str("path", "")}:${it.int("line")}  ${it.str("text", "").trim()}"
                    },
                display = ToolDisplay.Matches(
                    query = symbol,
                    hits = matches.map {
                        ToolDisplay.Matches.Hit(it.str("path", ""), it.int("line"), it.str("text", ""))
                    },
                    filesScanned = result.int("filesScanned"),
                    truncated = result.bool("truncated"),
                ),
            )
        } catch (e: NativeException) {
            ToolResult.error("symbol_search failed: ${e.detail}")
        }
    }
}

/**
 * Escape [text] for the Rust `regex` crate the search engine runs.
 *
 * Not `Regex.escape`: on the JVM that is `Pattern.quote`, which wraps the text
 * in `\Q…\E`, and Rust's regex has no such syntax — every symbol_search came
 * back "bad pattern". This is the crate's own meta-character set.
 */
internal fun rustRegexEscape(text: String): String = buildString(text.length + 8) {
    for (ch in text) {
        if (ch in RUST_REGEX_META) append('\\')
        append(ch)
    }
}

private const val RUST_REGEX_META = "\\.+*?()|[]{}^$#&-~"

/* ----------------------------------------------------------------- outline */

class OutlineTool : Tool {
    override val name = "outline"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        List what one file declares, such as functions and classes, with line
        numbers.

        When to use it: before reading a large file, to find the lines you need and
        read only those with fs_read. To find where a name is defined across the
        project use symbol_search; for text inside files, grep.

        Works by extension for Kotlin, Java, Rust, Python, JS/TS, Go, C/C++, Swift,
        Ruby, PHP, C#, Dart, Lua, shell and SQL, and lists Markdown headings; other
        files get a note instead. Matching is line by line, so a declaration split
        over lines, or in a form the patterns miss such as a Java method with no
        modifier, can be absent.

        Output: "<path> — N symbol(s):", then one line per symbol with its line
        number, kind (fun, class, method, heading...) and name, indented to follow
        the source's nesting.
    """.trimIndent()

    override val parameters = Schema.obj(required = listOf("path")) {
        string("path", "File to outline.")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.error("'path' is required.")
        val file = ctx.resolve(path) ?: return outsideSandbox(ctx, path)
        if (!file.isFile) return ToolResult.error("${ctx.display(file)} is not a file.")

        return try {
            val result = NativeCore.search("outline", jsonOf("path" to file.absolutePath))
            val symbols = result.objects("symbols")
            if (symbols.isEmpty()) {
                val unsupported = result.str("unsupportedExtension")
                return ToolResult.ok(
                    if (!unsupported.isNullOrEmpty()) {
                        "No outline support for .$unsupported files. Use grep or read the file."
                    } else {
                        "No symbols found in ${ctx.display(file)}."
                    },
                )
            }
            ToolResult.ok(
                "${ctx.display(file)} — ${symbols.size} symbol(s):\n" +
                    symbols.joinToString("\n") { symbol ->
                        val indent = " ".repeat((symbol.int("indent") / 2).coerceAtMost(8))
                        "  ${symbol.int("line").toString().padStart(5)}  $indent" +
                            "${symbol.str("kind", "")} ${symbol.str("name", "")}"
                    },
            )
        } catch (e: NativeException) {
            ToolResult.error("outline failed: ${e.detail}")
        }
    }
}

/* ------------------------------------------------------------ project_tree */

class ProjectTreeTool : Tool {
    override val name = "project_tree"
    override val readOnly = true
    override val taskClass = TaskClass.INTERACTIVE

    override val description = """
        Show the project's directories and files, a few levels deep.

        When to use it: orientation in an unfamiliar project, to see where the
        source lives and which build files exist. It always starts at the project
        root; for one directory use fs_list, to find files by name fs_glob, to find
        text grep.

        Dotfiles, ignore-file matches and build or dependency directories (build,
        node_modules, .gradle and the like) are left out. The walk stops at
        `maxEntries`, so a truncated tree can lack whole directories: lower
        `maxDepth`, raise `maxEntries`, or pass `dirsOnly` for just the layout.

        Output: the root, "(truncated)" if cut, then one name per line, indented two
        spaces per level, each entry under its own directory, directories first and
        ending in "/".
    """.trimIndent()

    override val parameters = Schema.obj {
        integer("maxDepth", "How deep to go. 1 shows only the root's own entries.", default = 3, maximum = 8)
        integer("maxEntries", "Entry limit.", default = 400, maximum = 3000)
        boolean("dirsOnly", "Show only directories; files do not count toward maxEntries.", default = false)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return try {
            val result = NativeCore.search(
                "tree",
                jsonOf(
                    "path" to ctx.workspace.absolutePath,
                    "maxDepth" to args.int("maxDepth", 3),
                    "maxEntries" to args.int("maxEntries", 400),
                    "dirsOnly" to args.bool("dirsOnly", false),
                ),
            )
            val entries = result.objects("entries")
            if (entries.isEmpty()) {
                return ToolResult.ok("${ctx.display(ctx.workspace)} is empty.")
            }

            // Indent by path depth so the shape is legible without drawing
            // box characters, which are pure token overhead.
            val body = entries.joinToString("\n") { entry ->
                val depth = entry.int("depth").coerceAtMost(8)
                val indent = "  ".repeat(depth)
                val name = entry.str("path", "").substringAfterLast('/')
                if (entry.bool("dir")) "$indent$name/" else "$indent$name"
            }
            ToolResult.ok(
                "${ctx.display(ctx.workspace)}" +
                    (if (result.bool("truncated")) " (truncated)" else "") + "\n$body",
            )
        } catch (e: NativeException) {
            ToolResult.error("project_tree failed: ${e.detail}")
        }
    }
}
