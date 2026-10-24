package dev.apkcli.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the composer's words are as a command: the plaque's rows, and what sending them does. */
class ChatCommandsTest {

    private val compact = ChatCommands.Command.COMPACT

    private fun typed(words: String): ChatCommands.Typed = checkNotNull(ChatCommands.parse(words)) { "not a command: $words" }

    @Test
    fun `a slash and the start of a name offer the commands it can become`() {
        for (words in listOf("/", "/c", "/comp", "  /compa")) {
            val typed = typed(words)
            assertNull(words, typed.exact)
            assertEquals(words, listOf(compact), typed.matches)
        }
    }

    @Test
    fun `the whole name is the command, with the words after it`() {
        val bare = typed("/compact")
        assertEquals(compact, bare.exact)
        assertEquals("", bare.args)
        assertEquals(listOf(compact), bare.matches)

        val focused = typed("/COMPACT   keep the API   \n")
        assertEquals(compact, focused.exact)
        assertEquals("keep the API", focused.args)

        val lines = typed("/compact\nthe build steps\nand the keys")
        assertEquals(compact, lines.exact)
        assertEquals("the build steps\nand the keys", lines.args)
    }

    @Test
    fun `a name no command has offers nothing`() {
        val unknown = typed("/compactx")
        assertNull(unknown.exact)
        assertTrue(unknown.matches.isEmpty())

        val followed = typed("/comp the tests")
        assertNull(followed.exact)
        assertTrue(followed.matches.isEmpty())
    }

    @Test
    fun `paths, numbers and words are ordinary messages`() {
        for (words in listOf("/etc/hosts", "/etc/hosts is wrong", "/2", "/compact/x", "hello /compact", "", "compact")) {
            assertNull(words, ChatCommands.parse(words))
        }
    }
}
