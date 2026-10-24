package dev.apkcli.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.apkcli.R
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.ui.EditorBus
import dev.apkcli.ui.text.UiText
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Open files.
 *
 * Each buffer is a sora [Content], held here rather than in the view: Content
 * carries the undo history, so switching tabs, rotating, or turning the split
 * off and on keeps undo working. The single CodeEditor view is pointed at
 * whichever buffer is active.
 *
 * Unsaved state is "document version differs from the version last written",
 * which sora bumps on every edit. Undoing back to the saved text still counts
 * as modified; that is what most editors do, and it errs on the safe side.
 *
 * When the agent changes a file that is open: a clean buffer reloads silently,
 * a modified one keeps the user's text and shows a banner asking which to keep
 * — silently discarding either side's work would be the worse failure.
 */
class EditorViewModel(private val env: EnvLayout) : ViewModel() {

    private class Buffer(
        val path: String,
        val content: Content,
        var savedVersion: Long,
        var cursorLine: Int = 0,
        var cursorColumn: Int = 0,
    ) {
        val dirty: Boolean get() = content.documentVersion != savedVersion
    }

    data class Tab(
        val path: String,
        val name: String,
        val dirty: Boolean,
        val changedOnDisk: Boolean,
    )

    /** Scroll the active buffer to a line, once. */
    data class Jump(val path: String, val line: Int, val serial: Long)

    data class UiState(
        val tabs: List<Tab> = emptyList(),
        val active: String? = null,
        val message: UiText? = null,
        val jump: Jump? = null,
        /** Bumped on every edit, so undo/redo availability recomposes. */
        val editSerial: Long = 0,
        val wordWrap: Boolean = false,
    ) {
        val activeTab: Tab? get() = tabs.firstOrNull { it.path == active }
    }

    private val buffers = LinkedHashMap<String, Buffer>()
    private var jumpSerial = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _reveals = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A file was opened on someone else's behalf (the agent, the changes list): bring it on screen. */
    val reveals: SharedFlow<String> = _reveals.asSharedFlow()

    init {
        viewModelScope.launch {
            EditorBus.opens.collect { request ->
                open(request.path, request.line, reveal = true, outcome = request.outcome)
            }
        }
        viewModelScope.launch {
            EditorBus.externallyChanged.collect { changed -> onExternallyChanged(changed) }
        }
    }

    fun content(path: String): Content? = buffers[path]?.content

    /**
     * @param outcome completed with null once the file is open, or with why it
     *   was refused, for an agent waiting to know whether the user can see it.
     */
    fun open(
        path: String,
        line: Int? = null,
        reveal: Boolean = false,
        outcome: CompletableDeferred<String?>? = null,
    ) {
        val existing = buffers[path]
        if (existing != null) {
            activate(path, line)
            if (reveal) _reveals.tryEmit(path)
            outcome?.complete(null)
            return
        }
        viewModelScope.launch {
            val file = File(path)
            val loaded = withContext(Dispatchers.IO) { load(file) }
            when (loaded) {
                is Load.Ok -> {
                    if (buffers[path] == null) {
                        buffers[path] = Buffer(path, loaded.content, loaded.content.documentVersion)
                        EditorBus.onOpened(path)
                    }
                    activate(path, line)
                    if (reveal) _reveals.tryEmit(path)
                    outcome?.complete(null)
                }
                is Load.Refused -> {
                    _state.update { it.copy(message = loaded.reason) }
                    outcome?.complete(loaded.forModel)
                }
            }
        }
    }

    fun activate(path: String, line: Int? = null) {
        if (path !in buffers) return
        EditorBus.onFocused(path)
        _state.update { current ->
            current.copy(
                active = path,
                jump = line?.let { Jump(path, it, ++jumpSerial) } ?: current.jump,
                tabs = tabs(),
            )
        }
    }

    fun consumeJump(serial: Long) = _state.update { if (it.jump?.serial == serial) it.copy(jump = null) else it }

    fun rememberCursor(path: String, line: Int, column: Int) {
        buffers[path]?.let {
            it.cursorLine = line
            it.cursorColumn = column
        }
    }

    fun cursorOf(path: String): Pair<Int, Int> = buffers[path]?.let { it.cursorLine to it.cursorColumn } ?: (0 to 0)

    fun isDirty(path: String): Boolean = buffers[path]?.dirty == true

    /** Close without saving. The UI asks first when the buffer is modified. */
    fun close(path: String) {
        val index = buffers.keys.indexOf(path)
        if (buffers.remove(path) == null) return
        EditorBus.onClosed(path)
        _state.update { current ->
            val remaining = buffers.keys.toList()
            val nextActive = if (current.active == path) {
                remaining.getOrNull(index.coerceAtMost(remaining.lastIndex)) ?: remaining.lastOrNull()
            } else {
                current.active
            }
            nextActive?.let(EditorBus::onFocused)
            current.copy(active = nextActive, tabs = tabs())
        }
    }

