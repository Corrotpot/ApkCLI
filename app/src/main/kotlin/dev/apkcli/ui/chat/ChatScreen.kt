package dev.apkcli.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.apkcli.R
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.llm.ThinkTagSplitter
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.MessageStatus
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ToolCallStatus
import dev.apkcli.core.store.ConversationStore
import dev.apkcli.ui.EditorBus
import dev.apkcli.ui.chat.components.ChangesBar
import dev.apkcli.ui.chat.components.CheckpointRow
import dev.apkcli.ui.chat.components.CommandRow
import dev.apkcli.ui.chat.components.CommandsPlaque
import dev.apkcli.ui.chat.components.CompactionSeam
import dev.apkcli.ui.chat.components.CompactionStatus
import dev.apkcli.ui.chat.components.ComposerBox
import dev.apkcli.ui.chat.components.EmptyChat
import dev.apkcli.ui.chat.components.ErrorCard
import dev.apkcli.ui.chat.components.FindNav
import dev.apkcli.ui.chat.components.InfoLine
import dev.apkcli.ui.chat.components.SubagentArgs
import dev.apkcli.ui.chat.components.SubagentFind
import dev.apkcli.ui.chat.components.KeyNoticeCard
import dev.apkcli.ui.chat.components.PlanCard
import dev.apkcli.ui.chat.components.ThinkingBlock
import dev.apkcli.ui.chat.components.ToolCallCard
import dev.apkcli.ui.chat.components.UserBubble
import dev.apkcli.ui.chat.components.WaitingRow
import dev.apkcli.ui.chat.components.diffFindSegments
import dev.apkcli.ui.chat.components.digestFindSegments
import dev.apkcli.ui.chat.components.monoShown
import dev.apkcli.ui.chat.components.reasoningPieces
import dev.apkcli.ui.chat.components.toolTarget
import dev.apkcli.ui.chat.find.ChatFindIndex
import dev.apkcli.ui.chat.find.FindHit
import dev.apkcli.ui.chat.find.FindItem
import dev.apkcli.ui.chat.find.FindMarks
import dev.apkcli.ui.chat.find.FindReveal
import dev.apkcli.ui.chat.find.LocalFindItem
import dev.apkcli.ui.chat.find.LocalFindMarks
import dev.apkcli.ui.chat.markdown.MarkdownText
import dev.apkcli.ui.chat.markdown.markdownFindSegments
import dev.apkcli.ui.chat.sheets.ChangesSheet
import dev.apkcli.ui.chat.sheets.CheckpointSheet
import dev.apkcli.ui.chat.sheets.ContextSheet
import dev.apkcli.ui.chat.sheets.ModelSheet
import dev.apkcli.ui.chat.sheets.ReasoningSheet
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.LocalToaster
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.reasoningLevelLabel
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.text.tokensText
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The agent tab's sheets; one at a time. */
private enum class ChatSheet { MODEL, REASONING, CONTEXT, CHANGES }

