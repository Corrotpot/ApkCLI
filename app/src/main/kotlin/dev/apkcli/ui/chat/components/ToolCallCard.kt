package dev.apkcli.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.model.ToolCallStatus
import dev.apkcli.core.runtime.TerminalBuffer
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.chat.find.LocalFindItem
import dev.apkcli.ui.chat.find.LocalFindMarks
import dev.apkcli.ui.chat.find.rememberFindable
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.rememberFollowingScrollState
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.terminal.TerminalTheme
import dev.apkcli.ui.terminal.annotated
import dev.apkcli.ui.text.bytesText
import dev.apkcli.ui.theme.Design
import dev.apkcli.ui.theme.LocalEditorColors
import dev.apkcli.ui.theme.MonoSmallStyle
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * One tool invocation.
 *
 * Collapsed by default and showing only the tool, its target and a status
 * glyph. A session makes dozens of these, and expanding them all turns the
 * transcript into an unreadable wall — the user wants to see *that* a file was
 * read, and only occasionally *what* was in it.
 *
 * Status is a distinct glyph per state (spinner, check, cross, slash, clock),
 * not a coloured dot, so it survives colour vision deficiency; the content
 * description says it in words.
 */
@Composable
fun ToolCallCard(
    call: ChatViewModel.LiveToolCall,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    onRevert: ((String) -> Unit)? = null,
) {
    // A subagent is a window onto another agent's run, not a call's output.
    if (call.name == "subagent_dispatch") {
        SubagentCard(call, modifier, initiallyExpanded)
        return
    }
    var expanded by remember(call.callId) { mutableStateOf(initiallyExpanded) }
    val colors = LocalEditorColors.current
    val scheme = MaterialTheme.colorScheme
    val failed = call.isError || call.status == ToolCallStatus.ERROR
    // A call that fails while on screen opens once, like a restored failed
    // one does; collapsing it afterwards sticks.
    LaunchedEffect(call.callId, failed) {
        if (failed) expanded = true
    }

    val title = toolTitle(call.name)
    val target = remember(call.name, call.argumentsJson) { toolTarget(call.name, call.argumentsJson) }
    val diff = call.display as? ToolDisplay.Diff
    val exitCode = (call.display as? ToolDisplay.Command)?.exitCode
    val failedText = if (exitCode != null && exitCode != 0) {
        stringResource(R.string.tool_result_failed_code, exitCode.toString())
    } else {
        stringResource(R.string.tool_result_failed)
    }
    val okText = stringResource(R.string.tool_result_ok)
    val duration = call.durationMs?.let { durationText(it) }
    val running = call.status == ToolCallStatus.RUNNING || call.status == ToolCallStatus.APPROVED
    // A ticking clock on every running call: a long one that prints nothing
    // otherwise looks exactly like one that hung. While the model is still
    // writing the call, the clock counts the writing, under "writing", so the
    // model's time never reads as the tool's.
    val since = call.runningSince
    val clockFrom = if (call.preparing) call.writingSince else since
    val elapsed by produceState<Long?>(null, call.callId, running, clockFrom) {
        value = null
        if (!running || clockFrom == null) return@produceState
        while (true) {
            value = System.currentTimeMillis() - clockFrom
            delay(1_000)
        }
    }
    val ticking = elapsed?.takeIf { it >= 1_000 }?.let(::elapsedClock)
    // The size only once there is more than the keys and a path: a provider
    // that sends a long value whole when it is done (Kiro does) would show
    // "8 B" for the entire wait.
    val written = call.argsChars.takeIf { it >= WRITTEN_WORTH_SHOWING }?.let { bytesText(it.toLong()) }
    val runningText = when {
        call.preparing && written != null && ticking != null ->
            stringResource(R.string.tool_status_writing_size_for, written, ticking)
        call.preparing && written != null -> stringResource(R.string.tool_status_writing_size, written)
        call.preparing && ticking != null -> stringResource(R.string.tool_status_writing_for, ticking)
        call.preparing -> stringResource(R.string.tool_status_writing)
        // Written, waiting for the calls before it.
        since == null -> stringResource(R.string.tool_status_queued)
        else -> ticking?.let { stringResource(R.string.tool_status_running_for, it) }
            ?: stringResource(R.string.tool_status_running)
    }

    // The design's status is the time it took, after a glyph that says how;
    // words only where there is no time to show.
    val statusText = when (call.status) {
        ToolCallStatus.RUNNING -> elapsed?.takeIf { it >= 1_000 && !call.preparing }?.let { elapsedClock(it) } ?: runningText
        ToolCallStatus.PENDING -> stringResource(R.string.tool_status_asking)
        ToolCallStatus.APPROVED -> stringResource(R.string.tool_status_approved)
        ToolCallStatus.DENIED -> stringResource(R.string.tool_status_denied)
        ToolCallStatus.ERROR -> duration ?: failedText
        ToolCallStatus.ABORTED -> stringResource(R.string.tool_status_stopped)
        ToolCallStatus.OK -> duration ?: if (call.isError) failedText else okText
    }

    val spokenStatus = when {
        call.status == ToolCallStatus.RUNNING -> stringResource(R.string.a11y_tool_running, title)
        failed -> stringResource(R.string.a11y_tool_failed, title)
        else -> stringResource(R.string.a11y_tool_succeeded, title, statusText)
    }
    val subtitle = target ?: call.partialTarget ?: summaryText(call.display)

    // Find in the chat: the target, the output and the diff are searched
    // (ChatScreen lists them under these keys), and a collapsed card that
    // holds a match is open while the search is on.
    val findTarget = "tool:${call.callId}:t"
    val findBody = "tool:${call.callId}:b"
    val findDiff = "tool:${call.callId}:d"
    val marks = LocalFindMarks.current
    val findItem = LocalFindItem.current
    val findOpens = marks != null && (marks.opens(findItem, findBody) || marks.opens(findItem, findDiff))
    val open = expanded || findOpens

    // `.tool`: surface 1, a quiet border that turns red-tinted when the call
    // failed and strong while it runs; `.tool-head` 48 dp.
    val shape = RoundedCornerShape(Design.Radius.ToolCard)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(
                1.dp,
                when {
                    failed -> Design.Colors.Error.copy(alpha = 0.35f)
                    running -> Design.Colors.BorderStrong
                    else -> Design.Colors.Border
                },
                shape,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                .clearAndSetSemantics { contentDescription = spokenStatus },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                toolIcon(call.name),
                contentDescription = null,
                tint = Design.Colors.TextSecondary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = title,
                        style = Design.Type.LabelMedium,
                        color = Design.Colors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // A zero side is left out: "+27", not "+27 −0".
                    if (diff != null && (diff.addedLines > 0 || diff.removedLines > 0)) {
                        DiffStat(diff.addedLines, diff.removedLines)
                    }
                }
                subtitle?.let { summary ->
                    val findable = rememberFindable(findTarget, summary)
                    Text(
                        text = findable.text,
                        style = Design.Type.MonoCaption,
                        color = Design.Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = findable.onTextLayout,
                        modifier = findable.modifier,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusGlyph(call)
                Text(
                    text = statusText,
                    style = Design.Type.LabelTiny,
                    color = Design.Colors.TextTertiary,
                    maxLines = 1,
                )
            }
            Icon(
                if (open) Codicons.ChevronUp else Codicons.ChevronDown,
                contentDescription = null,
                tint = Design.Colors.TextTertiary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
        }

        // A running command shows its terminal, without being opened: a
        // build that takes minutes looked exactly like one that had hung.
        if (running && call.liveRows.isNotEmpty()) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                LiveTerminal(call.liveRows)
            }
        }

        // The plan is collapsed like every other call: the panel above the
        // transcript already shows it, and an open copy per update turned a
        // long session into a wall of checklists.
        AnimatedVisibility(visible = open && !running) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                when (val display = call.display) {
                    is ToolDisplay.Diff -> DiffView(display, truncated = call.diffTruncated, onRevert = onRevert, findSegment = findDiff)
                    is ToolDisplay.Command -> CommandView(display)
                    is ToolDisplay.Matches -> MatchesView(display)
                    is ToolDisplay.SearchResults -> SearchResultsView(display)
                    is ToolDisplay.Tasks -> TaskChecklist(display.items)
                    else -> MonoBlock(call.resultPreview, findSegment = findBody)
                }
            }
        }
    }
}

