package dev.apkcli.core.agent.tools

import java.io.File

/**
 * What fs_read shows of a file: whole lines from where it was asked to start,
 * as many as one tool result may hold, and where to go on from. A result over
 * the per-call budget used to lose its middle, which for a file meant the
 * very lines asked for went missing between its two ends.
 */
internal object FsReadPage {

    /** Longer lines are cut: one line of minified code could fill the window. */
    const val MAX_LINE_CHARS = 2_000

    data class Line(val number: Int, val text: String)

    /** [text] as shown: no carriage return at its end, cut when over [MAX_LINE_CHARS]. */
    fun shown(text: String): String {
        val line = text.removeSuffix("\r")
        if (line.length <= MAX_LINE_CHARS) return line
        var end = MAX_LINE_CHARS
        if (Character.isHighSurrogate(line[end - 1])) end--
        return line.substring(0, end) + " … [line cut at $MAX_LINE_CHARS of ${line.length} characters]"
    }

    /** The lines as fs_read prints them, each after its number. */
    fun body(lines: List<Line>): String = lines.joinToString("\n") { "${it.number.toString().padStart(5)}  ${it.text}" }

    /**
     * The longest start of [lines] whose [body] [fits], and never none: a
     * single line too long for the budget is still shown, to be cut after.
     */
    fun fit(lines: List<Line>, fits: (String) -> Boolean): List<Line> {
        if (lines.size <= 1 || fits(body(lines))) return lines
        var low = 1
        var high = lines.size
        while (high - low > 1) {
            val mid = (low + high) / 2
            if (fits(body(lines.subList(0, mid)))) low = mid else high = mid
        }
        return lines.subList(0, low)
    }

    /**
     * How many lines a file has whose text the reader split at every `\n`:
     * a final line break ends the last line rather than starting another,
     * and an empty file has none.
     */
    fun lineCount(splitCount: Int, endsWithNewline: Boolean, empty: Boolean): Int =
        if (empty) 0 else splitCount - if (endsWithNewline) 1 else 0

    /** Whether [file]'s last byte is a line break. */
    fun endsWithNewline(file: File): Boolean = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (raf.length() == 0L) return@use false
            raf.seek(raf.length() - 1)
            raf.read() == '\n'.code
        }
    }.getOrDefault(false)
}

/**
 * What to say about a path that is not there, so the next call can be right
 * without a listing first: names in its folder that look like the one asked
 * for, or, when the folder itself is missing, the nearest one that exists.
 * OpenCode's read answers a miss the same way.
 */
internal object PathHints {

    /** Up to three of [names] that [wanted] may have meant, the likeliest first. */
    fun similar(names: Collection<String>, wanted: String): List<String> {
        val name = wanted.lowercase()
        val stem = stemOf(name)
        val typos = when {
            stem.length < 3 -> 0
            stem.length < 5 -> 1
            else -> 2
        }
        fun tier(candidate: String): Int? {
            val lower = candidate.lowercase()
            val found = stemOf(lower)
            return when {
                lower == name -> 1
                found == stem -> 2
                minOf(found.length, stem.length) >= 3 && (found.contains(stem) || stem.contains(found)) -> 3
                typos > 0 && kotlin.math.abs(found.length - stem.length) <= typos && distance(found, stem) <= typos -> 4
                else -> null
            }
        }
        return names.asSequence()
            .filter { it != wanted }
            .mapNotNull { candidate -> tier(candidate)?.let { Triple(candidate, it, distance(candidate.lowercase(), name)) } }
            .sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
            .take(3)
            .map { it.first }
            .toList()
    }

    /** "<path> does not exist", and what may have been meant. */
    fun missing(file: File, display: (File) -> String): String {
        val said = "${display(file)} does not exist."
        val parent = file.parentFile ?: return said
        if (parent.isDirectory) {
            val guesses = similar(parent.list()?.toList().orEmpty(), file.name)
            return if (guesses.isEmpty()) {
                "$said Nothing in ${display(parent)} has a similar name; fs_glob finds a file by name anywhere."
            } else {
                "$said Did you mean " + guesses.joinToString(", ") { display(File(parent, it)) } + "?"
            }
        }
        // A folder on the way is missing: name the first one, and where the path still holds.
        var missingDir = parent
        var existing = parent.parentFile
        while (existing != null && !existing.isDirectory) {
            missingDir = existing
            existing = existing.parentFile
        }
        if (existing == null) return said
        val guesses = similar(existing.list()?.toList().orEmpty(), missingDir.name)
        return "$said The folder ${display(missingDir)} does not exist; the nearest one that does is " +
            "${display(existing)}." +
            if (guesses.isEmpty()) "" else " Did you mean " + guesses.joinToString(", ") { display(File(existing, it)) } + "?"
    }

    private fun stemOf(name: String): String = name.substringBeforeLast('.').ifEmpty { name }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