/**
 * The agent tab: design/prototype/app.js agentTab().
 *
 * Layout decisions worth noting:
 *
 *  * The list is **not** reversed. A reversed list is the usual trick for chat
 *    autoscroll, but an assistant turn is a group of heterogeneous items
 *    (reasoning, prose, several tool cards) that must read top to bottom, and
 *    in a reversed list they arrive in the wrong order. Scroll is driven
 *    explicitly instead.
 *
 *  * Autoscroll stops the moment the user scrolls up. Following a long
 *    streaming answer while trying to read something above it is the most
 *    annoying behaviour a chat UI can have.
 *
 *  * Tool-result messages are not rendered. Their content is already inside
 *    the tool cards of the assistant message that made the calls.
 *
 *  * Every message of the user's has its checkpoint above it: going back to
 *    it takes the chat, the context and the agent's file changes back to
 *    before that message, and puts the message into the composer again.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf<ChatSheet?>(null) }
    var planOpen by rememberSaveable { mutableStateOf(false) }
    // The system photo picker: no storage permission, and only what the user
    // picks is readable.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImageAttachments.MAX_PER_MESSAGE),
    ) { uris -> if (uris.isNotEmpty()) viewModel.attachImages(uris) }

    // Pair each assistant turn with the results of the calls it made, so a
    // restored session shows the same success/failure state the live one did;
    // and a seam after the last message each compaction folded.
    val rendered = remember(state.messages, state.digests) { buildRenderList(state.messages, state.digests) }

    // Find in the chat: the matches in what the transcript draws, found off
    // the main thread whenever the words or the transcript change.
    val find = state.find
    val noResult = stringResource(R.string.tool_no_result)
    val clipped = stringResource(R.string.tool_output_clipped)
    val locale = LocalConfiguration.current.locales[0]
    val inFlightIds = state.inFlightTools.keys
    val found by produceState<FoundMatches?>(null, find?.query, rendered, inFlightIds, noResult, clipped, locale) {
        val query = find?.query?.trim()
        value = when {
            query == null -> null
            query.isEmpty() -> FoundMatches(query, emptyList())
            else -> withContext(Dispatchers.Default) {
                FoundMatches(
                    query,
                    ChatFindIndex.hits(rendered.mapNotNull { findItemOf(it, inFlightIds, noResult, clipped, locale) }, query),
                )
            }
        }
    }
    val hits = found?.hits.orEmpty()
    // Where the reader is, or the newest match while they have not moved.
    val currentHit = find?.current?.takeIf { it in hits } ?: hits.lastOrNull()
    SideEffect { viewModel.findHits = hits }

    // Where each message's open reasoning is scrolled, held above the items:
    // the streaming message and the stored one are different composables,
    // and the swap between them reset a reader's place to the top.
    val thinkingScrolls = remember(state.sessionId) { HashMap<String, ScrollState>() }
    val thinkingScroll: (String) -> ScrollState = remember(thinkingScrolls) {
        { id -> thinkingScrolls.getOrPut(id) { ScrollState(0) } }
    }
    val itemCount = rendered.size + (if (state.streaming != null) 1 else 0) + (if (state.compaction != null) 1 else 0)
    val currentItemCount by rememberUpdatedState(itemCount)

    // The scroll position outlives this screen: switching tabs takes the chat
    // out of composition, so the view model keeps where the user was and
    // whether they were following the bottom.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (viewModel.followBottom) (itemCount - 1).coerceAtLeast(0) else viewModel.scrollIndex,
        initialFirstVisibleItemScrollOffset = if (viewModel.followBottom) 0 else viewModel.scrollOffset,
    )
    DisposableEffect(listState) {
        onDispose { viewModel.rememberScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    // Following the bottom is decided by the user's own scrolling and by
    // nothing else: a layout change (the keyboard, a hidden pane) is not the
    // user leaving. Every finger scroll passes through here on its way up —
    // the list's own and those of the boxes inside it — while the app's own
    // scrolling does not. Toward the history: reading back, following stops.
    // Back at the end by the user's hand: following resumes.
    val pinSlackPx = with(LocalDensity.current) { PIN_SLACK.toPx() }
    val readingBack = remember(viewModel, listState, pinSlackPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) viewModel.followBottom = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if ((consumed.y < 0f || available.y < 0f) && pinnedToBottom(listState.layoutInfo, pinSlackPx) == true) {
                    viewModel.followBottom = true
                }
                return Offset.Zero
            }
        }
    }
    // The viewport changed — the keyboard, a tab switch, the split pane
    // un-hiding it: stay at the bottom if that is where the user was.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { height -> if (height > 0 && viewModel.followBottom) listState.followToBottom(currentItemCount) }
    }
    // Another session starts at its bottom; the same one, back from another
    // tab, stays where the user left it.
    LaunchedEffect(state.sessionId) {
        if (state.sessionId != null && state.sessionId != viewModel.shownSessionId) {
            viewModel.shownSessionId = state.sessionId
            viewModel.followBottom = true
            listState.followToBottom(currentItemCount)
        }
    }
    // Whatever grew — a new message, a streaming delta, a running call's
    // output — the list goes to its very end, in one step.
    val liveSize = state.inFlightTools.values.sumOf { call -> call.liveRows.size + call.liveRows.sumOf { it.text.length } }
    LaunchedEffect(
        rendered.size,
        state.streaming?.text?.length,
        state.streaming?.thinking?.length,
        state.streaming?.toolCalls?.size,
        liveSize,
        state.error != null,
        state.compaction,
    ) {
        if (viewModel.followBottom && itemCount > 0) listState.followToBottom(currentItemCount)
    }

    // A message that could not be sent, or one taken back by a checkpoint,
    // comes back to the composer, unless something new was typed meanwhile.
    LaunchedEffect(state.returnedDraft) {
        state.returnedDraft?.let { returned ->
            if (draft.isBlank()) draft = returned
            viewModel.consumeReturnedDraft()
        }
    }

    // The match to show changed: the transcript stops following its end,
    // brings the match's item on screen if it is not, and then the text
    // that holds the match shows it, some lines in from the edge (FindReveal).
    // The newest match, where a search starts, is pinned as the current
    // one, so a turn landing meanwhile does not move the reader to its own.
    // Only once the matches are those of the words in the field: not for the
    // previous words' while the next are worked out, and not again when a
    // turn lands and the matches are found anew around the same one.
    var reveal by remember { mutableStateOf<FindReveal?>(null) }
    val settledQuery = found?.query?.takeIf { it == find?.query?.trim() }
    LaunchedEffect(currentHit, settledQuery) {
        val hit = currentHit ?: return@LaunchedEffect
        if (settledQuery == null) return@LaunchedEffect
        viewModel.pinFind(hit)
        viewModel.followBottom = false
        val index = rendered.indexOfFirst { it.message.id == hit.item }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        reveal = FindReveal(hit)
    }
    val marks = remember(found, currentHit, reveal) { found?.let { FindMarks(it.query, currentHit, it.hits, reveal) } }

    Column(modifier.fillMaxSize().background(Design.Colors.Bg)) {
        if (state.starting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 22.dp) }
            return@Column
        }

        if (state.needsApiKey) {
            KeyNoticeCard(state.settings.activeEndpoint?.name.orEmpty(), onOpenSettings)
        }
        state.compactionNote?.let { InfoLine(it.resolve(), icon = Codicons.Fold) }
        if (state.tasks.isNotEmpty()) {
            PlanCard(state.tasks, expanded = planOpen, onToggle = { planOpen = !planOpen }, onDismiss = viewModel::dismissTasks)
        }

        // `[main.go](calc-go/main.go)` in a reply opens the file, resolved
        // against the session's project; a path that is not a file does nothing.
        val workspace = state.workspace
        val openPath: (String, Int?) -> Unit = remember(workspace) {
            { path, line ->
                val file = File(path).let { if (it.isAbsolute || workspace == null) it else File(workspace, path) }
                if (file.isFile) EditorBus.requestOpen(file.absolutePath, line)
            }
        }

        Box(Modifier.weight(1f)) {
            if (state.messages.isEmpty() && state.streaming == null) {
                EmptyChat(onSuggest = { draft = it })
            } else CompositionLocalProvider(LocalFindMarks provides marks) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(readingBack),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(
                        items = rendered,
                        // Stable keys: without them a streaming update
                        // re-creates every item's state and collapsed tool
                        // cards spring open.
                        key = { it.message.id },
                    ) { item ->
                        // Which item the texts below belong to, for find
                        // in the chat; the turn streaming in is not searched.
                        CompositionLocalProvider(LocalFindItem provides item.message.id) {
                            when (item.message.role) {
                                Role.USER -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CheckpointRow(
                                        restoring = state.restoringCheckpoint && state.checkpoint == null,
                                        enabled = !state.restoringCheckpoint,
                                        onRestore = { viewModel.askRestoreCheckpoint(item.message.id) },
                                    )
                                    UserBubble(item.message)
                                }
                                Role.DIGEST -> item.digest?.let { CompactionSeam(it) }
                                Role.ASSISTANT -> AssistantMessage(
                                    message = item.message,
                                    results = item.results,
                                    inFlight = state.inFlightTools,
                                    thinkingOpen = item.message.id in state.openThinking,
                                    thinkingScroll = thinkingScroll(item.message.id),
                                    openPath = openPath,
                                )
                                Role.SYSTEM, Role.TOOL -> Unit
                            }
                        }
                    }

                    state.streaming?.let { streaming ->
                        // The key the finished message will have, so the swap
                        // from streaming to stored is the same item to the
                        // list and keeps its place on screen.
                        val key = streaming.messageId.takeIf { id -> rendered.none { it.message.id == id } }
                            ?: "streaming-${streaming.messageId}"
                        item(key = key) {
                            StreamingItem(streaming, viewModel::toggleThinking, thinkingScroll(streaming.messageId), openPath)
                        }
                    }

                    // A compaction running, or one that ended changing
                    // nothing: at the end, until the next message.
                    state.compaction?.let { run ->
                        item(key = "compaction") {
                            CompactionStatus(run, onRetry = { viewModel.compact(run.focus) })
                        }
                    }
                }
            }

            // Over the bottom of the transcript rather than above the
            // composer: in a short pane an expanded error used to take the
            // composer's place, leaving the keyboard typing into a field
            // nobody could see.
            if (state.error != null || state.changesMessage != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.error?.let { ErrorCard(it, onDismiss = viewModel::dismissError, onOpenSettings = onOpenSettings) }
                    state.changesMessage?.let { InfoLine(it.resolve(), onDismiss = viewModel::dismissChangesMessage) }
                }
            }
        }

        // While searching, the matches' bar in the composer's place; the
        // words go in the top bar (WorkspaceScreen). The count is the last
        // one worked out — for a moment, while the next is, the previous
        // words' — rather than a flash of "no matches" on every key.
        if (find != null) {
            val matches = found
            Box(Modifier.imePadding()) {
                FindNav(
                    text = when {
                        matches == null || matches.query.isEmpty() -> stringResource(R.string.find_hint)
                        matches.hits.isEmpty() -> stringResource(R.string.find_none)
                        else -> stringResource(R.string.find_count, matches.hits.indexOf(currentHit) + 1, matches.hits.size)
                    },
                    canMove = hits.size > 1,
                    onPrevious = { viewModel.moveFind(-1) },
                    onNext = { viewModel.moveFind(1) },
                )
            }
        } else Column(Modifier.imePadding()) {
            // "/" at the start of the words: the commands they can still
            // become, in the changes bar's place. Why one cannot run is only
            // worked out then, not on every streamed delta.
            val typed = remember(draft) { ChatCommands.parse(draft) }
            val command = typed?.exact
            if (typed != null && typed.matches.isNotEmpty()) {
                CommandsPlaque(
                    rows = typed.matches.map { match ->
                        val blocked = blockOf(match, state)
                        CommandRow(
                            command = match,
                            exact = match == command,
                            enabled = blocked == null,
                            about = blocked?.let { commandBlockedText(it) } ?: commandAbout(match, typed, state),
                        )
                    },
                    onRun = { match ->
                        // A tap runs it, with the words typed after it when the words are that command.
                        if (viewModel.runCommand(match, if (match == command) typed.args else "")) draft = ""
                    },
                )
            } else {
                ChangesBar(
                    count = state.changes.size,
                    added = state.changesLines?.first,
                    removed = state.changesLines?.second,
                    onView = { sheet = ChatSheet.CHANGES },
                    onKeep = viewModel::acceptChanges,
                )
            }
            val settings = state.settings
            val model = settings.activeModel
            val reasoning = settings.activeEndpoint?.let { endpoint -> model?.let { settings.reasoningEffortFor(endpoint.id, it) } }
            val reasoningShown = model != null && (model.reasoningEfforts.isNotEmpty() || model.reasoning != ReasoningSupport.NONE)
            ComposerBox(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                onStop = viewModel::stop,
                busy = state.busy,
                enabled = state.canSend,
                accounting = state.context,
                modelLabel = when {
                    model != null -> model.id.substringAfterLast('/')
                    state.loadingModels -> stringResource(R.string.composer_loading_models)
                    else -> stringResource(R.string.composer_no_model)
                },
                reasoningLabel = if (reasoningShown) {
                    reasoning?.let { reasoningLevelLabel(it) } ?: stringResource(R.string.reasoning_default_chip)
                } else {
                    null
                },
                onModel = { sheet = ChatSheet.MODEL },
                onReasoning = { sheet = ChatSheet.REASONING },
                onContext = { sheet = ChatSheet.CONTEXT },
                attachments = state.attachments,
                preparingAttachments = state.preparingAttachments,
                onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveAttachment = viewModel::removeAttachment,
                maxAttachments = ImageAttachments.MAX_PER_MESSAGE,
                // Many endpoints do not say; only a model that says it takes
                // no images gets the warning, and sending stays possible.
                warning = if (state.attachments.isNotEmpty() && model != null && !model.supportsImages && model.metadataFromEndpoint) {
                    stringResource(R.string.chat_attach_model_may_not_see)
                } else {
                    null
                },
                command = command,
                commandEnabled = command != null && blockOf(command, state) == null,
            )
        }
    }

    val settings = state.settings
    val toaster = LocalToaster.current
    val keyFirst = stringResource(R.string.connection_key_first)
    when (sheet) {
        ChatSheet.MODEL -> ModelSheet(
            endpoint = settings.activeEndpoint,
            others = settings.endpoints.filter { it.enabled && it.id != settings.activeEndpoint?.id },
            hasKey = viewModel::hasKey,
            active = settings.activeModel,
            loading = state.loadingModels,
            error = state.modelsError,
            onSelect = { id ->
                viewModel.selectModel(id)
                sheet = null
            },
            onSelectEndpoint = { id ->
                // A connection with no key would only fail the next message.
                val other = settings.endpoints.firstOrNull { it.id == id }
                if (other != null && !viewModel.hasKey(other)) {
                    toaster.show(keyFirst.format(other.name), Codicons.Warning)
                } else {
                    viewModel.selectEndpoint(id)
                }
            },
            onRefresh = viewModel::refreshModels,
            onDismiss = { sheet = null },
        )
        ChatSheet.REASONING -> ReasoningSheet(
            model = settings.activeModel,
            selected = settings.activeEndpoint?.let { endpoint -> settings.activeModel?.let { settings.reasoningEffortFor(endpoint.id, it) } },
            onSelect = { level ->
                viewModel.setReasoningEffort(level)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        ChatSheet.CONTEXT -> ContextSheet(
            accounting = state.context,
            model = settings.activeModel,
            compactBlocked = state.compactBlock != null,
            onCompact = {
                sheet = null
                // With the words after /compact when the composer holds the
                // command; any other words in it stay where they are.
                val typed = ChatCommands.parse(draft)?.takeIf { it.exact == ChatCommands.Command.COMPACT }
                if (viewModel.runCommand(ChatCommands.Command.COMPACT, typed?.args.orEmpty()) && typed != null) draft = ""
            },
            onDismiss = { sheet = null },
        )
        ChatSheet.CHANGES -> ChangesSheet(
            changes = state.changes,
            workspace = state.workspace,
            busy = state.revertingChanges,
            canRevert = !state.busy,
            loadDiff = viewModel::diffFor,
            onOpen = { path ->
                sheet = null
                EditorBus.requestOpen(path)
            },
            onRevert = viewModel::revertChange,
            onRevertAll = viewModel::revertAllChanges,
            onKeepAll = {
                sheet = null
                viewModel.acceptChanges()
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    state.checkpoint?.let { prompt ->
        CheckpointSheet(
            prompt = prompt,
            busy = state.busy,
            onConfirm = viewModel::restoreCheckpoint,
            onDismiss = viewModel::dismissCheckpoint,
        )
    }
}

/**
 * An assistant turn plus the results of the calls it made; or a seam, a
 * [Role.DIGEST] item standing for [digest].
 */
