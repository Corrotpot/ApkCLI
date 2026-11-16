package dev.apkcli.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prose wraps by default and code does not, each kind switched on its own ([isProse], [EditorViewModel.UiState]). */
class WordWrapTest {

    @Test
    fun `Markdown, text and the instruction files are prose, code is not`() {
        listOf("AGENTS.md", "notes.MARKDOWN", "todo.txt", "guide.rst", ".cursorrules", "README", "LICENSE").forEach {
            assertTrue(it, isProse(it))
        }
        listOf("Main.kt", "app.js", "build.gradle.kts", "Makefile", "data.json", "readme.py").forEach {
            assertFalse(it, isProse(it))
        }
    }

    @Test
    fun `a prose file wraps until switched off, and code keeps its own setting`() {
        val prose = EditorViewModel.UiState(active = "/p/AGENTS.md")
        val code = EditorViewModel.UiState(active = "/p/Main.kt")
        assertTrue(prose.wordWrap)
        assertFalse(code.wordWrap)
        assertFalse(prose.copy(wrapProse = false).wordWrap)
        assertTrue(code.copy(wrapCode = true).wordWrap)
        assertEquals(false, EditorViewModel.UiState().wordWrap)
    }
}
