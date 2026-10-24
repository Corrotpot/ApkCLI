package dev.apkcli.core.pkg

import java.io.File
import java.io.RandomAccessFile

/**
 * Rewrites an ELF file's `DT_RUNPATH` from Termux's absolute library
 * directory to one relative to `$ORIGIN`, in place.
 *
 * Only needed in [Relocation.Mode.DIRECT]: there the real prefix is longer
 * than Termux's, so the absolute path cannot be patched in, but
 * `$ORIGIN/../lib` is shorter than `/data/data/com.termux/files/usr/lib` and
 * bionic expands `$ORIGIN` in `DT_RUNPATH` (API 24 and up). The new string is
 * written over the old one and NUL-padded, so no offset in the file moves.
 *
 * The one hazard is string sharing: linkers merge a string that is the tail
 * of another into it, so some other reference (a needed library, a symbol
 * name, a version name) could point into the middle of the old path. Every
 * reference into `.dynstr` that this reader can find is checked first, and a
 * file where one lands inside the path is left alone.
 */
object ElfRunpath {

    sealed interface Result {
        data object NotElf : Result

        data object NoRunpath : Result

        /** Has a runpath, but nothing in it points into Termux's prefix. */
        data object Unchanged : Result

        data class Patched(val old: String, val new: String) : Result

        data class Skipped(val reason: String) : Result
    }

    /**
     * @param relativePath where the file sits under the prefix, e.g.
     *   `bin/python3.14`; `$ORIGIN` paths are computed from it.
     */
    fun relocate(file: File, relativePath: String, termuxPrefix: String = Relocation.TERMUX_PREFIX): Result =
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val elf = Elf.read(raf) ?: return@use Result.NotElf
                val dynamic = elf.dynamic ?: return@use Result.NoRunpath
                val runpaths = dynamic.filter { it.tag == DT_RUNPATH || it.tag == DT_RPATH }
                if (runpaths.isEmpty()) return@use Result.NoRunpath
                val strtab = elf.strtabOffset ?: return@use Result.Skipped("no string table")