private data class RenderItem(
    val message: Message,
    val results: Map<String, Block.ToolResult>,
    val digest: ConversationStore.StoredDigest? = null,
)

/** Why [command] cannot run now; null when it can. */
private fun blockOf(command: ChatCommands.Command, state: ChatViewModel.UiState): ChatViewModel.CompactBlock? = when (command) {
    ChatCommands.Command.COMPACT -> state.compactBlock
}

@Composable
private fun commandBlockedText(block: ChatViewModel.CompactBlock): String = stringResource(
    when (block) {
        ChatViewModel.CompactBlock.RUNNING -> R.string.command_blocked_running
        ChatViewModel.CompactBlock.BUSY -> R.string.command_blocked_busy
        ChatViewModel.CompactBlock.EMPTY -> R.string.command_blocked_empty
        ChatViewModel.CompactBlock.NOTHING_NEW -> R.string.command_blocked_nothing_new
        ChatViewModel.CompactBlock.SHORT -> R.string.command_blocked_short
        ChatViewModel.CompactBlock.SHORT_SINCE -> R.string.command_blocked_short_since
    },
)

/** What [command] would do, with the words typed after it. */
@Composable
private fun commandAbout(command: ChatCommands.Command, typed: ChatCommands.Typed, state: ChatViewModel.UiState): String =
    when (command) {
        ChatCommands.Command.COMPACT -> if (typed.exact == command && typed.args.isNotEmpty()) {
            stringResource(R.string.command_compact_about_focus, typed.args)
        } else {
            stringResource(R.string.command_compact_about, tokensText(state.foldableTokens))
        }
    }

