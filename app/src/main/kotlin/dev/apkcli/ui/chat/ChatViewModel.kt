package dev.apkcli.ui.chat

import android.content.Context
import android.os.LocaleList
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.apkcli.ApkCliApplication
import dev.apkcli.R
import dev.apkcli.core.AgentRuntime
import dev.apkcli.core.agent.AgentEvent
import dev.apkcli.core.agent.ApprovalGate
import dev.apkcli.core.agent.Compactor
import dev.apkcli.core.agent.ContextBudget
import dev.apkcli.core.agent.MemoryScopes
import dev.apkcli.core.agent.ProjectInstructions
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.agent.UiBridge
import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.model.ApprovalRule
import dev.apkcli.core.model.AuthScheme
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.MessageStatus
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.SubagentRun
import dev.apkcli.core.model.ToolCallStatus
import dev.apkcli.core.projects.ProjectImport
import dev.apkcli.core.runtime.TerminalBuffer
import dev.apkcli.core.service.AgentService
import dev.apkcli.core.store.ChangeLedger
import dev.apkcli.core.store.ConversationStore
import dev.apkcli.ui.EditorBus
import dev.apkcli.ui.chat.components.partialStringValue
import dev.apkcli.ui.chat.components.toolTargetPartial
import dev.apkcli.ui.chat.find.FindHit
import dev.apkcli.ui.locale.AppLocales
import dev.apkcli.ui.text.UiText
import dev.apkcli.ui.text.explanationText
import dev.apkcli.ui.text.headlineText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Chat screen state and the agent's two hooks into the UI.
 *
 * Implements [UiBridge] and [ApprovalGate] directly rather than delegating,
 * because both are inherently about this screen: an approval is a dialog here,
 * and the task list is drawn here.
 *
 * The streaming design is the part worth reading. Deltas do **not** mutate the
 * message list — a 4000-token answer would otherwise rebuild an immutable
 * `Message` 4000 times and recompose the list item each time. Instead the
 * in-flight message lives in [UiState.streaming] as plain strings, and the
 * finished message is appended to the list once. Updates are also coalesced on
 * a short interval, so a fast endpoint cannot drive recomposition at token rate.
 */
