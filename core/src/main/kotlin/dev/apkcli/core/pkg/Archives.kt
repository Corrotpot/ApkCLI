package dev.apkcli.core.pkg

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Readers for the two container formats inside a `.deb`: the outer `ar`
 * archive and the `tar` streams it carries.
 *
 * Hand-written rather than pulled from Commons Compress: a `.deb` needs a
 * sliver of each format, and that sliver is small enough to read in full.
 * Both readers stream — a 30 MB compiler package is never held in memory.
 */

/** One entry of a tar stream, with GNU long-name and pax records folded in. */
class TarEntry(
    /** As stored, e.g. `./data/data/com.termux/files/usr/bin/python3`. */
    val name: String,
    val type: Type,
    /** Permission bits, e.g. `0700`. */
    val mode: Int,
    val size: Long,
    /** Symlink target, or for a hard link the archive path of the file it repeats. */
    val linkName: String,
) {
    enum class Type { FILE, DIRECTORY, SYMLINK, HARDLINK, OTHER }
}

class TarReader(input: InputStream) {

    private val input: InputStream = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER)
    private val header = ByteArray(BLOCK)

    /** Unread bytes of the current entry's data, then its padding to the block size. */
    private var remaining = 0L
    private var padding = 0L
    private var finished = false

    /** Advance to the next entry, skipping whatever is left of the current one. Null at the end. */
    fun next(): TarEntry? {
        if (finished) return null
        skipFully(remaining + padding)
        remaining = 0
        padding = 0

        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        while (true) {
            if (!readBlock(header)) return end()
            if (header.all { it == 0.toByte() }) return end()
            verifyChecksum()

            val typeflag = header[156].toInt().toChar()
            val size = numeric(124, 12)
            when (typeflag) {
                // GNU: the next header's name or link target, too long for its field.
                'L' -> { longName = cString(readData(size)); continue }
                'K' -> { longLink = cString(readData(size)); continue }
                // pax: key=value overrides for the next header.
                'x' -> { pax = parsePax(readData(size)); continue }
                // pax global header: nothing in it matters for extraction.
                'g' -> { skipFully(size + paddingFor(size)); continue }
            }

            val ustar = header[257] == 'u'.code.toByte() && header[262] == 0.toByte()
            val name = longName ?: pax["path"] ?: run {
                val base = cString(header, 0, 100)
                // Only POSIX ustar has a prefix field; GNU tar keeps other data there.
                val prefix = if (ustar) cString(header, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$base" else base
            }
            val link = longLink ?: pax["linkpath"] ?: cString(header, 157, 100)
            val entrySize = pax["size"]?.toLongOrNull() ?: size
            val type = when (typeflag) {
                '0', '\u0000', '7' -> if (name.endsWith('/')) TarEntry.Type.DIRECTORY else TarEntry.Type.FILE
                '1' -> TarEntry.Type.HARDLINK
                '2' -> TarEntry.Type.SYMLINK
                '5' -> TarEntry.Type.DIRECTORY
                else -> TarEntry.Type.OTHER
            }
            // Links and directories carry no data, whatever the size field says.
            val dataSize = if (type == TarEntry.Type.FILE || type == TarEntry.Type.OTHER) entrySize else 0L
            remaining = dataSize
            padding = paddingFor(dataSize)
            return TarEntry(name, type, numeric(100, 8).toInt() and 0xfff, dataSize, link)
        }
    }

    /** The current entry's data. Valid until [next] is called again. */
    fun data(): InputStream = object : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b < 0) throw EOFException("tar entry truncated")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("tar entry truncated")
            remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, input.available().toLong()).toInt()
    }

    private fun end(): TarEntry? {
        finished = true
        return null
    }

    private fun readData(size: Long): ByteArray {
        if (size > MAX_META_BYTES) throw IOException("tar metadata record of $size bytes")
        val bytes = ByteArray(size.toInt())
        readFully(bytes, 0, bytes.size)
        skipFully(paddingFor(size))
        return bytes
    }

    private fun readBlock(into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("tar header truncated")
            }
            read += n
        }
        return true
    }

    private fun readFully(into: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(into, off + read, len - read)
            if (n < 0) throw EOFException("tar data truncated")
            read += n
        }
    }

    private fun skipFully(count: Long) {
        var left = count
        val scratch = ByteArray(minOf(count, BUFFER.toLong()).toInt().coerceAtLeast(1))
        while (left > 0) {
            // skip() may legitimately return 0 on a decompressing stream, so read instead.
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("tar stream truncated")
            left -= n
        }
    }

    private fun verifyChecksum() {
        val stored = numeric(148, 8)
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until BLOCK) {
            val b = if (i in 148 until 156) ' '.code.toByte() else header[i]
            unsigned += b.toInt() and 0xff
            signed += b.toInt()
        }
        // Some historic writers summed signed bytes; both are accepted, as GNU tar does.
        if (stored != unsigned && stored != signed) throw IOException("tar header checksum mismatch")
    }

    /** Octal ASCII, or GNU base-256 when the top bit of the first byte is set. */
    private fun numeric(offset: Int, length: Int): Long {
        val first = header[offset].toInt() and 0xff
        if (first and 0x80 != 0) {
            var value = (first and 0x7f).toLong()
            for (i in offset + 1 until offset + length) value = (value shl 8) or (header[i].toLong() and 0xff)
            return value
        }
        var value = 0L
        for (i in offset until offset + length) {
            val c = header[i].toInt().toChar()
            if (c == ' ' && value == 0L) continue
            if (c !in '0'..'7') break
            value = value * 8 + (c - '0')
        }
        return value
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var pos = 0
        while (pos < bytes.size) {
            val space = (pos until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() } ?: break
            val length = String(bytes, pos, space - pos, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || pos + length > bytes.size) break
            val record = String(bytes, space + 1, pos + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            pos += length
        }
        return out
    }

    companion object {
        private const val BLOCK = 512
        private const val BUFFER = 64 * 1024

        /** Long names and pax records are paths; anything bigger is a corrupt stream. */
        private const val MAX_META_BYTES = 1L shl 20

        private fun paddingFor(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

        private fun cString(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
            var end = offset
            while (end < offset + length && bytes[end] != 0.toByte()) end++
            return String(bytes, offset, end - offset, Charsets.UTF_8)
        }
    }
}

/** The `ar` container of a `.deb`. */
class ArReader(input: InputStream) {

    class Member(val name: String, val size: Long)

    private val input: InputStream = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
    private var remaining = 0L
    private var padding = 0L

    init {
        val magic = ByteArray(8)
        readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "!<arch>\n") throw IOException("not a .deb (no ar header)")
    }

    fun next(): Member? {
        skipFully(remaining + padding)
        remaining = 0
        padding = 0
        val header = ByteArray(60)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(header, 1)
        if (header[58] != '`'.code.toByte() || header[59] != '\n'.code.toByte()) {
            throw IOException("corrupt .deb (bad ar member header)")
        }
        // GNU ar ends names with '/', BSD ar pads with spaces; .deb members use either.
        val name = String(header, 0, 16, Charsets.US_ASCII).trim().removeSuffix("/")
        val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull()
            ?: throw IOException("corrupt .deb (bad member size)")
        remaining = size
        padding = size and 1
        return Member(name, size)
    }

    /** The current member's bytes. Valid until [next]. */
    fun data(): InputStream = object : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b < 0) throw EOFException(".deb truncated")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException(".deb truncated")
            remaining -= n
            return n
        }
    }

    private fun readFully(into: ByteArray, from: Int = 0) {
        var read = from
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) throw EOFException(".deb truncated")
            read += n
        }
    }

    private fun skipFully(count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException(".deb truncated")
            left -= n
        }
    }
}