/** What an assistant message shows: its reasoning and its answer. */
private class AssistantText(val reasoning: String, val answer: String)

private fun assistantText(message: Message): AssistantText {
    // Messages stored before reasoning tags were split out at the source
    // still carry "…</think>" in their text.
    val legacy = if (message.text.contains("think>", ignoreCase = true)) ThinkTagSplitter.split(message.text) else null
    val stored = message.thinking.firstOrNull()
    val reasoning = listOfNotNull(
        stored?.takeIf { !it.redacted }?.text?.takeIf { it.isNotBlank() },
        legacy?.thinking?.takeIf { it.isNotBlank() },
    ).joinToString("\n\n")
    return AssistantText(reasoning, legacy?.text ?: message.text)
}

/** The matches of [query] (trimmed), as last worked out. */
private class FoundMatches(val query: String, val hits: List<FindHit>)

/**
 * What find in the chat searches in [item], under the keys its parts are
 * drawn with: the user's words ("u"); the reasoning piece by piece
 * ("think:i", [reasoningPieces]), the answer piece by piece ("md:…",
 * [markdownFindSegments]), and for each call its target ("tool:id:t") and
 * its output as its box draws it ("tool:id:b", [monoShown]) or the lines of
 * its diff ("tool:id:d:row", [diffFindSegments]). A call still running
 * ([inFlight]) has no output yet; one that never finished shows [noResult].
 */
