package dev.apkcli.ui.chat

import dev.apkcli.ui.chat.markdown.MarkdownParser
import dev.apkcli.ui.chat.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A file shared from another app, read as text and pasted into the composer: [SharedFiles]. */
class SharedFilesTest {

    @Test
    fun `UTF-8 is text, without its byte order mark`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("Ошибка: exit 137\n", SharedFiles.textOf(bom + "Ошибка: exit 137\n".toByteArray()))
    }

    @Test
    fun `a NUL, or bytes that are not UTF-8, are not text`() {
        // The start of a zip, and "При" in Windows-1251.
        assertNull(SharedFiles.textOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)))
        assertNull(SharedFiles.textOf(byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte())))
    }

    @Test
    fun `a pasted file is its name over one code block, whatever fences it holds`() {
        val pasted = SharedFiles.pasted("notes.md", "Run:\n```sh\n./gradlew test\n```\n")
        assertEquals("notes.md:", pasted.substringBefore('\n'))
        val blocks = MarkdownParser.parse(pasted.substringAfter('\n'))
        assertEquals(1, blocks.size)
        assertEquals("Run:\n```sh\n./gradlew test\n```", (blocks.single() as MdBlock.Code).code)
    }
}
