package dev.apkcli.ui.terminal

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.apkcli.R
import dev.apkcli.core.runtime.TerminalBuffer
import dev.apkcli.core.runtime.TerminalSessions
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Dot
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.LocalToaster
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.hairline
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.theme.Design
import java.io.File

/**
 * The Terminal tab: design/prototype/app.js terminalTab().
 *
 * The project's sessions as chips — "Shell #N", a pulsing dot while
 * something runs, an x on the picked one — then its output, then the input
 * line and the keys a phone keyboard lacks.
 *
 * Input is a line at a time rather than key by key. A phone has no physical
 * keyboard: every character arrives through an IME that composes, predicts
 * and replaces, and forwarding that stream raw produces duplicated and
 * reordered input. A line committed on Enter is what a shell wants anyway,
 * and the keys it needs that an IME has no key for are buttons.
 */
@Composable
fun TerminalTab(
    viewModel: TerminalViewModel,
    workspace: File?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val terminal = viewModel.terminal(state.activeId)

    LaunchedEffect(workspace) { viewModel.setWorkspace(workspace) }
    // The busy dots stay live only while this is on screen.
    LaunchedEffect(viewModel) { viewModel.watch() }

    // A project's terminal starts with a shell, as the prototype's has one
    // from the start — unless the user closed the last one, which is theirs
    // to do and not to be undone a frame later.
    val listed = state.workspace != null && state.workspace == workspace?.absolutePath
    val empty = listed && state.sessions.isEmpty()
    LaunchedEffect(empty, state.available, state.autoOpen) {
        if (empty && state.available && state.autoOpen) viewModel.newSession(workspace)
    }

    val message = state.message?.resolve()
    LaunchedEffect(message) {
        if (message != null) {
            toaster.show(message, Codicons.Warning)
            viewModel.dismissMessage()
        }
    }

    Column(modifier.fillMaxSize().background(Design.Colors.Bg).imePadding()) {
        SessionsRow(
            sessions = state.sessions,
            activeId = state.activeId,
            starting = state.starting,
            canOpen = state.available && !state.starting,
            onSelect = viewModel::select,
            onClose = viewModel::close,
            onNew = { viewModel.newSession(workspace) },
        )

        Box(Modifier.weight(1f).fillMaxWidth().background(Design.Colors.TerminalBg)) {
            when {
                !state.available -> Unavailable()
                terminal != null -> Output(
                    terminal = terminal,
                    cursorShown = state.active?.alive == true,
                    onGeometry = viewModel::resize,
                )
                else -> {
                    // Measured even with nothing open, so the first shell
                    // starts at this width: one started at 80 columns and
                    // resized a frame later redraws its prompt at once.
                    val cell = rememberCell()
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val (cols, rows) = geometry(maxWidth, maxHeight, cell)
                        LaunchedEffect(cols, rows) { viewModel.resize(cols, rows) }
                        // The user closed the last one: said, with the way
                        // back, rather than a black page.
                        if (empty && !state.autoOpen && !state.starting) {
                            NoSessions(onNew = { viewModel.newSession(workspace) })
                        }
                    }
                }
            }
        }

        TerminalBar(viewModel = viewModel, state = state)
    }
}

/* ============================================================ sessions */

/** `.sessions`: 48 dp of chips, scrolling sideways, and the "+" after them. */
@Composable
private fun SessionsRow(
    sessions: List<TerminalViewModel.SessionRow>,
    activeId: String?,
    starting: Boolean,
    canOpen: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .hairline(Design.Colors.Border)
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sessions.forEach { session ->
            SessionChip(
                session = session,
                on = session.id == activeId,
                // On every chip, not only the picked one: closing the first
                // shell should not need selecting it first.
                closable = true,
                onSelect = { onSelect(session.id) },
                onClose = { onClose(session.id) },
            )
        }
        if (starting) {
            Box(Modifier.size(Design.Size.IconButtonSmall), contentAlignment = Alignment.Center) { Spinner() }
        } else {
            IconBtn(
                Codicons.Add,
                stringResource(R.string.terminal_new),
                onClick = onNew,
                size = IconBtnSize.Small,
                enabled = canOpen,
            )
        }
    }
}

