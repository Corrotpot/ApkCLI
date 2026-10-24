package dev.apkcli.ui.terminal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.apkcli.R
import dev.apkcli.core.AgentRuntime
import dev.apkcli.core.runtime.PtyManager
import dev.apkcli.core.runtime.TerminalSessions
import dev.apkcli.ui.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Terminal tab's state: design/prototype/app.js terminalTab().
 *
 * Deliberately thin: the sessions themselves live in [TerminalSessions],
 * which belongs to the process, so this holds only what is on screen — the
 * project's sessions, which one is picked, what is typed. Leaving the tab,
 * or losing this ViewModel, does not disturb a running build.
 *
 * One kind of terminal, as the prototype has it: the user's shells, what the
 * agent left running, and the chat's transcript of the agent's own commands
 * are all "Shell #N" in one row, take the same input and the same keys. A
 * line typed into a transcript runs the way the agent's commands do, one at
 * a time ([dev.apkcli.core.runtime.TranscriptInput]).
 *
 * Nothing here touches the kernel on the main thread. Session changes arrive
 * from the registry's change counter; what each shell is running is fetched
 * on an IO thread, and only while the tab is on screen — see [watch].
 */
class TerminalViewModel(private val runtime: AgentRuntime) : ViewModel() {

    data class SessionRow(
        val id: String,
        /** "Shell #N": the lowest number free in the project when it first showed up. */
        val number: Int,
        /** A chat's transcript of the agent's commands; typed lines run one at a time. */
        val transcript: Boolean,
        /** There is something to type to: the shell or the program has not ended. A transcript always has. */
        val alive: Boolean,
        /** Something is running in it now: the pulsing dot. */
        val busy: Boolean,
        val exitCode: Int?,
    )

    data class UiState(
        /** The project's sessions, oldest first. */
        val sessions: List<SessionRow> = emptyList(),
        val activeId: String? = null,
        val input: String = "",
        val message: UiText? = null,
        /** No native core: there is no PTY to run a shell on. */
        val available: Boolean = true,
        val starting: Boolean = false,
        /** The project [sessions] were listed for; null until the tab says. */
        val workspace: String? = null,
        /**
         * Whether the tab may open the first shell itself. False once the
         * user has closed every one: re-opening what they just closed is
         * not a tab that is never empty, it is a button that does nothing.
         */
        val autoOpen: Boolean = true,
        /** Lines typed into transcripts, newest last, for ↑ and ↓. */
        val history: List<String> = emptyList(),
        /**
         * Bumped when [input] is set from here rather than typed — a send,
         * ↑ and ↓, Esc — so the field takes it, cursor at the end, without
         * fighting what is being typed.
         */
        val inputSerial: Long = 0,
    ) {
        val active: SessionRow? get() = sessions.firstOrNull { it.id == activeId }

        /** Something runs somewhere in the project: what the bottom bar marks. */
        val anyBusy: Boolean get() = sessions.any { it.busy }
    }

