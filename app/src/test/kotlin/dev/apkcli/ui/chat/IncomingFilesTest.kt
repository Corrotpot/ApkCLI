package dev.apkcli.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What becomes of a file opened from another app or picked from the device ([IncomingFiles]). */
class IncomingFilesTest {

    @Test
    fun `an instruction file keeps the name agents look for, whatever its copy number or case`() {
        assertEquals("AGENTS.md", IncomingFiles.targetName("AGENTS.md"))
        assertEquals("AGENTS.md", IncomingFiles.targetName("agents.md"))
        assertEquals("AGENTS.md", IncomingFiles.targetName("AGENTS (1).md"))
        assertEquals("AGENTS.md", IncomingFiles.targetName("AGENTS-2.md"))
        assertEquals("CLAUDE.md", IncomingFiles.targetName("claude [3].md"))
        assertTrue(IncomingFiles.isInstructions("AGENTS.md"))
    }

    @Test
    fun `any other file keeps its own name, without a path`() {
        assertEquals("notes (1).md", IncomingFiles.targetName("notes (1).md"))
        assertEquals("main.py", IncomingFiles.targetName("Download/sub/main.py"))
        assertEquals("config.yaml", IncomingFiles.targetName("C:\\Users\\me\\config.yaml"))
        assertEquals("README", IncomingFiles.targetName("README"))
        assertEquals("AGENTS.md.bak", IncomingFiles.targetName("AGENTS.md.bak"))
        assertFalse(IncomingFiles.isInstructions("notes.md"))
    }

    @Test
    fun `nothing usable is no name at all`() {
        assertNull(IncomingFiles.targetName(""))
        assertNull(IncomingFiles.targetName("   "))
        assertNull(IncomingFiles.targetName("dir/.."))
        assertNull(IncomingFiles.targetName("a\u0000b.md"))
        assertNull(IncomingFiles.targetName("x".repeat(300)))
    }

    @Test
    fun `two picked files of one name get a copy number, a dotfile keeps its dot`() {
        assertEquals("notes.md", IncomingFiles.unique("notes.md", emptySet()))
        assertEquals("notes (2).md", IncomingFiles.unique("notes.md", setOf("notes.md")))
        assertEquals("notes (3).md", IncomingFiles.unique("notes.md", setOf("notes.md", "notes (2).md")))
        assertEquals(".gitignore (2)", IncomingFiles.unique(".gitignore", setOf(".gitignore")))
        assertEquals("Makefile (2)", IncomingFiles.unique("Makefile", setOf("Makefile")))
    }

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `files go into their folder, replacing one of the name and never a folder`() {
        val project = temp.newFolder("app")
        val into = File(project, "src").apply { mkdirs() }
        File(into, "old.md").writeText("before")
        File(into, "busy").mkdirs()
        File(into, "busy/kept.txt").writeText("kept")
        val staging = temp.newFolder("staging")
        fun staged(name: String, text: String) = File(staging, name).apply { writeText(text) }

        val placed = IncomingFiles.place(
            listOf(
                "new.kt" to staged("new.kt", "fun main() {}"),
                "old.md" to staged("old.md", "after"),
                "busy" to staged("busy", "a file named like the folder"),
            ),
            into,
        )

        assertEquals(listOf("new.kt", "old.md"), placed.added.map { it.name })
        assertEquals(listOf("busy"), placed.refused)
        assertEquals("fun main() {}", File(into, "new.kt").readText())
        assertEquals("after", File(into, "old.md").readText())
        assertEquals("kept", File(into, "busy/kept.txt").readText())
        assertFalse(File(staging, "new.kt").exists())
    }

    @Test
    fun `a folder outside the project means its root, and folders are named as the tree names them`() {
        val project = temp.newFolder("app")
        val inside = File(project, "src/main").apply { mkdirs() }
        val outside = temp.newFolder("elsewhere")
        val root = project.canonicalFile

        assertEquals(inside.canonicalFile, IncomingFiles.intoFolder(inside, project))
        assertEquals(root, IncomingFiles.intoFolder(outside, project))
        assertEquals(root, IncomingFiles.intoFolder(File(project, "src/../.."), project))
        assertEquals(root, IncomingFiles.intoFolder(null, project))
        assertEquals("app", IncomingFiles.folderLabel(root, project))
        assertEquals("app/src/main", IncomingFiles.folderLabel(inside, project))
        assertTrue(IncomingFiles.isInside(File(inside, "x.kt"), project))
        assertFalse(IncomingFiles.isInside(outside, project))
    }

    @Test
    fun `a notice names the first few files and marks the rest`() {
        assertEquals("a.md", IncomingFiles.namesText(listOf("a.md")))
        assertEquals("a, b, c, d, …", IncomingFiles.namesText(listOf("a", "b", "c", "d", "e", "f")))
    }
}
