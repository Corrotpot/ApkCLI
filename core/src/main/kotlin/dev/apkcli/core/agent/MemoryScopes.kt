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
     * Longest one note may be.
     *
     * A cap exists because every note is in the system prompt on every
     * request, not because a fact is supposed to be short: 500 characters
     * was about three lines, too few to write out a build gotcha or a
     * constraint with its reason, and the agent had to drop the reason to
     * fit. 2 000 is a short paragraph, about 500 tokens. Anything longer is
     * documentation, and belongs in AGENTS.md, which the prompt also carries.
     */
    const val MAX_NOTE_CHARS = 2_000

    /**
     * Notes one scope puts in the prompt, in characters; past it the prompt
     * says how many are left and that `memory_read` lists them.
     *
     * Six notes at [MAX_NOTE_CHARS], or many more short ones. Both scopes at
     * once are about 6 000 tokens, which is 3% of a 200k window.
     */
    const val MAX_SCOPE_CHARS = 12_000

    /**
     * A project's facts, keyed by its directory. The format is the one the
     * first builds wrote; changing it would orphan every fact saved so far.
     */
    fun project(workspace: File): String = "workspace:" + workspace.absolutePath.hashCode().toString(16)
}
