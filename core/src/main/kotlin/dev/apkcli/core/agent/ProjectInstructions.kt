package dev.apkcli.core.agent

import java.io.File

/**
 * The project's instruction file, as every request carries it: `AGENTS.md`,
 * or the first of the names that projects set up for other agents use.
 *
 * At most [MAX_BYTES] of it go into the prompt. A longer one used to be
 * skipped without a word, and the next name read instead, or none: the agent
 * worked without the project's notes and nobody knew. Now its beginning goes
 * in, up to the last whole line, and the prompt says how much is left and
 * where ([Loaded.note]); the memory sheet tells the user.
 */
object ProjectInstructions {

    /** What of the file goes into the prompt, at most. */
    const val MAX_BYTES = 32 * 1024

    /**
     * In precedence order, relative to the project. `AGENTS.md` first because
     * it is the emerging cross-tool convention; the rest are recognised so a
     * project already set up for another agent works without being
     * re-documented.
     */
    val FILES = listOf(
        "AGENTS.md",
        ".agents.md",
        "CLAUDE.md",
        ".cursorrules",
        ".github/copilot-instructions.md",
    )

    /** The file a request reads, with its name as [FILES] has it; null when the project has none. */
    fun find(workspace: File): Pair<String, File>? =
        FILES.asSequence().map { it to File(workspace, it) }.firstOrNull { (_, file) -> file.isFile }

    class Loaded(
        /** As [FILES] names it, e.g. `AGENTS.md`. */
        val name: String,
        /** What the prompt carries: the whole file, or its beginning up to a whole line. */
        val text: String,
        val totalBytes: Long,
        /**
         * Where the rest begins, as fs_read's 0-based line offset, when [text]
         * is only the beginning; null for the whole file.
         */
        val restFromLine: Int?,
    ) {
        val cut: Boolean get() = restFromLine != null

        /** For the prompt, after [text]: how much was left out and how to read it. Null when nothing was. */
        fun note(): String? = restFromLine?.let { line ->
            "[$name is ${totalBytes / 1024} KB; only its first ${MAX_BYTES / 1024} KB are above. Read the rest " +
                "with fs_read(path: \"$name\", offset: $line) when it bears on the task.]"
        }
    }

    /** The file a request reads, as it reads it; null when there is none or it cannot be read. */
    fun load(workspace: File): Loaded? {
        val (name, file) = find(workspace) ?: return null
        return runCatching { read(file, name) }.getOrNull()
    }

    internal fun read(file: File, name: String): Loaded {
        val total = file.length()
        if (total <= MAX_BYTES) return Loaded(name, file.readText(), total, restFromLine = null)
        // Only the head is read: the file may be any size.
        val head = ByteArray(MAX_BYTES)
        var read = 0
        file.inputStream().use { input ->
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        // To the last whole line: the bytes stop anywhere, inside a line or
        // a character. A file with no line break in its head goes in as far
        // as it came, and the rest is read from its start.
        val decoded = String(head, 0, read, Charsets.UTF_8)
        val lineEnd = decoded.lastIndexOf('\n')
        return if (lineEnd > 0) {
            val text = decoded.substring(0, lineEnd)
            Loaded(name, text, total, restFromLine = text.count { it == '\n' } + 1)
        } else {
            Loaded(name, decoded.trimEnd('\uFFFD'), total, restFromLine = 0)
        }
    }
}
