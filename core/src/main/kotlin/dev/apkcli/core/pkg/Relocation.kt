package dev.apkcli.core.pkg

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where Termux's compiled-in paths are pointed.
 *
 * Termux builds every package for `/data/data/com.termux/files/usr`, and that
 * string ends up everywhere: script shebangs, `RUNPATH` in every ELF file,
 * OpenSSL's certificate directory, Python's subprocess shell, terminfo lookups.
 * Those paths do not exist in this app, so they are rewritten at install.
 *
 * Two ways to do that:
 *
 *  * [Mode.ALIAS] — the replacement has exactly the original's length, e.g.
 *    `/data/data/dev.apkcli.power/usr`, a symlink in the app's data directory
 *    pointing at the real prefix. Same-length replacement is safe in *any*
 *    file, binaries included: no offset moves, no string gets longer. This is
 *    the mode that makes packages behave as they do in Termux.
 *  * [Mode.DIRECT] — when no alias fits (a package name too long to leave room
 *    for one, or a secondary user, whose data does not live under
 *    `/data/data`). Text files get the real prefix; ELF files get their
 *    `RUNPATH` rewritten relative to `$ORIGIN`, see [ElfRunpath]; strings
 *    compiled into binaries keep pointing at Termux's paths, so some lookups
 *    (certificates, terminfo) need the environment variables set by
 *    [dev.apkcli.core.runtime.ExecPolicy] instead — except the parts of the
 *    tree that still have a same-length alias, see [binaryAliases].
 */
class Relocation private constructor(
    val mode: Mode,
    /** What `/data/data/com.termux/files/usr` becomes. */
    val prefixPath: String,
    /** What `/data/data/com.termux/files/home` becomes. */
    val homePath: String,
    /**
     * [Mode.DIRECT] only: same-length aliases for parts of Termux's tree,
     * `Termux path -> alias`, patched into binaries where the prefix itself
     * could not be. `$PREFIX/tmp` is the one that matters most: POSIX
     * semaphores (libandroid-posix-semaphore), SysV shared-memory keys
     * (libandroid-shmem) and PostgreSQL's socket live there, at a path
     * compiled into the library with no variable to move it. Out of reach,
     * PostgreSQL does not fail: it takes the error for a name collision and
     * retries forever. `$PREFIX/bin` is next: `pg_ctl` starts the server
     * through `execl(".../usr/bin/sh")`, which failed with ENOENT without it.
     */
    val binaryAliases: Map<String, String> = emptyMap(),
) {
    enum class Mode { ALIAS, DIRECT }

    /** True when every rewrite keeps its length, so binary files can be patched too. */
    val inPlace: Boolean = prefixPath.length == TERMUX_PREFIX.length && homePath.length == TERMUX_HOME.length

    /** For text files, and for every file when [inPlace]. */
    val replacer = ByteReplacer(
        listOf(
            ByteReplacer.Rule(TERMUX_PREFIX.toByteArray(), prefixPath.toByteArray()),
            ByteReplacer.Rule(TERMUX_HOME.toByteArray(), homePath.toByteArray()),
        ),
    )

    /**
     * For ELF and other binary files: [replacer] when it keeps lengths,
     * otherwise only the [binaryAliases]; null when nothing can be patched.
     *
     * An alias matches a whole path component only, the Termux path followed
     * by `/` or by the NUL ending a C string, so `.../usr/tmpl` is never taken
     * for `.../usr/tmp`.
     */
    val binaryReplacer: ByteReplacer? = when {
        inPlace -> replacer
        binaryAliases.isEmpty() -> null
        else -> ByteReplacer(
            binaryAliases.flatMap { (from, to) ->
                listOf("/", "\u0000").map { end -> ByteReplacer.Rule((from + end).toByteArray(), (to + end).toByteArray()) }
            },
        )
    }

    /** A path (a symlink target, a conffile) with its Termux prefix rewritten. */
    fun relocatePath(path: String): String = when {
        path == TERMUX_PREFIX || path.startsWith("$TERMUX_PREFIX/") -> prefixPath + path.removePrefix(TERMUX_PREFIX)
        path == TERMUX_HOME || path.startsWith("$TERMUX_HOME/") -> homePath + path.removePrefix(TERMUX_HOME)
        else -> path
    }

    /** Recorded per package, so a later change of mode is visible in the database. */
    fun describe(): String = "${mode.name.lowercase()}:$prefixPath" + binaryAliases.values.joinToString("") { "+$it" }

    override fun toString(): String = describe()

    companion object {
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val TERMUX_TMP = "$TERMUX_PREFIX/tmp"

        fun alias(prefixAlias: String, homeAlias: String): Relocation {
            require(prefixAlias.length == TERMUX_PREFIX.length) { "prefix alias must be ${TERMUX_PREFIX.length} chars" }
            require(homeAlias.length == TERMUX_HOME.length) { "home alias must be ${TERMUX_HOME.length} chars" }
            return Relocation(Mode.ALIAS, prefixAlias, homeAlias)
        }

        fun direct(prefix: File, home: File, binaryAliases: Map<String, String> = emptyMap()): Relocation {
            binaryAliases.forEach { (from, to) ->
                require(from.length == to.length) { "alias $to must be as long as $from" }
            }
            return Relocation(Mode.DIRECT, prefix.absolutePath.trimEnd('/'), home.absolutePath.trimEnd('/'), binaryAliases)
        }

        /** True when files installed under [description] (see [describe]) rely on links in the data directory. */
        fun usesLinks(description: String): Boolean = description.startsWith("alias:") || '+' in description
    }
}

