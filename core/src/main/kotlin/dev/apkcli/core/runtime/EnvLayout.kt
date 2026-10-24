package dev.apkcli.core.runtime

import android.content.Context
import java.io.File

/**
 * Where everything lives.
 *
 * The explicit goal is that the whole environment is three directories under
 * the app's private storage, not scattered across app-specific external dirs,
 * media stores, cache dirs that the system can evict mid-build, and
 * `/sdcard/Android/data`. On a desktop IDE you know where your project is; on
 * Android that is normally the first thing you lose.
 *
 * ```
 * /data/data/dev.apkcli/files/
 * ├── home/      $HOME     — projects, dotfiles, anything the user owns
 * ├── usr/       $PREFIX   — the toolchain: bin, lib, etc, share, tmp
 * └── var/       state     — database, logs, index, caches. Disposable.
 * ```
 *
 * The split is not cosmetic:
 *   * `home` is what gets backed up and exported. Nothing in it is generated.
 *   * `usr` is reconstructible: [Bootstrap] rebuilds its links from the APK,
 *     and on the power build installed packages can be installed again.
 *     Deleting it is a supported repair action.
 *   * `var` can be deleted to reclaim space; the app rebuilds it. It is kept
 *     out of `home` precisely so "clear caches" can never touch user code.
 *
 * Nothing is placed in `context.cacheDir`: the system deletes it under storage
 * pressure, which would silently destroy a conversation database.
 */
class EnvLayout(context: Context) {

    /** `/data/data/<pkg>/files` */
    val root: File = context.filesDir

    val home: File = File(root, "home")
    val prefix: File = File(root, "usr")
    val state: File = File(root, "var")

    // --- $PREFIX ------------------------------------------------------------
    /** Symlinks into [nativeLibDir]. The only exec-capable PATH entry we own. */
    val bin: File = File(prefix, "bin")
    val lib: File = File(prefix, "lib")
    val etc: File = File(prefix, "etc")
    val share: File = File(prefix, "share")
    val tmp: File = File(prefix, "tmp")

    // --- $HOME --------------------------------------------------------------
    /** Parent of all user projects. A "workspace" is a direct child of this. */
    val projects: File = File(home, "projects")

    // --- state --------------------------------------------------------------
    val db: File = File(state, "db")
    val logs: File = File(state, "log")
    val index: File = File(state, "index")
    val runtimeDir: File = File(state, "run")

    val conversationDb: File = File(db, "conversations.sqlite")

    /**
     * `/data/app/~~<hash>/<pkg>-<hash>/lib/<arch>`
     *
     * Read-only, populated by the installer, and — critically — labelled
     * `apk_data_file`, which permits `execute_no_trans`. This is the only
     * directory from which a non-root app on `targetSdk >= 29` can exec.
     *
     * The path contains install-specific hashes and therefore **changes on
     * every app update**. Nothing may cache it across versions; [Bootstrap]
     * re-points all of [bin] whenever it moves.
     */
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    /** Bootstrap stamp: records what was built and against which install. */
    val stamp: File = File(state, "bootstrap.json")

    val allRoots: List<File> get() = listOf(home, prefix, state)

    fun ensureDirectories() {
        listOf(
            home, projects,
            prefix, bin, lib, etc, share, tmp,
            state, db, logs, index, runtimeDir,
        ).forEach { it.mkdirs() }
    }

    /**
     * Resolve a user- or model-supplied path against a workspace, refusing
     * anything that escapes the sandbox.
     *
     * Checks the *canonical* path, so `..` segments and symlinks pointing out
     * of the tree are both caught. A model that writes
     * `../../../../data/data/com.bank/x` gets a refusal, not a surprise.
     */
    fun resolveInside(workspace: File, candidate: String): File? {
        val target = if (candidate.startsWith("/")) File(candidate) else File(workspace, candidate)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val allowed = allRoots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return if (allowed.any { canonical.path == it.path || canonical.path.startsWith(it.path + File.separator) }) {
            canonical
        } else {
            null
        }
    }

    fun isInsideSandbox(file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return allRoots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .any { canonical.path == it.path || canonical.path.startsWith(it.path + File.separator) }
    }

    /** Display form: `~/projects/app` instead of the full /data/data path. */
    fun shorten(file: File): String {
        val path = file.absolutePath
        return when {
            path.startsWith(home.absolutePath) -> "~" + path.removePrefix(home.absolutePath)
            path.startsWith(prefix.absolutePath) -> "\$PREFIX" + path.removePrefix(prefix.absolutePath)
            path.startsWith(state.absolutePath) -> "\$STATE" + path.removePrefix(state.absolutePath)
            else -> path
        }
    }

    /** Bytes available on the filesystem backing the environment. */
    fun freeBytes(): Long = runCatching { root.usableSpace }.getOrDefault(0L)

    fun usedBytes(): Long = allRoots.sumOf { dirSize(it) }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        dir.walkTopDown()
            .onEnter { !it.isSymlinkLike() }
            .forEach { if (it.isFile) total += it.length() }
        return total
    }

    /**
     * `isSymlink` is not in the public API at our minSdk, and following the
     * `bin` symlink farm during a size walk would count the entire APK's
     * native libraries repeatedly.
     */
    private fun File.isSymlinkLike(): Boolean =
        runCatching { canonicalFile.path != absoluteFile.path }.getOrDefault(false)
}
