package dev.apkcli.ui.editor

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import dev.apkcli.R
import dev.apkcli.ui.components.Banner
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.ButtonKind
import dev.apkcli.ui.components.EditorButton
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.LocalToaster
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.components.TileTone
import dev.apkcli.ui.components.Tone
import dev.apkcli.ui.components.hairline
import dev.apkcli.ui.files.FileTypeIcon
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.theme.Design
import dev.apkcli.ui.theme.EditorColors
import dev.apkcli.ui.theme.LocalEditorColors
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.File

/**
 * The editor half of the Files tab: design/prototype/app.js editorView().
 *
 * The tab row (the way back to the tree first), the code, and the status
 * line. Two things the prototype does not have: saving is explicit, so the
 * tab row ends with undo, redo and save, and the status line has the word
 * wrap switch.
 *
 * The CodeEditor is a classic View hosted through [AndroidView]. It is
 * created once and re-pointed at buffers as tabs change, rather than one view
 * per tab: a CodeEditor is several megabytes of layout cache, and only one is
 * ever visible.
 */
@Composable
fun EditorView(
    viewModel: EditorViewModel,
    state: EditorViewModel.UiState,
    onShowTree: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEditorColors.current
    var pendingClose by remember { mutableStateOf<String?>(null) }
    val holder = remember { EditorHolder() }
    val toaster = LocalToaster.current

    // A file that could not be opened or saved: one line, no answer needed.
    val message = state.message?.resolve()
    LaunchedEffect(message) {
        if (message != null) {
            toaster.show(message, Codicons.Warning)
            viewModel.dismissMessage()
        }
    }

    Column(modifier.fillMaxSize().background(Design.Colors.Bg)) {
        TabsRow(
            state = state,
            holder = holder,
            onShowTree = onShowTree,
            onSelect = { path ->
                holder.saveCursor(viewModel)
                viewModel.activate(path)
            },
            onClose = { path -> if (viewModel.isDirty(path)) pendingClose = path else viewModel.close(path) },
            onSave = { state.active?.let(viewModel::save) },
        )

        val activeTab = state.activeTab
        if (activeTab != null && activeTab.changedOnDisk && activeTab.dirty) {
            Banner(
                title = stringResource(R.string.editor_changed_title),
                text = stringResource(R.string.editor_changed_body, activeTab.name),
                tone = Tone.Warning,
                actions = {
                    EditorButton(
                        text = stringResource(R.string.editor_reload),
                        kind = ButtonKind.Primary,
                        onClick = { viewModel.reload(activeTab.path) },
                    )
                    EditorButton(
                        text = stringResource(R.string.editor_keep_mine),
                        kind = ButtonKind.Ghost,
                        onClick = { viewModel.keepMine(activeTab.path) },
                    )
                },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            CodeEditorView(viewModel = viewModel, state = state, holder = holder, colors = colors)
        }

        StatusLine(
            state = state,
            holder = holder,
            lines = state.active?.let(viewModel::content)?.lineCount ?: 0,
            onToggleWrap = viewModel::toggleWordWrap,
        )
    }

    pendingClose?.let { path ->
        Sheet(onDismiss = { pendingClose = null }) {
            SheetHead(
                title = stringResource(R.string.editor_unsaved_title, File(path).name),
                subtitle = stringResource(R.string.editor_unsaved_body),
                icon = Codicons.Save,
                tone = TileTone.Warning,
            )
            SheetActions {
                Btn(
                    stringResource(R.string.editor_discard),
                    onClick = {
                        pendingClose = null
                        viewModel.close(path)
                    },
                    kind = BtnKind.Danger,
                    modifier = Modifier.weight(1f),
                )
                Btn(
                    stringResource(R.string.editor_save),
                    onClick = {
                        pendingClose = null
                        viewModel.save(path)
                        viewModel.close(path)
                    },
                    kind = BtnKind.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The live CodeEditor, for undo and redo, and where its cursor is. */
private class EditorHolder {
    var editor: CodeEditor? = null
    var shownPath: String? = null

    /** 1-based, for the status line. */
    var line by mutableIntStateOf(1)
    var column by mutableIntStateOf(1)

    fun saveCursor(viewModel: EditorViewModel) {
        val editor = editor ?: return
        val path = shownPath ?: return
        val cursor = editor.cursor
        viewModel.rememberCursor(path, cursor.leftLine, cursor.leftColumn)
    }

    fun readCursor() {
        val cursor = editor?.cursor ?: return
        line = cursor.leftLine + 1
        column = cursor.leftColumn + 1
    }
}

@Composable
private fun CodeEditorView(
    viewModel: EditorViewModel,
    state: EditorViewModel.UiState,
    holder: EditorHolder,
    colors: EditorColors,
) {
    val currentViewModel by rememberUpdatedState(viewModel)
    val editorColors = remember(colors) { editorScheme(colors, Design.Colors.Accent) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // The design's code font, the same JetBrains Mono the chat uses.
            val mono = runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull()
                ?: Typeface.MONOSPACE
            CodeEditor(context).apply {
                val density = context.resources.displayMetrics.density
                setTypefaceText(mono)
                setTypefaceLineNumber(mono)
                // `code`: 13sp text on 20dp rows. A row is the font's own
                // height plus the spacing, which sora evens down to whole
                // pixels: one pixel more lands it on the nearest.
                setTextSize(13f)
                setLineSpacing(20 * density - rowHeightOfText + 1f, 1f)
                // `.cl .n`: 12 dp before the numbers, 16 between them and the
                // code, no rule: the design separates them by spacing alone.
                setLineNumberMarginLeft(12 * density)
                setDividerWidth(0f)
                setDividerMargin(16 * density, 0f)
                // The design's code font has its ligatures off: `<!--` and
                // `->` are what was typed, not arrows.
                isLigatureEnabled = false
                setPinLineNumber(true)
                setHighlightCurrentLine(true)
                setTabWidth(4)
                setCursorAnimationEnabled(false)
                // Lets a horizontal swipe on a short line reach the split
                // divider and the pane around it instead of being eaten.
                setInterceptParentHorizontalScrollIfNeeded(true)
                setText("")
                isEditable = false
                subscribeAlways(ContentChangeEvent::class.java) { _ ->
                    holder.shownPath?.let { currentViewModel.onEdited(it) }
                }
                subscribeAlways(SelectionChangeEvent::class.java) { _ -> holder.readCursor() }
                holder.editor = this
            }
        },
        update = { editor ->
            if (editor.colorScheme !== editorColors) editor.colorScheme = editorColors
            if (editor.isWordwrap != state.wordWrap) editor.setWordwrap(state.wordWrap)

            val path = state.active
            val content = path?.let(viewModel::content)
            if (path == null || content == null) {
                if (holder.shownPath != null) {
                    holder.shownPath = null
                    editor.setEditorLanguage(EmptyLanguage())
                    editor.setText("")
                    editor.isEditable = false
                }
                return@AndroidView
            }

            if (holder.shownPath != path || editor.text !== content) {
                holder.shownPath = path
                editor.setEditorLanguage(Syntax.forFile(File(path).name)?.let(::HighlightedLanguage) ?: EmptyLanguage())
                // Reuse the Content object: it carries this buffer's undo history.
                editor.setText(content, true, null)
                editor.isEditable = true
                val (line, column) = viewModel.cursorOf(path)
                runCatching { editor.setSelection(line, column) }
                holder.readCursor()
            }

            state.jump?.takeIf { it.path == path }?.let { jump ->
                val line = (jump.line - 1).coerceIn(0, (content.lineCount - 1).coerceAtLeast(0))
                runCatching {
                    editor.setSelection(line, 0)
                    editor.ensureSelectionVisible()
                }
                viewModel.consumeJump(jump.serial)
            }
        },
        onRelease = { editor ->
            holder.editor = null
            holder.shownPath = null
            editor.release()
        },
    )

    DisposableEffect(Unit) {
        onDispose { holder.saveCursor(currentViewModel) }
    }
}

/** The design's editor palette, mapped onto sora's colour ids. */
private fun editorScheme(colors: EditorColors, accent: Color): EditorColorScheme {
    val text = colors.syntaxPlain
    val keyword = colors.syntaxKeyword
    val literal = colors.syntaxString
    val function = colors.syntaxFunction
    val annotation = colors.syntaxAnnotation
    val comment = colors.syntaxComment

    return EditorColorScheme().apply {
        setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.canvas.toArgb())
        setColor(EditorColorScheme.TEXT_NORMAL, text.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.canvas.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER, colors.gutter.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_CURRENT, Design.Colors.TextSecondary.toArgb())
        // No divider between gutter and code: the design separates them by
        // spacing alone.
        setColor(EditorColorScheme.LINE_DIVIDER, colors.canvas.toArgb())
        setColor(EditorColorScheme.CURRENT_LINE, colors.currentLine.toArgb())
        setColor(SyntaxColorIds.NUMBER, colors.syntaxNumber.toArgb())
        setColor(SyntaxColorIds.CONTROL, colors.syntaxControl.toArgb())
        setColor(SyntaxColorIds.CONSTANT, colors.syntaxConstant.toArgb())
        setColor(SyntaxColorIds.VARIABLE, colors.syntaxVariable.toArgb())
        setColor(SyntaxColorIds.TYPE, colors.syntaxType.toArgb())
        setColor(EditorColorScheme.SELECTION_INSERT, accent.toArgb())
        setColor(EditorColorScheme.SELECTION_HANDLE, accent.toArgb())
        setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.selection.toArgb())
        setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, colors.selection.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB, colors.borderStrong.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, accent.toArgb())
        setColor(EditorColorScheme.BLOCK_LINE, colors.border.toArgb())
        setColor(EditorColorScheme.BLOCK_LINE_CURRENT, colors.borderStrong.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_PANEL, colors.raised.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, text.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, colors.raised.toArgb())
        setColor(EditorColorScheme.COMPLETION_WND_CORNER, colors.border.toArgb())
        setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, colors.raised.toArgb())
        setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, text.toArgb())
        setColor(EditorColorScheme.KEYWORD, keyword.toArgb())
        setColor(EditorColorScheme.COMMENT, comment.toArgb())
        setColor(EditorColorScheme.LITERAL, literal.toArgb())
        setColor(EditorColorScheme.OPERATOR, text.toArgb())
        setColor(EditorColorScheme.IDENTIFIER_NAME, text.toArgb())
        setColor(EditorColorScheme.IDENTIFIER_VAR, text.toArgb())
        setColor(EditorColorScheme.FUNCTION_NAME, function.toArgb())
        setColor(EditorColorScheme.ANNOTATION, annotation.toArgb())
        setColor(EditorColorScheme.ATTRIBUTE_NAME, annotation.toArgb())
        setColor(EditorColorScheme.ATTRIBUTE_VALUE, literal.toArgb())
        setColor(EditorColorScheme.HTML_TAG, keyword.toArgb())
        setColor(EditorColorScheme.NON_PRINTABLE_CHAR, colors.textTertiary.toArgb())
    }
}

