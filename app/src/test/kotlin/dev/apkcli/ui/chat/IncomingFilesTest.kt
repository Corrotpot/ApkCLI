package dev.apkcli.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name a file opened from another app gets in the project's root ([IncomingFiles]). */
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
        assertNull(IncomingFiles.targetName("x".repeat(300)))
    }
}
