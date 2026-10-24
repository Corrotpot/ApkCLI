package dev.apkcli.core.agent

import dev.apkcli.core.agent.tools.BuildRunTool
import dev.apkcli.core.agent.tools.ConversationReadTool
import dev.apkcli.core.agent.tools.ConversationSearchTool
import dev.apkcli.core.agent.tools.EditorOpenTool
import dev.apkcli.core.agent.tools.EnvInfoTool
import dev.apkcli.core.agent.tools.FsApplyPatchTool
import dev.apkcli.core.agent.tools.FsDeleteTool
import dev.apkcli.core.agent.tools.FsEditTool
import dev.apkcli.core.agent.tools.FsGlobTool
import dev.apkcli.core.agent.tools.FsListTool
import dev.apkcli.core.agent.tools.FsMoveTool
import dev.apkcli.core.agent.tools.FsMultiEditTool
import dev.apkcli.core.agent.tools.FsReadTool
import dev.apkcli.core.agent.tools.FsStatTool
import dev.apkcli.core.agent.tools.FsWriteTool
import dev.apkcli.core.agent.tools.GrepTool
import dev.apkcli.core.agent.tools.MemoryForgetTool
import dev.apkcli.core.agent.tools.MemoryReadTool
import dev.apkcli.core.agent.tools.MemoryWriteTool
import dev.apkcli.core.agent.tools.OutlineTool
import dev.apkcli.core.agent.tools.PkgInstallTool
import dev.apkcli.core.agent.tools.PkgRemoveTool
import dev.apkcli.core.agent.tools.PkgSearchTool
import dev.apkcli.core.agent.tools.ProjectTreeTool
import dev.apkcli.core.agent.tools.RevertEditTool
import dev.apkcli.core.agent.tools.ShBackgroundTool
import dev.apkcli.core.agent.tools.ShExecTool
import dev.apkcli.core.agent.tools.ShJobKillTool
import dev.apkcli.core.agent.tools.ShJobOutputTool
import dev.apkcli.core.agent.tools.ShJobsTool
import dev.apkcli.core.agent.tools.SubagentDispatchTool
import dev.apkcli.core.agent.tools.SymbolSearchTool
import dev.apkcli.core.agent.tools.TaskListTool
import dev.apkcli.core.agent.tools.TestRunTool
import dev.apkcli.core.agent.tools.SearchBackend
import dev.apkcli.core.agent.tools.WebFetchTool
import dev.apkcli.core.agent.tools.WebSearchTool

/**
 * Builds the tool catalogue.
 *
 * Order here is the order the model sees, and it is not arbitrary: the tools a
 * coding agent reaches for constantly come first. Models weight earlier items
 * in a long list slightly more, and more practically, a human debugging a
 * prompt reads top to bottom.
 *
 * Two tools are conditional rather than always declared:
 *
 *  * `web_search` is keyless by default (DuckDuckGo), but the user can switch
 *    it off, or pick a custom API and leave the URL blank. Either way it is not
 *    declared: a tool that always fails costs tokens on every request.
 *  * `pkg_install`, `pkg_search` and `pkg_remove` exist only where an installed
 *    binary can run: the power build, on a CPU Termux builds for. On the Play
 *    build the environment section of the system prompt says why nothing can
 *    be installed, which costs fewer tokens than three tools that only refuse.
 */
object Tools {

    data class Options(
        /** Where `web_search` gets results, or null to omit the tool. */
        val search: SearchBackend? = SearchBackend.DuckDuckGo(),
        /** Register the `pkg_*` tools. */
        val packageManagementAvailable: Boolean = true,
    )

    fun registry(options: Options = Options()): ToolRegistry = ToolRegistry(
        buildList {
            // --- reading and searching: the bulk of any session -------------
            add(FsReadTool())
            add(GrepTool())
            add(FsGlobTool())
            add(SymbolSearchTool())
            add(OutlineTool())
            add(FsListTool())
            add(ProjectTreeTool())
            add(FsStatTool())

            // --- editing ----------------------------------------------------
            add(FsEditTool())
            add(FsMultiEditTool())
            add(FsWriteTool())
            add(FsApplyPatchTool())
            add(FsMoveTool())
            add(FsDeleteTool())
            add(RevertEditTool())

            // --- running things ---------------------------------------------
            add(ShExecTool())
            add(BuildRunTool())
            add(TestRunTool())
            add(ShBackgroundTool())
            add(ShJobOutputTool())
            add(ShJobsTool())
            add(ShJobKillTool())
            add(EnvInfoTool())

            // No version control for now. The git_* tools in GitTools.kt are
            // kept but not registered: a declared tool the app cannot back is
            // schema tokens on every request and a failed call when used.

            // --- planning and delegation ------------------------------------
            add(TaskListTool())
            add(SubagentDispatchTool())
            add(EditorOpenTool())

            // --- memory and history -----------------------------------------
            add(ConversationSearchTool())
            add(ConversationReadTool())
            add(MemoryReadTool())
            add(MemoryWriteTool())
            add(MemoryForgetTool())

            // --- the web ----------------------------------------------------
            add(WebFetchTool())
            options.search?.let { add(WebSearchTool(it)) }

            // --- packages (power build only) --------------------------------
            if (options.packageManagementAvailable) {
                add(PkgInstallTool())
                add(PkgSearchTool())
                add(PkgRemoveTool())
            }
        },
    )

    /**
     * Read-only subset, for the Explorer agent and for subagents dispatched to
     * investigate.
     *
     * Kept next to the registry so the two cannot drift: a tool added above
     * that should be investigable has to be named here deliberately.
     */
    val readOnlyToolNames: Set<String> = setOf(
        "fs_read", "grep", "fs_glob", "symbol_search", "outline",
        "fs_list", "project_tree", "fs_stat",
        "conversation_search", "conversation_read", "memory_read",
        "env_info", "web_fetch", "pkg_search",
        // Reading what a terminal printed changes nothing, and an investigation
        // often starts from the error the user is looking at in one.
        "sh_jobs", "sh_job_output",
    )
}
