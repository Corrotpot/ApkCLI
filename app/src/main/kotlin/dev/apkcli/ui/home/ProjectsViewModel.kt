package dev.apkcli.ui.home

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.apkcli.core.AgentRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The projects under `~/projects`, as the main menu and the project list
 * show them: most recently worked on first.
 *
 * "Recently" is the later of the folder's own change time and the last
 * session about it, because a session that only read files changes nothing
 * on disk and is still the work the user remembers.
 */
class ProjectsViewModel(
    private val runtime: AgentRuntime,
    private val appContext: Context,
) : ViewModel() {

    data class Project(
        val dir: File,
        val name: String,
        /** Epoch millis of the last activity in it. */
        val lastActive: Long,
        /** Files in it, outside build output and dependency folders; capped at [FILE_COUNT_CAP]. */
        val files: Int,
    )

    data class UiState(
        val loaded: Boolean = false,
        val projects: List<Project> = emptyList(),
        /** A project being written to an archive, by name. */
        val exporting: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val sessions = runCatching { runtime.store.listSessions(limit = 500) }.getOrDefault(emptyList())
            val lastSession = sessions.groupBy { it.workspace }.mapValues { (_, list) -> list.maxOf { it.updatedAt } }
            val projects = withContext(Dispatchers.IO) {
                val root = runtime.layout.projects.apply { mkdirs() }
                root.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.map { dir ->
                        Project(
                            dir = dir,
                            name = dir.name,
                            lastActive = maxOf(dir.lastModified(), lastSession[dir.absolutePath] ?: 0L),
                            files = countFiles(dir),
                        )
                    }
                    ?.sortedByDescending { it.lastActive }
                    .orEmpty()
            }
            _state.update { it.copy(loaded = true, projects = projects) }
        }
    }

    /**
     * Rename [project] to [name]. Sessions follow it, so its history is
     * still its own afterwards. Returns the new folder, or null when the name
     * is taken or the rename failed.
     */
    suspend fun rename(project: Project, name: String): File? {
        val clean = name.trim()
        if (!validName(clean) || clean == project.name) return null
        val target = File(project.dir.parentFile, clean)
        val moved = withContext(Dispatchers.IO) {
            !target.exists() && project.dir.renameTo(target)
        }
        if (!moved) return null
        runCatching {
            runtime.store.listSessions(limit = 500, includeArchived = true)
                .filter { it.workspace == project.dir.absolutePath }
                .forEach { runtime.store.updateSession(it.id, workspace = target.absolutePath) }
        }.onFailure { Log.w(TAG, "sessions of ${project.name} were not moved along", it) }
        refresh()
        return target
    }

    /** Delete [project]'s folder and its sessions. There is no undo; the UI asks first. */
    fun delete(project: Project) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { project.dir.deleteRecursively() }
            runCatching {
                runtime.store.listSessions(limit = 500, includeArchived = true)
                    .filter { it.workspace == project.dir.absolutePath }
                    .forEach { runtime.store.deleteSession(it.id) }
            }.onFailure { Log.w(TAG, "sessions of ${project.name} were not deleted", it) }
            refresh()
        }
    }

    /**
     * Write [project] as a zip to [target], a document the user picked.
     * Dependency and build folders stay out: they are re-created, and they
     * are most of the size.
     */
    fun export(project: Project, target: Uri, onDone: (Boolean) -> Unit) {
        if (_state.value.exporting != null) return
        _state.update { it.copy(exporting = project.name) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(target)?.use { out ->
                        ZipOutputStream(out.buffered()).use { zip ->
                            project.dir.walkTopDown()
                                .onEnter { it == project.dir || it.name !in SKIPPED }
                                .filter { it.isFile }
                                .forEach { file ->
                                    zip.putNextEntry(ZipEntry("${project.name}/${file.relativeTo(project.dir).invariantSeparatorsPath}"))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                        }
                    } ?: error("no stream for $target")
                }.onFailure { Log.w(TAG, "export of ${project.name} failed", it) }.isSuccess
            }
            _state.update { it.copy(exporting = null) }
            onDone(ok)
        }
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        dir.walkTopDown()
            .onEnter { it == dir || (it.name !in SKIPPED && !it.name.startsWith(".")) }
            .forEach { file ->
                if (file.isFile) count++
                if (count >= FILE_COUNT_CAP) return count
            }
        return count
    }

    companion object {
        private const val TAG = "ProjectsViewModel"

        /** Counting stops here: past it the number is "a lot" either way. */
        const val FILE_COUNT_CAP = 9_999

        /** Folders that are output or downloaded, not the project. */
        private val SKIPPED = setOf(
            ".git", "node_modules", "build", "target", "dist", ".gradle", ".venv", "venv",
            "__pycache__", ".idea", ".next", ".cache",
        )

        /** Letters, digits, `.`, `_` and `-`; not a path, not hidden. */
        fun validName(name: String): Boolean =
            name.isNotEmpty() && name.length <= 100 && !name.startsWith(".") &&
                name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

        fun factory(runtime: AgentRuntime, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ProjectsViewModel(runtime, context.applicationContext) as T
            }
    }
}
