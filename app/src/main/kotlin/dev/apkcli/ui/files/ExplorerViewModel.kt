package dev.apkcli.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.apkcli.R
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.ui.EditorBus
import dev.apkcli.ui.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The project tree.
 *
 * Directories are listed lazily, on first expansion, and the visible tree is
 * flattened into rows for a lazy list — a node per row, indented by depth, the
 * way an editor's explorer draws it. A project with `node_modules` or a Gradle
 * `build/` directory would otherwise be walked in full just to show its top
 * level.
 *
 * Chains of directories that contain nothing but one more directory are drawn
 * as one row, `src/main/kotlin`, as VS Code's "compact folders" does. On a
 * phone that matters more than on a desktop: Java-style package paths would
 * otherwise push every file off the right edge.
 *
 * All file system work runs on [Dispatchers.IO]; the listing cache is touched
 * only from there, under its own lock.
 */
class ExplorerViewModel(private val env: EnvLayout) : ViewModel() {

    data class Row(
        /** Absolute path of the node this row opens: the last directory of a compact chain. */
        val path: String,
        /** Display name; "a/b/c" for a compact chain. */
        val name: String,
        val depth: Int,
        val isDir: Boolean,
        val expanded: Boolean,
    )

    data class UiState(
        val root: File? = null,
        val rows: List<Row> = emptyList(),
        val selected: String? = null,
        val loading: Boolean = false,
        val message: UiText? = null,
        /**
         * The Files tab shows the tree rather than the editor. Kept here, not
         * in the tab, because the tab leaves composition whenever another
         * one is picked and should come back the way it was left.
         */
        val treeShown: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val expanded = mutableSetOf<String>()
    private val cache = HashMap<String, List<File>>()
    private val lock = Any()
    private var rebuildJob: Job? = null

    init {
        viewModelScope.launch {
            EditorBus.treeRefresh.collect { refresh() }
        }
    }

    fun setRoot(root: File?) {
        if (root?.absolutePath == _state.value.root?.absolutePath) return
        synchronized(lock) {
            expanded.clear()
            cache.clear()
        }
        _state.update { UiState(root = root) }
        rebuild()
    }

    /** Drop every cached listing and re-read what is expanded. */
    fun refresh() {
        synchronized(lock) { cache.clear() }
        rebuild()
    }

    fun toggle(row: Row) {
        if (!row.isDir) {
            _state.update { it.copy(selected = row.path) }
            return
        }
        synchronized(lock) {
            if (!expanded.remove(row.path)) expanded += row.path
        }
        _state.update { it.copy(selected = row.path) }
        rebuild()
    }

    fun select(path: String) = _state.update { it.copy(selected = path) }

    /** The tree, or the editor with the open files. */
    fun showTree(show: Boolean) = _state.update { it.copy(treeShown = show) }

    fun collapseAll() {
        synchronized(lock) { expanded.clear() }
        rebuild()
    }

    /** Expand every ancestor of [path] and select it, e.g. for the file the agent just opened. */
    fun reveal(path: String) {
        val root = _state.value.root ?: return
        var parent = File(path).parentFile
        synchronized(lock) {
            while (parent != null && parent.absolutePath.startsWith(root.absolutePath) &&
                parent.absolutePath != root.absolutePath
            ) {
                expanded += parent.absolutePath
                parent = parent.parentFile
            }
        }
        _state.update { it.copy(selected = path) }
        rebuild()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /* ------------------------------------------------------------ editing */

    /** Problem with a proposed file name, or null when it is usable. */
    fun validateName(name: String): UiText? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> UiText.res(R.string.explorer_name_empty)
            trimmed == "." || trimmed == ".." -> UiText.res(R.string.explorer_name_invalid)
            trimmed.any { it == '\u0000' || it == '\\' } -> UiText.res(R.string.explorer_name_invalid)
            trimmed.startsWith("/") || trimmed.split('/').any { it == ".." } -> UiText.res(R.string.explorer_name_invalid)
            else -> null
        }
    }

