package dev.apkcli.ui.root

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.apkcli.R
import dev.apkcli.di.ServiceLocator
import dev.apkcli.ui.chat.ChatScreen
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.chat.sheets.AgentSheet
import dev.apkcli.ui.chat.sheets.ApprovalSheet
import dev.apkcli.ui.chat.sheets.HistorySheets
import dev.apkcli.ui.chat.sheets.MemorySheets
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.ProvideToaster
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.components.TileTone
import dev.apkcli.ui.components.ToastHost
import dev.apkcli.ui.components.Toaster
import dev.apkcli.ui.editor.EditorViewModel
import dev.apkcli.ui.files.ExplorerViewModel
import dev.apkcli.ui.files.FilesTab
import dev.apkcli.ui.home.HomeScreen
import dev.apkcli.ui.home.ProjectActions
import dev.apkcli.ui.home.ProjectsScreen
import dev.apkcli.ui.home.ProjectsViewModel
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.nav.Destination
import dev.apkcli.ui.nav.rememberNavigator
import dev.apkcli.ui.settings.SettingsScreen
import dev.apkcli.ui.terminal.TerminalTab
import dev.apkcli.ui.terminal.TerminalViewModel
import dev.apkcli.core.model.ApprovalRule
import dev.apkcli.core.store.BuiltInAgents
import dev.apkcli.ui.text.displayName
import dev.apkcli.ui.text.inSentence
import dev.apkcli.ui.theme.Design
import dev.apkcli.ui.workbench.Pane
import dev.apkcli.ui.workbench.WorkbenchViewModel
import dev.apkcli.ui.workspace.WorkspaceScreen
import java.io.File

/**
 * The app: the main menu, the project list, a project's workspace, settings.
 *
 * Every ViewModel is hoisted to this level rather than created inside a
 * screen: a running turn and unsaved editor buffers must survive going to
 * Settings and coming back, and the main menu is where the user goes
 * between projects.
 */