private fun findItemOf(item: RenderItem, inFlight: Set<String>, noResult: String, clipped: String, locale: Locale): FindItem? {
    val message = item.message
    val segments = ArrayList<Pair<String, String>>()
    when (message.role) {
        Role.USER -> if (message.text.isNotBlank()) segments += "u" to message.text
        Role.ASSISTANT -> {
            val parts = assistantText(message)
            // Piece by piece, as ThinkingBlock draws it.
            reasoningPieces(parts.reasoning).forEachIndexed { i, piece -> segments += "think:$i" to piece.text }
            if (parts.answer.isNotBlank()) segments += markdownFindSegments(parts.answer)
            message.toolUses.forEach { use ->
                val result = item.results[use.callId]
                // A subagent's card: its task, its steps, its answer.
                if (use.name == "subagent_dispatch") {
                    segments += SubagentFind.segments(use.callId, SubagentArgs.parse(use.argumentsJson), result?.subagent)
                    return@forEach
                }
                val diff = result?.diff
                (toolTarget(use.name, use.argumentsJson) ?: diff?.path)?.let { segments += "tool:${use.callId}:t" to it }
                when {
                    diff != null -> segments += diffFindSegments("tool:${use.callId}:d", diff.unified)
                    // As its output box draws it.
                    result != null -> segments += "tool:${use.callId}:b" to monoShown(result.content, clipped, locale)
                    use.callId !in inFlight -> segments += "tool:${use.callId}:b" to noResult
                }
            }
        }
        // A seam: the summary under it, as its card draws it.
        Role.DIGEST -> segments += digestFindSegments(item.digest?.digest)
        else -> return null
    }
    return FindItem(message.id, segments)
}

