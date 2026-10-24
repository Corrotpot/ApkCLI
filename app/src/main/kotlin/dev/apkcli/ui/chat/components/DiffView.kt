package dev.apkcli.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.ui.chat.find.rememberFindable
import dev.apkcli.ui.components.ButtonKind
import dev.apkcli.ui.components.EditorButton
import dev.apkcli.ui.editor.Syntax
import dev.apkcli.ui.editor.SyntaxPalette
import dev.apkcli.ui.editor.highlightLines
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.LocalEditorColors
import dev.apkcli.ui.theme.MonoSmallStyle
import dev.apkcli.ui.theme.MonoStyle

/**
 * A unified diff, rendered the way an editor renders one: line numbers in a
 * gutter, syntax colours, added lines on green and removed lines on red.
 *
 * Deliberately not side-by-side. A phone column is ~45 monospace characters
 * wide; two of them side by side is 22 each, which truncates almost every real
 * line of code. Unified keeps full lines and only costs vertical space, which
 * is the dimension a phone has.
 *
 * Added and removed lines differ in **three** ways — background tint, the
 * gutter colour, and the leading `+`/`−` glyph. Relying on the red/green tint
 * alone fails for the ~8% of men with colour vision deficiency, and red/green
 * is precisely the pair being distinguished.
 */