@Composable
fun RootScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val navigator = rememberNavigator(Destination.Home)
    val runtime = ServiceLocator.runtime(context)

    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(runtime, context))
    val workbenchViewModel: WorkbenchViewModel = viewModel(factory = WorkbenchViewModel.factory(context))
    val editorViewModel: EditorViewModel = viewModel(factory = EditorViewModel.factory(runtime.layout))
    val explorerViewModel: ExplorerViewModel = viewModel(factory = ExplorerViewModel.factory(runtime.layout))
    val terminalViewModel: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(runtime))
    val projectsViewModel: ProjectsViewModel = viewModel(factory = ProjectsViewModel.factory(runtime, context))

    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val layout by workbenchViewModel.layout.collectAsStateWithLifecycle()
    val terminalState by terminalViewModel.state.collectAsStateWithLifecycle()
    val editorState by editorViewModel.state.collectAsStateWithLifecycle()
    val projects by projectsViewModel.state.collectAsStateWithLifecycle()

    val toaster = remember { Toaster() }
    var agentSheet by rememberSaveable { mutableStateOf(false) }
    // Settings opened from the mode sheet's "Rules": it scrolls to them.
    var showRules by rememberSaveable { mutableStateOf(false) }

    // The explorer shows the project the agent works in.
    LaunchedEffect(chatState.workspace) { explorerViewModel.setRoot(chatState.workspace) }

    // The lists are re-read whenever they come on screen: a project created,
    // cloned or worked on elsewhere shows up, and in the right order.
    val current = navigator.current
    LaunchedEffect(current) {
        if (current == Destination.Home || current == Destination.Projects) projectsViewModel.refresh()
    }

    // A workspace with nothing open — the project was deleted, or the
    // process came back without it — is the main menu.
    LaunchedEffect(current, chatState.starting, chatState.workspace) {
        if (current == Destination.Workspace && !chatState.starting && chatState.workspace == null) {
            navigator.replaceAll(Destination.Home)
        }
    }

    // Something opened a file on the user's behalf: put it on screen, in
    // the editor rather than the tree.
    LaunchedEffect(editorViewModel) {
        editorViewModel.reveals.collect { path ->
            if (chatState.workspace != null) navigator.navigate(Destination.Workspace)
            workbenchViewModel.update { it.copy(focus = Pane.FILES, side = Pane.FILES) }
            explorerViewModel.reveal(path)
            explorerViewModel.showTree(false)
        }
    }

    BackHandler(enabled = navigator.canGoBack) { navigator.pop() }

    val busyText = stringResource(R.string.projects_busy)
    val actions = remember(chatViewModel, navigator, toaster, busyText) {
        ProjectActions(
            open = { dir: File ->
                if (!chatViewModel.openProject(dir) { navigator.navigate(Destination.Workspace) }) {
                    toaster.show(busyText, Codicons.Warning)
                }
            },
            create = { name -> chatViewModel.createProject(name) { navigator.navigate(Destination.Workspace) } },
            clone = chatViewModel::importFromGitHub,
            importFolder = chatViewModel::importFromDevice,
            cancelImport = chatViewModel::cancelImport,
            dismissImport = {
                chatViewModel.dismissImport()
                projectsViewModel.refresh()
            },
            openSettings = { navigator.navigate(Destination.Settings) },
        )
    }

    chatState.startupProblem?.let { problem ->
        StartupProblem(problem)
        return
    }

    ProvideToaster(toaster) {
        Box(modifier.fillMaxSize().background(Design.Colors.Bg)) {
            when (current) {
                Destination.Home -> HomeScreen(
                    projects = projects,
                    needsKey = chatState.needsApiKey || chatState.settings.activeEndpoint == null,
                    importing = chatState.importing,
                    actions = actions,
                    onShowAll = { navigator.navigate(Destination.Projects) },
                )

                Destination.Projects -> ProjectsScreen(
                    projects = projects,
                    viewModel = projectsViewModel,
                    importing = chatState.importing,
                    actions = actions,
                    onBack = { navigator.pop() },
                    // Not under a running turn, and not with unsaved edits:
                    // both would end up writing into a folder that moved.
                    busyProject = chatState.workspace?.takeIf { chatState.busy || editorState.tabs.any { it.dirty } },
                    onProjectDeleted = { dir ->
                        editorViewModel.closeAll(dir)
                        chatViewModel.forgetWorkspace(dir)
                    },
                    onProjectRenamed = { from, to ->
                        editorViewModel.closeAll(from)
                        if (chatState.workspace?.absolutePath == from.absolutePath) chatViewModel.switchWorkspace(to)
                    },
                )

                Destination.Workspace -> {
                    val agent = chatState.activeAgent
                    WorkspaceScreen(
                        projectName = chatState.workspace?.name.orEmpty(),
                        // The mode alone, which the top bar never cuts.
                        agentLine = agent?.displayName().orEmpty(),
                        layout = layout,
                        onUpdateLayout = workbenchViewModel::update,
                        onResetLayout = workbenchViewModel::reset,
                        onBack = { navigator.navigate(Destination.Home) },
                        onAgentSheet = { agentSheet = true },
                        onMemory = chatViewModel::openMemory,
                        onFind = chatViewModel::startFind,
                        findEnabled = chatState.messages.isNotEmpty(),
                        findQuery = chatState.find?.query,
                        onFindQuery = chatViewModel::setFindQuery,
                        onFindSearch = { chatViewModel.moveFind(-1) },
                        onFindClose = chatViewModel::endFind,
                        onHistory = chatViewModel::openHistory,
                        // A chat with nothing in it yet is already new:
                        // "+" leaves it open (ChatViewModel.newSession).
                        onNewChat = chatViewModel::newSession,
                        newChatEnabled = !chatState.busy,
                        onSettings = { navigator.navigate(Destination.Settings) },
                        filesBadge = chatState.changes.isNotEmpty(),
                        terminalBadge = terminalState.anyBusy,
                        agent = {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onOpenSettings = { navigator.navigate(Destination.Settings) },
                            )
                        },
                        files = {
                            FilesTab(
                                explorer = explorerViewModel,
                                editor = editorViewModel,
                                changes = chatState.changes,
                            )
                        },
                        terminal = {
                            TerminalTab(
                                viewModel = terminalViewModel,
                                workspace = chatState.workspace,
                            )
                        },
                    )
                }

                Destination.Settings -> SettingsScreen(
                    onBack = { navigator.pop() },
                    layout = layout,
                    onUpdateLayout = workbenchViewModel::update,
                    showRules = showRules,
                    onRulesShown = { showRules = false },
                )
            }

            ToastHost(toaster, bottom = if (current == Destination.Workspace) 88.dp else 32.dp)
        }

        if (agentSheet) {
            AgentSheet(
                agents = chatState.settings.agents,
                activeId = chatState.activeAgent?.id,
                rules = chatState.settings.approvalRules,
                onSelect = { id ->
                    chatViewModel.selectAgent(id)
                    agentSheet = false
                },
                onRules = {
                    agentSheet = false
                    showRules = true
                    navigator.navigate(Destination.Settings)
                },
                onDismiss = { agentSheet = false },
            )
        }

        MemorySheets(chatViewModel)

        HistorySheets(
            viewModel = chatViewModel,
            projectName = chatState.workspace?.name.orEmpty(),
            openId = chatState.sessionId,
            busy = chatState.busy,
            openEmpty = chatState.messages.isEmpty() && chatState.streaming == null,
        )

        // Hosted here, not in the agent tab: an approval suspends the agent
        // until it is answered, and the tab leaves composition when the
        // user goes to Settings — which is just what they do when the call
        // turns out to need a different model or a missing key.
        chatState.approval?.let { approval ->
            val agent = chatState.activeAgent
            // Why it asks, and the way to stop it asking, both as the mode
            // says: From Rules names the rule and ticks it from here; Confirm
            // offers Bypass Mode.
            // The rule the call falls under, when the mode is From Rules.
            val rule = ApprovalRule.of(approval.toolName)?.takeIf { agent?.id == BuiltInAgents.RULES }
            val confirm = agent?.id == BuiltInAgents.CONFIRM
            ApprovalSheet(
                approval = approval,
                projectName = chatState.workspace?.name.orEmpty(),
                why = when {
                    rule != null -> stringResource(R.string.approval_body_rules, rule.inSentence())
                    confirm -> stringResource(R.string.approval_body_confirm)
                    else -> stringResource(R.string.approval_sheet_body, agent?.displayName().orEmpty())
                },
                onResolve = { allow, always -> chatViewModel.resolveApproval(allow, always) },
                alternative = when {
                    rule != null -> stringResource(R.string.approval_allow_rule, rule.inSentence()) to {
                        chatViewModel.allowAndStopAsking(rule)
                    }
                    confirm -> stringResource(R.string.approval_switch_bypass) to {
                        chatViewModel.selectAgent(BuiltInAgents.BYPASS)
                        chatViewModel.resolveApproval(true, false)
                    }
                    else -> null
                },
            )
        }
    }
}

/**
 * The native core or the store did not come up: nothing else can work, so
 * this is the whole screen — what happened, and the report a bug report
 * needs, selectable for copying.
 */
@Composable
private fun StartupProblem(report: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Design.Colors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        SheetHead(
            title = stringResource(R.string.startup_failed_title),
            subtitle = stringResource(R.string.startup_failed_body),
            icon = Codicons.Error,
            tone = TileTone.Error,
        )
        Card(Modifier.padding(top = 20.dp)) {
            SelectionContainer {
                Text(
                    report,
                    style = Design.Type.MonoCaption,
                    color = Design.Colors.TextSecondary,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}