    /**
     * Close every tab under [root], or every tab: the project moved away
     * under them. The caller makes sure none is modified first.
     */
    fun closeAll(root: File? = null) {
        val prefix = root?.absolutePath?.let { "$it/" }
        buffers.keys.filter { prefix == null || it.startsWith(prefix) }.forEach(::close)
    }

    fun onEdited(path: String) {
        if (path !in buffers) return
        _state.update { it.copy(tabs = tabs(), editSerial = it.editSerial + 1) }
    }

    fun toggleWordWrap() = _state.update { it.copy(wordWrap = !it.wordWrap) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun save(path: String) {
        val buffer = buffers[path] ?: return
        // Snapshot on the main thread, where the editor writes; write off it.
        val text = buffer.content.toString()
        val version = buffer.content.documentVersion
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    check(env.isInsideSandbox(file))
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                }.isSuccess
            }
            if (ok) {
                buffer.savedVersion = version
                EditorBus.onReloaded(path)
                EditorBus.requestTreeRefresh()
                _state.update { it.copy(tabs = tabs()) }
            } else {
                _state.update { it.copy(message = UiText.res(R.string.editor_save_failed, File(path).name)) }
            }
        }
    }

    fun saveAll() = buffers.values.filter { it.dirty }.forEach { save(it.path) }

    /** Replace the buffer with what is on disk, discarding unsaved edits. */
    fun reload(path: String) {
        viewModelScope.launch {
            when (val loaded = withContext(Dispatchers.IO) { load(File(path)) }) {
                is Load.Ok -> {
                    val old = buffers[path] ?: return@launch
                    buffers[path] = Buffer(path, loaded.content, loaded.content.documentVersion, old.cursorLine, old.cursorColumn)
                    EditorBus.onReloaded(path)
                    _state.update { it.copy(tabs = tabs(), editSerial = it.editSerial + 1) }
                }
                is Load.Refused -> {
                    // Gone from disk (deleted, or reverted out of existence).
                    if (!File(path).exists()) close(path) else _state.update { it.copy(message = loaded.reason) }
                }
            }
        }
    }

    /** Keep the buffer's text; the next save overwrites the disk version. */
    fun keepMine(path: String) {
        EditorBus.onReloaded(path)
        _state.update { it.copy(tabs = tabs()) }
    }

    private fun onExternallyChanged(changed: Set<String>) {
        changed.forEach { path ->
            val buffer = buffers[path] ?: return@forEach
            if (!buffer.dirty) reload(path)
        }
        _state.update { it.copy(tabs = tabs()) }
    }

    private fun tabs(): List<Tab> {
        val changed = EditorBus.externallyChanged.value
        return buffers.values.map { Tab(it.path, File(it.path).name, it.dirty, it.path in changed) }
    }

    private sealed interface Load {
        class Ok(val content: Content) : Load

        /** [reason] is shown to the user; [forModel] goes back to the agent that asked. */
        class Refused(val reason: UiText, val forModel: String) : Load
    }

    private fun load(file: File): Load {
        if (!env.isInsideSandbox(file)) {
            return Load.Refused(UiText.res(R.string.editor_outside_sandbox), "it is outside the sandbox")
        }
        if (!file.isFile) {
            return Load.Refused(UiText.res(R.string.editor_not_found, file.name), "it is not an existing file")
        }
        if (file.length() > MAX_BYTES) {
            return Load.Refused(
                UiText.res(R.string.editor_too_large, file.name, MAX_BYTES / (1024 * 1024)),
                "it is over the editor's ${MAX_BYTES / (1024 * 1024)} MB limit",
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return Load.Refused(UiText.res(R.string.editor_unreadable, file.name), "it could not be read")
        // A NUL in the first few KB is what git uses to call a file binary.
        if (bytes.take(8_000).any { it == 0.toByte() }) {
            return Load.Refused(UiText.res(R.string.editor_binary, file.name), "it is a binary file")
        }
        return Load.Ok(Content(String(bytes, Charsets.UTF_8)))
    }

    override fun onCleared() {
        buffers.keys.toList().forEach(EditorBus::onClosed)
        super.onCleared()
    }

    companion object {
        /** sora handles larger, but a phone's heap and the analyzer do not need to. */
        private const val MAX_BYTES = 4L * 1024 * 1024

        fun factory(env: EnvLayout): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(env) as T
            }
    }
}