    private val _state = MutableStateFlow(UiState(available = runtime.terminals != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val registry: TerminalSessions? get() = runtime.terminals

    private var workspace: File? = null

    /** Transcripts with a typed line on its way to starting. */
    private val typing = mutableSetOf<String>()

    /** Where ↑ and ↓ are in [UiState.history]; its size means "the line being typed". */
    private var historyIndex = 0

    fun terminal(id: String?): TerminalSessions.Terminal? = id?.let { registry?.get(it) }

    init {
        val registry = registry
        if (registry != null) {
            viewModelScope.launch {
                registry.changes.collect { refresh() }
            }
        }
    }

    /** The project whose sessions the tab shows. */
    fun setWorkspace(dir: File?) {
        val changed = dir?.absolutePath != workspace?.absolutePath
        workspace = dir
        if (changed) _state.update { it.copy(autoOpen = true) }
        refresh()
    }

    /**
     * Keep the busy dots current while the tab is visible.
     *
     * What runs in a shell, and where, changes without any output, so it has
     * to be asked for. The tab calls this from its composition; leaving the
     * tab cancels it, so a backgrounded app is not woken twice a second for a
     * screen nobody sees.
     */
    suspend fun watch() {
        val registry = registry ?: return
        while (true) {
            val ids = _state.value.sessions.map { it.id }
            withContext(Dispatchers.IO) { ids.forEach { registry.status(it) } }
            refresh()
            delay(POLL_MS)
        }
    }

    private fun refresh() {
        val registry = registry ?: return
        val root = workspace?.absolutePath
        val mine = if (root == null) {
            emptyList()
        } else {
            registry.sessions.value.filter { it.workingDir == root || it.workingDir.startsWith("$root/") }
        }
        // Oldest first, each named with the lowest number free in the project,
        // as the prototype's newShell() does; kept on the session for good.
        // Shells and the agent's transcripts are numbered apart, because they
        // are named apart: "Shell #2" beside "Agent" rather than two things
        // called a shell, one of which the agent inexplicably would not use.
        for (terminal in mine) {
            if (terminal.number > 0) continue
            val kind = mine.filter { it.isTranscript == terminal.isTranscript }
            var n = 1
            while (kind.any { it.number == n }) n++
            terminal.number = n
        }
        val rows = mine.map { terminal ->
            val alive = terminal.isTranscript || terminal.state.value == TerminalSessions.State.RUNNING
            SessionRow(
                id = terminal.id,
                number = terminal.number,
                transcript = terminal.isTranscript,
                alive = alive,
                busy = when {
                    terminal.isTranscript -> terminal.busy.value || terminal.id in typing
                    !alive -> false
                    // Started for a command: busy until it ends.
                    terminal.command != null -> true
                    // A shell: busy while something other than the shell holds it.
                    else -> registry.lastStatus(terminal.id)?.foreground != null
                },
                exitCode = terminal.exitCode,
            )
        }
        _state.update { current ->
            val active = current.activeId?.takeIf { id -> rows.any { it.id == id } } ?: rows.lastOrNull()?.id
            current.copy(sessions = rows, activeId = active, workspace = root)
        }
    }

    /**
     * Open a shell in [workspace].
     *
     * The fork happens on an IO dispatcher: it is a blocking syscall, and on a
     * cold start it also loads the native library.
     */
    fun newSession(workspace: File?) {
        val registry = registry ?: return
        if (_state.value.starting) return
        _state.update { it.copy(starting = true, message = null, autoOpen = true) }
        val (cols, rows) = geometry ?: (80 to 24)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    registry.open(
                        workingDir = workspace ?: runtime.layout.projects,
                        cols = cols,
                        rows = rows,
                    )
                }
            }
            opened
                .onSuccess { terminal ->
                    _state.update { it.copy(starting = false, activeId = terminal.id) }
                    refresh()
                }
                .onFailure { failure ->
                    Log.w(TAG, "could not open a terminal", failure)
                    val text = when (failure) {
                        is PtyManager.NotAvailable -> UiText.raw(failure.message.orEmpty())
                        else -> UiText.res(R.string.terminal_open_failed, failure.message ?: failure.javaClass.simpleName)
                    }
                    _state.update { it.copy(starting = false, message = text) }
                }
        }
    }

    fun select(id: String) = _state.update { it.copy(activeId = id) }

    fun onInputChange(text: String) {
        historyIndex = _state.value.history.size
        _state.update { it.copy(input = text) }
    }

    /** Whether Enter would do anything now. */
    fun canSend(state: UiState = _state.value): Boolean {
        val active = state.active ?: return false
        return when {
            active.transcript -> !active.busy && state.input.isNotBlank()
            else -> active.alive
        }
    }

    /**
     * Enter. A shell gets the line and echoes it itself; nothing is printed
     * here. A transcript runs it, unless something is running there already.
     */
    fun send() {
        val current = _state.value
        val active = current.active ?: return
        if (!canSend(current)) return
        val line = current.input
        if (active.transcript) {
            runInTranscript(active.id, line)
            val history = (current.history.filter { it != line } + line).takeLast(MAX_HISTORY)
            historyIndex = history.size
            _state.update { it.copy(input = "", history = history, inputSerial = it.inputSerial + 1) }
        } else {
            terminal(active.id)?.writeLine(line)
            _state.update { it.copy(input = "", inputSerial = it.inputSerial + 1) }
        }
    }

    private fun setInput(text: String) = _state.update { it.copy(input = text, inputSerial = it.inputSerial + 1) }

    /**
     * In the runtime's scope, not this ViewModel's: a build typed here goes
     * on when the screen goes. [typing] marks the session busy until the
     * line has started and its own busy flag takes over.
     */
    private fun runInTranscript(id: String, line: String) {
        val input = runtime.transcriptInput ?: return
        typing += id
        refresh()
        val started = input.start(id, line) {
            viewModelScope.launch {
                typing -= id
                refresh()
            }
        }
        if (!started) {
            typing -= id
            refresh()
        }
    }

    /** One of the keys under the input that are not text. */
    fun key(key: TermKey) {
        val active = _state.value.active ?: return
        if (key == TermKey.CTRL_C) {
            terminal(active.id)?.interrupt()
            return
        }
        if (!active.transcript) {
            terminal(active.id)?.write(key.bytes)
            return
        }
        // A transcript has no line editor behind it: the keys work on the input.
        val history = _state.value.history
        when (key) {
            TermKey.UP -> if (history.isNotEmpty()) {
                historyIndex = (historyIndex - 1).coerceIn(0, history.lastIndex)
                setInput(history[historyIndex])
            }
            TermKey.DOWN -> if (historyIndex < history.size) {
                historyIndex++
                setInput(history.getOrNull(historyIndex).orEmpty())
            }
            TermKey.ESC -> {
                historyIndex = history.size
                setInput("")
            }
            else -> Unit
        }
    }

    /** Whether [key] does anything in [session]. */
    fun keyEnabled(key: TermKey, session: SessionRow?, state: UiState = _state.value): Boolean {
        session ?: return false
        if (!session.transcript) return session.alive
        return when (key) {
            TermKey.CTRL_C -> session.busy
            TermKey.UP -> state.history.isNotEmpty()
            TermKey.DOWN -> historyIndex < state.history.size
            TermKey.ESC -> state.input.isNotEmpty()
            TermKey.TAB, TermKey.CTRL_D -> false
        }
    }

    /**
     * Close [id], whichever it is. The prototype keeps the last one open;
     * here it can go, because a shell on a phone holds a process the user may
     * want gone, and the tab offers "+" when it is empty. Closing the last
     * one also stops the tab opening another by itself.
     */
    fun close(id: String) {
        val sessions = _state.value.sessions
        if (sessions.none { it.id == id }) return
        if (sessions.size <= 1) _state.update { it.copy(autoOpen = false) }
        registry?.close(id)
        runtime.transcriptInput?.forget(id)
        refresh()
    }

    /**
     * What the tab last measured, kept for the next shell: one opened at 80
     * columns and resized a frame later redraws its prompt straight away.
     */
    private var geometry: Pair<Int, Int>? = null

    fun resize(cols: Int, rows: Int) {
        geometry = cols to rows
        terminal(_state.value.activeId)?.resize(cols, rows)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val TAG = "TerminalViewModel"

        /**
         * How often the kernel is asked what each session is doing, while the
         * tab is visible. Twice a second reads as live for a status dot.
         */
        private const val POLL_MS = 500L

        private const val MAX_HISTORY = 100

        fun factory(runtime: AgentRuntime): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TerminalViewModel(runtime) as T
            }
    }
}

/** The keys under the terminal's input a phone keyboard has none of. */
enum class TermKey(val label: String, val bytes: ByteArray) {
    CTRL_C("Ctrl-C", byteArrayOf(0x03)),
    CTRL_D("Ctrl-D", byteArrayOf(0x04)),
    TAB("Tab", byteArrayOf(0x09)),
    UP("↑", byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())),
    DOWN("↓", byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())),
    ESC("Esc", byteArrayOf(0x1b)),
}
