package dev.apkcli.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The project's instruction file in the prompt: whole, or its beginning with where the rest is. */
class ProjectInstructionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun project(vararg files: Pair<String, String>): File {
        val dir = folder.newFolder()
        files.forEach { (name, text) -> File(dir, name).apply { parentFile?.mkdirs() }.writeText(text) }
        return dir
    }

    private fun load(dir: File): ProjectInstructions.Loaded =
        checkNotNull(ProjectInstructions.load(dir)) { "no instruction file read from $dir" }

    @Test
    fun `a short file goes in whole and says nothing more`() {
        val loaded = load(project("AGENTS.md" to "# Notes\n\nBuild with make.\n"))
        assertEquals("AGENTS.md", loaded.name)
        assertEquals("# Notes\n\nBuild with make.\n", loaded.text)
        assertFalse(loaded.cut)
        assertNull(loaded.note())
    }

    @Test
    fun `a long file goes in up to its last whole line, and the note says where the rest is`() {
        val lines = (1..3_000).map { "line $it: ${"x".repeat(20)} привет" }
        val loaded = load(project("AGENTS.md" to lines.joinToString("\n")))

        assertTrue(loaded.cut)
        assertTrue(loaded.text.toByteArray().size <= ProjectInstructions.MAX_BYTES)
        val shown = loaded.text.split('\n')
        // Whole lines only, from the first, and nothing mangled at the cut.
        assertEquals(lines.subList(0, shown.size), shown)
        assertEquals(shown.size, loaded.restFromLine)
        val note = checkNotNull(loaded.note())
        assertTrue(note, note.contains("fs_read(path: \"AGENTS.md\", offset: ${shown.size})"))
        assertTrue(note, note.contains("AGENTS.md is ${loaded.totalBytes / 1024} KB"))
    }

    @Test
    fun `a long AGENTS_md is still the one read, not the next name`() {
        val loaded = load(
            project(
                "AGENTS.md" to "a\n".repeat(ProjectInstructions.MAX_BYTES),
                "CLAUDE.md" to "short",
            ),
        )
        assertEquals("AGENTS.md", loaded.name)
        assertTrue(loaded.cut)
    }

    @Test
    fun `the next name is read when there is no AGENTS_md, and none when there is nothing`() {
        assertEquals(".github/copilot-instructions.md", load(project(".github/copilot-instructions.md" to "be brief")).name)
        assertNull(ProjectInstructions.load(project("README.md" to "not instructions")))
    }

    @Test
    fun `a long file with no line break goes in as far as it came, read again from its start`() {
        // One byte ahead, so the cut falls inside a two-byte character.
        val loaded = load(project("AGENTS.md" to "a" + "я".repeat(ProjectInstructions.MAX_BYTES)))
        assertTrue(loaded.cut)
        assertEquals(0, loaded.restFromLine)
        assertFalse("no half character at the cut", loaded.text.endsWith('\uFFFD'))
        assertTrue(loaded.text.startsWith("a"))
        assertTrue(loaded.text.drop(1).all { it == 'я' })
        assertEquals((ProjectInstructions.MAX_BYTES - 1) / 2, loaded.text.length - 1)
    }
}