/**
 * `.tabs`: 40 dp. The tree button, the open files — the active one on
 * surface 1 under a 2 dp accent line, an unsaved one with a dot for its close
 * button — then undo, redo and save.
 */
@Composable
private fun TabsRow(
    state: EditorViewModel.UiState,
    holder: EditorHolder,
    onShowTree: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onSave: () -> Unit,
) {
    val modified = stringResource(R.string.editor_modified)
    val active = state.activeTab
    // Read so undo and redo availability follows edits.
    state.editSerial

    Row(
        Modifier
            .fillMaxWidth()
            .height(Design.Size.Tab)
            .hairline(Design.Colors.Border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClick = onShowTree)
                .rightEdge(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Codicons.ListTree,
                contentDescription = stringResource(R.string.explorer_show),
                tint = Design.Colors.TextSecondary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
        }

        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            state.tabs.forEach { tab ->
                val on = tab.path == state.active
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 200.dp)
                        .background(if (on) Design.Colors.Surface1 else Color.Transparent)
                        .drawBehind {
                            if (on) drawRect(Design.Colors.Accent, size = size.copy(height = 2.dp.toPx()))
                        }
                        .clickable(role = Role.Tab) { onSelect(tab.path) }
                        .rightEdge()
                        .semantics {
                            selected = on
                            contentDescription = if (tab.dirty) "${tab.name}, $modified" else tab.name
                        }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FileTypeIcon(tab.name, size = Design.Size.IconTiny)
                    Text(
                        text = tab.name,
                        style = Design.Type.BodySmall,
                        color = if (on) Design.Colors.Text else Design.Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val closeLabel = stringResource(R.string.editor_close_tab, tab.name)
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { onClose(tab.path) }
                            .semantics { contentDescription = closeLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        // VS Code's convention: a dot stands in for the close
                        // button of an unsaved tab; tapping it still closes.
                        if (tab.dirty) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Design.Colors.Text))
                        } else {
                            Icon(
                                Codicons.Close,
                                contentDescription = null,
                                tint = Design.Colors.TextTertiary,
                                modifier = Modifier.size(Design.Size.IconTiny),
                            )
                        }
                    }
                }
            }
        }

        if (active != null) {
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(Design.Colors.Divider))
            IconBtn(
                Codicons.Discard,
                stringResource(R.string.editor_undo),
                onClick = { holder.editor?.undo() },
                size = IconBtnSize.Composer,
                enabled = holder.editor?.canUndo() == true,
            )
            IconBtn(
                Codicons.Redo,
                stringResource(R.string.editor_redo),
                onClick = { holder.editor?.redo() },
                size = IconBtnSize.Composer,
                enabled = holder.editor?.canRedo() == true,
            )
            IconBtn(
                Codicons.Save,
                stringResource(R.string.editor_save),
                onClick = onSave,
                size = IconBtnSize.Composer,
                enabled = active.dirty,
                tint = Design.Colors.Accent,
            )
        }
    }
}