/**
 * What a file is, for deciding how to relocate it. Decided from its name and
 * its first few kilobytes, as it streams out of the archive.
 */
enum class ContentKind {
    ELF,
    TEXT,
    /** Binary but not ELF: terminfo entries, static archives, databases. */
    BINARY,

    /**
     * Compressed or checksummed containers. Never rewritten: a path inside
     * would be compressed beyond recognition anyway, and patching a stored
     * zip entry would break its CRC.
     */
    ARCHIVE,
    ;

    companion object {
        const val SNIFF_BYTES = 8192

        private val ARCHIVE_SUFFIXES = listOf(
            ".whl", ".zip", ".jar", ".egg", ".apk", ".aar",
            ".gz", ".tgz", ".xz", ".txz", ".bz2", ".zst", ".lz4", ".lzma", ".7z",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
        )

        fun classify(path: String, head: ByteArray, headLength: Int): ContentKind {
            val lower = path.lowercase()
            if (ARCHIVE_SUFFIXES.any { lower.endsWith(it) }) return ARCHIVE
            if (headLength >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            ) {
                return ELF
            }
            for (i in 0 until headLength) if (head[i] == 0.toByte()) return BINARY
            return TEXT
        }
    }
}

/**
 * Streaming multi-pattern byte replacement.
 *
 * Works on bytes, not characters: the same pass handles UTF-8 text and binary
 * files, and never re-encodes anything it did not match. Matches are found
 * across buffer boundaries by carrying the last `longest - 1` bytes over.
 */
class ByteReplacer(rules: List<Rule>) {

    class Rule(val from: ByteArray, val to: ByteArray) {
        init {
            require(from.isNotEmpty()) { "empty pattern" }
        }
    }

    /** Longest first, so a pattern that extends another wins. */
    private val rules: List<Rule> = rules.sortedByDescending { it.from.size }
    private val longest: Int = this.rules.maxOf { it.from.size }
    private val firstBytes = BooleanArray(256).also { table ->
        this.rules.forEach { table[it.from[0].toInt() and 0xff] = true }
    }

    /** Copy [input] to [output], rewriting every match. Returns the number of matches. */
    fun copy(input: InputStream, output: OutputStream): Int {
        val buffer = ByteArray(BUFFER + longest)
        var length = 0
        var eof = false
        var matches = 0
        while (true) {
            while (!eof && length < buffer.size) {
                val n = input.read(buffer, length, buffer.size - length)
                if (n < 0) eof = true else length += n
            }
            // A match starting before `limit` lies wholly inside the buffer.
            val limit = if (eof) length else length - longest + 1
            var i = 0
            var flushed = 0
            while (i < limit) {
                if (firstBytes[buffer[i].toInt() and 0xff]) {
                    val rule = rules.firstOrNull { matchesAt(buffer, i, length, it.from) }
                    if (rule != null) {
                        output.write(buffer, flushed, i - flushed)
                        output.write(rule.to)
                        i += rule.from.size
                        flushed = i
                        matches++
                        continue
                    }
                }
                i++
            }
            if (eof) {
                output.write(buffer, flushed, length - flushed)
                return matches
            }
            // Everything before `i` is decided; carry the undecided tail over.
            output.write(buffer, flushed, i - flushed)
            val carry = length - i
            System.arraycopy(buffer, i, buffer, 0, carry)
            length = carry
        }
    }

