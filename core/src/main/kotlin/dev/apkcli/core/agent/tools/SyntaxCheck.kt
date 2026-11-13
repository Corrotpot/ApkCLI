package dev.apkcli.core.agent.tools

import dev.apkcli.core.runtime.ShellRunner
import dev.apkcli.core.sched.TaskClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.File
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * Does a file an edit just wrote still parse? Said in the edit's result while
 * the model still has the change in mind: a stray comma in package.json or an
 * unclosed tag in a layout otherwise surfaces steps later, as a build error
 * that seems to be about something else.
 *
 * JSON and XML are parsed here. Python, TOML and YAML are checked by `python3`
 * when it is installed (TOML from Python 3.11, YAML only with PyYAML),
 * JavaScript by `node --check`, shell scripts by `sh -n` or `bash -n`. A
 * checker that is missing or cannot decide says nothing: only a definite
 * failure becomes a line, and a file that parses gets none.
 */
internal object SyntaxCheck {

    /** Past this a file is left to the build: parsing it is no longer instant. */
    private const val MAX_BYTES = 2L * 1024 * 1024

    /** An interpreter's check is milliseconds; this is for one that hangs. */
    private const val TIMEOUT_MS = 10_000L

    /** What is wrong with [file]'s syntax, as a line for the model; null when nothing is. */
    suspend fun problem(file: File, display: String, shell: ShellRunner): String? {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val (language, why) = when (file.extension.lowercase()) {
            "json", "jsonc" -> "JSON" to withContext(Dispatchers.IO) { json(file.readText()) }
            "xml", "svg" -> "XML" to withContext(Dispatchers.IO) { xml(file) }
            "py" -> "Python" to python(shell, "py", file)
            "toml" -> "TOML" to python(shell, "toml", file)
            "yaml", "yml" -> "YAML" to python(shell, "yaml", file)
            "js", "mjs", "cjs" -> "JavaScript" to javascript(shell, file)
            "sh", "bash" -> "a shell script" to script(shell, file)
            else -> return null
        }
        why ?: return null
        return "Note: $display does not parse as $language as written ($why). " +
            "Fix it, unless the file is still being written in parts."
    }

    /** Comments and trailing commas are allowed: tsconfig.json and VS Code's settings have them. */
    @OptIn(ExperimentalSerializationApi::class)
    private val lenientJson = Json {
        allowComments = true
        allowTrailingComma = true
    }

    /** Why [text] is not JSON, with the line; null when it is. */
    fun json(text: String): String? {
        try {
            lenientJson.parseToJsonElement(text)
            return null
        } catch (e: SerializationException) {
            val message = e.message.orEmpty().lineSequence().firstOrNull().orEmpty()
            val offset = Regex("at offset (\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
            val reason = message
                .substringAfter("at offset $offset: ", message)
                .substringBefore(" at path")
                .trim()
                .ifEmpty { "not JSON" }
            return if (offset != null) "line ${lineAt(text, offset)}: $reason" else reason
        }
    }

    /**
     * Why [file] is not well-formed XML, with the line; null when it is. No
     * DTD or external entity is fetched: whether it parses is a question
     * about the file alone, and fetching one is how XML parsers leak files.
     */
    fun xml(file: File): String? = file.inputStream().use { input -> xml(InputSource(input)) }

    /** [xml] for text in hand. */
    fun xml(text: String): String? = xml(InputSource(StringReader(text)))

    private fun xml(source: InputSource): String? {
        val reader = runCatching {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        }.getOrNull() ?: return null
        reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.errorHandler = object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit

            // Validity, which a well-formedness check does not ask about.
            override fun error(exception: SAXParseException) = Unit

            override fun fatalError(exception: SAXParseException) = throw exception
        }
        return try {
            reader.parse(source)
            null
        } catch (e: SAXParseException) {
            // Android's parser (Expat) puts the place in the message as well.
            val reason = e.message?.replace(Regex("^At line \\d+, column \\d+: "), "")?.trim()?.ifEmpty { null }
                ?: "not well-formed"
            if (e.lineNumber > 0) "line ${e.lineNumber}: $reason" else reason
        } catch (e: SAXException) {
            e.message ?: "not well-formed"
        }
    }

