package dev.apkcli.ui.editor

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * A small lexical highlighter: comments, strings, numbers, keywords,
 * annotations and call sites.
 *
 * Deliberately not TextMate. sora's TextMate support needs grammar and theme
 * JSON shipped as assets, plus the oniguruma port whose R8 warning the README
 * already flags as unverified. A hand-written lexer over a keyword table is a
 * few hundred lines, starts instantly, and is enough to make code readable on
 * a phone — which is the job of an editor that exists mostly to review and
 * touch up what the agent wrote.
 *
 * It runs on sora's analysis thread, re-lexes the whole buffer after an edit
 * (SimpleAnalyzeManager's model), and gives up on files above [MAX_CHARS],
 * which are then shown plain rather than making typing lag.
 */
internal class Syntax(
    val keywords: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val quotes: String = "\"'",
    /** `"""` strings, as in Kotlin and Python. */
    val tripleQuotes: Boolean = false,
    val annotations: Boolean = false,
    /** `<tag attr="">`-style markup: tag names coloured, no keywords. */
    val markup: Boolean = false,
    /** Names coloured as variables rather than keywords: Python's `self` and `cls`. */
    val variables: Set<String> = emptySet(),
) {
    companion object {
        private val cKeywords = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
            "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "return", "short",
            "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile",
            "while", "bool", "true", "false", "nullptr", "NULL", "class", "namespace", "public", "private",
            "protected", "template", "typename", "virtual", "override", "new", "delete", "this", "using", "try",
            "catch", "throw", "constexpr", "noexcept", "include", "define", "ifdef", "ifndef", "endif", "pragma",
        )

        private val kotlin = Syntax(
            keywords = setOf(
                "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when",
                "for", "while", "do", "return", "break", "continue", "throw", "try", "catch", "finally", "in",
                "is", "as", "null", "true", "false", "this", "super", "typealias", "constructor", "init",
                "companion", "data", "sealed", "enum", "abstract", "open", "override", "private", "protected",
                "public", "internal", "inline", "suspend", "operator", "infix", "lateinit", "const", "by",
                "where", "out", "reified", "crossinline", "noinline", "vararg", "annotation", "inner", "get",
                "set", "field", "it", "external", "tailrec", "value", "actual", "expect",
            ),
            lineComments = listOf("//"),
            blockComment = "/*" to "*/",
            quotes = "\"'",
            tripleQuotes = true,
            annotations = true,
        )

        private val java = Syntax(
            keywords = setOf(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
                "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
                "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
                "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
                "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
                "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield",
            ),
            lineComments = listOf("//"),
            blockComment = "/*" to "*/",
            annotations = true,
        )

        private val script = Syntax(
            keywords = setOf(
                "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
                "delete", "do", "else", "export", "extends", "finally", "for", "from", "function", "if", "import",
                "in", "instanceof", "let", "new", "of", "return", "super", "switch", "this", "throw", "try",
                "typeof", "var", "void", "while", "with", "yield", "true", "false", "null", "undefined",
                "interface", "type", "enum", "implements", "private", "protected", "public", "readonly",
                "static", "abstract", "as", "any", "number", "string", "boolean", "never", "unknown", "declare",
            ),
            lineComments = listOf("//"),
            blockComment = "/*" to "*/",
            quotes = "\"'`",
            annotations = true,
        )

        private val rust = Syntax(
            keywords = setOf(
                "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
                "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub",
                "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe",
                "use", "where", "while", "Some", "None", "Ok", "Err", "macro_rules",
            ),
            lineComments = listOf("//"),
            blockComment = "/*" to "*/",
            quotes = "\"",
        )

        private val go = Syntax(
            keywords = setOf(
                "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
                "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
                "struct", "switch", "type", "var", "true", "false", "nil", "iota",
            ),
            lineComments = listOf("//"),
            blockComment = "/*" to "*/",
            quotes = "\"'`",
        )

        private val python = Syntax(
            keywords = setOf(
                "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
                "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
                "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with",
                "yield", "match", "case",
            ),
            lineComments = listOf("#"),
            tripleQuotes = true,
            annotations = true,
            variables = setOf("self", "cls"),
        )

        private val shell = Syntax(
            keywords = setOf(
                "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do", "done", "in",
                "function", "return", "exit", "export", "local", "readonly", "set", "unset", "shift", "source",
                "echo", "cd", "true", "false",
            ),
            lineComments = listOf("#"),
        )

        private val config = Syntax(
            keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
            lineComments = listOf("#", ";"),
        )

        private val json = Syntax(keywords = setOf("true", "false", "null"), quotes = "\"")

        private val markup = Syntax(blockComment = "<!--" to "-->", quotes = "\"'", markup = true)

        private val css = Syntax(blockComment = "/*" to "*/", lineComments = listOf("//"))

        private val sql = Syntax(
            keywords = setOf(
                "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
                "table", "index", "drop", "alter", "add", "and", "or", "not", "null", "primary", "key", "join",
                "left", "right", "inner", "outer", "on", "group", "by", "order", "limit", "as", "distinct",
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
                "TABLE", "INDEX", "DROP", "ALTER", "AND", "OR", "NOT", "NULL", "PRIMARY", "KEY", "JOIN", "ON",
                "GROUP", "BY", "ORDER", "LIMIT", "AS", "DISTINCT",
            ),
            lineComments = listOf("--"),
            blockComment = "/*" to "*/",
        )

        private val clike = Syntax(keywords = cKeywords, lineComments = listOf("//"), blockComment = "/*" to "*/")

        /** Null means plain text. */
        fun forFile(name: String): Syntax? {
            val lower = name.lowercase()
            if (lower == "makefile" || lower == "dockerfile" || lower.startsWith(".env")) return shell
            if (lower == "cmakelists.txt") return config
            return when (lower.substringAfterLast('.', "")) {
                "kt", "kts", "gradle" -> kotlin
                "java", "groovy", "scala" -> java
                "js", "mjs", "cjs", "jsx", "ts", "tsx", "dart" -> script
                "rs" -> rust
                "go" -> go
                "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm", "cs", "swift" -> clike
                "py", "pyi" -> python
                "sh", "bash", "zsh", "fish", "ps1" -> shell
                "toml", "ini", "cfg", "conf", "properties", "yaml", "yml", "pro", "mk" -> config
                "json", "jsonc", "json5" -> json
                "xml", "html", "htm", "svg", "vue" -> markup
                "css", "scss", "sass", "less" -> css
                "sql" -> sql
                else -> null
            }
        }

        /** The language of a Markdown code fence: ```kotlin, ```py, ```console. Null means plain text. */
        fun forFence(language: String): Syntax? {
            val tag = language.trim().lowercase().substringBefore(' ').substringBefore('{')
            if (tag.isEmpty()) return null
            val alias = when (tag) {
                "kotlin" -> "kt"
                "python", "python3", "py3" -> "py"
                "javascript", "node", "nodejs" -> "js"
                "typescript" -> "ts"
                "golang" -> "go"
                "rust" -> "rs"
                "bash", "shell", "console", "shellsession", "sh-session", "terminal", "zsh" -> "sh"
                "powershell", "pwsh" -> "ps1"
                "c++", "cplusplus" -> "cpp"
                "csharp", "c#" -> "cs"
                "objective-c", "objc" -> "m"
                "yml" -> "yaml"
                "html5", "xhtml" -> "html"
                "docker", "dockerfile" -> return shell
                "makefile", "make" -> return shell
                "ini", "dotenv", "env" -> "ini"
                else -> tag
            }
            return forFile("x.$alias")
        }
    }
}