/** What the tool did to what, in one line. Null when there is nothing to add. */
@Composable
private fun summaryText(display: ToolDisplay?): String? = when (display) {
    null -> null
    is ToolDisplay.Diff -> display.path
    is ToolDisplay.FileContent ->
        "${display.path}  " + pluralStringResource(R.plurals.tool_lines, display.totalLines, display.totalLines)
    is ToolDisplay.Matches -> pluralStringResource(R.plurals.tool_matches, display.hits.size, display.hits.size) + " · " +
        pluralStringResource(R.plurals.tool_files, display.filesScanned, display.filesScanned)
    is ToolDisplay.Listing ->
        "${display.root}  " + pluralStringResource(R.plurals.tool_entries, display.entries.size, display.entries.size)
    is ToolDisplay.Command -> display.command
    is ToolDisplay.WebPage -> display.title.ifBlank { display.url }
    is ToolDisplay.Tasks -> stringResource(R.string.tool_tasks_done, display.items.count { it.done }, display.items.size)
    is ToolDisplay.SearchResults -> "\"${display.query}\"  " +
        pluralStringResource(R.plurals.tool_results, display.results.size, display.results.size) + " · ${display.engine}"
    is ToolDisplay.Plain -> display.text.lineSequence().firstOrNull()
    // Drawn by SubagentCard, which reads the task itself.
    is ToolDisplay.Subagent -> null
}?.takeIf { it.isNotBlank() }