/** A 1 dp divider down the right edge, as each tab and the tree button have. */
private fun Modifier.rightEdge(): Modifier = drawBehind {
    val x = size.width - 0.5.dp.toPx()
    drawLine(Design.Colors.Divider, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
}

/** `.statusline`: 24 dp of mono caption — where the cursor is, how long the file is, its language. */
@Composable
private fun StatusLine(
    state: EditorViewModel.UiState,
    holder: EditorHolder,
    lines: Int,
    onToggleWrap: () -> Unit,
) {
    val name = state.activeTab?.name.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .height(Design.Size.StatusLine)
            .hairline(Design.Colors.Border, top = true)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val style = Design.Type.MonoCaption
        val color = Design.Colors.TextTertiary
        Text(stringResource(R.string.editor_line_col, holder.line, holder.column), style = style, color = color, maxLines = 1)
        Text(pluralStringResource(R.plurals.editor_lines, lines, lines), style = style, color = color, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(languageName(name), style = style, color = color, maxLines = 1)
        Text("UTF-8", style = style, color = color, maxLines = 1)
        val wrapLabel = stringResource(R.string.editor_word_wrap)
        Text(
            stringResource(R.string.editor_wrap_short),
            style = style,
            color = if (state.wordWrap) Design.Colors.Accent else color,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Switch, onClick = onToggleWrap)
                .semantics {
                    contentDescription = wrapLabel
                    toggleableState = ToggleableState(state.wordWrap)
                }
                .padding(horizontal = 4.dp),
        )
    }
}

