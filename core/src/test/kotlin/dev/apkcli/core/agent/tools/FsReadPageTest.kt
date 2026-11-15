package dev.apkcli.core.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What fs_read shows of a file ([FsReadPage]), and what a missing path is answered with ([PathHints]). */
class FsReadPageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun lines(vararg texts: String, from: Int = 1) = texts.mapIndexed { i, text -> FsReadPage.Line(from + i, text) }

    @Test
    fun `a line is shown without its carriage return, and a very long one is cut whole characters short`() {
        assertEquals("val x = 1", FsReadPage.shown("val x = 1\r"))
        assertEquals("short", FsReadPage.shown("short"))
        val long = FsReadPage.shown("x".repeat(FsReadPage.MAX_LINE_CHARS - 1) + "\uD83D\uDE00" + "y".repeat(50))
        assertTrue(long, long.startsWith("x".repeat(FsReadPage.MAX_LINE_CHARS - 1) + " …"))
        assertTrue(long, long.endsWith("[line cut at ${FsReadPage.MAX_LINE_CHARS} of ${FsReadPage.MAX_LINE_CHARS + 51} characters]"))
    }

    @Test
    fun `lines are printed after their numbers`() {
        assertEquals("    9  a\n   10  b", FsReadPage.body(lines("a", "b", from = 9)))
    }

    @Test
    fun `a page is the longest run of whole lines that fits, and never empty`() {
        val all = lines("one", "two", "three", "four")
        assertEquals(all, FsReadPage.fit(all) { true })
        assertEquals(all.take(2), FsReadPage.fit(all) { it.length <= FsReadPage.body(all.take(2)).length })
        assertEquals(all.take(1), FsReadPage.fit(all) { false })
    }

    @Test
    fun `a final line break ends the last line, and an empty file has none`() {
        assertEquals(2, FsReadPage.lineCount(splitCount = 3, endsWithNewline = true, empty = false))
        assertEquals(2, FsReadPage.lineCount(splitCount = 2, endsWithNewline = false, empty = false))
        assertEquals(0, FsReadPage.lineCount(splitCount = 1, endsWithNewline = false, empty = true))

        val ended = temp.newFile("ended.txt").apply { writeText("a\nb\n") }
        val open = temp.newFile("open.txt").apply { writeText("a\nb") }
        assertTrue(FsReadPage.endsWithNewline(ended))
        assertFalse(FsReadPage.endsWithNewline(open))
        assertFalse(FsReadPage.endsWithNewline(temp.newFile("empty.txt")))
    }

    @Test
    fun `a missing name is matched by case, by stem, by containing and by a typo`() {
        assertEquals(listOf("readme.md"), PathHints.similar(listOf("readme.md", "notes.txt"), "README.md"))
        assertEquals(listOf("config.yaml"), PathHints.similar(listOf("config.yaml", "other.txt"), "config.yml"))
        assertEquals(listOf("MainActivity.kt"), PathHints.similar(listOf("MainActivity.kt", "Labels.kt"), "Main.kt"))
        assertEquals(listOf("SettingsScreen.kt"), PathHints.similar(listOf("SettingsScreen.kt", "Labels.kt"), "SetingsScreen.kt"))
        assertEquals(emptyList<String>(), PathHints.similar(listOf("a.kt", "b.kt"), "zzz.kt"))
    }

    @Test
    fun `at most three guesses, the likeliest first`() {
        val names = listOf("main.py", "Main.kt", "main.kt", "MainActivity.kt", "domain.kt")
        // The name asked for itself is no guess; the closest spelling decides within a kind.
        assertEquals(listOf("Main.kt", "main.py", "domain.kt"), PathHints.similar(names, "main.kt"))
    }

    @Test
    fun `a missing file is answered with what is next to it, a missing folder with where the path still holds`() {
        val root = temp.newFolder("project")
        File(root, "src").mkdirs()
        File(root, "src/Settings.kt").writeText("")
        val display = { file: File ->
            listOf("project", file.relativeTo(root).path).filter(String::isNotEmpty).joinToString("/").replace('\\', '/')
        }

        assertEquals(
            "project/src/Setings.kt does not exist. Did you mean project/src/Settings.kt?",
            PathHints.missing(File(root, "src/Setings.kt"), display),
        )
        assertEquals(
            "project/srcs/main/A.kt does not exist. The folder project/srcs does not exist; " +
                "the nearest one that does is project. Did you mean project/src?",
            PathHints.missing(File(root, "srcs/main/A.kt"), display),
        )
    }
}