/**
 * Flatten the transcript for display. Tool-result messages are folded into
 * the assistant message that produced the calls: their content already
 * appears inside the tool cards.
 *
 * Each of [digests] is a seam after the last message it archived: the
 * messages above it are out of the agent's context, still on screen. A
 * message without its stored place yet (seq -1, appended live) is newer
 * than any of them.
 */
private fun buildRenderList(messages: List<Message>, digests: List<ConversationStore.StoredDigest>): List<RenderItem> {
    val items = mutableListOf<RenderItem>()
    val seams = digests.sortedBy { it.toSeq }
    var nextSeam = 0
    // The seams whose last message comes before one at [seq].
    fun seamsBefore(seq: Long) {
        while (nextSeam < seams.size && seams[nextSeam].toSeq < seq) items += seamItem(seams[nextSeam++])
    }
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        seamsBefore(if (message.seq < 0) Long.MAX_VALUE else message.seq)
        if (message.role == Role.TOOL) {
            // Orphaned results: the turn was interrupted between the two writes.
            index++
            continue
        }
        if (message.role == Role.ASSISTANT && message.toolUses.isNotEmpty()) {
            val results = mutableMapOf<String, Block.ToolResult>()
            var scan = index + 1
            while (scan < messages.size && messages[scan].role == Role.TOOL) {
                messages[scan].toolResults.forEach { results[it.callId] = it }
                scan++
            }
            items += RenderItem(message, results)
            index = scan
            continue
        }
        items += RenderItem(message, emptyMap())
        index++
    }
    // After everything: /compact folds the whole conversation.
    while (nextSeam < seams.size) items += seamItem(seams[nextSeam++])
    return items
}