/**
 * An icon for the kind of work, keyed on the tool name's family. Unknown tools
 * get the generic one rather than a guess.
 */
internal fun toolIcon(name: String): ImageVector = when {
    name == "fs_read" || name == "outline" -> Codicons.File
    name == "fs_write" -> Codicons.NewFile
    name in setOf("fs_edit", "fs_multi_edit", "fs_apply_patch", "revert_edit") -> Codicons.Diff
    name in setOf("fs_delete") -> Codicons.Trash
    name in setOf("fs_move") -> Codicons.Files
    name in setOf("fs_list", "fs_glob", "project_tree", "fs_stat") -> Codicons.ListTree
    name in setOf("grep", "symbol_search") -> Codicons.Search
    name == "web_search" -> Codicons.Search
    name == "web_fetch" -> Codicons.Globe
    name.startsWith("sh_") -> Codicons.Terminal
    name == "build_run" -> Codicons.Tools
    name == "test_run" -> Codicons.Beaker
    name.startsWith("git_") -> Codicons.SourceControl
    name.startsWith("memory_") -> Codicons.Book
    name.startsWith("conversation_") -> Codicons.History
    name.contains("agent") -> Codicons.Hubot
    name.startsWith("task") -> Codicons.Checklist
    name.startsWith("pkg_") -> Codicons.Layers
    name == "env_info" -> Codicons.Info
    else -> Codicons.Tools
}

