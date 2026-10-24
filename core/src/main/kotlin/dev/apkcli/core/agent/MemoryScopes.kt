package dev.apkcli.core.agent

import java.io.File

/**
 * Where remembered facts are kept: one scope per project, one shared by all.
 *
 * In one place because three readers must agree — the memory tools, the
 * prompt that shows the facts, and the screen where the user edits them. A
 * mismatch would save facts nobody ever reads back.
 */
object MemoryScopes {
    /** Facts for every project: the user, their setup, their preferences. */
    const val GLOBAL = "global"

    /**
     * A project's facts, keyed by its directory. The format is the one the
     * first builds wrote; changing it would orphan every fact saved so far.
     */
    fun project(workspace: File): String = "workspace:" + workspace.absolutePath.hashCode().toString(16)
}
