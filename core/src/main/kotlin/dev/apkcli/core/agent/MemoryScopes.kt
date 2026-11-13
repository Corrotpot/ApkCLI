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
     * request, not because a fact is supposed to be short: 500 characters,
     * some 80 words, were too few to write out a build gotcha or a
     * constraint with its reason, and the agent had to drop the reason to
     * fit. 2 000 is 300 to 350 words, a paragraph or two, which the token
     * estimator counts as about 600 tokens in English and 950 in Russian.
     * Anything longer is documentation, and belongs in AGENTS.md, which the
     * prompt also carries.
     */
    const val MAX_NOTE_CHARS = 2_000

    /**
     * Notes one scope puts in the prompt, in characters; past it the prompt
     * says how many are left and that `memory_read` lists them.
     *
     * Five notes at [MAX_NOTE_CHARS], since each line carries its key too,
     * or many more short ones. Both scopes full are about 7 500 tokens in
     * English and 11 500 in Russian: 4% and 6% of a 200k window.
     */
    const val MAX_SCOPE_CHARS = 12_000

    /**
     * [facts] as the prompt shows them: "- key: value" each, up to
     * [MAX_SCOPE_CHARS], then how many were left out and how to read them.
     *
     * A value's later lines are indented under its first, so a list inside a
     * note reads as part of it and not as notes of their own. A note too long
     * for the room left is passed over rather than ending the list: facts come
     * sorted by key, and one long note early on used to hide every short one
     * after it.
     */
    fun promptLines(facts: Map<String, String>, global: Boolean): String = buildString {
        var used = 0
        var shown = 0
        for ((key, value) in facts) {
            val body = value.trim().lines().mapIndexed { index, line ->
                if (index == 0 || line.isBlank()) line.trimEnd() else "  ${line.trimEnd()}"
            }.joinToString("\n")
            val line = "- $key: $body"
            if (used + line.length > MAX_SCOPE_CHARS) continue
            appendLine(line)
            used += line.length + 1
            shown++
        }
        if (shown < facts.size) {
            val read = if (global) "memory_read with global: true" else "memory_read"
            appendLine("- (${facts.size - shown} more not shown; $read lists every one)")
        }
    }

    /**
     * A project's facts, keyed by its directory. The format is the one the
     * first builds wrote; changing it would orphan every fact saved so far.
     */
    fun project(workspace: File): String = "workspace:" + workspace.absolutePath.hashCode().toString(16)
}