                var result: Result = Result.Unchanged
                for (offset in runpaths.map { it.value }.distinct()) {
                    val old = elf.readString(strtab + offset) ?: return@use Result.Skipped("unreadable runpath")
                    val new = rewrite(old, relativePath, termuxPrefix)
                    if (new == old) continue
                    if (new.length > old.length) return@use Result.Skipped("relative runpath is longer than '$old'")
                    val shared = elf.stringReferences().any { it > offset && it <= offset + old.length }
                    if (shared) return@use Result.Skipped("runpath string is shared with another name")

                    val bytes = ByteArray(old.length)
                    new.toByteArray().copyInto(bytes)
                    raf.seek(strtab + offset)
                    raf.write(bytes)
                    result = Result.Patched(old, new)
                }
                result
            }
        }.getOrElse { Result.Skipped("${it.javaClass.simpleName}: ${it.message}") }

    /** The runpath as stored, for checks. */
    fun read(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val elf = Elf.read(raf) ?: return@use null
            val entry = elf.dynamic?.firstOrNull { it.tag == DT_RUNPATH || it.tag == DT_RPATH } ?: return@use null
            elf.readString((elf.strtabOffset ?: return@use null) + entry.value)
        }
    }.getOrNull()

    /** `/data/data/com.termux/files/usr/lib` seen from `bin/x` is `$ORIGIN/../lib`. */
    internal fun rewrite(runpath: String, relativePath: String, termuxPrefix: String): String =
        runpath.split(':').joinToString(":") { element ->
            if (element != termuxPrefix && !element.startsWith("$termuxPrefix/")) return@joinToString element
            val target = element.removePrefix(termuxPrefix).split('/').filter { it.isNotEmpty() }
            val from = relativePath.split('/').filter { it.isNotEmpty() }.dropLast(1)
            var common = 0
            while (common < from.size && common < target.size && from[common] == target[common]) common++
            val parts = List(from.size - common) { ".." } + target.drop(common)
            if (parts.isEmpty()) "\$ORIGIN" else "\$ORIGIN/" + parts.joinToString("/")
        }

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_SONAME = 14L
    private const val DT_RPATH = 15L
    private const val DT_RUNPATH = 29L
    private const val DT_AUXILIARY = 0x7ffffffdL
    private const val DT_FILTER = 0x7fffffffL
    private const val SHT_DYNSYM = 11
    private const val SHT_GNU_VERDEF = 0x6ffffffd
    private const val SHT_GNU_VERNEED = 0x6ffffffe

    private class DynEntry(val tag: Long, val value: Long)

    private class Segment(val type: Int, val offset: Long, val vaddr: Long, val filesz: Long)

    private class Section(val type: Int, val offset: Long, val size: Long, val entsize: Long, val info: Int)

    /** Just enough of an ELF reader for the dynamic section and what refers to its strings. */
    private class Elf(
        private val raf: RandomAccessFile,
        private val is64: Boolean,
        private val segments: List<Segment>,
        private val sections: List<Section>,
    ) {
        val dynamic: List<DynEntry>? = segments.firstOrNull { it.type == PT_DYNAMIC }?.let(::readDynamic)

        val strtabOffset: Long? = dynamic?.firstOrNull { it.tag == DT_STRTAB }?.value?.let(::vaddrToOffset)

        fun readString(at: Long): String? {
            if (at < 0 || at >= raf.length()) return null
            raf.seek(at)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val b = raf.read()
                if (b <= 0) break
                out.write(b)
                if (out.size() > 4096) return null
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** Every `.dynstr` offset referenced by the dynamic section, symbols and version records. */
        fun stringReferences(): List<Long> = buildList {
            dynamic?.filter { it.tag in STRING_TAGS }?.forEach { add(it.value) }
            for (section in sections) {
                when (section.type) {
                    SHT_DYNSYM -> {
                        // st_name is the first word of a symbol in both classes.
                        val entsize = if (section.entsize > 0) section.entsize else if (is64) 24L else 16L
                        val count = (section.size / entsize).coerceAtMost(MAX_SYMBOLS)
                        val bytes = ByteArray((count * entsize).toInt())
                        raf.seek(section.offset)
                        raf.readFully(bytes)
                        for (i in 0 until count) {
                            val at = (i * entsize).toInt()
                            add(
                                (bytes[at].toLong() and 0xff) or
                                    ((bytes[at + 1].toLong() and 0xff) shl 8) or
                                    ((bytes[at + 2].toLong() and 0xff) shl 16) or
                                    ((bytes[at + 3].toLong() and 0xff) shl 24),
                            )
                        }
                    }

                    SHT_GNU_VERNEED -> walkVerneed(section, this)
                    SHT_GNU_VERDEF -> walkVerdef(section, this)
                }
            }
        }

        /** `Elf_Verneed` records (needed file names) and their `Elf_Vernaux` (version names). */
        private fun walkVerneed(section: Section, out: MutableList<Long>) {
            var at = section.offset
            var records = section.info.coerceIn(0, MAX_VERSION_RECORDS)
            while (records-- > 0) {
                raf.seek(at + 2)
                val count = u16(raf)
                out += u32(raf) // vn_file
                val aux = u32(raf)
                val next = u32(raf)
                var auxAt = at + aux
                var left = count.coerceAtMost(MAX_VERSION_RECORDS)
                while (left-- > 0) {
                    raf.seek(auxAt + 8)
                    out += u32(raf) // vna_name
                    val auxNext = u32(raf)
                    if (auxNext == 0L) break
                    auxAt += auxNext
                }
                if (next == 0L) break
                at += next
            }
        }

        /** `Elf_Verdef` records and their `Elf_Verdaux` names. */
        private fun walkVerdef(section: Section, out: MutableList<Long>) {
            var at = section.offset
            var records = section.info.coerceIn(0, MAX_VERSION_RECORDS)
            while (records-- > 0) {
                raf.seek(at + 6)
                val count = u16(raf)
                raf.skipBytes(4)
                val aux = u32(raf)
                val next = u32(raf)
                var auxAt = at + aux
                var left = count.coerceAtMost(MAX_VERSION_RECORDS)
                while (left-- > 0) {
                    raf.seek(auxAt)
                    out += u32(raf) // vda_name
                    val auxNext = u32(raf)
                    if (auxNext == 0L) break
                    auxAt += auxNext
                }
                if (next == 0L) break
                at += next
            }
        }

        private fun readDynamic(segment: Segment): List<DynEntry> {
            val entrySize = if (is64) 16 else 8
            val out = mutableListOf<DynEntry>()
            var at = segment.offset
            while (at + entrySize <= segment.offset + segment.filesz) {
                raf.seek(at)
                val tag = if (is64) u64(raf) else u32(raf)
                val value = if (is64) u64(raf) else u32(raf)
                if (tag == DT_NULL) break
                out += DynEntry(tag, value)
                at += entrySize
            }
            return out
        }

        private fun vaddrToOffset(vaddr: Long): Long? = segments
            .firstOrNull { it.type == PT_LOAD && vaddr >= it.vaddr && vaddr < it.vaddr + it.filesz }
            ?.let { vaddr - it.vaddr + it.offset }

        companion object {
            private val STRING_TAGS = setOf(DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH, DT_AUXILIARY, DT_FILTER)

            /** Bounds the walks, so a corrupt count cannot spin for minutes. */
            private const val MAX_VERSION_RECORDS = 4096
            private const val MAX_SYMBOLS = 2_000_000L

            fun read(raf: RandomAccessFile): Elf? {
                if (raf.length() < 64) return null
                val ident = ByteArray(16)
                raf.seek(0)
                raf.readFully(ident)
                if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                    ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
                ) {
                    return null
                }
                // Every Android ABI is little-endian; a big-endian file is not ours to patch.
                if (ident[5] != 1.toByte()) return null
                val is64 = when (ident[4]) {
                    2.toByte() -> true
                    1.toByte() -> false
                    else -> return null
                }
                val phoff: Long
                val shoff: Long
                val phentsize: Int
                val phnum: Int
                val shentsize: Int
                val shnum: Int
                if (is64) {
                    raf.seek(0x20); phoff = u64(raf); shoff = u64(raf)
                    raf.seek(0x36); phentsize = u16(raf); phnum = u16(raf); shentsize = u16(raf); shnum = u16(raf)
                } else {
                    raf.seek(0x1c); phoff = u32(raf); shoff = u32(raf)
                    raf.seek(0x2a); phentsize = u16(raf); phnum = u16(raf); shentsize = u16(raf); shnum = u16(raf)
                }

                val segments = (0 until phnum).map { i ->
                    raf.seek(phoff + i.toLong() * phentsize)
                    if (is64) {
                        val type = u32(raf).toInt()
                        raf.skipBytes(4)
                        val offset = u64(raf)
                        val vaddr = u64(raf)
                        raf.skipBytes(8)
                        Segment(type, offset, vaddr, u64(raf))
                    } else {
                        val type = u32(raf).toInt()
                        val offset = u32(raf)
                        val vaddr = u32(raf)
                        raf.skipBytes(4)
                        Segment(type, offset, vaddr, u32(raf))
                    }
                }

                val sections = if (shoff <= 0 || shnum == 0 || shoff + shnum.toLong() * shentsize > raf.length()) {
                    emptyList()
                } else {
                    (0 until shnum).map { i ->
                        raf.seek(shoff + i.toLong() * shentsize)
                        if (is64) {
                            raf.skipBytes(4)
                            val type = u32(raf).toInt()
                            raf.skipBytes(16)
                            val offset = u64(raf)
                            val size = u64(raf)
                            raf.skipBytes(4)
                            val info = u32(raf).toInt()
                            raf.skipBytes(8)
                            Section(type, offset, size, u64(raf), info)
                        } else {
                            raf.skipBytes(4)
                            val type = u32(raf).toInt()
                            raf.skipBytes(8)
                            val offset = u32(raf)
                            val size = u32(raf)
                            raf.skipBytes(4)
                            val info = u32(raf).toInt()
                            raf.skipBytes(4)
                            Section(type, offset, size, u32(raf), info)
                        }
                    }
                }
                return Elf(raf, is64, segments, sections)
            }

            private fun u16(raf: RandomAccessFile): Int {
                val b0 = raf.read()
                val b1 = raf.read()
                return b0 or (b1 shl 8)
            }

            private fun u32(raf: RandomAccessFile): Long {
                var value = 0L
                for (i in 0 until 4) value = value or (raf.read().toLong() shl (8 * i))
                return value
            }

            private fun u64(raf: RandomAccessFile): Long {
                var value = 0L
                for (i in 0 until 8) value = value or (raf.read().toLong() shl (8 * i))
                return value
            }
        }
    }
}
