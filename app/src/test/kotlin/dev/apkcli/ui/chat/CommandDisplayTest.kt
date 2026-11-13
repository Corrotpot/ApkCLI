package dev.apkcli.ui.chat

import dev.apkcli.core.model.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A command's card once its result is stored: drawn as while it ran, not as the model's text ([commandDisplay]). */
class CommandDisplayTest {

    @Test
    fun `a command that printed nothing shows no output, not the model's words`() {
        val use = Block.ToolUse("c1", "sh_exec", """{"command":"mkdir -p out\ncp a out/"}""")
        val display = checkNotNull(commandDisplay(use, Block.ToolResult("c1", "sh_exec", "(no output)\n\nExit code 0 in 15ms.")))
        assertEquals("mkdir -p out\ncp a out/", display.command)
        assertEquals("", display.output)
        assertEquals(0, display.exitCode)
        assertEquals(15L, display.durationMs)
    }

    @Test
    fun `a build's card names the command it ran`() {
        val use = Block.ToolUse("c2", "test_run", "{}")
        val result = Block.ToolResult("c2", "test_run", "`./gradlew test` (Gradle)\n\nBUILD FAILED\n\nExit code 1 in 52000ms.", isError = true)
        val display = checkNotNull(commandDisplay(use, result))
        assertEquals("./gradlew test", display.command)
        assertEquals("BUILD FAILED", display.output)
        assertEquals(1, display.exitCode)
    }

    @Test
    fun `other tools keep their own card`() {
        assertNull(commandDisplay(Block.ToolUse("c3", "fs_read", """{"path":"a"}"""), Block.ToolResult("c3", "fs_read", "a  lines 1-1 of 1")))
    }
}