/** A `.deb` on disk: its control metadata, and a stream over its files. */
object DebArchive {

    class Control(
        val fields: Map<String, String>,
        /** Absolute paths, as dpkg records them: `/data/data/com.termux/files/usr/etc/inputrc`. */
        val conffiles: List<String>,
        /** Maintainer scripts present, e.g. `postinst`. Never run; see [PackageManager]. */
        val scripts: List<String>,
    )

    fun readControl(file: File): Control {
        FileInputStream(file).use { raw ->
            val ar = ArReader(raw)
            while (true) {
                val member = ar.next() ?: break
                if (!member.name.startsWith("control.tar")) continue
                val tar = TarReader(decompress(member.name, ar.data()))
                var fields: Map<String, String> = emptyMap()
                var conffiles: List<String> = emptyList()
                val scripts = mutableListOf<String>()
                while (true) {
                    val entry = tar.next() ?: break
                    if (entry.type != TarEntry.Type.FILE) continue
                    when (val base = entry.name.removePrefix("./")) {
                        "control" -> fields = DebControl.parseStanzas(tar.data().readBytes().toString(Charsets.UTF_8))
                            .firstOrNull().orEmpty()

                        "conffiles" -> conffiles = tar.data().readBytes().toString(Charsets.UTF_8)
                            .lines().map(String::trim).filter { it.startsWith("/") }

                        in MAINTAINER_SCRIPTS -> scripts += base
                    }
                }
                return Control(fields, conffiles, scripts)
            }
        }
        throw IOException("${file.name} has no control member")
    }

    /** Stream the files of [file] through [block]. */
    fun <T> readData(file: File, block: (TarReader) -> T): T {
        FileInputStream(file).use { raw ->
            val ar = ArReader(raw)
            while (true) {
                val member = ar.next() ?: break
                if (!member.name.startsWith("data.tar")) continue
                return block(TarReader(decompress(member.name, ar.data())))
            }
        }
        throw IOException("${file.name} has no data member")
    }

    private fun decompress(member: String, input: InputStream): InputStream = when {
        member.endsWith(".xz") -> XZInputStream(BufferedInputStream(input, 64 * 1024))
        member.endsWith(".gz") -> GZIPInputStream(input, 64 * 1024)
        member == "data.tar" || member == "control.tar" -> input
        else -> throw IOException("unsupported compression in $member (only xz and gzip are)")
    }

    private val MAINTAINER_SCRIPTS = setOf("preinst", "postinst", "prerm", "postrm", "config", "triggers")
}