/**
 * What a session is called: "Shell #2" for the user's own, "Agent" for a
 * chat's transcript of the agent's commands — which is not a shell the agent
 * declined to reuse, but the place its commands are shown, and which takes a
 * typed line of its own.
 */
@Composable
private fun sessionName(session: TerminalViewModel.SessionRow): String = when {
    !session.transcript -> stringResource(R.string.terminal_shell_name, session.number)
    session.number <= 1 -> stringResource(R.string.terminal_agent_name)
    else -> stringResource(R.string.terminal_agent_numbered, session.number)
}

/** `.session`: 32 dp, 10 dp corners; the picked one a step lighter with a stronger edge. */
@Composable
private fun SessionChip(
    session: TerminalViewModel.SessionRow,
    on: Boolean,
    closable: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(Design.Radius.Session)
    val name = sessionName(session)
    val busyLabel = stringResource(R.string.terminal_busy)
    Row(
        Modifier
            .height(Design.Size.Session)
            .widthIn(max = 230.dp)
            .clip(shape)
            .background(if (on) Design.Colors.Surface3 else Design.Colors.Surface1)
            .border(1.dp, if (on) Design.Colors.BorderStrong else Design.Colors.Border, shape)
            .selectable(selected = on, role = Role.Tab, onClick = onSelect)
            .semantics { contentDescription = if (session.busy) "$name, $busyLabel" else name }
            .padding(start = 12.dp, end = if (closable) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Codicons.Terminal,
            contentDescription = null,
            tint = if (on) Design.Colors.TextSecondary else Design.Colors.TextTertiary,
            modifier = Modifier.size(Design.Size.IconSmall),
        )
        Text(
            name,
            style = Design.Type.LabelSmall,
            color = if (on) Design.Colors.Text else Design.Colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (session.busy) Dot(pulse = true)
        if (closable) {
            val closeLabel = stringResource(R.string.terminal_close, name)
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Codicons.Close, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = Modifier.size(Design.Size.IconTiny))
            }
        }
    }
}

/* ============================================================== output */

/**
 * `.term-out`: the scrollback on the terminal's black, 10 by 12 dp in.
 *
 * Measured, not guessed: the number of columns is derived from the width of
 * one monospace character, and reported to the PTY. Without that, every
 * program wraps at the default 80 and a phone in portrait shows ragged
 * half-lines.
 */
@Composable
private fun Output(
    terminal: TerminalSessions.Terminal,
    cursorShown: Boolean,
    onGeometry: (cols: Int, rows: Int) -> Unit,
) {
    // Redrawn when the session says its scrollback changed, not on a timer.
    val revision by terminal.revision.collectAsStateWithLifecycle()
    val theme = TerminalTheme.Default
    // One scroll position per session: switching sessions must land at the
    // end of the other one's output, not at the offset this one was left at.
    val listState = remember(terminal.id) { LazyListState() }
    val cell = rememberCell()

    val rows = remember(revision, terminal.id) { terminal.rows(MAX_VISIBLE_LINES) }
    val cursor = remember(revision, terminal.id) { terminal.cursor(MAX_VISIBLE_LINES) }
    val lines = remember(rows) { rows.map { row -> row.annotated(theme) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (cols, visibleRows) = geometry(maxWidth, maxHeight, cell)

        LaunchedEffect(cols, visibleRows, terminal.id) { onGeometry(cols, visibleRows) }

        // Screen rows, split at the width the program was given — not word
        // wrapped. A shell wraps a long prompt at that column and redraws
        // from it; breaking at a space instead puts its redraw in the wrong
        // place. The cursor's row and column are found on the way.
        val screen = remember(lines, cols, cursor) { splitRows(lines, cols, cursor) }

        // Follow the output only while the user is already at the bottom:
        // scrolling up to read something must not be yanked away by the next
        // line of a build.
        val atBottom = !listState.canScrollForward
        LaunchedEffect(revision, screen.rows.size) {
            if (atBottom && screen.rows.isNotEmpty()) listState.scrollToItem(screen.rows.lastIndex)
        }

        val density = LocalDensity.current
        val cellWidth = with(density) { cell.width.toDp() }

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = H_PADDING, vertical = V_PADDING),
            ) {
                itemsIndexed(screen.rows) { index, line ->
                    Box {
                        Text(text = line, style = Design.Type.CodeSmall, color = theme.foreground, softWrap = false, maxLines = 1)
                        if (cursorShown && index == screen.cursorRow) {
                            BlinkingCursor(Modifier.offset(x = cellWidth * screen.cursorColumn).align(Alignment.CenterStart))
                        }
                    }
                }
            }
        }
    }
}