@Composable
private fun statusColor(call: ChatViewModel.LiveToolCall) = with(LocalEditorColors.current) {
    when {
        call.status == ToolCallStatus.RUNNING || call.status == ToolCallStatus.APPROVED -> info
        call.status == ToolCallStatus.PENDING || call.status == ToolCallStatus.DENIED -> warning
        call.isError || call.status == ToolCallStatus.ERROR -> error
        call.status == ToolCallStatus.ABORTED -> textTertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** `.tool-status` glyphs: spinner, pass, error, question; distinct shapes, not just colours. */
@Composable
private fun StatusGlyph(call: ChatViewModel.LiveToolCall) {
    val modifier = Modifier.size(Design.Size.IconSmall)
    when {
        call.status == ToolCallStatus.RUNNING || call.status == ToolCallStatus.APPROVED ->
            Spinner(color = Design.Colors.Accent)
        call.status == ToolCallStatus.PENDING ->
            Icon(Codicons.Question, contentDescription = null, tint = Design.Colors.Warning, modifier = modifier)
        call.status == ToolCallStatus.DENIED ->
            Icon(Codicons.CircleSlash, contentDescription = null, tint = Design.Colors.Warning, modifier = modifier)
        call.isError || call.status == ToolCallStatus.ERROR ->
            Icon(Codicons.Error, contentDescription = null, tint = Design.Colors.Error, modifier = modifier)
        call.status == ToolCallStatus.ABORTED ->
            Icon(Codicons.DebugStop, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = modifier)
        else ->
            Icon(Codicons.Pass, contentDescription = null, tint = Design.Colors.Success, modifier = modifier)
    }
}

/**
 * What an output box draws of [text]: all of it up to [MONO_MAX_CHARS];
 * past that its start and its end, cut at line breaks, with a line between
 * them saying how much is left out — written with [clippedPattern], the
 * `tool_output_clipped` string, and numbers in [locale]. A command's live
 * card held its whole output, megabytes of a build log, and one text of it
 * took seconds to lay out on the main thread. Find in the chat searches
 * this same text, so its matches are the ones drawn.
 */
internal fun monoShown(text: String, clippedPattern: String, locale: Locale): String {
    if (text.length <= MONO_MAX_CHARS) return text
    var headEnd = MONO_HEAD_CHARS
    text.lastIndexOf('\n', headEnd).takeIf { it > MONO_HEAD_CHARS / 2 }?.let { headEnd = it }
    var tailStart = text.length - MONO_TAIL_CHARS
    text.indexOf('\n', tailStart).takeIf { it in tailStart until tailStart + MONO_TAIL_CHARS / 2 }?.let { tailStart = it + 1 }
    val omitted = String.format(locale, "%,d", tailStart - headEnd)
    return text.substring(0, headEnd) + "\n\n" + clippedPattern.format(omitted) + "\n\n" + text.substring(tailStart)
}

/** Past this an output box draws only its start and end; see [monoShown]. */
private const val MONO_MAX_CHARS = 40_000
private const val MONO_HEAD_CHARS = 10_000
private const val MONO_TAIL_CHARS = 24_000

/**
 * `.termbox`: the terminal's black, a quiet border, 8 dp corners. With
 * [findSegment], searchable as that segment of the transcript. A long text
 * is drawn in part ([monoShown]).
 */
@Composable
private fun MonoBlock(text: String, maxHeight: Int = 220, findSegment: String? = null) {
    if (text.isBlank()) return
    val pattern = stringResource(R.string.tool_output_clipped)
    val locale = LocalConfiguration.current.locales[0]
    val shown = remember(text, pattern, locale) { monoShown(text, pattern, locale) }
    val shape = RoundedCornerShape(Design.Radius.Box)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.TerminalBg)
            .border(1.dp, Design.Colors.Border, shape)
            .heightIn(max = maxHeight.dp),
    ) {
        // Without a segment, a key no match has.
        val findable = rememberFindable(findSegment ?: "", shown)
        Text(
            text = findable.text,
            style = Design.Type.CodeSmall,
            color = Design.Colors.Text,
            onTextLayout = findable.onTextLayout,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                // Horizontal scroll rather than wrapping: wrapped code and
                // wrapped log lines are much harder to read than scrolled ones.
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .then(findable.modifier),
        )
    }
}

