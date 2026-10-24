package dev.apkcli.core.model

import kotlinx.serialization.Serializable

/**
 * The structured summary that replaces archived turns when the context window
 * fills up.
 *
 * Why a schema instead of a paragraph of prose: a free-form "summary of the
 * conversation so far" loses exactly the things that matter after it is written
 * — which file held the bug, which approach was already tried and failed, what
 * the user said not to do. The model then re-explores dead ends and re-reads
 * files it has already read, which costs more tokens than the summary saved.
 *
 * Every field here answers a question that actually comes up on turn 200 of a
 * long session. The digest is **cumulative**: each compaction merges the
 * previous digest with the newly archived span rather than re-summarising from
 * scratch, so detail accumulates instead of degrading through repeated lossy
 * passes.
 *
 * The verbatim history is never deleted — it stays in SQLite and stays indexed
 * for FTS. The digest is a fast path, not an archive.
 */
@Serializable
data class ConversationDigest(
    val version: Int = 1,
    val coversFromSeq: Long = 0,
    val coversToSeq: Long = 0,
    val createdAt: Long = 0,
    val model: String? = null,

    /** What the user is ultimately trying to achieve. Rarely changes. */
    val objective: String = "",

    /** Where things stand right now. Rewritten each compaction. */
    val currentState: String = "",

    /** Choices already made, with the reason. Prevents re-litigating them. */
    val decisions: List<Decision> = emptyList(),

    /** Hard requirements and prohibitions stated by the user. */
    val constraints: List<String> = emptyList(),

    /** What is known about which files, so they need not be re-read. */
    val fileMap: List<FileNote> = emptyList(),

    /** Changes already applied to the workspace. */
    val edits: List<EditNote> = emptyList(),

    /** Commands whose outcome is worth remembering (build works, tests fail). */
    val commands: List<CommandNote> = emptyList(),

    /** Unfinished business. */
    val openThreads: List<String> = emptyList(),

    /**
     * Things that were tried and did not work.
     *
     * The single highest-value field in the whole structure: without it a
     * compacted agent will cheerfully retry the approach that already failed
     * twice, because the evidence was in the part of the history that got
     * summarised away.
     */
    val failedApproaches: List<FailedApproach> = emptyList(),

    /** Standing preferences: style, language, verbosity, tooling. */
    val userPreferences: List<String> = emptyList(),

    /** Project-specific vocabulary, so names stay consistent. */
    val glossary: List<Term> = emptyList(),

    val nextSteps: List<String> = emptyList(),

    /**
     * Short excerpts to carry through unchanged — an exact error message, a
     * required API signature, a literal the user supplied. Paraphrasing these
     * is how a compacted session starts getting details subtly wrong.
     */
    val verbatimPins: List<String> = emptyList(),

    /** What `/compact <words>` asked to keep in detail, with those details. */
    val keptByRequest: List<String> = emptyList(),

    /*
     * About the compaction that wrote this version, for the chat's seam: set
     * by the app after the model has written the rest, and never rendered
     * for the model.
     */

    /** [TRIGGER_COMMAND] for /compact, [TRIGGER_AUTO] when the window filled; null from builds before either. */
    val trigger: String? = null,

    /** The words after /compact, as the user typed them. */
    val focus: String? = null,

    /** Tokens the window gave back: what was archived, less what the summary grew by. */
    val tokensFreed: Long = 0,
) {

    @Serializable
    data class Decision(
        val what: String,
        val why: String = "",
        val atSeq: Long = 0,
    )

    @Serializable
    data class FileNote(
        val path: String,
        /** What this file is and what was learned about it. */
        val summary: String,
        val symbols: List<String> = emptyList(),
        val lastReadSeq: Long = 0,
    )

    @Serializable
    data class EditNote(
        val path: String,
        val description: String,
        val applied: Boolean = true,
        val atSeq: Long = 0,
    )

    @Serializable
    data class CommandNote(
        val command: String,
        val outcome: String,
        val exitCode: Int? = null,
        val atSeq: Long = 0,
    )

    @Serializable
    data class FailedApproach(
        val approach: String,
        val whyItFailed: String,
        val atSeq: Long = 0,
    )

    @Serializable
    data class Term(val term: String, val meaning: String)

    /**
     * Render for inclusion in the prompt.
     *
     * Markdown with stable headings, because the model reads this back and
     * consistent structure makes it reliably findable. Empty sections are
     * omitted rather than emitted as "none", which would waste tokens on every
     * single request for the rest of the session.
     */
    fun render(): String = buildString {
        appendLine("# Session digest (v$version, covers turns $coversFromSeq–$coversToSeq)")
        appendLine()
        appendLine(
            "This replaces earlier turns that no longer fit in the context window. " +
                "The full history is still stored and searchable — use " +
                "`conversation_search` to find a specific exchange and " +
                "`conversation_read` to read it verbatim.",
        )
        appendLine()

        if (objective.isNotBlank()) {
            appendLine("## Objective"); appendLine(objective); appendLine()
        }
        if (currentState.isNotBlank()) {
            appendLine("## Current state"); appendLine(currentState); appendLine()
        }
        if (keptByRequest.isNotEmpty()) {
            appendLine("## Kept in detail, as the user asked")
            keptByRequest.forEach { appendLine("- $it") }
            appendLine()
        }
        if (constraints.isNotEmpty()) {
            appendLine("## Constraints")
            constraints.forEach { appendLine("- $it") }
            appendLine()
        }
        if (userPreferences.isNotEmpty()) {
            appendLine("## User preferences")
            userPreferences.forEach { appendLine("- $it") }
            appendLine()
        }
        if (decisions.isNotEmpty()) {
            appendLine("## Decisions already made")
            decisions.forEach {
                appendLine("- ${it.what}" + if (it.why.isNotBlank()) " — ${it.why}" else "")
            }
            appendLine()
        }
        if (failedApproaches.isNotEmpty()) {
            appendLine("## Already tried and failed — do not repeat")
            failedApproaches.forEach { appendLine("- ${it.approach} → ${it.whyItFailed}") }
            appendLine()
        }
        if (fileMap.isNotEmpty()) {
            appendLine("## Files already examined")
            fileMap.forEach { note ->
                append("- `${note.path}` — ${note.summary}")
                if (note.symbols.isNotEmpty()) {
                    append(" [${note.symbols.take(8).joinToString(", ")}]")
                }
                appendLine()
            }
            appendLine()
        }
        if (edits.isNotEmpty()) {
            appendLine("## Changes applied")
            edits.forEach {
                appendLine("- `${it.path}` — ${it.description}" + if (!it.applied) " (reverted)" else "")
            }
            appendLine()
        }
        if (commands.isNotEmpty()) {
            appendLine("## Command outcomes")
            commands.forEach {
                appendLine("- `${it.command}` → ${it.outcome}" + (it.exitCode?.let { c -> " (exit $c)" } ?: ""))
            }
            appendLine()
        }
        if (glossary.isNotEmpty()) {
            appendLine("## Project vocabulary")
            glossary.forEach { appendLine("- **${it.term}**: ${it.meaning}") }
            appendLine()
        }
        if (verbatimPins.isNotEmpty()) {
            appendLine("## Verbatim details")
            verbatimPins.forEach { appendLine("```\n$it\n```") }
            appendLine()
        }
        if (openThreads.isNotEmpty()) {
            appendLine("## Open threads")
            openThreads.forEach { appendLine("- $it") }
            appendLine()
        }
        if (nextSteps.isNotEmpty()) {
            appendLine("## Next steps")
            nextSteps.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
            appendLine()
        }
    }.trimEnd()

    /**
     * Merge a freshly produced digest into this one.
     *
     * Append-and-dedupe rather than replace, except for the two fields that are
     * genuinely snapshots ([currentState], [nextSteps]). Lists are capped so the
     * digest cannot grow without bound over a few hundred compactions — the
     * oldest entries are the ones dropped, since recency correlates with
     * relevance.
     */
    fun mergedWith(next: ConversationDigest): ConversationDigest = ConversationDigest(
        version = version + 1,
        coversFromSeq = minOf(coversFromSeq, next.coversFromSeq),
        coversToSeq = maxOf(coversToSeq, next.coversToSeq),
        createdAt = next.createdAt,
        model = next.model ?: model,
        objective = next.objective.ifBlank { objective },
        currentState = next.currentState.ifBlank { currentState },
        decisions = (decisions + next.decisions).distinctBy { it.what }.takeLast(CAP_DECISIONS),
        constraints = (constraints + next.constraints).distinct().takeLast(CAP_SMALL),
        // Keyed by path so a later observation about a file supersedes an
        // earlier one instead of accumulating duplicates.
        fileMap = (fileMap + next.fileMap)
            .associateBy { it.path }
            .values
            .sortedByDescending { it.lastReadSeq }
            .take(CAP_FILES),
        edits = (edits + next.edits).takeLast(CAP_EDITS),
        commands = (commands + next.commands).distinctBy { it.command }.takeLast(CAP_SMALL),
        openThreads = next.openThreads.ifEmpty { openThreads },
        failedApproaches = (failedApproaches + next.failedApproaches)
            .distinctBy { it.approach }
            .takeLast(CAP_DECISIONS),
        userPreferences = (userPreferences + next.userPreferences).distinct().takeLast(CAP_SMALL),
        glossary = (glossary + next.glossary).associateBy { it.term }.values.toList().take(CAP_SMALL),
        nextSteps = next.nextSteps.ifEmpty { nextSteps },
        verbatimPins = (verbatimPins + next.verbatimPins).distinct().takeLast(CAP_PINS),
        keptByRequest = (keptByRequest + next.keptByRequest).distinct().takeLast(CAP_SMALL),
        // Of the compaction that writes the merged version; set by the app.
        trigger = next.trigger,
        focus = next.focus,
        tokensFreed = next.tokensFreed,
    )

    companion object {
        /** [trigger]: by /compact, and by the app when the window filled. */
        const val TRIGGER_COMMAND = "command"
        const val TRIGGER_AUTO = "auto"

        private const val CAP_DECISIONS = 40
        private const val CAP_SMALL = 30
        private const val CAP_FILES = 80
        private const val CAP_EDITS = 60
        private const val CAP_PINS = 15

        /**
         * JSON Schema handed to the model when asking it to produce a digest.
         * Kept next to the data class so the two cannot drift apart.
         */
        val REQUEST_SCHEMA: String = """
        {
          "type": "object",
          "required": ["objective", "currentState"],
          "properties": {
            "objective":        { "type": "string", "description": "What the user ultimately wants. Carry forward unchanged unless it genuinely shifted." },
            "currentState":     { "type": "string", "description": "Where the work stands now, in 2-5 sentences." },
            "decisions":        { "type": "array", "items": { "type": "object", "properties": { "what": {"type":"string"}, "why": {"type":"string"} }, "required": ["what"] } },
            "constraints":      { "type": "array", "items": { "type": "string" }, "description": "Hard requirements and prohibitions the user stated." },
            "fileMap":          { "type": "array", "items": { "type": "object", "properties": { "path": {"type":"string"}, "summary": {"type":"string"}, "symbols": {"type":"array","items":{"type":"string"}} }, "required": ["path","summary"] } },
            "edits":            { "type": "array", "items": { "type": "object", "properties": { "path": {"type":"string"}, "description": {"type":"string"}, "applied": {"type":"boolean"} }, "required": ["path","description"] } },
            "commands":         { "type": "array", "items": { "type": "object", "properties": { "command": {"type":"string"}, "outcome": {"type":"string"}, "exitCode": {"type":"integer"} }, "required": ["command","outcome"] } },
            "openThreads":      { "type": "array", "items": { "type": "string" } },
            "failedApproaches": { "type": "array", "items": { "type": "object", "properties": { "approach": {"type":"string"}, "whyItFailed": {"type":"string"} }, "required": ["approach","whyItFailed"] }, "description": "Critical. Anything attempted that did not work, so it is not attempted again." },
            "userPreferences":  { "type": "array", "items": { "type": "string" } },
            "glossary":         { "type": "array", "items": { "type": "object", "properties": { "term": {"type":"string"}, "meaning": {"type":"string"} }, "required": ["term","meaning"] } },
            "nextSteps":        { "type": "array", "items": { "type": "string" } },
            "verbatimPins":     { "type": "array", "items": { "type": "string" }, "description": "Short exact strings that must survive unparaphrased: error messages, signatures, literals the user gave." },
            "keptByRequest":    { "type": "array", "items": { "type": "string" }, "description": "Only when the user asked to keep something in detail: every detail that bears on it, complete, with exact names, values and paths." }
          }
        }
        """.trimIndent()
    }
}