    /**
     * `python3` reading the program from stdin: exit 0 parses, 1 does not
     * (why on stderr), 3 cannot tell (no tomllib, no PyYAML). YAML is
     * composed, not loaded, so a custom tag (CloudFormation's `!Ref`) is
     * not taken for an error.
     */
    private val PYTHON_CHECK = """
        import sys
        kind, path = sys.argv[1], sys.argv[2]
        try:
            data = open(path, "rb").read()
        except OSError:
            sys.exit(3)
        try:
            if kind == "py":
                import ast
                ast.parse(data, path)
            elif kind == "toml":
                try:
                    import tomllib
                except ImportError:
                    sys.exit(3)
                tomllib.loads(data.decode("utf-8"))
            else:
                try:
                    import yaml
                except ImportError:
                    sys.exit(3)
                for _ in yaml.compose_all(data):
                    pass
        except SyntaxError as e:
            sys.stderr.write("line %s: %s\n" % (e.lineno, e.msg))
            sys.exit(1)
        except Exception as e:
            mark = getattr(e, "problem_mark", None)
            problem = getattr(e, "problem", None)
            if mark is not None and problem:
                sys.stderr.write("line %d: %s\n" % (mark.line + 1, problem))
            else:
                text = str(e).strip()
                sys.stderr.write((text.splitlines()[0] if text else type(e).__name__) + "\n")
            sys.exit(1)
    """.trimIndent() + "\n"

    private suspend fun python(shell: ShellRunner, kind: String, file: File): String? {
        if (shell.which("python3") == null) return null
        val result = shell.exec(
            command = "python3 - $kind ${shellQuote(file.absolutePath)}",
            workingDir = file.parentFile ?: file,
            timeoutMs = TIMEOUT_MS,
            stdin = PYTHON_CHECK,
            maxOutputBytes = 16 * 1024,
            taskClass = TaskClass.INTERACTIVE,
        )
        if (result.exitCode != 1) return null
        return result.stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    }

    private suspend fun javascript(shell: ShellRunner, file: File): String? {
        if (shell.which("node") == null) return null
        val result = shell.exec(
            command = "node --check ${shellQuote(file.absolutePath)}",
            workingDir = file.parentFile ?: file,
            timeoutMs = TIMEOUT_MS,
            maxOutputBytes = 16 * 1024,
            taskClass = TaskClass.INTERACTIVE,
        )
        if (result.exitCode == 0 || result.timedOut || result.exitCode == null) return null
        return nodeProblem(result.stderr)
    }

    /**
     * Node's report: `path:LINE`, the line and a caret, then `SyntaxError: why`.
     * Null when the error is only that the file was read as the other module
     * kind: whether a `.js` file is ESM is up to its package.json and the
     * Node version, not a syntax error in the file.
     */
    fun nodeProblem(stderr: String): String? {
        val lines = stderr.lines().map { it.trimEnd() }
        val why = lines.firstOrNull { it.startsWith("SyntaxError") }?.substringAfter("SyntaxError:")?.trim()
            ?: return null
        val moduleKind = listOf("outside a module", "Unexpected token 'export'", "require is not defined")
        if (moduleKind.any { it in why }) return null
        val line = lines.firstOrNull()?.let { Regex(":(\\d+)$").find(it)?.groupValues?.get(1) }
        return if (line != null) "line $line: $why" else why
    }

    private suspend fun script(shell: ShellRunner, file: File): String? {
        val shebang = file.bufferedReader().use { it.readLine() }.orEmpty()
        val interpreter = when {
            !shebang.startsWith("#!") -> "sh"
            "bash" in shebang -> "bash"
            Regex("\\b(zsh|fish|ksh|csh|tcsh)\\b").containsMatchIn(shebang) -> return null
            Regex("\\b(sh|dash|ash)\\b").containsMatchIn(shebang) -> "sh"
            // Python, node, awk behind a .sh name: not a shell's to read.
            else -> return null
        }
        if (shell.which(interpreter) == null) return null
        val result = shell.exec(
            command = "$interpreter -n ${shellQuote(file.absolutePath)}",
            workingDir = file.parentFile ?: file,
            timeoutMs = TIMEOUT_MS,
            maxOutputBytes = 16 * 1024,
            taskClass = TaskClass.INTERACTIVE,
        )
        if (result.exitCode == 0 || result.timedOut || result.exitCode == null) return null
        return (result.stderr.ifBlank { result.stdout }).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    private fun lineAt(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }
}
