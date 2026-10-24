package dev.apkcli.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.Menu
import dev.apkcli.ui.components.MenuItem
import dev.apkcli.ui.components.TopBar
import dev.apkcli.ui.components.hairline
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.Design
import dev.apkcli.ui.workbench.Pane
import dev.apkcli.ui.workbench.Workbench
import dev.apkcli.ui.workbench.WorkbenchLayout

/**
 * One project: design/prototype/app.js wsScreen().
 *
 * The top bar names the project and, under it, the agent's mode — a tap
 * there changes it — then the chat's search, its history and a new chat.
 * Below, the tab picked in the bottom bar: the agent, the files, the
 * terminals.
 *
 * Where there is room — a tablet, a foldable, landscape, or always if the
 * user says so — the agent keeps one side and the other shows the files or
 * the terminals, whichever the bar picked last. The arrangement is
 * [WorkbenchLayout]'s, the same one Settings › Interface sets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(
    projectName: String,
    agentLine: String,
    layout: WorkbenchLayout,
    onUpdateLayout: ((WorkbenchLayout) -> WorkbenchLayout) -> Unit,
    onResetLayout: () -> Unit,
    onBack: () -> Unit,
    onAgentSheet: () -> Unit,
    onMemory: () -> Unit,
    onFind: () -> Unit,
    findEnabled: Boolean,
    /** The words searched for in the chat while its search is on; null while it is off. */
    findQuery: String?,
    onFindQuery: (String) -> Unit,
    /** The keyboard's search key: the next older match. */
    onFindSearch: () -> Unit,
    onFindClose: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    newChatEnabled: Boolean,
    onSettings: () -> Unit,
    filesBadge: Boolean,
    terminalBadge: Boolean,
    agent: @Composable () -> Unit,
    files: @Composable () -> Unit,
    terminal: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Design.Colors.Bg).statusBarsPadding()) {
        val resolved = layout.resolve(maxWidth, maxHeight - Design.Size.TopBar - Design.Size.BottomNav)
        val agentShown = resolved.split || layout.focus == Pane.CHAT
        // The mode under the title is never cut. What it needs is measured in
        // the user's font size; when the bar cannot hold it beside every
        // icon, "New chat" goes into the menu (the history sheet has it too),
        // and then the history.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val modeWidth = remember(agentLine, density) {
            with(density) {
                measurer.measure(agentLine, Design.Type.LabelSmall, maxLines = 1, softWrap = false).size.width.toDp()
            } + 4.dp + Design.Size.IconTiny
        }
        val fixed = 8.dp + Design.Size.IconButton + 16.dp + modeWidth
        val icons = ((maxWidth - fixed) / Design.Size.IconButton).toInt()
        val chatIcons = if (agentShown) (icons - 1).coerceIn(1, 3) else 0
        val newChatInBar = chatIcons >= 3
        val historyInBar = chatIcons >= 2

        // The search is the chat's: it ends with back, and when the chat
        // goes off screen (another tab on a phone).
        val searching = findQuery != null && agentShown
        BackHandler(enabled = findQuery != null, onBack = onFindClose)
        LaunchedEffect(agentShown, findQuery != null) {
            if (!agentShown && findQuery != null) onFindClose()
        }
        Column(Modifier.fillMaxSize()) {
            if (searching) {
                FindTopBar(initial = findQuery.orEmpty(), onQuery = onFindQuery, onSearch = onFindSearch, onClose = onFindClose)
            } else {
                TopBar(
                    title = projectName,
                    subtitle = agentLine,
                    onSubtitleClick = onAgentSheet,
                    onBack = onBack,
                    backDescription = stringResource(R.string.a11y_back),
                    actions = {
                        if (agentShown) {
                            IconBtn(Codicons.Search, stringResource(R.string.find_open), onClick = onFind, enabled = findEnabled)
                            if (historyInBar) IconBtn(Codicons.History, stringResource(R.string.history_title), onClick = onHistory)
                            if (newChatInBar) {
                                IconBtn(Codicons.Add, stringResource(R.string.history_new_chat), onClick = onNewChat, enabled = newChatEnabled)
                            }
                        }
                        Overflow(
                            onMemory = onMemory,
                            onSettings = onSettings,
                            onHistory = onHistory.takeIf { agentShown && !historyInBar },
                            onNewChat = onNewChat.takeIf { agentShown && !newChatInBar && newChatEnabled },
                        )
                    },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (resolved.split) {
                    Workbench(
                        layout = layout,
                        resolved = resolved,
                        onUpdate = onUpdateLayout,
                        onReset = onResetLayout,
                        files = { if (layout.side == Pane.TERMINAL) terminal() else files() },
                        chat = agent,
                    )
                } else {
                    when (layout.focus) {
                        Pane.CHAT -> agent()
                        Pane.FILES -> files()
                        Pane.TERMINAL -> terminal()
                    }
                }
            }

            // Hidden while the keyboard is up: it would sit on top of the
            // keys, and the composer's own inset already lifts it above them.
            if (!WindowInsets.isImeVisible) {
                BottomNav(
                    selected = { pane ->
                        if (resolved.split) pane == Pane.CHAT || pane == layout.side else pane == layout.focus
                    },
                    filesBadge = filesBadge,
                    terminalBadge = terminalBadge,
                    onSelect = { pane ->
                        onUpdateLayout { current ->
                            if (pane == Pane.CHAT) current.copy(focus = pane) else current.copy(focus = pane, side = pane)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The kebab: only what has nowhere else to live — and the chat's actions
 * that did not fit in the bar beside the mode ([onHistory], [onNewChat]).
 */
@Composable
private fun Overflow(
    onMemory: () -> Unit,
    onSettings: () -> Unit,
    onHistory: (() -> Unit)? = null,
    onNewChat: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(Codicons.KebabVertical, stringResource(R.string.a11y_more), onClick = { open = true })
        Menu(expanded = open, onDismiss = { open = false }) {
            if (onNewChat != null) {
                MenuItem(stringResource(R.string.history_new_chat), onClick = { open = false; onNewChat() }, icon = Codicons.Add)
            }
            if (onHistory != null) {
                MenuItem(stringResource(R.string.history_title), onClick = { open = false; onHistory() }, icon = Codicons.History)
            }
            MenuItem(stringResource(R.string.memory_title), onClick = { open = false; onMemory() }, icon = Codicons.Book)
            MenuItem(stringResource(R.string.nav_settings), onClick = { open = false; onSettings() }, icon = Codicons.SettingsGear)
        }
    }
}

/**
 * `.find-top`: while the chat is searched, the top bar is the field — back
 * ends the search, the keyboard's search key steps to the next older match.
 * The words are kept here as they are typed and passed on, so the field
 * never waits for the search to echo them back.
 */
@Composable
private fun FindTopBar(initial: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(Design.Radius.Input)
    Row(
        Modifier
            .fillMaxWidth()
            .height(Design.Size.TopBar)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconBtn(Codicons.ArrowLeft, stringResource(R.string.find_close), onClick = onClose)
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(shape)
                .background(Design.Colors.Surface2)
                .border(1.dp, Design.Colors.BorderFocus, shape)
                .padding(start = 12.dp, end = if (text.isNotEmpty()) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Codicons.Search, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = Modifier.size(Design.Size.IconSmall))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(stringResource(R.string.find_open), style = Design.Type.Body, color = Design.Colors.TextTertiary, maxLines = 1)
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onQuery(it)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    textStyle = Design.Type.Body.copy(color = Design.Colors.Text),
                    cursorBrush = SolidColor(Design.Colors.Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (text.isNotEmpty()) {
                IconBtn(
                    Codicons.Close,
                    contentDescription = stringResource(R.string.a11y_clear),
                    onClick = {
                        text = ""
                        onQuery("")
                        focus.requestFocus()
                    },
                    size = IconBtnSize.Composer,
                    iconSize = Design.Size.IconSmall,
                )
            }
        }
    }
}

private class NavItem(val pane: Pane, val icon: ImageVector, val label: Int, val badge: Boolean)

/**
 * `.bottomnav`: three tabs, a pill behind the icon of the one shown, a dot
 * when something happened in another — changed files, a running command.
 */
@Composable
private fun BottomNav(selected: (Pane) -> Boolean, filesBadge: Boolean, terminalBadge: Boolean, onSelect: (Pane) -> Unit) {
    val items = listOf(
        NavItem(Pane.CHAT, Codicons.CommentDiscussion, R.string.nav_agent, badge = false),
        NavItem(Pane.FILES, Codicons.Files, R.string.nav_files, badge = filesBadge),
        NavItem(Pane.TERMINAL, Codicons.Terminal, R.string.nav_terminal, badge = terminalBadge),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Design.Colors.Bg)
            .hairline(Design.Colors.Border, top = true)
            .navigationBarsPadding()
            .height(Design.Size.BottomNav)
            .selectableGroup(),
    ) {
        items.forEach { item ->
            val on = selected(item.pane)
            val color = if (on) Design.Colors.Text else Design.Colors.TextTertiary
            // The press shows on the pill, not across the whole third of the bar.
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .selectable(
                        selected = on,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                    ) { onSelect(item.pane) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier
                        .size(Design.Size.NavPillWidth, Design.Size.NavPillHeight)
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            when {
                                on -> Design.Colors.Surface3
                                pressed -> Design.Colors.Pressed
                                else -> Color.Transparent
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(Design.Size.Icon))
                    if (item.badge) {
                        // A 7 dp accent dot inside a 2 dp ring of the bar's own black.
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-11).dp, y = 2.dp)
                                .size(11.dp)
                                .background(Design.Colors.Bg, CircleShape)
                                .drawBehind { drawCircle(Design.Colors.Accent, radius = 3.5.dp.toPx()) },
                        )
                    }
                }
                Text(stringResource(item.label), style = Design.Type.Nav, color = color)
            }
        }
    }
}