    /** Matches in [bytes], for checks that do not need a rewrite. */
    fun count(bytes: ByteArray): Int {
        var i = 0
        var matches = 0
        while (i < bytes.size) {
            val rule = if (firstBytes[bytes[i].toInt() and 0xff]) {
                rules.firstOrNull { matchesAt(bytes, i, bytes.size, it.from) }
            } else {
                null
            }
            if (rule != null) {
                matches++
                i += rule.from.size
            } else {
                i++
            }
        }
        return matches
    }

    private fun matchesAt(buffer: ByteArray, at: Int, length: Int, pattern: ByteArray): Boolean {
        if (at + pattern.size > length) return false
        for (k in pattern.indices) if (buffer[at + k] != pattern[k]) return false
        return true
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * The few filesystem operations `java.io.File` cannot do: symlinks, modes,
 * and a rename that replaces its target. An interface so the package logic can
 * be exercised off-device; the app uses `android.system.Os`.
 */
interface FileOps {
    fun symlink(target: String, link: File)

    /** The link's target, or null when [link] is not a symlink (or does not exist). */
    fun readlink(link: File): String?

    fun chmod(file: File, mode: Int)

    /** Atomically replace [to] with [from], as rename(2) does. */
    fun rename(from: File, to: File)

    fun isSymlink(file: File): Boolean = readlink(file) != null
}

/**
 * The same-length alias of the prefix: `/data/data/<package>/usr`.
 *
 * `/data/data/com.termux/files/usr` is 31 characters. `/data/data/` plus this
 * app's package name plus a short directory name can be made exactly as long,
 * and that path is reachable: `/data/data/<package>` is this app's own data
 * directory for the device's primary user. A symlink there to the real
 * `$PREFIX` gives every rewritten path somewhere to resolve.
 *
 * When the prefix has no room (the debug build's longer package name; a
 * secondary user, whose data directory is `/data/user/<id>/<package>`), the
 * longer paths below it may still fit: `$PREFIX/tmp` is 35 characters, and
 * `/data/data/dev.apkcli.power.debug/t` is too; `$PREFIX/bin` gets `.../b`,
 * `$PREFIX/share` `.../sha`. Those become [Relocation.binaryAliases].
 */
class PrefixAlias(
    /** `context.dataDir`, the parent of `files/`. */
    private val dataDir: File,
    packageName: String,
    private val prefix: File,
    private val home: File,
) {
    sealed interface Status {
        data class Ready(val relocation: Relocation) : Status

        /** No prefix alias. [relocation] is direct, with whatever partial aliases could be made. */
        data class Unavailable(val reason: String, val relocation: Relocation) : Status
    }

    /** The primary user's spelling, the one the prefix alias was designed around. */
    private val primaryBase = "/data/data/$packageName"

    val prefixAliasPath: String? = aliasPath(primaryBase, Relocation.TERMUX_PREFIX.length, "usr")
    val homeAliasPath: String? = aliasPath(primaryBase, Relocation.TERMUX_HOME.length, "home")

    /** Create the links if needed, then check that the literal paths reach the real directories. */
    fun ensure(files: FileOps): Status {
        val prefixAlias = prefixAliasPath
        val homeAlias = homeAliasPath
        val why = if (prefixAlias == null || homeAlias == null) {
            "the package name leaves no room for a ${Relocation.TERMUX_PREFIX.length}-character alias"
        } else {
            link(files, prefixAlias, prefix) ?: link(files, homeAlias, home)
                ?: return Status.Ready(Relocation.alias(prefixAlias, homeAlias))
        }
        return Status.Unavailable(why, Relocation.direct(prefix, home, partialAliases(files)))
    }

    /** Both aliases exist and reach the real directories. Checks only; creates nothing. */
    fun isActive(): Boolean {
        val prefixAlias = prefixAliasPath ?: return false
        val homeAlias = homeAliasPath ?: return false
        return leadsTo(prefixAlias, prefix) && leadsTo(homeAlias, home)
    }

    /**
     * Same-length aliases for the top-level directories of `$PREFIX` and for
     * `$HOME`, from whichever spelling of the data directory reaches them:
     * `/data/data/<package>` for the primary user, the real path
     * (`/data/user/10/<package>`) otherwise. `/data/data/dev.apkcli.power.debug/b`
     * is as long as `/data/data/com.termux/files/usr/bin`, and so on.
     */
    private fun partialAliases(files: FileOps): Map<String, String> {
        val base = listOf(primaryBase, dataDir.absolutePath.trimEnd('/'))
            .firstOrNull { leadsTo(it, dataDir) } ?: return emptyMap()
        val found = LinkedHashMap<String, String>()
        val targets = PREFIX_DIRS.map { dir -> Triple("${Relocation.TERMUX_PREFIX}/$dir", File(prefix, dir), dir) } +
            Triple(Relocation.TERMUX_HOME, home, "home")
        for ((termux, target, name) in targets) {
            val alias = aliasPath(base, termux.length, name) ?: continue
            // Two names cut down to the same one: the first keeps it.
            if (alias in found.values) continue
            // The link is checked by reaching its target, so the target must exist.
            target.mkdirs()
            if (link(files, alias, target) == null) found[termux] = alias
        }
        return found
    }

    /** Point the link [alias] names at [target]. Null when [alias] now reaches it, else why not. */
    private fun link(files: FileOps, alias: String, target: File): String? {
        val link = File(dataDir, alias.substringAfterLast('/'))
        val wanted = relativeTarget(target)
        try {
            when (val current = files.readlink(link)) {
                wanted -> Unit
                null -> {
                    if (link.exists()) return "${link.path} exists and is not a link"
                    files.symlink(wanted, link)
                }

                else -> {
                    // Only a link into this app's files/ is one of ours. Anything
                    // else (older Android puts a `lib` link to the native library
                    // directory here) is left exactly as it is.
                    if (!isOwnTarget(current)) return "${link.path} is a link to $current, not one of this app's"
                    link.delete()
                    files.symlink(wanted, link)
                    if (files.readlink(link) != wanted) return "could not repoint ${link.path} (was $current)"
                }
            }
        } catch (e: IOException) {
            return "could not create ${link.path}: ${e.message}"
        }
        // The literal path is what the rewritten files will use. It only
        // reaches this app's data for the user it was made for, so check it
        // rather than assume.
        return if (leadsTo(alias, target)) null else "$alias does not lead to ${target.path}"
    }

    /**
     * By identity, not by canonical path: with app data isolation (Android
     * 11+) `/data/data/<pkg>` and `/data/user/0/<pkg>` are separate mounts of
     * one directory, so the two spellings never canonicalise to the same
     * string even though they are the same inode.
     */
    private fun leadsTo(alias: String, target: File): Boolean = runCatching {
        java.nio.file.Files.isSameFile(java.nio.file.Paths.get(alias), target.toPath())
    }.getOrDefault(false)

    /** A link target this class could have written: inside `files/`, relative or absolute. */
    private fun isOwnTarget(target: String): Boolean =
        target.startsWith("files/") ||
            target.startsWith(dataDir.absolutePath.trimEnd('/') + "/files/") ||
            target.startsWith("$primaryBase/files/")

    /** `files/usr` when the target is inside the data directory, so the link survives the data directory moving. */
    private fun relativeTarget(target: File): String {
        val base = dataDir.absolutePath.trimEnd('/') + "/"
        val path = target.absolutePath
        return if (path.startsWith(base)) path.removePrefix(base) else path
    }

    private companion object {
        /**
         * Top-level directories of a Termux prefix whose paths binaries have
         * compiled in: helpers exec'd by path (`bin/sh` from `pg_ctl`),
         * semaphores and sockets (`tmp`), config (`etc`), plugins and module
         * trees (`lib`, `libexec`), data (`share`), headers for the on-device
         * compiler (`include`), state (`var`).
         */
        val PREFIX_DIRS = listOf("tmp", "bin", "etc", "lib", "libexec", "share", "include", "var")

        /** `<base>/<name>`, exactly [length] characters, the name cut or padded from [preferred]; null when no name fits. */
        fun aliasPath(base: String, length: Int, preferred: String): String? {
            val dir = base.trimEnd('/') + "/"
            val room = length - dir.length
            if (room < 1) return null
            val name = if (room <= preferred.length) preferred.take(room) else preferred + "_".repeat(room - preferred.length)
            return dir + name
        }
    }
}