/** A seam's place in the list: an item of its own, keyed apart from every message. */
private fun seamItem(digest: ConversationStore.StoredDigest) = RenderItem(
    message = Message(
        id = "digest-${digest.id}",
        sessionId = "",
        seq = digest.toSeq,
        role = Role.DIGEST,
        createdAt = digest.createdAt,
    ),
    results = emptyMap(),
    digest = digest,
)

/**
 * Whether the end of the last item is on screen, give or take [slackPx], or
 * null when this layout says nothing about it: nothing laid out yet, or a
 * pane hidden at zero size.
 */
private fun pinnedToBottom(layout: LazyListLayoutInfo, slackPx: Float): Boolean? {
    if (layout.viewportSize.height <= 0 || layout.totalItemsCount == 0) return null
    val last = layout.visibleItemsInfo.lastOrNull() ?: return null
    if (last.index < layout.totalItemsCount - 1) return false
    val end = layout.viewportEndOffset - layout.afterContentPadding
    return last.offset + last.size <= end + slackPx
}

/** How far above the end still counts as following it. */
private val PIN_SLACK = 32.dp

/**
 * To the very end, including the bottom of a last item taller than the
 * screen. Gives way to the user: a scroll they start meanwhile wins.
 */
private suspend fun LazyListState.followToBottom(itemCount: Int) {
    if (itemCount <= 0) return
    try {
        if (layoutInfo.visibleItemsInfo.none { it.index == itemCount - 1 }) scrollToItem(itemCount - 1)
        scrollBy(BOTTOM_OVERSCROLL_PX)
    } catch (interrupted: kotlinx.coroutines.CancellationException) {
        if (!currentCoroutineContext().isActive) throw interrupted
    }
}

/** More than any one message is tall; the list stops at its end. */
private const val BOTTOM_OVERSCROLL_PX = 1_000_000f

/** `.a`: reasoning, prose and tool cards, 10 dp apart. */
@Composable
private fun AssistantMessage(
    message: Message,
    results: Map<String, Block.ToolResult>,
    inFlight: Map<String, ChatViewModel.LiveToolCall>,
    thinkingOpen: Boolean,
    thinkingScroll: ScrollState,
    openPath: (String, Int?) -> Unit,
) {
    // Open if it was open while streaming: closing it as the message lands
    // would shrink the item under the reader.
    var thinkingExpanded by remember(message.id) { mutableStateOf(thinkingOpen) }
    // And open while a search has a match in it.
    val thinkingFound = LocalFindMarks.current?.opens(LocalFindItem.current, "think") == true
    val noResult = stringResource(R.string.tool_no_result)

    val parts = remember(message.id, message.text, message.thinking) { assistantText(message) }
    val stored = message.thinking.firstOrNull()
    val reasoning = parts.reasoning
    val answer = parts.answer

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (reasoning.isNotBlank()) {
            ThinkingBlock(
                text = reasoning,
                expanded = thinkingExpanded || thinkingFound,
                inProgress = false,
                tokens = stored?.tokens ?: 0,
                onToggle = { thinkingExpanded = !thinkingExpanded },
                scroll = thinkingScroll,
            )
        }
        if (answer.isNotBlank()) MarkdownText(text = answer, onOpenPath = openPath)

        message.toolUses.forEach { use ->
            val result = results[use.callId]
            // Tools run after their message is stored: until the results
            // message lands, the live state says whether a call still runs.
            val live = if (result == null) inFlight[use.callId] else null
            ToolCallCard(
                call = live ?: ChatViewModel.LiveToolCall(
                    callId = use.callId,
                    name = use.name,
                    status = when {
                        // No result recorded: the turn was interrupted before
                        // the tool finished. Showing this as OK would be a lie.
                        result == null -> ToolCallStatus.ABORTED
                        result.isError -> ToolCallStatus.ERROR
                        else -> ToolCallStatus.OK
                    },
                    resultPreview = result?.content ?: noResult,
                    // The stored diff, so a reopened session shows the change
                    // itself and not the one-line summary the model received;
                    // and a subagent's run, so its card is the one watched.
                    display = result?.diff?.let { stored ->
                        ToolDisplay.Diff(
                            path = stored.path,
                            unified = stored.unified,
                            addedLines = stored.added,
                            removedLines = stored.removed,
                            snapshotId = stored.snapshotId,
                        )
                    } ?: result?.subagent?.let { ToolDisplay.Subagent(it) },
                    durationMs = result?.durationMs,
                    isError = result?.isError == true,
                    argumentsJson = use.argumentsJson,
                    diffTruncated = result?.diff?.truncated == true,
                ),
                // Failed calls open expanded: the error is the point.
                initiallyExpanded = (result?.isError ?: live?.isError) == true,
            )
        }

        EndNotes(message)
    }
}

