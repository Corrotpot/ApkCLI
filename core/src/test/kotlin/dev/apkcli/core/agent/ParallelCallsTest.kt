package dev.apkcli.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which calls of a batch may run side by side. */
class ParallelCallsTest {

    private val registry = Tools.registry(Tools.Options(search = null, packageManagementAvailable = true))

    @Test
    fun `explorers run together, workers and agents of the user's own run alone`() {
        assertTrue(registry.parallelSafe("subagent_dispatch", """{"agent":"builtin-explorer","prompt":"find the router"}"""))
        assertTrue(registry.parallelSafe("subagent_dispatch", """{"agent":" builtin-explorer ","prompt":"x"}"""))
        assertFalse(registry.parallelSafe("subagent_dispatch", """{"agent":"builtin-worker","prompt":"fix it"}"""))
        assertFalse(registry.parallelSafe("subagent_dispatch", """{"agent":"my-reviewer","prompt":"x"}"""))
        assertFalse("arguments that do not parse", registry.parallelSafe("subagent_dispatch", """{"agent":"builtin-explorer","""))
    }

    @Test
    fun `reads run together, changes and unknown tools alone`() {
        assertTrue(registry.parallelSafe("fs_read", """{"path":"a.txt"}"""))
        assertTrue(registry.parallelSafe("grep", """{"pattern":"x"}"""))
        assertFalse(registry.parallelSafe("fs_edit", """{"path":"a.txt"}"""))
        assertFalse(registry.parallelSafe("sh_exec", """{"command":"ls"}"""))
        assertFalse(registry.parallelSafe("no_such_tool", "{}"))
    }
}
