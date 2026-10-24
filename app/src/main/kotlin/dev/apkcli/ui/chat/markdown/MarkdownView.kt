package dev.apkcli.ui.chat.markdown

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.apkcli.R
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.text.LatexMath
import dev.apkcli.ui.chat.components.TaskGlyph
import dev.apkcli.ui.chat.find.rememberFindable
import dev.apkcli.ui.editor.Syntax
import dev.apkcli.ui.editor.SyntaxPalette
import dev.apkcli.ui.editor.highlightLines
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.LocalEditorColors
import dev.apkcli.ui.theme.MonoSmallStyle
import dev.apkcli.ui.theme.MonoStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An agent reply, rendered: headings, lists, tables, quotes, code blocks with
 * syntax colours and a copy button, links that open in the browser — or, for a
 * project path such as `[main.go](src/main.go)`, in the editor.
 *
 * Selectable, because copying a command or a value out of a reply is the most
 * common thing done with one.
 *
 * @param onOpenPath opens a project file; null leaves path links as text.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onOpenPath: ((path: String, line: Int?) -> Unit)? = null,
    /** Find in the chat's keys for this text's pieces: another than "md" where a message shows more than one reply. */
    findPrefix: String = "md",
    /**
     * The text is a reply still arriving, which only grows: it is parsed
     * again from its last blocks only ([MarkdownParser.Streaming]), and the
     * blocks before are drawn as they were.
     */
    streaming: Boolean = false,
) {
    val parser = remember(streaming) { if (streaming) MarkdownParser.Streaming() else null }
    val blocks = remember(text, parser) { parser?.update(text) ?: MarkdownParser.parse(text) }
    val inline = rememberInlineStyles(onOpenPath)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { i, block -> BlockView(block, inline, style, depth = 0, findKey = "$findPrefix:$i") }
        }
    }
}

/**
 * The words of a reply as [MarkdownText] draws them, piece by piece, under
 * the keys it draws them with ([prefix] as its `findPrefix`): what find in
 * the chat searches. A paragraph, a heading, a table cell, a code block, a
 * formula is a piece; quotes and lists are walked into.
 */
internal fun markdownFindSegments(text: String, prefix: String = "md"): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    MarkdownParser.parse(text).forEachIndexed { i, block -> collectFindSegments(block, "$prefix:$i", out) }
    return out
}

private fun collectFindSegments(block: MdBlock, key: String, out: MutableList<Pair<String, String>>) {
    when (block) {
        is MdBlock.Paragraph -> out += key to plainInline(block.text)
        is MdBlock.Heading -> out += key to plainInline(block.text)
        is MdBlock.Code -> out += key to block.code
        is MdBlock.Math -> out += key to LatexMath.translate(block.latex)
        is MdBlock.Quote -> block.blocks.forEachIndexed { j, child -> collectFindSegments(child, "$key.$j", out) }
        is MdBlock.ListBlock -> block.items.forEachIndexed { index, item ->
            item.blocks.forEachIndexed { b, child -> collectFindSegments(child, "$key.$index.$b", out) }
        }
        is MdBlock.Table -> (listOf(block.header) + block.rows).forEachIndexed { r, row ->
            row.forEachIndexed { c, cell -> out += "$key:$r:$c" to plainInline(cell) }
        }
        MdBlock.Rule -> Unit
    }
}

/** What [renderInline] shows of [text], without its styles and links. */
private fun plainInline(text: String): String {
    val sink = PlainSink()
    MarkdownInline.parse(text, sink)
    return sink.text.toString()
}

private class PlainSink : InlineSink {
    val text = StringBuilder()
    override val length: Int get() = text.length

    override fun append(text: String) {
        this.text.append(text)
    }

    override fun style(style: InlineStyle, start: Int, end: Int) = Unit

    override fun link(target: LinkTarget, start: Int, end: Int) = Unit
}