/** The language as the status line names it; "text" when there is no highlighting for it. */
internal fun languageName(fileName: String): String {
    val lower = fileName.lowercase()
    if (lower == "makefile") return "makefile"
    if (lower == "dockerfile") return "dockerfile"
    if (lower.startsWith(".env") || lower == "requirements.txt") return "conf"
    return when (lower.substringAfterLast('.', "")) {
        "kt", "kts" -> "kotlin"
        "gradle", "groovy" -> "groovy"
        "java" -> "java"
        "scala" -> "scala"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "dart" -> "dart"
        "rs" -> "rust"
        "go" -> "go"
        "c", "h" -> "c"
        "cc", "cpp", "cxx", "hpp", "hh" -> "cpp"
        "m", "mm" -> "objective-c"
        "cs" -> "csharp"
        "swift" -> "swift"
        "py", "pyi" -> "python"
        "sh", "bash", "zsh", "fish" -> "sh"
        "ps1" -> "powershell"
        "toml" -> "toml"
        "yaml", "yml" -> "yaml"
        "ini", "cfg", "conf", "properties", "pro", "mk" -> "conf"
        "json", "jsonc", "json5" -> "json"
        "xml", "svg" -> "xml"
        "html", "htm" -> "html"
        "vue" -> "vue"
        "css", "scss", "sass", "less" -> "css"
        "sql" -> "sql"
        "md", "markdown" -> "markdown"
        else -> "text"
    }
}