/** The editor language for one buffer: sora's empty language plus [Syntax] colouring. */
internal class HighlightedLanguage(syntax: Syntax) : EmptyLanguage() {
    private val manager = SyntaxAnalyzer(syntax)

    override fun getAnalyzeManager(): AnalyzeManager = manager

    override fun destroy() {
        manager.destroy()
        super.destroy()
    }
}

private class SyntaxAnalyzer(private val syntax: Syntax) : SimpleAnalyzeManager<Unit>() {

    override fun analyze(text: StringBuilder, delegate: SimpleAnalyzeManager<Unit>.Delegate<Unit>): Styles {
        val builder = MappedSpans.Builder()
        val lines = if (text.length > MAX_CHARS) emptyList() else text.split('\n')
        var inBlock = false
        val tokens = ArrayList<LexToken>()

        lines.forEachIndexed { index, raw ->
            if (index % 256 == 0 && delegate.isCancelled) return Styles(builder.apply { determine(0) }.build())
            val line = raw.removeSuffix("\r")
            tokens.clear()
            inBlock = SyntaxLexer.lexLine(syntax, line, inBlock, tokens)
            emit(builder, index, line.length, tokens)
        }
        builder.determine((lines.size - 1).coerceAtLeast(0))
        builder.addNormalIfNull()
        return Styles(builder.build())
    }

    private fun styleOf(kind: TokenKind): Long = when (kind) {
        TokenKind.COMMENT -> COMMENT
        TokenKind.STRING -> STRING
        TokenKind.NUMBER -> NUMBER
        TokenKind.KEYWORD -> KEYWORD
        TokenKind.CONTROL -> CONTROL
        TokenKind.CONSTANT -> CONSTANT
        TokenKind.VARIABLE -> VARIABLE
        TokenKind.TYPE -> TYPE
        TokenKind.FUNCTION -> FUNCTION
        TokenKind.ANNOTATION -> ANNOTATION
        TokenKind.ATTRIBUTE -> ATTRIBUTE
    }

    private fun emit(builder: MappedSpans.Builder, line: Int, length: Int, tokens: List<LexToken>) {
        if (tokens.isEmpty() || tokens[0].start > 0) builder.addIfNeeded(line, 0, NORMAL)
        tokens.forEachIndexed { i, token ->
            builder.addIfNeeded(line, token.start, styleOf(token.kind))
            val nextStart = tokens.getOrNull(i + 1)?.start ?: length
            if (token.end < nextStart) builder.addIfNeeded(line, token.end, NORMAL)
        }
    }

    private companion object {
        const val MAX_CHARS = 400_000

        val NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        val COMMENT = TextStyle.makeStyle(EditorColorScheme.COMMENT)
        val STRING = TextStyle.makeStyle(EditorColorScheme.LITERAL)
        val NUMBER = TextStyle.makeStyle(SyntaxColorIds.NUMBER)
        val KEYWORD = TextStyle.makeStyle(EditorColorScheme.KEYWORD)
        val CONTROL = TextStyle.makeStyle(SyntaxColorIds.CONTROL)
        val CONSTANT = TextStyle.makeStyle(SyntaxColorIds.CONSTANT)
        val VARIABLE = TextStyle.makeStyle(SyntaxColorIds.VARIABLE)
        val TYPE = TextStyle.makeStyle(SyntaxColorIds.TYPE)
        val FUNCTION = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
        val ANNOTATION = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
        val ATTRIBUTE = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME)
    }
}

/**
 * Colour ids sora has no name for. Its own ids stop well below 256, and a
 * scheme accepts any id, so the extra Dark Modern roles live above that.
 */
internal object SyntaxColorIds {
    const val NUMBER = 301
    const val CONTROL = 302
    const val CONSTANT = 303
    const val VARIABLE = 304
    const val TYPE = 305
}
