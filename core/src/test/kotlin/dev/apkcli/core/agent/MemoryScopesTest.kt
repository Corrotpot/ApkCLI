package dev.apkcli.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How remembered facts read in the prompt: [MemoryScopes.promptLines]. */
class MemoryScopesTest {

    private val full = "x".repeat(MemoryScopes.MAX_NOTE_CHARS)

    @Test
    fun `a value's later lines are indented under its first`() {
        val text = MemoryScopes.promptLines(
            mapOf("release" to "Sign with the release key.\r\nSteps:  \n- bump the version\n\n- tag it  \n"),
            global = false,
        )

        assertEquals(
            "- release: Sign with the release key.\n  Steps:\n  - bump the version\n\n  - tag it\n",
            text,
        )
    }

    @Test
    fun `five notes at the cap fit in a scope and a sixth does not`() {
        val facts = (1..6).associate { "k$it" to full }

        val text = MemoryScopes.promptLines(facts, global = false)

        assertEquals(5, text.lines().count { it.startsWith("- k") })
        assertTrue(text.endsWith("- (1 more not shown; memory_read lists every one)\n"))
    }

    @Test
    fun `a note too long for the room left does not hide the short ones after it`() {
        // Sorted by key, as the store lists them.
        val facts = linkedMapOf<String, String>()
        (1..5).forEach { facts["a$it"] = full }
        facts["b"] = full
        facts["c"] = "yes"

        val text = MemoryScopes.promptLines(facts, global = false)

        assertFalse("- b: " in text)
        assertTrue("- c: yes\n" in text)
        assertTrue(text.endsWith("- (1 more not shown; memory_read lists every one)\n"))
    }

    @Test
    fun `left out facts of the global scope are read with global true`() {
        val facts = (1..6).associate { "k$it" to full }

        val text = MemoryScopes.promptLines(facts, global = true)

        assertTrue(text.endsWith("- (1 more not shown; memory_read with global: true lists every one)\n"))
    }
}