    /**
     * Create a file or directory under [parentDir].
     *
     * `a/b/c.kt` creates the intermediate directories, as typing a path into
     * VS Code's new-file box does. Calls [onCreated] with the new file, so the
     * caller can open it.
     */
    fun create(parentDir: String, name: String, directory: Boolean, onCreated: (File) -> Unit = {}) {
        if (validateName(name) != null) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val target = inside(File(parentDir, name.trim()))
                    ?: return@withContext Result.failure(IllegalArgumentException("outside"))
                runCatching {
                    check(!target.exists()) { "exists" }
                    if (directory) {
                        check(target.mkdirs()) { "mkdirs" }
                    } else {
                        target.parentFile?.mkdirs()
                        check(target.createNewFile()) { "create" }
                    }
                    target
                }
            }
            result.onSuccess { file ->
                synchronized(lock) {
                    expanded += parentDir
                    if (directory) expanded += file.absolutePath
                }
                refresh()
                _state.update { it.copy(selected = file.absolutePath) }
                onCreated(file)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        message = if (error.message == "exists") {
                            UiText.res(R.string.explorer_exists, name.trim())
                        } else {
                            UiText.res(R.string.explorer_create_failed, name.trim())
                        },
                    )
                }
            }
        }
    }

    fun rename(path: String, newName: String) {
        if (validateName(newName) != null || newName.contains('/')) return
        viewModelScope.launch {
            val source = File(path)
            val ok = withContext(Dispatchers.IO) {
                val target = inside(File(source.parentFile, newName.trim())) ?: return@withContext false
                !target.exists() && source.renameTo(target)
            }
            if (ok) {
                val target = File(source.parentFile, newName.trim()).absolutePath
                synchronized(lock) {
                    if (expanded.remove(path)) expanded += target
                }
                EditorBus.onFilesChangedOnDisk(listOf(path))
                refresh()
                _state.update { it.copy(selected = target) }
            } else {
                _state.update { it.copy(message = UiText.res(R.string.explorer_rename_failed, source.name)) }
            }
        }
    }

    /** Recursive for a directory. The UI confirms first; this does not. */
    fun delete(path: String) {
        viewModelScope.launch {
            val file = File(path)
            val ok = withContext(Dispatchers.IO) {
                inside(file) != null && file.absolutePath != _state.value.root?.absolutePath && file.deleteRecursively()
            }
            if (ok) {
                EditorBus.onFilesChangedOnDisk(listOf(path))
                refresh()
            } else {
                _state.update { it.copy(message = UiText.res(R.string.explorer_delete_failed, file.name)) }
            }
        }
    }

    /** Canonical [file] when it is inside the current project, else null. */
    private fun inside(file: File): File? {
        val root = _state.value.root?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val ok = canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
        return if (ok && env.isInsideSandbox(canonical)) canonical else null
    }

    /* ----------------------------------------------------------- listing */

    private fun rebuild() {
        val root = _state.value.root ?: return
        rebuildJob?.cancel()
        _state.update { it.copy(loading = it.rows.isEmpty()) }
        rebuildJob = viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                root.mkdirs()
                ArrayList<Row>().also { flatten(root, 0, it) }
            }
            _state.update { it.copy(rows = rows, loading = false) }
        }
    }

    private fun flatten(dir: File, depth: Int, out: MutableList<Row>) {
        if (out.size >= MAX_ROWS) return
        for (child in children(dir)) {
            if (out.size >= MAX_ROWS) return
            if (!child.isDirectory) {
                out += Row(child.absolutePath, child.name, depth, isDir = false, expanded = false)
                continue
            }
            // Fold a chain of single-directory directories into one row.
            var tail = child
            val name = StringBuilder(child.name)
            while (true) {
                val inner = children(tail)
                val only = inner.singleOrNull() ?: break
                if (!only.isDirectory) break
                tail = only
                name.append('/').append(only.name)
                if (name.length > 120) break
            }
            val open = synchronized(lock) { tail.absolutePath in expanded }
            out += Row(tail.absolutePath, name.toString(), depth, isDir = true, expanded = open)
            if (open) flatten(tail, depth + 1, out)
        }
    }

    private fun children(dir: File): List<File> {
        synchronized(lock) { cache[dir.absolutePath] }?.let { return it }
        val listed = dir.listFiles()
            ?.filter { it.name !in HIDDEN }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.take(MAX_PER_DIR)
            .orEmpty()
        synchronized(lock) { cache[dir.absolutePath] = listed }
        return listed
    }

    companion object {
        /** Never interesting in a tree, and `.git` alone can hold thousands of entries. */
        private val HIDDEN = setOf(".git", ".gradle", ".idea", ".DS_Store")

        private const val MAX_PER_DIR = 2_000
        private const val MAX_ROWS = 10_000

        fun factory(env: EnvLayout): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ExplorerViewModel(env) as T
            }
    }
}