/**
 * How a reply ended, when it did not simply finish: the request failed and
 * why, the output limit or a filter cut it, the user stopped it. Read from
 * the message (its Block.Notice and its status), so a reopened chat says
 * what the live one did; the failure's reason used to be lost on reopening.
 */
@Composable
private fun EndNotes(message: Message) {
    val notices = message.notices
    val failure = message.errorMessage ?: notices.firstOrNull { it.kind == Block.Notice.FAILED }?.text
    if (message.status == MessageStatus.ERROR && failure != null) {
        EndNote(Codicons.Error, failure, tint = Design.Colors.Error, color = Design.Colors.Error)
    }
    notices.forEach { notice ->
        when (notice.kind) {
            Block.Notice.OUTPUT_LIMIT -> EndNote(
                Codicons.Warning,
                if (notice.limit > 0) {
                    stringResource(R.string.chat_notice_output_limit, tokensText(notice.limit))
                } else {
                    stringResource(R.string.chat_notice_output_limit_unknown)
                },
                tint = Design.Colors.Warning,
            )
            Block.Notice.FILTERED -> EndNote(Codicons.Warning, stringResource(R.string.chat_notice_filtered), tint = Design.Colors.Warning)
        }
    }
    if (message.status == MessageStatus.ABORTED) {
        EndNote(Codicons.DebugStop, stringResource(R.string.chat_notice_stopped), tint = Design.Colors.TextTertiary, color = Design.Colors.TextTertiary)
    }
}

@Composable
private fun EndNote(icon: ImageVector, text: String, tint: Color, color: Color = Design.Colors.TextSecondary) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(Design.Size.IconTiny))
        Text(text, style = Design.Type.BodySmall, color = color)
    }
}

/** The turn in flight: streamed reasoning, the answer so far, the calls running. */
@Composable
private fun StreamingItem(
    streaming: ChatViewModel.Streaming,
    onToggleThinking: () -> Unit,
    thinkingScroll: ScrollState,
    openPath: (String, Int?) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (streaming.thinking.isNotBlank()) {
            ThinkingBlock(
                text = streaming.thinking,
                expanded = streaming.thinkingExpanded,
                // Done once the model writes anything else: the answer, or a
                // call, which can take a minute that is not thinking.
                inProgress = streaming.text.isEmpty() && streaming.toolCalls.isEmpty(),
                onToggle = onToggleThinking,
                startedAt = streaming.startedAt,
                scroll = thinkingScroll,
            )
        }
        if (streaming.text.isNotBlank()) MarkdownText(text = streaming.text, onOpenPath = openPath, streaming = true)
        streaming.toolCalls.forEach { call -> ToolCallCard(call = call) }

        // Nothing yet: show that something is happening rather than an empty
        // block. The first token of a reasoning model can be 30 seconds away.
        if (streaming.text.isBlank() && streaming.thinking.isBlank() && streaming.toolCalls.isEmpty()) {
            WaitingRow(stringResource(R.string.chat_waiting))
        }
    }
}
