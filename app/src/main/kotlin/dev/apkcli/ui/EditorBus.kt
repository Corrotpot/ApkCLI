package dev.apkcli.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared state between the agent and the editor.
 *
 * Three things genuinely need to cross between them, and a process-wide object
 * is the honest way to express it:
 *
 *  * The agent's `editor_open` tool has to make a file appear on screen, in a
 *    screen it does not own.
 *  * The system prompt needs the list of open files, so "fix this file" with no
 *    name resolves to the one the user is looking at.
 *  * After the agent edits a file, any open buffer for it is stale and must
 *    reload — otherwise the user saves over the agent's work from a buffer read
 *    before the edit.
 *
 * Routing these through a ViewModel would not work: the chat and editor screens
 * have separate ViewModels with separate lifecycles, and the agent keeps running
 * when neither is composed.
 */
object EditorBus {

    /**
     * @param outcome completed by the editor: null once the file is open, or
     *   why it was refused. Null when nobody is waiting for the answer.
     */
    data class OpenRequest(
        val path: String,
        val line: Int?,
        val outcome: CompletableDeferred<String?>? = null,
    )

    /**
     * Open requests, as a channel rather than a StateFlow.
     *
     * Each request is an event to act on once. With a StateFlow, opening the
     * same file twice in a row would be a no-op because the value did not
     * change, and a recomposition after a rotation would re-open whatever was
     * last requested.
     */
    private val openRequests = Channel<OpenRequest>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val opens: Flow<OpenRequest> = openRequests.receiveAsFlow()

    private val _openFiles = MutableStateFlow<List<String>>(emptyList())

    /** Absolute paths currently open, most recently focused first. */
    val openFiles: StateFlow<List<String>> = _openFiles.asStateFlow()

    private val _activeFile = MutableStateFlow<String?>(null)
    val activeFile: StateFlow<String?> = _activeFile.asStateFlow()

    private val _externallyChanged = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Open files whose contents changed on disk underneath the buffer.
     *
     * The editor shows a reload prompt rather than reloading silently: the user
     * may have unsaved edits of their own, and discarding them without asking
     * would be the worse failure.
     */
    val externallyChanged: StateFlow<Set<String>> = _externallyChanged.asStateFlow()

    fun requestOpen(path: String, line: Int? = null) {
        openRequests.trySend(OpenRequest(path, line))
    }

    /**
     * Open [path] and wait for the editor's answer: null once it is open, or
     * why it is not.
     *
     * Bounded, because there may be no editor to answer: its ViewModel lives
     * with the activity, and the agent keeps running in its service after the
     * activity is gone. The request still waits in the channel for the next
     * editor, but the agent is not told the user can see it.
     */
    suspend fun open(path: String, line: Int?): String? {
        val outcome = CompletableDeferred<String?>()
        if (openRequests.trySend(OpenRequest(path, line, outcome)).isFailure) {
            return "the editor did not take the request"
        }
        val answer = withTimeoutOrNull(OPEN_TIMEOUT_MS) { Answer(outcome.await()) }
            ?: return "the editor did not answer, so it is probably not on screen"
        return answer.refusal
    }

    /** Wraps the editor's answer, whose "opened" is itself a null. */
    private class Answer(val refusal: String?)

    private const val OPEN_TIMEOUT_MS = 5_000L

    fun onOpened(path: String) {
        _openFiles.value = (listOf(path) + _openFiles.value.filterNot { it == path }).take(MAX_TABS)
        _activeFile.value = path
    }

    fun onClosed(path: String) {
        _openFiles.value = _openFiles.value.filterNot { it == path }
        if (_activeFile.value == path) _activeFile.value = _openFiles.value.firstOrNull()
        _externallyChanged.value = _externallyChanged.value - path
    }

    fun onFocused(path: String) {
        _activeFile.value = path
        _openFiles.value = listOf(path) + _openFiles.value.filterNot { it == path }
    }

    /** Called with the paths a tool touched. */
    fun onFilesChangedOnDisk(paths: Collection<String>) {
        val open = _openFiles.value.toSet()
        val affected = paths.filter { it in open }
        if (affected.isNotEmpty()) {
            _externallyChanged.value = _externallyChanged.value + affected
        }
        requestTreeRefresh()
    }

    private val _treeRefresh = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * "The project tree may be stale."
     *
     * Fired for tool edits, and at the end of every turn: a shell command can
     * create or delete any number of files without a tool reporting a path,
     * and the end of the turn is the cheapest point that catches all of them.
     */
    val treeRefresh: SharedFlow<Unit> = _treeRefresh.asSharedFlow()

    fun requestTreeRefresh() {
        _treeRefresh.tryEmit(Unit)
    }

    fun onReloaded(path: String) {
        _externallyChanged.value = _externallyChanged.value - path
    }

    /**
     * Tab cap.
     *
     * Not a UI nicety: every open tab is a syntax-highlighted buffer held in
     * memory, and the open-file list goes into the system prompt on every
     * request. Twelve is already more than fits in a phone's tab strip.
     */
    private const val MAX_TABS = 12
}