/** `.cursor`: a 7 by 14 block in the output's colour, on and off each half second. */
@Composable
private fun BlinkingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0 using LinearEasing
                1f at 499 using LinearEasing
                0f at 500 using LinearEasing
                0f at 999 using LinearEasing
            },
        ),
        label = "cursor-alpha",
    )
    Box(
        modifier
            .size(7.dp, 14.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(Design.Ansi.Foreground),
    )
}

private class ScreenRows(val rows: List<AnnotatedString>, val cursorRow: Int, val cursorColumn: Int)

/** [lines] cut into rows of [cols], and where (line, column) [cursor] falls among them. */
private fun splitRows(lines: List<AnnotatedString>, cols: Int, cursor: Pair<Int, Int>): ScreenRows {
    val out = ArrayList<AnnotatedString>(lines.size)
    var cursorRow = -1
    var cursorColumn = 0
    lines.forEachIndexed { index, line ->
        val first = out.size
        if (line.length <= cols) {
            out += line
        } else {
            var start = 0
            while (start < line.length) {
                out += line.subSequence(start, minOf(start + cols, line.length))
                start += cols
            }
        }
        if (index == cursor.first) {
            val column = cursor.second.coerceAtLeast(0)
            val within = column / cols
            // At the very end of a full row the cursor waits at the start of
            // the next one, which has nothing on it yet.
            while (first + within >= out.size) out += AnnotatedString("")
            cursorRow = first + within
            cursorColumn = column % cols
        }
    }
    return ScreenRows(out, cursorRow, cursorColumn)
}

/** One monospace character cell, in pixels. */
private class Cell(val width: Float, val height: Int)

/**
 * The size of one character cell.
 *
 * Ten glyphs are measured, not one: a layout's width is rounded up to a
 * whole pixel, and one glyph's rounding times fifty columns loses a column or
 * two. Rounding up is still the safe direction — a column too few wastes a
 * sliver of screen, a column too many breaks every line the shell wraps.
 */
@Composable
private fun rememberCell(): Cell {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val measured = measurer.measure(AnnotatedString("M".repeat(CELL_SAMPLE)), style = Design.Type.CodeSmall)
        Cell(
            width = (measured.size.width.toFloat() / CELL_SAMPLE).coerceAtLeast(1f),
            height = measured.size.height.coerceAtLeast(1),
        )
    }
}

/** Columns and rows of output that fit [width] by [height], less the padding. */
@Composable
private fun geometry(width: Dp, height: Dp, cell: Cell): Pair<Int, Int> {
    val density = LocalDensity.current
    val widthPx = with(density) { (width - H_PADDING * 2).toPx() }
    val heightPx = with(density) { (height - V_PADDING * 2).toPx() }
    return (widthPx / cell.width).toInt().coerceIn(20, 500) to
        (heightPx / cell.height).toInt().coerceIn(4, 200)
}

/** One buffer row as styled text. Also how a running tool call's live view is drawn. */
internal fun TerminalBuffer.Row.annotated(theme: TerminalTheme): AnnotatedString {
    // A line with no attributes at all — most of them — skips the builder.
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            val (fg, bg) = theme.paint(span.style)
            addStyle(
                SpanStyle(
                    color = fg,
                    background = bg ?: Color.Unspecified,
                    fontWeight = if (span.style.bold) FontWeight.Bold else null,
                    fontStyle = if (span.style.italic) FontStyle.Italic else null,
                    textDecoration = if (span.style.underline) TextDecoration.Underline else null,
                ),
                span.start,
                span.end.coerceAtMost(text.length),
            )
        }
    }
}

/** No native core, so no PTY: said where the output would be. */
@Composable
private fun Unavailable() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Codicons.Terminal, contentDescription = null, tint = Design.Colors.TextSecondary, modifier = Modifier.size(28.dp))
        Text(
            stringResource(R.string.terminal_unavailable_title),
            style = Design.Type.Label,
            color = Design.Colors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.terminal_unavailable_body),
            style = Design.Type.BodySmall,
            color = Design.Colors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Every session closed: what is here, and the button that opens one. */