@Composable
private fun rememberInlineStyles(onOpenPath: ((String, Int?) -> Unit)?): InlineStyles {
    val colors = LocalEditorColors.current
    val accent = MaterialTheme.colorScheme.primary
    return remember(colors, accent, onOpenPath) {
        InlineStyles(
            code = SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.9.em,
                background = colors.raised,
            ),
            link = TextLinkStyles(
                // Underlined as well as coloured: a link must not be told
                // apart by colour alone.
                style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                pressedStyle = SpanStyle(color = accent, background = accent.copy(alpha = 0.18f)),
            ),
            mark = SpanStyle(background = colors.warning.copy(alpha = 0.28f)),
            // The mono family, so digits and operators line up, but no code
            // background: a formula is prose, not a snippet.
            math = SpanStyle(fontFamily = MonoStyle.fontFamily, fontSize = 0.95.em),
            onOpenPath = onOpenPath,
        )
    }
}

/** [findKey]: the block's key for find in the chat, as [markdownFindSegments] lists it. */
@Composable
private fun BlockView(block: MdBlock, inline: InlineStyles, style: TextStyle, depth: Int, findKey: String) {
    val typography = MaterialTheme.typography
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.text, inline, style, findKey)

        is MdBlock.Heading -> InlineText(
            text = block.text,
            inline = inline,
            style = when (block.level) {
                1 -> typography.titleLarge
                2 -> typography.titleMedium
                else -> typography.titleSmall
            },
            findKey = findKey,
            modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
        )

        is MdBlock.Code -> CodeBlockView(block, findKey)

        is MdBlock.Math -> MathBlockView(block, findKey)

        is MdBlock.Quote -> QuoteView(block, inline, style, depth, findKey)

        is MdBlock.ListBlock -> ListView(block, inline, style, depth, findKey)

        is MdBlock.Table -> TableView(block, inline, findKey)

        MdBlock.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(LocalEditorColors.current.border),
        )
    }
}

@Composable
private fun InlineText(
    text: String,
    inline: InlineStyles,
    style: TextStyle,
    findKey: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign? = null,
    fontWeight: FontWeight? = null,
) {
    val annotated = remember(text, inline) { renderInline(text, inline) }
    val findable = rememberFindable(findKey, annotated)
    Text(
        text = findable.text,
        style = style,
        color = color,
        textAlign = textAlign,
        fontWeight = fontWeight,
        onTextLayout = findable.onTextLayout,
        modifier = modifier.then(findable.modifier),
    )
}

/* ------------------------------------------------------------- code blocks */

@Composable
private fun CodeBlockView(block: MdBlock.Code, findKey: String) {
    val colors = LocalEditorColors.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val palette = remember(colors, textColor) { SyntaxPalette.of(colors, textColor) }
    val highlighted = remember(block.code, block.language, palette) {
        val lines = highlightLines(block.code.split('\n'), Syntax.forFence(block.language), palette)
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
        }
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.codeBackground)
            .border(1.dp, colors.border, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp)
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.substringBefore(' ').ifBlank { "text" },
                style = MonoSmallStyle,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", block.code)))
                            copied = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (copied) Codicons.Check else Codicons.Copy,
                    contentDescription = stringResource(if (copied) R.string.md_copied else R.string.md_copy_code),
                    tint = if (copied) colors.success else colors.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        ) {
            val findable = rememberFindable(findKey, highlighted)
            Text(
                text = findable.text,
                style = MonoStyle,
                color = textColor,
                softWrap = false,
                onTextLayout = findable.onTextLayout,
                modifier = findable.modifier,
            )
        }
    }
}

/* -------------------------------------------------------- display maths */

/**
 * A formula on its own line: centred, in the mono family, scrolled sideways
 * when it is wider than the screen rather than wrapped mid-expression.
 *
 * Transcribed, not typeset ([LatexMath]); no border or background, which
 * would make it read as a code block.
 */
@Composable
private fun MathBlockView(block: MdBlock.Math, findKey: String) {
    val text = remember(block.latex) { LatexMath.translate(block.latex) }
    if (text.isEmpty()) return
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        val findable = rememberFindable(findKey, text)
        Text(
            text = findable.text,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            softWrap = false,
            onTextLayout = findable.onTextLayout,
            modifier = Modifier.padding(vertical = 6.dp).then(findable.modifier),
        )
    }
}

/* ------------------------------------------------------- quotes and lists */