class ChatViewModel(
    private val runtime: AgentRuntime,
    private val appContext: Context,
) : ViewModel(), UiBridge, ApprovalGate {

    /** The message currently being produced. */
    data class Streaming(
        val messageId: String,
        val text: String = "",
        val thinking: String = "",
        val thinkingExpanded: Boolean = false,
        /** The user opened or closed the reasoning: from then on it stays as they left it. */
        val thinkingToggled: Boolean = false,
        val toolCalls: List<LiveToolCall> = emptyList(),
        val iteration: Int = 1,
        /** When the message started, for "Thinking · 12 s". */
        val startedAt: Long = System.currentTimeMillis(),
    )

    data class LiveToolCall(
        val callId: String,
        val name: String,
        val status: ToolCallStatus,
        val resultPreview: String = "",
        val display: ToolDisplay? = null,
        val durationMs: Long? = null,
        val isError: Boolean = false,
        /** The call's arguments, which name what it acted on. */
        val argumentsJson: String = "{}",
        /** The stored diff was shortened; see `AgentLoop.diffSummary`. */
        val diffTruncated: Boolean = false,
        /** The last lines its terminal shows while it runs, with their colours; empty when there are none. */
        val liveRows: List<TerminalBuffer.Row> = emptyList(),
        /** When the call started running (wall clock), for the timer on its card; null while it waits. */
        val runningSince: Long? = null,
        /** The model is still writing the call's arguments. */
        val preparing: Boolean = false,
        /** When the model began writing the call (wall clock), for the clock on its card while [preparing]. */
        val writingSince: Long? = null,
        /** How much of the arguments has arrived while [preparing]. */
        val argsChars: Int = 0,
        /** The path or command, read from the arguments before they are complete. */
        val partialTarget: String? = null,
        /** A subagent's task as far as the model has written it, while [preparing]. */
        val partialPrompt: String? = null,
        /** A subagent this call dispatched, as its run stands while it runs; the result's own once it ends. */
        val subagent: SubagentRun? = null,
    )

    /**
     * Find in the chat. [current] is the match the user is at, by where it
     * is, not by its number, so a message landing meanwhile does not move
     * them; null is the newest match, where a search starts.
     */
    data class Find(val query: String = "", val current: FindHit? = null)

    /** One of the project's chats in the history sheet. */
    data class ChatSummary(
        val id: String,
        /** Its first request, as AgentLoop names it, or what the user renamed it to; blank for a new chat. */
        val title: String,
        /** When it was last used. */
        val updatedAt: Long,
        /** The last request in it. */
        val lastRequest: String,
        /** Nothing has been sent in it yet. */
        val empty: Boolean,
    )

    /** An import in progress, one that failed, or one that is done and waiting to be opened. */
    data class ProjectImportState(
        val source: String,
        val progress: ProjectImport.Progress? = null,
        val failure: UiText? = null,
        /** The new project, once it is in place. */
        val result: File? = null,
        /** A GitHub clone rather than a copy of a folder on the device. */
        val fromGitHub: Boolean = true,
    ) {
        val running: Boolean get() = failure == null && result == null
    }

    /** An image waiting in the composer to go with the next message. */
    data class Attachment(
        val id: String,
        val image: Block.Image,
        val thumbnail: androidx.compose.ui.graphics.ImageBitmap,
    )

    /** A checkpoint the user asked to restore, waiting for them to confirm. */
    data class CheckpointPrompt(
        /** The user message the checkpoint sits above. */
        val messageId: String,
        /** Messages that would go: the checkpoint's own and every later one. */
        val messages: Int,
        /** Files that would be put back; null while they are being counted. */
        val files: Int? = null,
        /** A compaction since that message is undone with it: its turns count in the context again. */
        val undoesCompaction: Boolean = false,
    )

    /**
     * A compaction while it runs, or how one ended that changed nothing:
     * failed, stopped, or skipped as not worth it. Not stored: it is gone
     * with the next message, and a done one is a seam in the transcript,
     * drawn from its digest.
     */
    data class CompactionRun(
        val state: State,
        /** /compact, or the app's own as the window filled. */
        val manual: Boolean,
        val startedAt: Long = System.currentTimeMillis(),
        /** The words after /compact. */
        val focus: String? = null,
        val failure: AgentEvent.CompactionFailed? = null,
        val skipped: AgentEvent.CompactionSkipped.Why? = null,
    ) {
        enum class State { RUNNING, FAILED, STOPPED, SKIPPED }
    }

    /** Why /compact cannot run now. */
    enum class CompactBlock {
        /** One is running. */
        RUNNING,

        /** The agent is at work. */
        BUSY,

        /** Nothing has been said in the chat yet. */
        EMPTY,

        /** Nothing has been said since the last summary. */
        NOTHING_NEW,

        /** Too little to be worth a summary: no summary yet, or since the last. */
        SHORT,
        SHORT_SINCE,
    }

    data class PendingApproval(
        val callId: String,
        val toolName: String,
        val summary: String,
        /** The call's arguments, whole. */
        val detail: String,
        val destructive: Boolean,
        /** A subagent's name when it is the one asking; null for the chat's own agent. */
        val requester: String? = null,
    )

    data class UiError(
        val headline: UiText,
        val detail: String,
        val retryable: Boolean,
        /** Set when the endpoint revealed the model's real context window. */
        val discoveredContextLimit: Int? = null,
        /** Set when the turn stopped at the iteration cap: how many rounds it made. */
        val iterationLimit: Int? = null,
        /** Shown as the banner's text instead of "see the details", for errors this app explains itself. */
        val body: UiText? = null,
        /** The fix is in Settings: offer a button there. */
        val opensSettings: Boolean = false,
    )

    data class UiState(
        val starting: Boolean = true,
        val startupProblem: String? = null,
        val sessionId: String? = null,
        val workspace: File? = null,
        val workspaceLabel: String = "",
        val messages: List<Message> = emptyList(),
        val streaming: Streaming? = null,
        val busy: Boolean = false,
        /**
         * Calls of a completed assistant message whose result message has not
         * arrived: running, or finished while their neighbours still run. The
         * transcript shows these instead of "interrupted".
         */
        val inFlightTools: Map<String, LiveToolCall> = emptyMap(),
        val context: ContextBudget.Accounting? = null,
        val tasks: List<UiBridge.Task> = emptyList(),
        val settings: AppSettings = AppSettings(),
        val activeAgent: AgentDefinition? = null,
        val approval: PendingApproval? = null,
        val error: UiError? = null,
        val compactionNote: UiText? = null,
        /** Files the agent changed in this session since the user last accepted. */
        val changes: List<ChangeLedger.FileChange> = emptyList(),
        /** Lines added and removed across [changes], once counted; null until then. */
        val changesLines: Pair<Int, Int>? = null,
        val revertingChanges: Boolean = false,
        val changesMessage: UiText? = null,
        /**
         * In the constructor, not a body property: a body `var` is excluded
         * from `copy()`, so every state update would silently reset it and the
         * "add an API key" banner would flicker back on.
         */
        val hasApiKey: Boolean = false,
        /** The active endpoint's model list is being fetched. */
        val loadingModels: Boolean = false,
        /** Why the last fetch produced no models, for the model picker. */
        val modelsError: UiText? = null,
        /**
         * A message that could not be sent, handed back to the composer so it
         * is not lost: the composer clears itself on send.
         */
        val returnedDraft: String? = null,
        /** Messages whose reasoning was open when they finished streaming; they land open. */
        val openThinking: Set<String> = emptySet(),
        /** Images to send with the next message. */
        val attachments: List<Attachment> = emptyList(),
        /** Images being read and scaled; the composer shows a placeholder for each. */
        val preparingAttachments: Int = 0,
        /** A project being brought in, or why the last one could not be. */
        val importing: ProjectImportState? = null,
        /** A checkpoint the user tapped "Restore" on, until they confirm or cancel. */
        val checkpoint: CheckpointPrompt? = null,
        /** A checkpoint is being restored: files put back, then the history rewound. */
        val restoringCheckpoint: Boolean = false,
        /** Find in the chat, while it is on: the words, and which match is the current one. */
        val find: Find? = null,
        /**
         * The chat's compactions, oldest first: a seam in the transcript
         * after the last message each one archived. [messages] holds the
         * archived messages too, above their seams.
         */
        val digests: List<ConversationStore.StoredDigest> = emptyList(),
        /** A compaction running now, or one that ended changing nothing; see [CompactionRun]. */
        val compaction: CompactionRun? = null,
    ) {
        /** Nothing will work until a credential is entered. */
        val needsApiKey: Boolean
            get() = settings.activeEndpoint?.let {
                it.authScheme != AuthScheme.NONE
            } == true && !hasApiKey

        val canSend: Boolean get() = !busy && !starting && sessionId != null

        /** What the next /compact folds: every message not archived yet, pinned ones aside. */
        val foldable: List<Message>
            get() = messages.filter { !it.archived && !it.pinned }

        /**
         * What [foldable] costs, roughly: the stored count, or for a message
         * this screen appended and the store has not counted back to it, a
         * character estimate. What decides is the runtime's own count.
         */
        val foldableTokens: Int
            get() = foldable.sumOf { if (it.tokens > 0) it.tokens else it.promptText().length / 4 }

        /** Why /compact cannot run now; null when it can. */
        val compactBlock: CompactBlock?
            get() = when {
                compaction?.state == CompactionRun.State.RUNNING -> CompactBlock.RUNNING
                busy || starting || restoringCheckpoint || sessionId == null -> CompactBlock.BUSY
                messages.none { it.role == Role.USER } -> CompactBlock.EMPTY
                foldable.none { it.role == Role.USER } -> CompactBlock.NOTHING_NEW
                foldableTokens < Compactor.MIN_MANUAL_TOKENS ->
                    if (digests.isEmpty()) CompactBlock.SHORT else CompactBlock.SHORT_SINCE
                else -> null
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var turnJob: Job? = null

    /**
     * Resolved when the user answers an approval dialog.
     *
     * Volatile because the two sides are on different threads: the agent sets
     * it from the turn's producer (off the main thread, see
     * AgentRuntime.runTurn), and the dialog resolves it from the main thread.
     */
    @Volatile
    private var approvalAnswer: CompletableDeferred<Boolean>? = null

    /*
     * Where the transcript is scrolled, kept here because the screen does not
     * last: switching tabs takes it out of composition, and a scroll position
     * remembered there came back at the top of the history. Plain fields,
     * written and read on the main thread by the screen's effects.
     */

    /** The user is following the newest message rather than reading back. */
    var followBottom: Boolean = true

    var scrollIndex: Int = 0
        private set

    var scrollOffset: Int = 0
        private set

    fun rememberScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }

    /**
     * The session the screen last showed. Opening another one starts at its
     * bottom; coming back to the same one (from another tab) keeps the place.
     */
    var shownSessionId: String? = null

    // Declared before `init`: the coroutines it starts may read these, and
    // properties are initialised in declaration order.
    private val uiPrefs = appContext.getSharedPreferences("apkcli_ui", Context.MODE_PRIVATE)

    private val _history = MutableStateFlow<List<ChatSummary>?>(null)

    /** The project's chats, newest first, while the history sheet is open; null while it is closed. */
    val history: StateFlow<List<ChatSummary>?> = _history.asStateFlow()

    /** `endpointId|baseUrl` pairs already fetched automatically by this ViewModel. */
    private val autoFetched = mutableSetOf<String>()

    init {
        viewModelScope.launch { bootstrap() }
        observeSettings()
        observeNotificationStop()
    }

    /* ----------------------------------------------------------- lifecycle */

    private suspend fun bootstrap() {
        // Wait for the runtime's background startup rather than racing it:
        // opening a session before the store is up surfaces a confusing native
        // error instead of a clear startup problem.
        val application = appContext.applicationContext as? ApkCliApplication
        var waited = 0L
        while (application?.startupReport == null && waited < STARTUP_TIMEOUT_MS) {
            delay(STARTUP_POLL_MS)
            waited += STARTUP_POLL_MS
        }

        val report = application?.startupReport
        if (report != null && !report.usable) {
            _state.update { it.copy(starting = false, startupProblem = report.describe()) }
            return
        }
        if (report == null) {
            // Diagnostic text for a bug report: kept in English on purpose.
            _state.update {
                it.copy(
                    starting = false,
                    startupProblem = "Startup did not finish within " +
                        "${STARTUP_TIMEOUT_MS / 1000}s. The native core may be missing — " +
                        "check that the build included the Rust step.",
                )
            }
            return
        }

        val settings = runtime.settingsRepository.current()

        // Resume the most recent session whose project still exists rather
        // than starting fresh: reopening the app mid-task and finding an
        // empty chat is the most annoying thing an agent app can do. With
        // none, nothing is open: the main menu is where a project is chosen.
        val session = runtime.store.listSessions(limit = 20)
            .firstOrNull { it.workspace.isNotBlank() && File(it.workspace).isDirectory }
        val sessionWorkspace = session?.workspace?.let(::File)
        val chat = session?.let { loadChat(it.id) }

        _state.update {
            it.copy(
                starting = false,
                sessionId = session?.id,
                workspace = sessionWorkspace,
                workspaceLabel = sessionWorkspace?.let(runtime.layout::shorten).orEmpty(),
                messages = chat?.messages.orEmpty(),
                digests = chat?.digests.orEmpty(),
                settings = settings,
                activeAgent = settings.activeAgent,
                hasApiKey = settings.activeEndpointId
                    ?.let { id -> runtime.secrets.hasApiKey(id) } == true,
            )
        }
        if (session != null) refreshChanges()
    }

    /** A chat as the transcript shows it: its messages, the compacted ones too, and its compactions. */
    private class LoadedChat(val messages: List<Message>, val digests: List<ConversationStore.StoredDigest>)

    /**
     * Every message, archived ones included: a compaction takes turns out
     * of the agent's context, not off the screen. They used to disappear
     * from the chat once compacted, as if deleted.
     */
    private suspend fun loadChat(sessionId: String): LoadedChat = LoadedChat(
        messages = runtime.store.window(sessionId, includeArchived = true),
        // Without its seams the chat still reads; a stop is not a failure.
        digests = try {
            runtime.store.digests(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "could not list the digests of $sessionId", t)
            emptyList()
        },
    )

    /** A chat as a compaction left it, and what its conversation costs now; see [withCompacted]. */
    private class Compacted(val chat: LoadedChat, val liveTokens: Int?)

    private suspend fun reloadCompacted(sessionId: String): Compacted? = try {
        val chat = loadChat(sessionId)
        Compacted(chat, runtime.store.stats(sessionId).liveTokens.toInt())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Log.w(TAG, "could not reload $sessionId after a compaction", t)
        null
    }

    /** The compaction is done: its seam in the transcript, and the ring at once rather than at the next request. */
    private fun UiState.withCompacted(compacted: Compacted): UiState = copy(
        messages = compacted.chat.messages,
        digests = compacted.chat.digests,
        compaction = null,
        compactionNote = null,
        context = context?.let { accounting ->
            accounting.copy(
                liveMessageTokens = compacted.liveTokens ?: accounting.liveMessageTokens,
                digestTokens = compacted.chat.digests.lastOrNull()?.tokens ?: accounting.digestTokens,
            )
        },
    )

    /**
     * First run has no project. Create one rather than presenting an empty
     * state with no obvious next action.
     */
    private fun defaultWorkspace(): File {
        val projects = runtime.layout.projects
        projects.mkdirs()
        val existing = projects.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
        existing?.firstOrNull()?.let { return it }
        return File(projects, "scratch").apply { mkdirs() }
    }

    /**
     * Settings and stored credentials, together.
     *
     * Credentials are outside the settings blob, so they are watched
     * separately: saving a key must clear the "add an API key" banner and
     * fetch the endpoint's models, even though no setting changed.
     */
    private fun observeSettings() {
        viewModelScope.launch {
            combine(runtime.settingsRepository.settings, runtime.secrets.changes) { settings, _ -> settings }
                .collect { settings ->
                    // The active endpoint's own key: `activeEndpointId` can be
                    // null or stale while `activeEndpoint` falls back to the
                    // first enabled one, which is the one requests go to.
                    val active = settings.activeEndpoint
                    _state.update { current ->
                        current.copy(
                            settings = settings,
                            activeAgent = settings.activeAgent,
                            hasApiKey = active?.let { runtime.secrets.hasApiKey(it.id) } == true,
                        )
                    }
                    answerApprovalTheAgentWouldNotAsk(settings)
                    autoFetchModels(settings)
                }
        }
    }

    /**
     * The user switched to an agent that would not have asked: switching to
     * autopilot while a question is on screen answers it, and the turn goes
     * on under the new agent (it reads [UiState.activeAgent] on every call).
     * Only this chat's own calls: a subagent asks under its own permissions.
     */
    private fun answerApprovalTheAgentWouldNotAsk(settings: AppSettings) {
        val pending = _state.value.approval ?: return
        val agent = settings.activeAgent ?: return
        val ours = _state.value.inFlightTools.containsKey(pending.callId) ||
            _state.value.streaming?.toolCalls?.any { it.callId == pending.callId } == true
        if (!ours) return
        if (runtime.registry(settings).needsApproval(pending.toolName, agent) == false) {
            approvalAnswer?.complete(true)
        }
    }

    /**
     * List the active endpoint's models as soon as that can work, so the model
     * picker is filled and the first message needs no model id. Once per
     * endpoint and URL: an endpoint without a listing route is not asked again
     * on every settings change.
     */
    private fun autoFetchModels(settings: AppSettings) {
        val endpoint = settings.activeEndpoint ?: return
        if (endpoint.authScheme != AuthScheme.NONE && !runtime.secrets.hasApiKey(endpoint.id)) return
        if (!autoFetched.add("${endpoint.id}|${endpoint.baseUrl}")) return
        if (endpoint.models.isEmpty()) {
            refreshModels()
            return
        }
        // A stored list goes stale — new models, and what the listing says
        // about each (reasoning levels) that an older build did not read.
        // Once per process and quietly: the stored list still works if this fails.
        viewModelScope.launch {
            try {
                runtime.refreshModels(endpoint.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.d(TAG, "background model refresh failed: ${t.message}")
            }
        }
    }

    /** Fetch the active endpoint's model list; from the model picker, or automatically. */
    fun refreshModels() {
        val endpoint = _state.value.settings.activeEndpoint ?: return
        if (_state.value.loadingModels) return
        _state.update { it.copy(loadingModels = true, modelsError = null) }
        viewModelScope.launch {
            val problem: UiText? = try {
                val found = runtime.refreshModels(endpoint.id)
                if (found.isEmpty()) UiText.res(R.string.composer_models_none, endpoint.name) else null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "model listing failed", t)
                UiText.res(R.string.composer_models_failed, t.message ?: t.javaClass.simpleName)
            }
            _state.update { it.copy(loadingModels = false, modelsError = problem) }
            // The default model's window, where only its own route has it.
            if (problem == null) {
                runtime.settingsRepository.current().activeModel?.let { model ->
                    runCatching { runtime.describeIfUnverified(endpoint.id, model.id) }
                }
            }
        }
    }

    fun consumeReturnedDraft() = _state.update { it.copy(returnedDraft = null) }

    /** The notification's Stop button cannot cancel the job; it flips a flag. */
    private fun observeNotificationStop() {
        viewModelScope.launch {
            AgentService.stopRequested.collect { requested ->
                if (requested) {
                    stop()
                    AgentService.consumeStopRequest()
                }
            }
        }
    }

    /* --------------------------------------------------------------- input */

    /**
     * Read, turn upright and scale the picked images, off the main thread,
     * and queue them for the next message. Past [ImageAttachments.MAX_PER_MESSAGE]
     * the rest are left out, and the composer says so.
     */
    fun attachImages(uris: List<android.net.Uri>) {
        val room = ImageAttachments.MAX_PER_MESSAGE - _state.value.attachments.size - _state.value.preparingAttachments
        val taken = uris.take(room.coerceAtLeast(0))
        if (uris.size > taken.size) {
            _state.update {
                it.copy(changesMessage = UiText.res(R.string.chat_attach_limit, ImageAttachments.MAX_PER_MESSAGE))
            }
        }
        if (taken.isEmpty()) return
        _state.update { it.copy(preparingAttachments = it.preparingAttachments + taken.size) }
        viewModelScope.launch {
            for (uri in taken) {
                val prepared = withContext(Dispatchers.IO) {
                    runCatching { ImageAttachments.prepare(appContext.contentResolver, uri) }
                        .onFailure { Log.w(TAG, "could not read image $uri", it) }
                        .getOrNull()
                }
                _state.update { current ->
                    current.copy(
                        preparingAttachments = (current.preparingAttachments - 1).coerceAtLeast(0),
                        attachments = if (prepared != null) {
                            current.attachments + Attachment(
                                id = ConversationStore.newId("img"),
                                image = prepared.image,
                                thumbnail = prepared.thumbnail,
                            )
                        } else {
                            current.attachments
                        },
                        changesMessage = if (prepared == null) UiText.res(R.string.chat_attach_unreadable) else current.changesMessage,
                    )
                }
            }
        }
    }

    fun removeAttachment(id: String) = _state.update { current ->
        current.copy(attachments = current.attachments.filterNot { it.id == id })
    }

    fun send(text: String) {
        val trimmed = text.trim()
        // A command runs instead of being sent; one that cannot run now
        // hands its words back. "/etc/hosts …" is not one, and goes as words.
        ChatCommands.parse(trimmed)?.let { typed ->
            val command = typed.exact ?: return@let
            if (!runCommand(command, typed.args)) _state.update { it.copy(returnedDraft = text) }
            return
        }
        val snapshot = _state.value
        val attached = snapshot.attachments
        if (trimmed.isEmpty() && attached.isEmpty()) return
        // Still being read: sending now would quietly drop them.
        if (snapshot.preparingAttachments > 0) return
        val sessionId = snapshot.sessionId ?: return
        val workspace = snapshot.workspace ?: return
        val agent = snapshot.activeAgent ?: return
        if (snapshot.busy) return
        val attachments = attached.map { it.image }

        // Whoever sends wants to watch the answer, wherever they had scrolled.
        followBottom = true
        // A compaction that failed, stopped or was not worth it was never
        // part of the conversation: the next message takes it away.
        _state.update {
            it.copy(busy = true, error = null, compactionNote = null, compaction = null, attachments = emptyList())
        }
        AgentService.start(appContext, "Thinking…")

        turnJob = viewModelScope.launch {
            try {
                // The model first, here rather than only inside the runtime,
                // so that "no model" reads as an explanation with a way to fix
                // it, in the UI language — and the message is handed back
                // instead of vanishing with the cleared composer.
                if (snapshot.settings.activeModel == null) {
                    _state.update { it.copy(loadingModels = true, modelsError = null) }
                }
                val resolution = runtime.resolveModel(snapshot.settings)
                _state.update { it.copy(loadingModels = false) }
                val ready = when (resolution) {
                    is AgentRuntime.ModelResolution.Ready -> resolution
                    else -> {
                        // The words go back to the composer, and so do the images.
                        _state.update {
                            it.copy(
                                error = setupError(resolution),
                                returnedDraft = text,
                                attachments = attached + it.attachments,
                            )
                        }
                        return@launch
                    }
                }

                runtime.runTurn(
                    sessionId = sessionId,
                    workspace = workspace,
                    settings = ready.settings,
                    agent = agent,
                    userText = trimmed,
                    attachments = attachments,
                    ui = this@ChatViewModel,
                    approvals = this@ChatViewModel,
                    userLanguage = userLanguageName(),
                    liveAgent = { _state.value.activeAgent },
                ).collect { event -> handle(event) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "turn failed", t)
                _state.update {
                    it.copy(
                        error = UiError(
                            headline = UiText.res(R.string.chat_turn_failed),
                            detail = "${t.javaClass.simpleName}: ${t.message}",
                            retryable = true,
                        ),
                    )
                }
            } finally {
                flushStreaming()
                // The app's own compaction, stopped with the turn: a stop
                // that came while its summary was being saved lets it finish
                // (Compactor), and then its seam is due.
                val compacted = if (_state.value.compaction?.let { !it.manual && it.state == CompactionRun.State.RUNNING } == true) {
                    withContext(NonCancellable) { reloadCompacted(sessionId) }
                        ?.takeIf { it.chat.digests.size > _state.value.digests.size }
                } else {
                    null
                }
                // Whatever is still in flight now never got a result: the
                // transcript shows it as interrupted, which it was. A reply
                // stopped while it streamed stays as far as it came, marked
                // stopped, as AgentLoop saves it: it used to vanish from the
                // screen with the streaming bubble until the chat was reopened.
                _state.update { state ->
                    val current = if (compacted != null && state.sessionId == sessionId) state.withCompacted(compacted) else state
                    val stopped = current.streaming
                        ?.takeIf { streaming -> current.messages.none { it.id == streaming.messageId } }
                        ?.let { stoppedMessage(it, sessionId) }
                    current.copy(
                        busy = false,
                        streaming = null,
                        loadingModels = false,
                        inFlightTools = emptyMap(),
                        messages = if (stopped != null) current.messages + stopped else current.messages,
                        openThinking = if (stopped != null && current.streaming?.thinkingExpanded == true) {
                            current.openThinking + stopped.id
                        } else {
                            current.openThinking
                        },
                        // The app's own is over with the turn, done or not.
                        compaction = current.compaction?.takeIf { it.manual },
                    )
                }
                AgentService.finish(appContext)
                // A shell command can create or delete files no tool reports.
                EditorBus.requestTreeRefresh()
                refreshChanges()
            }
        }
    }

    /** [streaming] as the stopped message AgentLoop stores in its place: what had arrived, marked stopped. */
    private fun stoppedMessage(streaming: Streaming, sessionId: String): Message = Message(
        id = streaming.messageId,
        sessionId = sessionId,
        role = Role.ASSISTANT,
        status = MessageStatus.ABORTED,
        createdAt = streaming.startedAt,
        content = buildList {
            if (streaming.thinking.isNotBlank()) add(Block.Thinking(streaming.thinking))
            if (streaming.text.isNotBlank()) add(Block.Text(streaming.text))
            streaming.toolCalls.forEach { add(Block.ToolUse(it.callId, it.name, it.argumentsJson)) }
        },
    )

    /**
     * The user's language, as an English name for the system prompt.
     *
     * The in-app choice when one was made, otherwise the device's: the device
     * locale is the better guess at what the user reads, even where the UI
     * itself has no translation for it and falls back to English.
     */
    private fun userLanguageName(): String? = runCatching {
        val locale = AppLocales.current(appContext).tag?.let(Locale::forLanguageTag)
            ?: LocaleList.getDefault()[0]
            ?: return@runCatching null
        locale.getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Why a turn could not start, explained in the UI language. */
    private fun setupError(resolution: AgentRuntime.ModelResolution): UiError = when (resolution) {
        is AgentRuntime.ModelResolution.NoModels -> {
            val name = resolution.endpoint.name
            if (resolution.listingError != null) {
                UiError(
                    headline = UiText.res(R.string.chat_models_failed_title),
                    body = UiText.res(R.string.chat_models_failed_body, name),
                    detail = resolution.listingError.orEmpty(),
                    retryable = true,
                    opensSettings = true,
                )
            } else {
                UiError(
                    headline = UiText.res(R.string.chat_no_models_title),
                    body = UiText.res(R.string.chat_no_models_body, name),
                    detail = "",
                    retryable = false,
                    opensSettings = true,
                )
            }
        }
        else -> UiError(
            headline = UiText.res(R.string.chat_no_endpoint_title),
            body = UiText.res(R.string.chat_no_endpoint_body),
            detail = "",
            retryable = false,
            opensSettings = true,
        )
    }

    /* ------------------------------------------------------------ commands */

    /**
     * [command] with the words typed after it: from its row on the commands
     * plaque, from sending the words, from the context sheet. False, and
     * nothing done, when it cannot run now.
     */
    fun runCommand(command: ChatCommands.Command, args: String): Boolean = when (command) {
        ChatCommands.Command.COMPACT -> compact(args)
    }

    /**
     * /compact [focus]: the conversation folded into a summary for the
     * agent, with [focus] kept in detail. False, and nothing done, when it
     * cannot run now ([UiState.compactBlock]). Also a failed one's "Retry".
     *
     * Runs as a turn does, in [turnJob]: the composer's stop button stops
     * it, and then, as when it fails, nothing has changed — unless the stop
     * came while the summary was being saved, which Compactor lets finish
     * rather than leave half done; then it is done, and says so.
     */
    fun compact(focus: String?): Boolean {
        val snapshot = _state.value
        if (snapshot.compactBlock != null) return false
        val sessionId = snapshot.sessionId ?: return false
        val words = focus?.trim()?.takeIf { it.isNotEmpty() }
        val before = snapshot.digests.size
        followBottom = true
        _state.update {
            it.copy(
                busy = true,
                error = null,
                compactionNote = null,
                compaction = CompactionRun(
                    state = CompactionRun.State.RUNNING,
                    manual = true,
                    focus = words,
                ),
            )
        }
        AgentService.start(appContext, "Compacting the conversation")

        turnJob = viewModelScope.launch {
            try {
                runtime.compactNow(sessionId, snapshot.settings, words).collect { event -> handle(event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "compaction failed", t)
                _state.update { current ->
                    current.copy(
                        compaction = current.compaction?.copy(
                            state = CompactionRun.State.FAILED,
                            failure = AgentEvent.CompactionFailed(
                                AgentEvent.CompactionFailed.Kind.NO_SUMMARY,
                                "${t.javaClass.simpleName}: ${t.message}",
                            ),
                        ),
                    )
                }
            } finally {
                // Still running: stopped, or the event that it was done
                // did not get through the stop. The store says which.
                val stopped = _state.value.compaction?.let { it.manual && it.state == CompactionRun.State.RUNNING } == true
                val compacted = if (stopped) {
                    withContext(NonCancellable) { reloadCompacted(sessionId) }?.takeIf { it.chat.digests.size > before }
                } else {
                    null
                }
                _state.update { current ->
                    // Another chat by now (the stop let it open): this one's ending is not its business.
                    if (current.sessionId != sessionId) return@update current
                    when {
                        compacted != null -> current.withCompacted(compacted).copy(busy = false)
                        stopped -> current.copy(busy = false, compaction = current.compaction?.copy(state = CompactionRun.State.STOPPED))
                        else -> current.copy(busy = false)
                    }
                }
                AgentService.finish(appContext)
            }
        }
        return true
    }

    fun stop() {
        turnJob?.cancel()
        turnJob = null
        // Resolve anything the agent is blocked on, or the cancelled coroutine
        // never unwinds past the awaiting dialog.
        approvalAnswer?.complete(false)
        _state.update { it.copy(busy = false, approval = null, inFlightTools = emptyMap()) }
        AgentService.finish(appContext)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun toggleThinking() = _state.update { current ->
        current.copy(
            streaming = current.streaming?.let { it.copy(thinkingExpanded = !it.thinkingExpanded, thinkingToggled = true) },
        )
    }

    /* -------------------------------------------------------------- events */

    private var pendingTextUpdate = StringBuilder()
    private var pendingThinkingUpdate = StringBuilder()
    private var lastEmit = 0L

    private suspend fun handle(event: AgentEvent) {
        // Notification text comes from one place, so the shade and the screen
        // cannot disagree about what the agent is doing.
        AgentService.labelFor(event)?.let { AgentService.update(appContext, it) }

        when (event) {
            is AgentEvent.UserMessage ->
                _state.update { it.copy(messages = it.messages + event.message) }

            is AgentEvent.AssistantStarted -> {
                pendingTextUpdate = StringBuilder()
                pendingThinkingUpdate = StringBuilder()
                _state.update {
                    it.copy(
                        streaming = Streaming(
                            messageId = event.messageId,
                            iteration = event.iteration,
                        ),
                        // The app's own compaction is over by the time a request goes.
                        compaction = it.compaction?.takeIf { run -> run.manual },
                    )
                }
            }

            is AgentEvent.TextDelta -> {
                val first = pendingTextUpdate.isEmpty()
                pendingTextUpdate.append(event.text)
                if (first) foldThinkingForAnswer()
                maybeEmitStreaming()
            }

            is AgentEvent.ThinkingDelta -> {
                pendingThinkingUpdate.append(event.text)
                // Reasoning opens by itself while it is the only thing
                // happening: watching a blank bubble for thirty seconds is
                // worse than watching the model think. Once, not on every
                // delta: re-opening it each time undid the user closing it,
                // and every jump in its height moved the chat.
                val streaming = _state.value.streaming
                val open = streaming != null && !streaming.thinkingToggled && !streaming.thinkingExpanded &&
                    streaming.thinking.isEmpty() && pendingTextUpdate.isEmpty()
                maybeEmitStreaming(expandThinking = if (open) true else null)
            }

            is AgentEvent.TextWasThinking -> {
                // The model closed a </think> it never opened: what streamed
                // as the answer was its reasoning. Same join as AgentLoop's.
                val moved = pendingTextUpdate.toString().trim()
                if (moved.isNotEmpty()) {
                    if (pendingThinkingUpdate.isNotEmpty()) pendingThinkingUpdate.append("\n\n")
                    pendingThinkingUpdate.append(moved)
                }
                pendingTextUpdate = StringBuilder()
                flushStreaming()
            }

            is AgentEvent.ThinkingFinished -> {
                flushStreaming()
                // Collapse once real output starts, so the answer is what the
                // user sees — unless they opened or closed it themselves, or
                // scrolled up to read it: folding text away under a reader
                // moves everything they were looking at.
                _state.update { current ->
                    current.copy(
                        streaming = current.streaming?.let { streaming ->
                            if (streaming.thinkingToggled || !followBottom) streaming else streaming.copy(thinkingExpanded = false)
                        },
                    )
                }
            }

            is AgentEvent.ToolCallStarted -> {
                flushStreaming()
                _state.update { current ->
                    current.copy(
                        streaming = current.streaming?.let { streaming ->
                            streaming.copy(
                                // Being written: the clock starts when it runs.
                                toolCalls = streaming.toolCalls + LiveToolCall(
                                    callId = event.callId,
                                    name = event.name,
                                    status = ToolCallStatus.RUNNING,
                                    argumentsJson = event.argumentsJson,
                                    preparing = true,
                                    writingSince = System.currentTimeMillis(),
                                ),
                            )
                        },
                    )
                }
            }

            is AgentEvent.ToolCallProgress -> _state.update { current ->
                current.withCall(event.callId) { call ->
                    call.copy(
                        argsChars = event.chars,
                        partialTarget = call.partialTarget ?: toolTargetPartial(call.name, event.head),
                        partialPrompt = if (call.name == "subagent_dispatch") {
                            partialStringValue(event.head, "prompt") ?: call.partialPrompt
                        } else {
                            null
                        },
                    )
                }
            }

            is AgentEvent.SubagentProgress -> _state.update { current ->
                current.withCall(event.callId) { it.copy(subagent = event.run) }
            }

            is AgentEvent.ToolCallRunning -> _state.update { current ->
                current.withCall(event.callId) { call ->
                    if (call.status != ToolCallStatus.RUNNING) {
                        call
                    } else {
                        call.copy(preparing = false, runningSince = System.currentTimeMillis())
                    }
                }
            }

            is AgentEvent.ToolOutput -> _state.update { current ->
                current.withCall(event.callId) { it.copy(liveRows = event.rows) }
            }

            is AgentEvent.ToolCallFinished -> {
                val finish = { call: LiveToolCall ->
                    call.copy(
                        status = event.status,
                        // Whole, as the stored card shows it once the results
                        // land; it was cut to 2 000 characters here, so the
                        // same card read shorter live than after a reopen.
                        // The box draws a bounded part and says so (MonoBlock).
                        resultPreview = event.result.content,
                        display = event.result.display,
                        durationMs = event.durationMs,
                        isError = event.result.isError,
                    )
                }
                _state.update { current ->
                    current.copy(
                        streaming = current.streaming?.let { streaming ->
                            streaming.copy(
                                toolCalls = streaming.toolCalls.map { call ->
                                    if (call.callId != event.callId) call else finish(call)
                                },
                            )
                        },
                        // Tools run after their message is complete, so this is
                        // where a finished call usually is.
                        inFlightTools = current.inFlightTools[event.callId]?.let { call ->
                            current.inFlightTools + (event.callId to finish(call))
                        } ?: current.inFlightTools,
                    )
                }
            }

            is AgentEvent.MessageCompleted -> {
                flushStreaming()
                _state.update { current ->
                    // Tool-result messages are kept in state even though they
                    // are not drawn as bubbles. The renderer needs them: they
                    // carry whether each call actually succeeded, and dropping
                    // them here is what made a reloaded session show every
                    // failed call as a success. Filtering happens at render
                    // time instead, so live and restored sessions agree.
                    val message = event.message
                    val inFlight = when {
                        // The calls this message made are about to run: until
                        // their results arrive they are running, not interrupted.
                        message.role == Role.ASSISTANT && message.toolUses.isNotEmpty() && message.status != MessageStatus.ERROR -> {
                            val live = current.streaming?.toolCalls.orEmpty().associateBy { it.callId }
                            current.inFlightTools + message.toolUses.associate { use ->
                                val call = live[use.callId]
                                    ?: LiveToolCall(use.callId, use.name, ToolCallStatus.RUNNING)
                                // Written; queued until ToolCallRunning starts its clock.
                                use.callId to call.copy(argumentsJson = use.argumentsJson, preparing = false)
                            }
                        }
                        message.role == Role.TOOL -> current.inFlightTools - message.toolResults.map { it.callId }.toSet()
                        else -> current.inFlightTools
                    }
                    val finishing = current.streaming?.takeIf { it.messageId == message.id }
                    current.copy(
                        messages = current.messages.filterNot { it.id == message.id } + message,
                        streaming = if (finishing != null) null else current.streaming,
                        inFlightTools = inFlight,
                        openThinking = if (finishing?.thinkingExpanded == true) {
                            current.openThinking + message.id
                        } else {
                            current.openThinking
                        },
                    )
                }
            }

            is AgentEvent.FilesChanged -> {
                EditorBus.onFilesChangedOnDisk(event.paths)
                refreshChanges()
            }

            is AgentEvent.ContextUpdated ->
                _state.update { it.copy(context = event.accounting) }

            // The app's own, before a request once the window is nearly full:
            // drawn as /compact's is, at the end of the chat while it runs.
            // It was a line over the transcript with core's English reason.
            is AgentEvent.CompactionStarted -> if (!event.manual) {
                _state.update { it.copy(compaction = CompactionRun(CompactionRun.State.RUNNING, manual = false)) }
            }

            is AgentEvent.CompactionFinished -> {
                // Reload: the archived messages stay on screen, now above the
                // seam of the digest that took them out of the context.
                val sessionId = _state.value.sessionId ?: return
                val compacted = reloadCompacted(sessionId)
                _state.update { current ->
                    if (compacted == null || current.sessionId != sessionId) {
                        current.copy(compaction = null, compactionNote = null)
                    } else {
                        current.withCompacted(compacted)
                    }
                }
            }

            is AgentEvent.CompactionSkipped -> _state.update { current ->
                if (event.manual) {
                    current.copy(compaction = (current.compaction ?: CompactionRun(CompactionRun.State.SKIPPED, manual = true))
                        .copy(state = CompactionRun.State.SKIPPED, skipped = event.why))
                } else {
                    // Nearly full, and nothing to fold: the latest turns are most of it.
                    current.copy(compaction = null, compactionNote = UiText.res(R.string.chat_compact_nothing_archivable))
                }
            }

            is AgentEvent.CompactionFailed -> _state.update { current ->
                current.copy(compaction = (current.compaction ?: CompactionRun(CompactionRun.State.FAILED, manual = true))
                    .copy(state = CompactionRun.State.FAILED, failure = event))
            }

            is AgentEvent.Failed -> {
                event.error.discoveredContextLimit?.let { limit ->
                    persistDiscoveredLimit(limit)
                }
                _state.update {
                    it.copy(
                        error = UiError(
                            headline = event.error.headlineText(),
                            detail = buildString {
                                append(event.error.message)
                                event.error.body?.takeIf { body -> body.isNotBlank() }?.let { body ->
                                    append("\n\n")
                                    append(body.take(1_500))
                                }
                            },
                            retryable = event.recoverable,
                            discoveredContextLimit = event.error.discoveredContextLimit,
                            // Advice in the UI language and the server's words,
                            // quoted, instead of "see the details".
                            body = event.error.explanationText(),
                            opensSettings = event.error.kind in SETTINGS_FIXES,
                        ),
                    )
                }
            }

            is AgentEvent.TurnFinished -> {
                flushStreaming()
                // Every step ticked off: the plan is finished business. Left in
                // place, "4/4" hung over the transcript into the next request.
                // An unfinished plan stays — the agent may be waiting on an answer.
                _state.update { s ->
                    val plan = if (s.tasks.isNotEmpty() && s.tasks.all { it.done }) s.copy(tasks = emptyList()) else s
                    plan.copy(compaction = plan.compaction?.takeIf { run -> run.manual })
                }
                if (event.stoppedBy == AgentEvent.StopReason.ITERATION_LIMIT) {
                    _state.update {
                        it.copy(
                            error = UiError(
                                headline = UiText.res(R.string.chat_iteration_limit),
                                // The detail is formatted by the screen; see ErrorBanner.
                                detail = "",
                                retryable = false,
                                iterationLimit = event.iterations,
                            ),
                        )
                    }
                }
            }

            is AgentEvent.SubagentStarted -> Unit

            is AgentEvent.SubagentFinished -> Unit
        }
    }

    /**
     * Push accumulated deltas into state, at most every [EMIT_INTERVAL_MS].
     *
     * Without this, a fast endpoint drives one recomposition per token. The
     * interval is short enough to read as continuous and long enough that the
     * frame budget is never the bottleneck.
     */
    /**
     * The answer has started: the reasoning folds away, so the answer is what
     * is on screen. Not at the end of the message, where it used to happen —
     * the whole reasoning disappearing from above a finished answer moved
     * everything under the reader. Left open if the user opened or closed it
     * themselves, or is scrolled back reading.
     */
    private fun foldThinkingForAnswer() {
        if (!followBottom) return
        _state.update { current ->
            current.copy(
                streaming = current.streaming?.let { streaming ->
                    if (streaming.thinkingToggled || !streaming.thinkingExpanded) streaming else streaming.copy(thinkingExpanded = false)
                },
            )
        }
    }

    private fun maybeEmitStreaming(expandThinking: Boolean? = null) {
        val now = System.currentTimeMillis()
        if (now - lastEmit < EMIT_INTERVAL_MS) return
        lastEmit = now
        emitStreaming(expandThinking)
    }

    private fun flushStreaming() {
        lastEmit = 0
        emitStreaming(null)
    }

    private fun emitStreaming(expandThinking: Boolean?) {
        _state.update { current ->
            val streaming = current.streaming ?: return@update current
            current.copy(
                streaming = streaming.copy(
                    text = pendingTextUpdate.toString(),
                    thinking = pendingThinkingUpdate.toString(),
                    thinkingExpanded = expandThinking ?: streaming.thinkingExpanded,
                ),
            )
        }
    }

    /**
     * Record a context window learned from an overflow error.
     *
     * This is the self-correcting path that replaces a table of model windows:
     * the provider told us the real number, so store it.
     */
    private fun persistDiscoveredLimit(limit: Int) {
        val settings = _state.value.settings
        val endpointId = settings.activeEndpointId ?: return
        val modelId = settings.activeModel?.id ?: return
        viewModelScope.launch {
            runtime.settingsRepository.recordDiscoveredContextLimit(endpointId, modelId, limit)
        }
    }

    /* ------------------------------------------------------- ApprovalGate */

    /**
     * One dialog at a time. Calls in a parallel batch, and subagents running
     * side by side, can ask at once; the second request used to replace the
     * first's pending answer, and the first then waited forever.
     */
    private val approvalLock = Mutex()

    override suspend fun request(
        callId: String,
        toolName: String,
        summary: String,
        detail: String,
        destructive: Boolean,
        requester: String?,
    ): Boolean = approvalLock.withLock {
        askForApproval(callId, toolName, summary, detail, destructive, requester)
    }

    private suspend fun askForApproval(
        callId: String,
        toolName: String,
        summary: String,
        detail: String,
        destructive: Boolean,
        requester: String?,
    ): Boolean {
        val answer = CompletableDeferred<Boolean>()
        approvalAnswer = answer
        // The tool row says "waiting", not "running". This is the whole reason
        // the gate is handed a callId: the dialog is modal but the transcript
        // behind it would otherwise claim the call is in flight.
        _state.update {
            it.copy(approval = PendingApproval(callId, toolName, summary, detail, destructive, requester))
                .withCall(callId) { call -> call.copy(status = ToolCallStatus.PENDING, runningSince = null) }
        }
        AgentService.update(appContext, AgentService.AWAITING_APPROVAL_LABEL)
        return try {
            answer.await().also { allowed ->
                _state.update {
                    it.withCall(callId) { call ->
                        if (allowed) {
                            // The timer counts the work, not the wait for an answer.
                            call.copy(status = ToolCallStatus.RUNNING, runningSince = System.currentTimeMillis())
                        } else {
                            call.copy(status = ToolCallStatus.DENIED, runningSince = null)
                        }
                    }
                }
                if (allowed) AgentService.update(appContext, "Running $toolName")
            }
        } finally {
            approvalAnswer = null
            _state.update { it.copy(approval = null) }
        }
    }

    /**
     * [change] applied to call [callId] wherever it is: still in the streaming
     * message, or — the usual case once tools run, since that is after the
     * message completed — in flight.
     */
    private fun UiState.withCall(callId: String, change: (LiveToolCall) -> LiveToolCall): UiState = copy(
        streaming = streaming?.let { streaming ->
            streaming.copy(toolCalls = streaming.toolCalls.map { if (it.callId == callId) change(it) else it })
        },
        inFlightTools = inFlightTools[callId]?.let { call -> inFlightTools + (callId to change(call)) } ?: inFlightTools,
    )

    fun resolveApproval(allow: Boolean, alwaysAllowTool: Boolean = false) {
        val pending = _state.value.approval
        if (alwaysAllowTool && allow && pending != null) {
            val agent = _state.value.activeAgent
            if (agent != null && !agent.builtIn) {
                viewModelScope.launch {
                    runtime.settingsRepository.upsertAgent(
                        agent.copy(
                            toolPermissions = agent.toolPermissions +
                                (pending.toolName to dev.apkcli.core.model.ToolPermission.ALLOW),
                        ),
                    )
                }
            }
        }
        approvalAnswer?.complete(allow)
    }

    /**
     * From Rules' way out of asking, from the approval sheet: tick [rule] in
     * Settings › Rules and let this call through. The turn reads the mode on
     * every call, so the next call of the kind goes ahead too; one already
     * waiting when the tick lands is answered by [answerApprovalTheAgentWouldNotAsk].
     */
    fun allowAndStopAsking(rule: ApprovalRule) {
        viewModelScope.launch { runtime.settingsRepository.setApprovalRule(rule, true) }
        resolveApproval(true, false)
    }

    /* ---------------------------------------------------------- UiBridge */

    override suspend fun openInEditor(path: String, line: Int?): String? = EditorBus.open(path, line)

    override suspend fun setTasks(items: List<UiBridge.Task>) {
        _state.update { it.copy(tasks = items) }
    }

    override suspend fun currentTasks(): List<UiBridge.Task> = _state.value.tasks

    /** The user closed the plan panel. The agent's next task_list call brings it back. */
    fun dismissTasks() = _state.update { it.copy(tasks = emptyList()) }

    override suspend fun openFiles(): List<String> = EditorBus.openFiles.first()

    override suspend fun activeFile(): String? = EditorBus.activeFile.first()

    /* --------------------------------------------------------- sessions */

    /**
     * "+": a new chat. The one open stays in the project's history; one with
     * nothing sent in it yet is already new, so it stays the open one.
     */
    fun newSession() {
        if (_state.value.busy) return
        _history.value = null
        if (_state.value.sessionId != null && openChatIsEmpty()) return
        viewModelScope.launch { startNewSession() }
    }

    private suspend fun startNewSession() {
        val settings = _state.value.settings
        val workspace = _state.value.workspace ?: defaultWorkspace()
        val session = runtime.store.createSession(
            workspace = workspace.absolutePath,
            agentId = settings.activeAgentId,
            endpointId = settings.activeEndpointId,
            model = settings.activeModelId,
        )
        _state.update {
            it.copy(
                sessionId = session.id,
                messages = emptyList(),
                digests = emptyList(),
                compaction = null,
                streaming = null,
                context = null,
                tasks = emptyList(),
                error = null,
                compactionNote = null,
                changes = emptyList(),
                find = null,
            )
        }
    }

    fun openSession(sessionId: String) {
        if (_state.value.busy) return
        viewModelScope.launch { openSessionNow(sessionId) }
    }

    private suspend fun openSessionNow(sessionId: String) {
        val session = runtime.store.getSession(sessionId) ?: return
        val chat = loadChat(sessionId)
        val workspace = session.workspace.takeIf { it.isNotBlank() }?.let(::File)
            ?: _state.value.workspace
        _state.update {
            it.copy(
                sessionId = sessionId,
                workspace = workspace,
                workspaceLabel = workspace?.let(runtime.layout::shorten) ?: "",
                messages = chat.messages,
                digests = chat.digests,
                compaction = null,
                streaming = null,
                context = null,
                tasks = emptyList(),
                error = null,
                compactionNote = null,
                changes = emptyList(),
                find = null,
            )
        }
        refreshChanges()
    }

    /* ------------------------------------------------------------ history */

    fun openHistory() {
        viewModelScope.launch { _history.value = loadHistory() }
    }

    fun closeHistory() {
        _history.value = null
    }

    /** Nothing has been sent in the open chat, and nothing is on its way. */
    private fun openChatIsEmpty(): Boolean = _state.value.let { it.messages.isEmpty() && it.streaming == null && !it.busy }

    /**
     * The open project's chats. Empty ones other than the open chat are what
     * "+" used to leave behind, one per tap; they are deleted here rather
     * than listed, there being nothing in them.
     */
    private suspend fun loadHistory(): List<ChatSummary> {
        val workspace = _state.value.workspace?.absolutePath ?: return emptyList()
        val open = _state.value.sessionId
        val sessions = runCatching { runtime.store.listSessions(limit = HISTORY_LIMIT) }
            .onFailure { Log.w(TAG, "listing the chats failed", it) }
            .getOrDefault(emptyList())
            .filter { it.workspace == workspace }
        val summaries = sessions.map { session ->
            ChatSummary(
                id = session.id,
                title = session.title,
                updatedAt = session.updatedAt,
                lastRequest = session.preview.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                empty = session.title.isBlank() && session.preview.isBlank(),
            )
        }
        summaries.filter { it.empty && it.id != open }.forEach { stale ->
            runCatching { runtime.store.deleteSession(stale.id) }
        }
        return summaries.filter { !it.empty || it.id == open }
    }

    /** Open chat [id]; the open one stays in the history unless nothing was sent in it. Not while a turn runs. */
    fun openChat(id: String) {
        val current = _state.value.sessionId
        if (_state.value.busy || id == current) {
            _history.value = null
            return
        }
        viewModelScope.launch {
            val left = current?.takeIf { openChatIsEmpty() }
            openSessionNow(id)
            left?.let { runCatching { runtime.store.deleteSession(it) } }
            _history.value = null
        }
    }

    fun renameChat(id: String, title: String) {
        val name = title.trim().replace(Regex("\\s+"), " ").take(CHAT_TITLE_MAX)
        if (name.isEmpty()) return
        viewModelScope.launch {
            runCatching { runtime.store.updateSession(id, title = name) }.onFailure { Log.w(TAG, "renaming a chat failed", it) }
            if (_history.value != null) _history.value = loadHistory()
        }
    }

    /**
     * Delete chat [id] with its messages and checkpoints; the project's files
     * stay as they are. The open chat gives way to the most recent other
     * one, or to a new one; it cannot go while its turn runs.
     */
    fun deleteChat(id: String) {
        if (_state.value.busy && id == _state.value.sessionId) return
        viewModelScope.launch {
            runCatching { runtime.store.deleteSession(id) }.onFailure { Log.w(TAG, "deleting a chat failed", it) }
            uiPrefs.edit().remove("changes.since.$id").apply()
            if (id == _state.value.sessionId) {
                val workspace = _state.value.workspace?.absolutePath
                val next = runCatching { runtime.store.listSessions(limit = HISTORY_LIMIT) }.getOrDefault(emptyList())
                    .firstOrNull { it.workspace == workspace && !(it.title.isBlank() && it.preview.isBlank()) }
                if (next != null) openSessionNow(next.id) else startNewSession()
            }
            if (_history.value != null) _history.value = loadHistory()
        }
    }

    /* --------------------------------------------------------------- find */

    /** The magnifier: find in the open chat. */
    fun startFind() {
        if (_state.value.messages.isEmpty()) return
        _history.value = null
        _state.update { it.copy(find = Find()) }
    }

    /** New words: the search starts again from the newest match. */
    fun setFindQuery(query: String) {
        _state.update { state -> state.copy(find = state.find?.copy(query = query, current = null)) }
    }

    /**
     * The matches as the chat last listed them, top to bottom; ChatScreen
     * finds them in what it draws and leaves them here for ↑↓ and for the
     * keyboard's search key in the top bar.
     */
    var findHits: List<FindHit> = emptyList()

    /** ↑ (step -1, back in time) or ↓ (+1) through [findHits], wrapping at either end. */
    fun moveFind(step: Int) {
        val hits = findHits
        if (hits.size < 2) return
        _state.update { state ->
            val find = state.find ?: return@update state
            val at = find.current?.let(hits::indexOf)?.takeIf { it >= 0 } ?: hits.lastIndex
            state.copy(find = find.copy(current = hits[(at + step).mod(hits.size)]))
        }
    }

    /**
     * [hit] is the match on screen. A search starts at the newest match
     * without the reader moving; kept, it stays theirs when a turn lands
     * with newer ones.
     */
    fun pinFind(hit: FindHit) {
        _state.update { state -> state.find?.takeIf { it.current != hit }?.let { state.copy(find = it.copy(current = hit)) } ?: state }
    }

    fun endFind() {
        findHits = emptyList()
        _state.update { it.copy(find = null) }
    }

    fun selectAgent(agentId: String) {
        viewModelScope.launch { runtime.settingsRepository.setActiveAgent(agentId) }
    }

    /**
     * From the composer's model picker: another model on the active endpoint.
     * Its own route is asked for its window right away, so the context ring
     * is right before the first message rather than after it.
     */
    fun selectModel(modelId: String) {
        val endpointId = _state.value.settings.activeEndpoint?.id ?: return
        viewModelScope.launch {
            runtime.settingsRepository.setActiveEndpoint(endpointId, modelId)
            runCatching { runtime.describeIfUnverified(endpointId, modelId) }
        }
    }

    /** Pick one of the active model's published reasoning levels; null goes back to the model's default. */
    fun setReasoningEffort(level: String?) {
        val settings = _state.value.settings
        val endpointId = settings.activeEndpoint?.id ?: return
        val model = settings.activeModel ?: return
        if (level != null && level !in model.reasoningEfforts) return
        viewModelScope.launch { runtime.settingsRepository.setReasoningEffort(endpointId, model.id, level) }
    }

    /** [endpoint] can be used as it is: it needs no credential, or one is stored. */
    fun hasKey(endpoint: Endpoint): Boolean =
        endpoint.authScheme == AuthScheme.NONE || runtime.secrets.hasApiKey(endpoint.id)

    /**
     * From the model menu: send to another endpoint. Its models are fetched
     * by the settings observer if it has none yet.
     */
    fun selectEndpoint(endpointId: String) {
        if (_state.value.busy) return
        _state.update { it.copy(modelsError = null) }
        viewModelScope.launch { runtime.settingsRepository.setActiveEndpoint(endpointId) }
    }

    /* ----------------------------------------------------------- projects */

    /**
     * Work in another project.
     *
     * Resumes that project's most recent session when there is one — a
     * session is bound to its workspace, so carrying the current transcript
     * across would point the agent's relative paths at the wrong tree.
     */
    fun switchWorkspace(dir: File, onReady: () -> Unit = {}) {
        if (_state.value.busy) return
        viewModelScope.launch {
            val path = dir.absolutePath
            val existing = runtime.store.listSessions(limit = 200).firstOrNull { it.workspace == path }
            if (existing != null) {
                openSessionNow(existing.id)
            } else {
                val settings = _state.value.settings
                val session = runtime.store.createSession(
                    workspace = path,
                    agentId = settings.activeAgentId,
                    endpointId = settings.activeEndpointId,
                    model = settings.activeModelId,
                )
                _state.update {
                    it.copy(
                        sessionId = session.id,
                        workspace = dir,
                        workspaceLabel = runtime.layout.shorten(dir),
                        messages = emptyList(),
                        digests = emptyList(),
                        compaction = null,
                        find = null,
                        streaming = null,
                        context = null,
                        tasks = emptyList(),
                        error = null,
                        compactionNote = null,
                        changes = emptyList(),
                    )
                }
            }
            onReady()
        }
    }

    /* ------------------------------------------------------------- memory */

    /** What the agent remembers, as the memory screen shows it. */
    data class MemoryView(
        val projectName: String,
        val project: List<Pair<String, String>>,
        val global: List<Pair<String, String>>,
        /** The project's instruction file, AGENTS.md, when it exists. */
        val notesFile: File?,
        /**
         * The instruction file requests read (AGENTS.md, or another name
         * when there is none) and its size, when it is longer than they
         * carry; see [dev.apkcli.core.agent.ProjectInstructions].
         */
        val longInstructions: Pair<String, Long>? = null,
    )

    private val _memory = MutableStateFlow<MemoryView?>(null)

    /** Non-null while the memory screen is open. */
    val memory: StateFlow<MemoryView?> = _memory.asStateFlow()

    fun openMemory() {
        viewModelScope.launch { _memory.value = loadMemory() }
    }

    fun closeMemory() {
        _memory.value = null
    }

    /** Save [key] (renamed from [previousKey] when it changed) in the project's or the global scope. */
    fun saveMemory(global: Boolean, key: String, value: String, previousKey: String? = null) {
        val workspace = _state.value.workspace ?: return
        val scope = if (global) MemoryScopes.GLOBAL else MemoryScopes.project(workspace)
        viewModelScope.launch {
            runCatching {
                if (previousKey != null && previousKey != key) runtime.store.memoryDelete(scope, previousKey)
                runtime.store.memorySet(scope, key.trim(), value.trim())
            }.onFailure { Log.w(TAG, "memory save failed", it) }
            _memory.value = loadMemory()
        }
    }

    fun deleteMemory(global: Boolean, key: String) {
        val workspace = _state.value.workspace ?: return
        val scope = if (global) MemoryScopes.GLOBAL else MemoryScopes.project(workspace)
        viewModelScope.launch {
            runCatching { runtime.store.memoryDelete(scope, key) }.onFailure { Log.w(TAG, "memory delete failed", it) }
            _memory.value = loadMemory()
        }
    }

    /** The project's AGENTS.md, created with a heading when missing, opened in the editor. */
    fun openProjectNotes() {
        val workspace = _state.value.workspace ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                File(workspace, "AGENTS.md").also { notes ->
                    if (!notes.exists()) {
                        notes.writeText(
                            "# ${workspace.name}\n\n" +
                                "<!-- Notes for the agent: it reads this file on every request. -->\n\n",
                        )
                    }
                }
            }
            _memory.value = null
            EditorBus.requestTreeRefresh()
            EditorBus.requestOpen(file.absolutePath)
        }
    }

    private suspend fun loadMemory(): MemoryView? {
        val workspace = _state.value.workspace ?: return null
        return withContext(Dispatchers.IO) {
            val project = runCatching { runtime.store.memoryList(MemoryScopes.project(workspace)) }.getOrDefault(emptyMap())
            val global = runCatching { runtime.store.memoryList(MemoryScopes.GLOBAL) }.getOrDefault(emptyMap())
            MemoryView(
                projectName = workspace.name,
                project = project.toList(),
                global = global.toList(),
                notesFile = File(workspace, "AGENTS.md").takeIf { it.isFile },
                longInstructions = ProjectInstructions.find(workspace)
                    ?.let { (name, file) -> name to file.length() }
                    ?.takeIf { (_, bytes) -> bytes > ProjectInstructions.MAX_BYTES },
            )
        }
    }

    /* ------------------------------------------------------------ imports */

    private val importer by lazy { ProjectImport(runtime.layout) }
    private var importJob: Job? = null

    /** Download a public GitHub repository into `~/projects` and open it. */
    fun importFromGitHub(input: String) {
        val source = ProjectImport.parseGitHub(input)
        if (source == null) {
            _state.update {
                it.copy(importing = ProjectImportState(input.trim(), failure = UiText.res(R.string.import_github_invalid)))
            }
            return
        }
        startImport(source.display, fromGitHub = true) { progress -> importer.fromGitHub(source, progress) }
    }

    /** Copy a folder the user picked on the device into `~/projects`. */
    fun importFromDevice(tree: android.net.Uri) {
        val label = tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null }
            ?: tree.toString()
        startImport(label, fromGitHub = false) { progress -> importer.fromTree(appContext, tree, progress) }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun dismissImport() = _state.update { it.copy(importing = null) }

    private fun startImport(
        source: String,
        fromGitHub: Boolean,
        run: suspend ((ProjectImport.Progress) -> Unit) -> File,
    ) {
        if (importJob?.isActive == true) return
        _state.update { it.copy(importing = ProjectImportState(source, fromGitHub = fromGitHub)) }
        importJob = viewModelScope.launch {
            try {
                val dir = run { progress ->
                    _state.update { current ->
                        current.copy(importing = current.importing?.takeIf { it.running }?.copy(progress = progress))
                    }
                }
                // Done, and waiting: the sheet offers to open it, or to leave
                // it in the list for later.
                _state.update { it.copy(importing = it.importing?.copy(result = dir)) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(importing = null) }
                throw cancelled
            } catch (failed: ProjectImport.ImportException) {
                Log.w(TAG, "import of $source failed: ${failed.message}")
                val reason = when (failed.reason) {
                    ProjectImport.ImportException.Reason.NOT_FOUND -> UiText.res(R.string.import_not_found)
                    ProjectImport.ImportException.Reason.TOO_LARGE -> UiText.res(R.string.import_too_large, failed.message.orEmpty())
                    ProjectImport.ImportException.Reason.UNREADABLE -> UiText.res(R.string.import_unreadable, failed.message.orEmpty())
                    ProjectImport.ImportException.Reason.NETWORK -> UiText.res(R.string.import_network, failed.message.orEmpty())
                    ProjectImport.ImportException.Reason.FAILED -> UiText.res(R.string.import_failed, failed.message.orEmpty())
                }
                _state.update { it.copy(importing = ProjectImportState(source, failure = reason, fromGitHub = fromGitHub)) }
            } catch (t: Throwable) {
                Log.w(TAG, "import of $source failed", t)
                _state.update {
                    it.copy(
                        importing = ProjectImportState(
                            source,
                            failure = UiText.res(R.string.import_failed, "${t.javaClass.simpleName}: ${t.message.orEmpty()}"),
                            fromGitHub = fromGitHub,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Open [dir] as the workspace, then [onOpened]. False, and nothing done,
     * while the agent is working in another project: its paths would end up
     * pointing at the wrong tree.
     */
    fun openProject(dir: File, onOpened: () -> Unit = {}): Boolean {
        val current = _state.value.workspace
        if (current?.absolutePath == dir.absolutePath && _state.value.sessionId != null) {
            onOpened()
            return true
        }
        if (_state.value.busy) return false
        switchWorkspace(dir, onOpened)
        return true
    }

    /** The open project was deleted or renamed away: nothing is open now. */
    fun forgetWorkspace(dir: File) {
        if (_state.value.workspace?.absolutePath != dir.absolutePath || _state.value.busy) return
        _state.update {
            it.copy(
                sessionId = null,
                workspace = null,
                workspaceLabel = "",
                messages = emptyList(),
                digests = emptyList(),
                compaction = null,
                find = null,
                streaming = null,
                context = null,
                tasks = emptyList(),
                changes = emptyList(),
                error = null,
            )
        }
    }

    /**
     * Create `~/projects/<name>` with an AGENTS.md for notes to the agent,
     * switch to it, then [onReady].
     */
    fun createProject(name: String, onReady: () -> Unit = {}) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean == "." || clean == ".." || _state.value.busy) return
        viewModelScope.launch {
            val dir = withContext(Dispatchers.IO) {
                File(runtime.layout.projects, clean).also { dir ->
                    dir.mkdirs()
                    File(dir, "AGENTS.md").takeIf { !it.exists() }?.writeText(
                        "# $clean\n\n<!-- Notes for the agent: it reads this file on every request. -->\n\n",
                    )
                }.takeIf { it.isDirectory }
            } ?: return@launch
            switchWorkspace(dir, onReady)
        }
    }

    /* ------------------------------------------------------------ changes */



    private fun acceptedSince(sessionId: String): Long = uiPrefs.getLong("changes.since.$sessionId", 0L)

    private var changesJob: Job? = null

    /** Re-read the session's pending changes. Cheap: one store query, no diffs. */
    fun refreshChanges() {
        val sessionId = _state.value.sessionId ?: return
        changesJob?.cancel()
        changesJob = viewModelScope.launch {
            val pending = runCatching { runtime.changes.pending(sessionId, acceptedSince(sessionId)) }
                .getOrDefault(emptyList())
            if (_state.value.sessionId != sessionId) return@launch
            _state.update { it.copy(changes = pending, changesLines = if (pending.isEmpty()) null else it.changesLines) }
            // The bar's "+64 −1": counted from the diffs, a few files at most.
            if (pending.isNotEmpty() && pending.size <= MAX_COUNTED_CHANGES) {
                var added = 0
                var removed = 0
                for (change in pending) {
                    val diff = runCatching { diffFor(change) }.getOrNull() ?: continue
                    added += diff.added
                    removed += diff.removed
                }
                if (_state.value.sessionId == sessionId) _state.update { it.copy(changesLines = added to removed) }
            }
        }
    }

    /**
     * "Keep" the current changes: they stop being listed.
     *
     * Nothing is deleted; the snapshots stay, and the agent can still revert
     * them with `revert_edit`. Only the bar's window moves forward.
     */
    fun acceptChanges() {
        val sessionId = _state.value.sessionId ?: return
        uiPrefs.edit().putLong("changes.since.$sessionId", System.currentTimeMillis()).apply()
        _state.update { it.copy(changes = emptyList(), changesMessage = null) }
    }

    suspend fun diffFor(change: ChangeLedger.FileChange): ChangeLedger.FileDiff {
        val workspace = _state.value.workspace
        val display = workspace?.let { root ->
            File(change.path).relativeToOrNull(root)?.path
        } ?: File(change.path).name
        return runtime.changes.diff(change, display)
    }

    fun revertChange(change: ChangeLedger.FileChange) = revert(listOf(change))

    fun revertAllChanges() = revert(_state.value.changes)

    private fun revert(changes: List<ChangeLedger.FileChange>) {
        if (changes.isEmpty() || _state.value.revertingChanges || _state.value.busy) return
        _state.update { it.copy(revertingChanges = true, changesMessage = null) }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            changes.forEach { change ->
                when (val result = runtime.changes.revert(change)) {
                    ChangeLedger.RevertResult.Reverted -> Unit
                    is ChangeLedger.RevertResult.Failed -> failed += "${File(change.path).name}: ${result.reason}"
                }
            }
            EditorBus.onFilesChangedOnDisk(changes.map { it.path })
            _state.update {
                it.copy(
                    revertingChanges = false,
                    changesMessage = if (failed.isEmpty()) {
                        UiText.plural(R.plurals.changes_reverted, changes.size)
                    } else {
                        UiText.res(R.string.changes_revert_failed, failed.joinToString("; "))
                    },
                )
            }
            refreshChanges()
        }
    }

    fun dismissChangesMessage() = _state.update { it.copy(changesMessage = null) }

    /* -------------------------------------------------------- checkpoints */

    /**
     * "Restore" on the checkpoint above a user message: ask first, saying what
     * goes. The file count needs the disk, so it fills in a moment later.
     */
    fun askRestoreCheckpoint(messageId: String) {
        val current = _state.value
        val sessionId = current.sessionId ?: return
        if (current.restoringCheckpoint) return
        val message = current.messages.firstOrNull { it.id == messageId && it.role == Role.USER } ?: return
        // Messages as the chat shows them. First from what is on screen, by
        // position — a message appended live may not carry its stored seq
        // yet — then from the store, which is what goes.
        val index = current.messages.indexOfFirst { it.id == messageId }
        val shown = shownMessages(current.messages.drop(index))
        // A summary written since goes with it (ConversationStore.rewind):
        // the turns it folded are in the agent's context again.
        val undoes = current.digests.any { it.createdAt >= message.createdAt }
        _state.update { it.copy(checkpoint = CheckpointPrompt(messageId, messages = shown, undoesCompaction = undoes)) }
        viewModelScope.launch {
            // Compacted ones too: they are on screen, above their seams, and they go as well.
            val stored = runCatching { runtime.store.window(sessionId, includeArchived = true) }.getOrNull()
            val from = stored?.firstOrNull { it.id == messageId }?.seq
            val messages = if (stored != null && from != null) {
                shownMessages(stored.filter { it.seq >= from }.sortedBy { it.seq })
            } else {
                shown
            }
            val files = runCatching { runtime.changes.sinceCheckpoint(sessionId, message.createdAt).size }
                .getOrDefault(0)
            _state.update { state ->
                val prompt = state.checkpoint?.takeIf { it.messageId == messageId } ?: return@update state
                state.copy(checkpoint = prompt.copy(messages = messages, files = files))
            }
        }
    }

    /**
     * How many messages [messages] are to the person reading the chat: each
     * of theirs, and each reply of the agent's — its text, tool calls and
     * their results read as one reply, however many records it took.
     */
    private fun shownMessages(messages: List<Message>): Int {
        var count = 0
        var inReply = false
        for (message in messages) {
            when {
                message.role == Role.USER -> {
                    count++
                    inReply = false
                }
                !inReply -> {
                    count++
                    inReply = true
                }
            }
        }
        return count
    }

    fun dismissCheckpoint() = _state.update { it.copy(checkpoint = null) }

    /**
     * Go back to the confirmed checkpoint: the chat, its context and the
     * agent's file changes as they were when that message was sent.
     *
     * Files first, history second. A file that cannot be put back keeps its
     * snapshots, so it stays in the change list rather than vanishing with
     * the messages that explained it. The message itself comes back into the
     * composer, images included, to be edited and sent again.
     *
     * Changes the agent made through the shell were never snapshotted and
     * stay; the confirmation says so.
     */
    fun restoreCheckpoint() {
        val current = _state.value
        val prompt = current.checkpoint ?: return
        val sessionId = current.sessionId ?: return
        if (current.restoringCheckpoint) return
        val message = current.messages.firstOrNull { it.id == prompt.messageId } ?: return
        _state.update { it.copy(checkpoint = null, restoringCheckpoint = true, changesMessage = null) }

        viewModelScope.launch {
            // A running turn would go on writing into the history being taken
            // back. Stop it and wait until it has really stopped.
            turnJob?.takeIf { it.isActive }?.let { running ->
                stop()
                running.join()
            }

            val changes = runCatching { runtime.changes.sinceCheckpoint(sessionId, message.createdAt) }
                .getOrDefault(emptyList())
            val failed = mutableListOf<Pair<ChangeLedger.FileChange, String>>()
            for (change in changes) {
                val result = runtime.changes.revert(change)
                if (result is ChangeLedger.RevertResult.Failed) failed += change to result.reason
            }
            if (changes.isNotEmpty()) EditorBus.onFilesChangedOnDisk(changes.map { it.path })

            val rewound = runCatching {
                runtime.store.rewind(sessionId, message.id, failed.flatMap { (change, _) -> change.snapshotIds })
            }.onFailure { Log.e(TAG, "rewind of $sessionId to ${message.id} failed", it) }

            // With the summaries left: those written since went with the messages.
            val chat = runCatching { loadChat(sessionId) }.getOrNull()
            val words = message.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
            val images = withContext(Dispatchers.Default) {
                message.content.filterIsInstance<Block.Image>().mapIndexedNotNull { index, image ->
                    ImageAttachments.thumbnail("${message.id}:$index", image)?.let { thumbnail ->
                        Attachment(ConversationStore.newId("img"), image, thumbnail)
                    }
                }
            }
            _state.update { state ->
                if (state.sessionId != sessionId) return@update state.copy(restoringCheckpoint = false)
                val restored = rewound.isSuccess
                state.copy(
                    restoringCheckpoint = false,
                    messages = chat?.messages ?: state.messages,
                    digests = chat?.digests ?: state.digests,
                    compaction = null,
                    streaming = null,
                    inFlightTools = emptyMap(),
                    context = if (restored) null else state.context,
                    tasks = if (restored) emptyList() else state.tasks,
                    error = null,
                    compactionNote = null,
                    returnedDraft = if (restored) words else state.returnedDraft,
                    attachments = if (restored) {
                        (images + state.attachments).take(ImageAttachments.MAX_PER_MESSAGE)
                    } else {
                        state.attachments
                    },
                    changesMessage = when {
                        !restored -> UiText.res(
                            R.string.checkpoint_failed,
                            rewound.exceptionOrNull()?.message ?: rewound.exceptionOrNull()?.javaClass?.simpleName.orEmpty(),
                        )
                        failed.isNotEmpty() -> UiText.res(
                            R.string.checkpoint_files_failed,
                            failed.joinToString("; ") { (change, reason) -> "${File(change.path).name}: $reason" },
                        )
                        else -> null
                    },
                )
            }
            followBottom = true
            EditorBus.requestTreeRefresh()
            refreshChanges()
        }
    }

    override fun onCleared() {
        turnJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * Delta coalescing interval.
         *
         * 50ms reads as continuous to a human and caps recomposition at 20Hz
         * regardless of how fast the endpoint streams.
         */
        private const val EMIT_INTERVAL_MS = 50L

        private const val STARTUP_POLL_MS = 50L
        private const val STARTUP_TIMEOUT_MS = 15_000L

        /** The change bar counts lines only up to this many files: past it the number says little. */
        private const val MAX_COUNTED_CHANGES = 30

        /** Chats read for the history, across projects, before filtering to the open one. */
        private const val HISTORY_LIMIT = 1_000

        /** A chat renamed by hand is cut here: it is a line in a list, not a note. */
        private const val CHAT_TITLE_MAX = 80

        /** Failures whose fix is in Settings (key, model list, URL): the banner offers a button there. */
        private val SETTINGS_FIXES = setOf(
            dev.apkcli.core.llm.LlmError.Kind.AUTH,
            dev.apkcli.core.llm.LlmError.Kind.MODEL_UNAVAILABLE,
            dev.apkcli.core.llm.LlmError.Kind.INSECURE_TRANSPORT,
        )

        fun factory(runtime: AgentRuntime, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(runtime, context.applicationContext) as T
            }
    }
}