@Composable
private fun CommandView(display: ToolDisplay.Command) {
    val colors = LocalEditorColors.current
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            Text("$", style = Design.Type.CodeSmall, color = Design.Colors.Accent)
            // A command can carry a whole file in a heredoc: drawn in part, like its output.
            val pattern = stringResource(R.string.tool_output_clipped)
            val locale = LocalConfiguration.current.locales[0]
            val command = remember(display.command, pattern, locale) { monoShown(display.command, pattern, locale) }
            Text(command, style = Design.Type.CodeSmall, color = Design.Colors.Text)
        }
        MonoBlock(display.output)
        val ok = display.exitCode == 0 && !display.timedOut
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Icon(
                if (ok) Codicons.Check else Codicons.Error,
                contentDescription = null,
                tint = if (ok) colors.success else colors.error,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (display.timedOut) {
                    stringResource(R.string.tool_timed_out, durationText(display.durationMs))
                } else {
                    stringResource(R.string.tool_exit, display.exitCode?.toString() ?: "?", durationText(display.durationMs))
                },
                style = MonoSmallStyle,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else colors.error,
            )
        }
    }
}

@Composable
private fun MatchesView(display: ToolDisplay.Matches) {
    val colors = LocalEditorColors.current
    Column {
        display.hits.take(40).forEach { hit ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${hit.path}:${hit.line}",
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = hit.text.trim(),
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (display.hits.size > 40) {
            Text(
                stringResource(R.string.tool_more, display.hits.size - 40),
                style = MonoSmallStyle,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Search results as links: title, host, and the engine's excerpt. */
@Composable
private fun SearchResultsView(display: ToolDisplay.SearchResults) {
    val colors = LocalEditorColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        display.results.forEachIndexed { index, result ->
            Row {
                Text(
                    text = "${index + 1}",
                    style = MonoSmallStyle,
                    color = colors.textTertiary,
                    modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = result.url.removePrefix("https://").removePrefix("http://"),
                        style = MonoSmallStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.snippet.isNotBlank()) {
                        Text(
                            text = result.snippet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}



/** How tall the live terminal under a running call may grow. */
private val LIVE_HEIGHT = 220.dp

/**
 * The last lines a running call's terminal shows, as the terminal shows
 * them: its colours (a progress bar is a coloured line), long lines wrapped
 * rather than cut off, and the newest line at the bottom.
 *
 * It scrolls. New output keeps it at the bottom until the user drags it up
 * to read something; then it stays where they put it, and following resumes
 * once they bring it back to the bottom. At either end a drag goes on to
 * scroll the chat.
 */
@Composable
private fun LiveTerminal(rows: List<TerminalBuffer.Row>) {
    val theme = TerminalTheme.Default
    val foreground = theme.foreground
    // Blank lines at the end are where the cursor waits, not output.
    val text = remember(rows, theme) {
        val last = rows.indexOfLast { it.text.isNotBlank() }
        if (last < 0) {
            null
        } else {
            buildAnnotatedString {
                for (i in 0..last) {
                    if (i > 0) append('\n')
                    append(rows[i].annotated(theme))
                }
            }
        }
    } ?: return
    val scroll = rememberFollowingScrollState()
    val shape = RoundedCornerShape(Design.Radius.Box)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.TerminalBg)
            .border(1.dp, Design.Colors.Border, shape)
            .heightIn(max = LIVE_HEIGHT),
    ) {
        Text(
            text = text,
            style = Design.Type.CodeSmall,
            color = foreground,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}


/**
 * Below this much of a call's arguments, its size is not shown while it is
 * written: it is the keys and a path, and says nothing about the content.
 */
private const val WRITTEN_WORTH_SHOWING = 256

/** `0:42`, `12:05`, `1:02:03`: how long a call has been running. */
internal fun elapsedClock(ms: Long): String {
    val seconds = ms / 1_000
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}