@Composable
fun DiffView(
    diff: ToolDisplay.Diff,
    modifier: Modifier = Modifier,
    truncated: Boolean = false,
    onRevert: ((String) -> Unit)? = null,
    /** In the transcript: the diff's key for find in the chat, its lines keyed as [diffFindSegments] lists them. */
    findSegment: String? = null,
) {
    val editorColors = LocalEditorColors.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val palette = remember(editorColors, textColor) { SyntaxPalette.of(editorColors, textColor) }
    val rows = remember(diff.unified, diff.path, palette) { buildRows(diff.unified, Syntax.forFile(diff.path.substringAfterLast('/')), palette) }
    val gutter = remember(rows) { Gutter.of(rows) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = diff.path,
                style = MonoSmallStyle,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            DiffStat(diff.addedLines, diff.removedLines)
        }

        if (rows.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(editorColors.codeBackground)
                    .border(1.dp, editorColors.border, RoundedCornerShape(4.dp))
                    // Capped, and scrolls inside: a 2000-line diff in a chat
                    // bubble is not reviewable anyway, and the editor is the
                    // place for that.
                    .heightIn(max = 440.dp),
            ) {
                val viewport = maxWidth
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        // As wide as the longest line, and never narrower than
                        // the box, so every row's tint reaches the edge.
                        .widthIn(min = viewport)
                        .width(IntrinsicSize.Max)
                        .padding(vertical = 4.dp),
                ) {
                    rows.forEachIndexed { index, row -> DiffRowView(row, gutter, findSegment?.let { "$it:$index" }) }
                }
            }
        }

        if (truncated) {
            Text(
                text = stringResource(R.string.tool_diff_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = editorColors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Bound to a local: `snapshotId` is a public property of another module,
        // so the compiler will not smart-cast it across the null check.
        val snapshotId = diff.snapshotId
        if (onRevert != null && snapshotId != null) {
            EditorButton(
                text = stringResource(R.string.a11y_revert_change),
                icon = Codicons.DebugRestart,
                kind = ButtonKind.Ghost,
                onClick = { onRevert(snapshotId) },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "+12 −3", each in its diff colour. */
@Composable
fun DiffStat(added: Int, removed: Int, modifier: Modifier = Modifier) {
    val colors = LocalEditorColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("+$added", style = MonoSmallStyle, color = colors.diffAddForeground, maxLines = 1)
        Text("−$removed", style = MonoSmallStyle, color = colors.diffRemoveForeground, maxLines = 1)
    }
}

/**
 * Two number columns, old file then new, as on GitHub: a removed line has
 * only its old number, an added line only its new one, a context line both.
 * One column mixed the two — removed lines counted in the old file, the rest
 * in the new — so a run read "+42 +43 +44 −46 −47". A column no row uses (the
 * old one for a new file) is left out.
 */
private class Gutter(val oldDigits: Int, val newDigits: Int) {
    companion object {
        fun of(rows: List<DiffRow>): Gutter {
            fun digits(numbers: List<Int>) = numbers.maxOrNull()?.toString()?.length?.coerceAtLeast(2) ?: 0
            return Gutter(
                oldDigits = digits(rows.mapNotNull { it.oldNumber }),
                newDigits = digits(rows.mapNotNull { it.newNumber }),
            )
        }
    }
}

@Composable
private fun GutterNumber(number: Int?, digits: Int, color: Color) {
    if (digits == 0) return
    Text(
        text = number?.toString() ?: "",
        style = MonoStyle,
        color = color,
        textAlign = TextAlign.End,
        softWrap = false,
        maxLines = 1,
        modifier = Modifier
            .widthIn(min = (digits * 8 + 8).dp)
            .padding(start = 4.dp, end = 4.dp),
    )
}

@Composable
private fun DiffRowView(row: DiffRow, gutter: Gutter, findKey: String?) {
    val editorColors = LocalEditorColors.current
    if (row.kind == DiffKind.HUNK) {
        // A gap between hunks, not the raw "@@ -12,5 +12,7 @@": the numbers
        // are already in the gutter, and the header text means nothing to a
        // reader who is not a patch tool.
        Row(
            Modifier
                .fillMaxWidth()
                .background(editorColors.selection.copy(alpha = 0.35f))
                .padding(horizontal = 8.dp, vertical = 1.dp),
        ) {
            Text(
                text = "⋯" + row.text.let { if (it.isBlank()) "" else "  $it" },
                style = MonoSmallStyle,
                color = editorColors.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
        return
    }

    val (background, gutterColor, glyph) = when (row.kind) {
        DiffKind.ADDED -> Triple(editorColors.diffAddBackground, editorColors.diffAddForeground, "+")
        DiffKind.REMOVED -> Triple(editorColors.diffRemoveBackground, editorColors.diffRemoveForeground, "−")
        else -> Triple(Color.Transparent, editorColors.gutter, " ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(end = 12.dp),
    ) {
        val numberColor = gutterColor.copy(alpha = if (row.kind == DiffKind.CONTEXT) 0.8f else 1f)
        GutterNumber(row.oldNumber, gutter.oldDigits, numberColor)
        GutterNumber(row.newNumber, gutter.newDigits, numberColor)
        Spacer(Modifier.width(2.dp))
        Text(
            text = glyph,
            style = MonoStyle,
            color = gutterColor,
            modifier = Modifier.padding(end = 6.dp),
        )
        // Outside the transcript (the changes sheet) a key no match has.
        val findable = rememberFindable(findKey ?: "", row.code)
        Text(
            text = findable.text,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            maxLines = 1,
            onTextLayout = findable.onTextLayout,
            modifier = findable.modifier,
        )
    }
}

private enum class DiffKind { ADDED, REMOVED, CONTEXT, HUNK }

private class DiffRow(
    val kind: DiffKind,
    /** Raw text; for a hunk, the function context after the second `@@`. */
    val text: String,
    /** Line in the old file: removed and context lines. */
    val oldNumber: Int?,
    /** Line in the new file: added and context lines. */
    val newNumber: Int?,
    val code: AnnotatedString,
)

private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@ ?(.*)$""")

/**
 * Parse a unified diff into display rows, highlighted.
 *
 * Each hunk is highlighted twice — its old side (context and removed lines)
 * and its new side (context and added lines) — so a line is lexed among the
 * lines it really sits between, and a comment opened in one version does not
 * colour the other.
 *
 * The `---`/`+++` file headers are dropped: the path is already in the
 * card's own header. Only outside a hunk, though: the header's line counts
 * say where a hunk ends, so a removed line reading `-- note` (shown as
 * `--- note`) stays a removed line instead of vanishing as a header and
 * shifting every number after it.
 */
private fun buildRows(unified: String, syntax: Syntax?, palette: SyntaxPalette): List<DiffRow> {
    val raw = parseDiff(unified)
    val code = arrayOfNulls<AnnotatedString>(raw.size)
    var start = 0
    while (start < raw.size) {
        var end = start
        while (end < raw.size && raw[end].kind != DiffKind.HUNK) end++
        val hunk = (start until end)
        val oldSide = hunk.filter { raw[it].kind != DiffKind.ADDED }
        val newSide = hunk.filter { raw[it].kind != DiffKind.REMOVED }
        highlightLines(oldSide.map { raw[it].text }, syntax, palette).forEachIndexed { i, text ->
            if (raw[oldSide[i]].kind == DiffKind.REMOVED) code[oldSide[i]] = text
        }
        highlightLines(newSide.map { raw[it].text }, syntax, palette).forEachIndexed { i, text ->
            code[newSide[i]] = text
        }
        start = end + 1
    }
    return raw.mapIndexed { i, r -> DiffRow(r.kind, r.text, r.old, r.new, code[i] ?: AnnotatedString(r.text)) }
}

/**
 * The lines of a diff as [DiffView] draws them, keyed "[segment]:row" as it
 * keys them: what find in the chat searches. The gaps between hunks are
 * not searched; they hold no line of the file.
 */
internal fun diffFindSegments(segment: String, unified: String): List<Pair<String, String>> =
    parseDiff(unified).mapIndexedNotNull { index, row -> if (row.kind == DiffKind.HUNK) null else "$segment:$index" to row.text }

private class RawRow(val kind: DiffKind, val text: String, val old: Int?, val new: Int?)

/** The rows of a unified diff, unhighlighted; see [buildRows]. */
private fun parseDiff(unified: String): List<RawRow> {
    val raw = ArrayList<RawRow>()
    var oldLine = 0
    var newLine = 0
    // Lines of the current hunk still to come, per side; a header without
    // counts (or one that would not parse) leaves the hunk open until the
    // next header.
    var oldLeft = 0
    var newLeft = 0
    var numbered = false
    fun inHunk() = oldLeft > 0 || newLeft > 0

    for (rawLine in unified.removeSuffix("\n").lineSequence()) {
        val line = rawLine.removeSuffix("\r")
        if (line.startsWith("@@")) {
            val match = HUNK.find(line)
            numbered = match != null
            if (match != null) {
                oldLine = match.groupValues[1].toInt()
                newLine = match.groupValues[3].toInt()
                oldLeft = match.groupValues[2].ifEmpty { "1" }.toInt()
                newLeft = match.groupValues[4].ifEmpty { "1" }.toInt()
            } else {
                oldLeft = Int.MAX_VALUE
                newLeft = Int.MAX_VALUE
            }
            raw += RawRow(DiffKind.HUNK, match?.groupValues?.get(5).orEmpty(), null, null)
            continue
        }
        if (line.startsWith("\\")) continue // "\ No newline at end of file"
        if (inHunk()) {
            when {
                line.startsWith("+") -> {
                    raw += RawRow(DiffKind.ADDED, line.drop(1), null, newLine.takeIf { numbered })
                    newLine++
                    newLeft--
                }
                line.startsWith("-") -> {
                    raw += RawRow(DiffKind.REMOVED, line.drop(1), oldLine.takeIf { numbered }, null)
                    oldLine++
                    oldLeft--
                }
                // A context line, or an empty one whose leading space was
                // trimmed on the way.
                line.startsWith(" ") || line.isEmpty() -> {
                    raw += RawRow(DiffKind.CONTEXT, line.drop(1), oldLine.takeIf { numbered }, newLine.takeIf { numbered })
                    oldLine++
                    newLine++
                    oldLeft--
                    newLeft--
                }
                else -> {
                    // Not diff syntax: the hunk was shorter than its header
                    // claimed. Treat the line as outside it.
                    oldLeft = 0
                    newLeft = 0
                    if (!isFileHeader(line)) raw += RawRow(DiffKind.CONTEXT, line, null, null)
                }
            }
            continue
        }
        if (isFileHeader(line) || line.isEmpty()) continue
        raw += RawRow(DiffKind.CONTEXT, line, null, null)
    }
    // A diff that opens straight into its single hunk does not need the gap.
    if (raw.firstOrNull()?.kind == DiffKind.HUNK && raw.count { it.kind == DiffKind.HUNK } == 1 && raw.first().text.isBlank()) {
        raw.removeAt(0)
    }
    return raw
}

private fun isFileHeader(line: String): Boolean =
    line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("diff ") ||
        line.startsWith("index ") || line.startsWith("new file mode") ||
        line.startsWith("deleted file mode") || line.startsWith("similarity index") ||
        line.startsWith("rename from") || line.startsWith("rename to")

/**
 * The agent's task list.
 *
 * Checkbox glyphs rather than `Checkbox` composables: these are not
 * interactive — only the agent sets them — and a real Checkbox invites taps
 * that do nothing.
 */
@Composable
fun TaskChecklist(
    items: List<ToolDisplay.Tasks.Item>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                // Three distinct shapes — check, filled dot, hollow ring — so the
                // state reads without colour. Icons, not text glyphs: a "✓"
                // character renders as an emoji on some system fonts.
                TaskGlyph(item)
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (item.active) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        item.done -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.active -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
fun TaskGlyph(item: ToolDisplay.Tasks.Item, modifier: Modifier = Modifier.size(14.dp)) {
    val editorColors = LocalEditorColors.current
    when {
        item.done -> Icon(
            Codicons.Check,
            contentDescription = stringResource(R.string.task_done),
            tint = editorColors.success,
            modifier = modifier,
        )
        item.active -> Icon(
            Codicons.CircleFilled,
            contentDescription = stringResource(R.string.task_active),
            tint = editorColors.info,
            modifier = modifier,
        )
        else -> Icon(
            Codicons.CircleLarge,
            contentDescription = stringResource(R.string.task_todo),
            tint = editorColors.textTertiary,
            modifier = modifier,
        )
    }
}