@Composable
private fun NoSessions(onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Codicons.Terminal, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = Modifier.size(28.dp))
        Text(
            stringResource(R.string.terminal_none),
            style = Design.Type.BodySmall,
            color = Design.Colors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Btn(
            text = stringResource(R.string.terminal_new),
            onClick = onNew,
            kind = BtnKind.Secondary,
            small = true,
            icon = Codicons.Add,
        )
    }
}

/* ================================================================= bar */

/**
 * `.term-bar`: the input line — an accent "$", the command, send — and the
 * keys. `|`, `~`, `/` and `-` type themselves where the cursor is; the rest
 * go to the program.
 */
@Composable
private fun TerminalBar(viewModel: TerminalViewModel, state: TerminalViewModel.UiState) {
    val active = state.active
    val focus = remember { FocusRequester() }
    // The field's own value, for where its cursor is; the ViewModel has the
    // text. A change made there (history, a send) puts the cursor at the end.
    var field by remember { mutableStateOf(TextFieldValue(state.input, TextRange(state.input.length))) }
    LaunchedEffect(state.inputSerial) {
        if (field.text != state.input) field = TextFieldValue(state.input, TextRange(state.input.length))
    }
    val typable = active != null && active.alive

    Column(
        Modifier
            .fillMaxWidth()
            .background(Design.Colors.Surface1)
            .hairline(Design.Colors.Border, top = true),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$", style = Design.Type.MonoLabel, color = Design.Colors.Accent)
            val hint = stringResource(R.string.terminal_input_hint)
            BasicTextField(
                value = field,
                onValueChange = { value ->
                    // A cursor move alone is not an edit.
                    if (value.text != field.text) viewModel.onInputChange(value.text)
                    field = value
                },
                enabled = typable,
                textStyle = Design.Type.Code.copy(color = Design.Colors.Text),
                cursorBrush = SolidColor(Design.Colors.Accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (field.text.isEmpty()) {
                            Text(
                                hint,
                                style = Design.Type.Code,
                                color = Design.Colors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            IconBtn(
                Codicons.Send,
                stringResource(R.string.terminal_send),
                onClick = viewModel::send,
                size = IconBtnSize.Composer,
                enabled = viewModel.canSend(state),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TermKey.entries.forEach { key ->
                Key(key.label, enabled = viewModel.keyEnabled(key, active, state)) { viewModel.key(key) }
            }
            TEXT_KEYS.forEach { text ->
                Key(text, enabled = typable) {
                    val inserted = field.text.replaceRange(field.selection.min, field.selection.max, text)
                    val at = field.selection.min + text.length
                    field = TextFieldValue(inserted, TextRange(at))
                    viewModel.onInputChange(inserted)
                    runCatching { focus.requestFocus() }
                }
            }
        }
    }
}

/** `.key`: 30 dp, at least 44 wide, surface 2 with a strong edge; a step lighter while pressed. */
@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Design.Radius.Key)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(Design.Size.Key)
            .widthIn(min = 44.dp)
            .clip(shape)
            .background(if (pressed && enabled) Design.Colors.Surface3 else Design.Colors.Surface2)
            .border(1.dp, if (enabled) Design.Colors.BorderStrong else Design.Colors.Border, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Design.Type.MonoLabel,
            color = if (enabled) Design.Colors.TextSecondary else Design.Colors.TextDisabled,
            maxLines = 1,
        )
    }
}

/** Keys that type themselves: awkward to reach on a phone keyboard, common in a command. */
private val TEXT_KEYS = listOf("|", "~", "/", "-")

private val H_PADDING = 12.dp
private val V_PADDING = 10.dp

/**
 * Lines kept on screen.
 *
 * The buffer holds more (see `TerminalBuffer`), and the agent can read all
 * of it; this is what one composition turns into text. A phone shows forty
 * lines, so this is already several screens of scrolling.
 */
private const val MAX_VISIBLE_LINES = 600

/** Glyphs measured for [rememberCell]. */
private const val CELL_SAMPLE = 10