@Composable
private fun QuoteView(block: MdBlock.Quote, inline: InlineStyles, style: TextStyle, depth: Int, findKey: String) {
    val bar = LocalEditorColors.current.borderStrong
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Drawn rather than laid out, so the bar needs no intrinsic
            // height measurement of whatever the quote contains.
            .drawBehind { drawRect(bar, topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val quoted = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        block.blocks.forEachIndexed { j, child -> BlockView(child, inline, quoted, depth + 1, "$findKey.$j") }
    }
}

@Composable
private fun ListView(block: MdBlock.ListBlock, inline: InlineStyles, style: TextStyle, depth: Int, findKey: String) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row {
                Box(
                    modifier = Modifier
                        .widthIn(min = if (block.ordered) 24.dp else 18.dp)
                        .padding(end = 6.dp),
                    contentAlignment = if (block.ordered) Alignment.TopEnd else Alignment.TopCenter,
                ) {
                    val checked = item.checked
                    if (checked != null) {
                        TaskGlyph(
                            ToolDisplay.Tasks.Item(text = "", done = checked, active = false),
                            modifier = Modifier.padding(top = 4.dp).size(14.dp),
                        )
                    } else {
                        Text(
                            text = if (block.ordered) "${block.start + index}." else if (depth % 2 == 0) "•" else "◦",
                            style = style,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.blocks.forEachIndexed { b, child -> BlockView(child, inline, style, depth + 1, "$findKey.$index.$b") }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ tables */

/**
 * A table, sized to its content and scrolled sideways when wider than the
 * screen. Columns take their natural width up to a cap, after which cells
 * wrap; each row is as tall as its tallest cell.
 */
@Composable
private fun TableView(block: MdBlock.Table, inline: InlineStyles, findKey: String) {
    val colors = LocalEditorColors.current
    val columns = block.header.size
    val rows = remember(block) { listOf(block.header) + block.rows }
    val cellStyle = MaterialTheme.typography.bodyMedium
    val shape = RoundedCornerShape(6.dp)
    val line = colors.border

    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Layout(
            modifier = Modifier.clip(shape).border(1.dp, colors.border, shape),
            content = {
                rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        val align = block.alignments.getOrElse(c) { Align.START }
                        Box(
                            modifier = Modifier
                                .background(if (r == 0) colors.raised else Color.Transparent)
                                .drawBehind {
                                    val stroke = 1.dp.toPx()
                                    if (c < columns - 1) {
                                        drawRect(line, Offset(size.width - stroke, 0f), Size(stroke, size.height))
                                    }
                                    if (r < rows.size - 1) {
                                        drawRect(line, Offset(0f, size.height - stroke), Size(size.width, stroke))
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            contentAlignment = when (align) {
                                Align.START -> Alignment.CenterStart
                                Align.CENTER -> Alignment.Center
                                Align.END -> Alignment.CenterEnd
                            },
                        ) {
                            InlineText(
                                text = cell,
                                inline = inline,
                                style = cellStyle,
                                findKey = "$findKey:$r:$c",
                                fontWeight = if (r == 0) FontWeight.SemiBold else null,
                                textAlign = when (align) {
                                    Align.START -> TextAlign.Start
                                    Align.CENTER -> TextAlign.Center
                                    Align.END -> TextAlign.End
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val maxCell = 260.dp.roundToPx()
            val minCell = 40.dp.roundToPx()
            val rowCount = if (columns == 0) 0 else measurables.size / columns
            val widths = IntArray(columns)
            measurables.forEachIndexed { i, measurable ->
                val c = i % columns
                widths[c] = maxOf(widths[c], measurable.maxIntrinsicWidth(Constraints.Infinity).coerceIn(minCell, maxCell))
            }
            val heights = IntArray(rowCount)
            measurables.forEachIndexed { i, measurable ->
                val r = i / columns
                if (r < rowCount) heights[r] = maxOf(heights[r], measurable.maxIntrinsicHeight(widths[i % columns]))
            }
            val placeables = measurables.mapIndexed { i, measurable ->
                val r = (i / columns).coerceAtMost(rowCount - 1)
                measurable.measure(Constraints.fixed(widths[i % columns], heights[r]))
            }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}
