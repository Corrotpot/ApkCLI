package dev.apkcli.core.agent

import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.model.ApprovalRule
import dev.apkcli.core.model.ToolPermission
import dev.apkcli.core.store.BuiltInAgents
import dev.apkcli.core.store.withBuiltInAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three approval modes, Settings › Rules, and settings from builds that
 * had the Engineer, Explorer and Runner agents.
 */
class ApprovalModesTest {

    private val registry = Tools.registry(Tools.Options(search = null, packageManagementAvailable = true))

    private fun agents(settings: AppSettings = AppSettings()) = settings.withBuiltInAgents().agents.associateBy { it.id }

    private fun asks(agent: AgentDefinition, tool: String): Boolean {
        val answer = registry.needsApproval(tool, agent)
        assertNotNull("unknown tool $tool", answer)
        return answer!!
    }

    @Test
    fun `confirm asks before every change and never about a read`() {
        val confirm = agents().getValue(BuiltInAgents.CONFIRM)
        for (tool in listOf("fs_write", "fs_edit", "fs_delete", "fs_move", "revert_edit", "sh_exec", "sh_background", "pkg_install", "subagent_dispatch")) {
            assertTrue("$tool should ask under Confirm", asks(confirm, tool))
        }
        for (tool in listOf("fs_read", "grep", "fs_list", "web_fetch", "sh_job_output", "task_list", "memory_write")) {
            assertFalse("$tool should not ask under Confirm", asks(confirm, tool))
        }
    }

    @Test
    fun `bypass asks about nothing, deletions included`() {
        val bypass = agents().getValue(BuiltInAgents.BYPASS)
        for (tool in registry.all.map { it.name }) {
            assertFalse("$tool should not ask under Bypass Mode", asks(bypass, tool))
        }
    }

    @Test
    fun `from rules lets through what is ticked and asks about the rest`() {
        val defaults = agents().getValue(BuiltInAgents.RULES)
        assertEquals(ApprovalRule.DEFAULT, defaults.autoApproved)
        assertFalse(asks(defaults, "fs_write"))
        assertFalse(asks(defaults, "fs_apply_patch"))
        assertFalse(asks(defaults, "subagent_dispatch"))
        assertTrue(asks(defaults, "sh_exec"))
        assertTrue(asks(defaults, "fs_delete"))
        assertTrue(asks(defaults, "pkg_install"))
        assertFalse("reads never ask", asks(defaults, "fs_read"))

        val commandsToo = agents(AppSettings(approvalRules = setOf(ApprovalRule.COMMANDS, ApprovalRule.DELETE)))
            .getValue(BuiltInAgents.RULES)
        assertFalse(asks(commandsToo, "sh_exec"))
        assertFalse(asks(commandsToo, "build_run"))
        assertFalse("a deletion follows its rule like any change", asks(commandsToo, "fs_delete"))
        assertTrue("edits are no longer ticked", asks(commandsToo, "fs_write"))

        val nothing = agents(AppSettings(approvalRules = emptySet())).getValue(BuiltInAgents.RULES)
        assertTrue(asks(nothing, "fs_write"))
        assertFalse(asks(nothing, "grep"))
    }

    @Test
    fun `every tool that can ask belongs to a rule`() {
        val confirm = agents().getValue(BuiltInAgents.CONFIRM)
        val ungrouped = registry.all.map { it.name }.filter { asks(confirm, it) && ApprovalRule.of(it) == null }
        assertEquals("tools that could ask but no rule lets through", emptyList<String>(), ungrouped)
    }

    @Test
    fun `the explorer is read-only and not offered as a mode`() {
        val explorer = agents().getValue(BuiltInAgents.EXPLORER)
        assertTrue(explorer.hidden)
        assertEquals(ToolPermission.DENY, explorer.permissionFor("fs_write"))
        assertEquals(ToolPermission.DENY, explorer.permissionFor("sh_exec"))
        assertEquals(ToolPermission.ALLOW, explorer.permissionFor("fs_read"))
        assertEquals(
            listOf(BuiltInAgents.CONFIRM, BuiltInAgents.RULES, BuiltInAgents.BYPASS),
            AppSettings().withBuiltInAgents().agents.filterNot { it.hidden }.map { it.id },
        )
    }

    @Test
    fun `settings of earlier builds become the mode their agent amounts to`() {
        val engineer = AgentDefinition(id = "builtin-engineer", name = "Engineer", builtIn = true, modelId = "m-engineer")
        val runner = AgentDefinition(
            id = "builtin-runner", name = "Runner", builtIn = true, modelId = "m-runner",
            autonomy = AgentDefinition.Autonomy.AUTOPILOT,
        )
        val explorer = AgentDefinition(id = "builtin-explorer", name = "Explorer", builtIn = true, autonomy = AgentDefinition.Autonomy.READ_ONLY)
        val mine = AgentDefinition(id = "my-reviewer", name = "Reviewer")
        val old = AppSettings(agents = listOf(engineer, explorer, runner, mine))

        val fromRunner = old.copy(activeAgentId = "builtin-runner").withBuiltInAgents()
        assertEquals(BuiltInAgents.BYPASS, fromRunner.activeAgentId)
        assertEquals(
            listOf(BuiltInAgents.CONFIRM, BuiltInAgents.RULES, BuiltInAgents.BYPASS, BuiltInAgents.EXPLORER, "my-reviewer"),
            fromRunner.agents.map { it.id },
        )
        assertEquals("the model chosen for the Runner stays", "m-runner", fromRunner.agents.first { it.id == BuiltInAgents.BYPASS }.modelId)
        assertEquals("m-engineer", fromRunner.agents.first { it.id == BuiltInAgents.CONFIRM }.modelId)

        assertEquals(BuiltInAgents.CONFIRM, old.copy(activeAgentId = "builtin-engineer").withBuiltInAgents().activeAgentId)
        assertEquals(BuiltInAgents.CONFIRM, old.copy(activeAgentId = "builtin-explorer").withBuiltInAgents().activeAgentId)
        assertEquals(BuiltInAgents.CONFIRM, old.copy(activeAgentId = null).withBuiltInAgents().activeAgentId)
        assertEquals("my-reviewer", old.copy(activeAgentId = "my-reviewer").withBuiltInAgents().activeAgentId)
        assertEquals(BuiltInAgents.RULES, old.copy(activeAgentId = BuiltInAgents.RULES).withBuiltInAgents().activeAgentId)
    }
}
