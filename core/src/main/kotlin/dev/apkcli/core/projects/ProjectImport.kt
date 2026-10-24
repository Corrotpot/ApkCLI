package dev.apkcli.core.projects

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import dev.apkcli.core.llm.Http
import dev.apkcli.core.pkg.TarEntry
import dev.apkcli.core.pkg.TarReader
import dev.apkcli.core.runtime.EnvLayout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Bring an existing project in: a public GitHub repository, or a folder on
 * the device's shared storage.
 *
 * Either way it becomes a copy under `~/projects/<name>`, because that is
 * where everything works: the sandbox resolves paths there, the tools may
 * write there, and programs can run there. Shared storage is none of those —
 * it needs a storage permission, and nothing on it can be executed.
 *
 * GitHub without git: the repository's tarball from codeload, the same
 * snapshot GitHub's "Download ZIP" gives, so no git binary, no credentials
 * and one request. Public repositories only. Extraction checks every path
 * against the target directory — a crafted archive cannot write outside it
 * or plant a symlink that points out — and is capped in size and count.
 *
 * Both write to a hidden directory first and rename it into place at the end,
 * so a failed or cancelled import leaves nothing half-made in the project list.
 */
class ProjectImport(private val layout: EnvLayout) {

    /** What an import is doing, for a progress line. */
    sealed interface Progress {
        data class Downloading(val bytes: Long) : Progress
        data class Writing(val files: Int, val bytes: Long) : Progress
    }

    /** Why an import failed: [reason] for the UI's own words, the message for detail. */
    class ImportException(val reason: Reason, message: String) : IOException(message) {
        enum class Reason { NOT_FOUND, TOO_LARGE, UNREADABLE, NETWORK, FAILED }
    }

    /** `owner/repo` and, if one was named, the branch, tag or commit. */
    data class GitHubSource(val owner: String, val repo: String, val ref: String? = null) {
        val display: String get() = "$owner/$repo" + (ref?.let { "@$it" } ?: "")
    }

    /** Download and unpack [source] into a new project; returns its directory. */
    suspend fun fromGitHub(source: GitHubSource, onProgress: (Progress) -> Unit): File = withContext(Dispatchers.IO) {
        val url = "https://codeload.github.com/${source.owner}/${source.repo}/tar.gz/${source.ref ?: "HEAD"}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val call = Http.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
            .newCall(request)
        // A cancelled import stops the download too: the call is cancelled
        // from another thread, since this one may be blocked reading it.
        // Undispatched, so it is waiting before the download starts: a
        // cancellation that came first would otherwise never run its finally.
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                when {
                    response.code == 404 -> throw ImportException(
                        ImportException.Reason.NOT_FOUND,
                        "${source.display}: HTTP 404",
                    )
                    !response.isSuccessful -> throw ImportException(
                        ImportException.Reason.NETWORK,
                        "${source.display}: HTTP ${response.code}",
                    )
                }
                val body = response.body
                    ?: throw ImportException(ImportException.Reason.NETWORK, "${source.display}: empty response")
                val counted = CountingInputStream(body.byteStream()) { bytes -> onProgress(Progress.Downloading(bytes)) }
                into(source.repo) { staging ->
                    untar(TarReader(GZIPInputStream(counted, 64 * 1024)), staging, onProgress)
                }
            }
        } catch (e: IOException) {
            // Cancelled: the read failed because the call was, so end as cancelled.
            ensureActive()
            throw e as? ImportException
                ?: ImportException(ImportException.Reason.NETWORK, e.message ?: e.javaClass.simpleName)
        } finally {
            canceller.cancel()
        }
    }

    /** Copy the folder the user picked (a SAF tree) into a new project; returns its directory. */
    suspend fun fromTree(context: Context, tree: Uri, onProgress: (Progress) -> Unit): File = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw ImportException(ImportException.Reason.UNREADABLE, tree.toString())
        val name = root.name?.takeIf { it.isNotBlank() } ?: "project"
        into(name) { staging ->
            val counter = Counter(onProgress)
            copyTree(context, root, staging, counter, depth = 0)
        }
    }

    /* ---------------------------------------------------------------- tar */

    private suspend fun untar(tar: TarReader, staging: File, onProgress: (Progress) -> Unit) {
        val counter = Counter(onProgress)
        val root = staging.canonicalFile
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = tar.next() ?: break
            // GitHub puts everything under one "<repo>-<commit>/" directory.
            val relative = entry.name.removePrefix("./").substringAfter('/', "").trimEnd('/')
            if (relative.isEmpty()) continue
            val target = safeTarget(root, relative) ?: continue
            when (entry.type) {
                TarEntry.Type.DIRECTORY -> target.mkdirs()
                TarEntry.Type.FILE -> {
                    counter.add(entry.size)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> tar.data().copyTo(out, 64 * 1024) }
                    if (entry.mode and 0b001_000_000 != 0) target.setExecutable(true, false)
                }
                TarEntry.Type.SYMLINK -> symlink(root, target, entry.linkName)
                TarEntry.Type.HARDLINK -> {
                    // The archive path of a file already written.
                    val original = entry.linkName.removePrefix("./").substringAfter('/', "")
                    val source = safeTarget(root, original)
                    if (source != null && source.isFile) {
                        target.parentFile?.mkdirs()
                        source.copyTo(target, overwrite = true)
                    }
                }
                TarEntry.Type.OTHER -> Unit
            }
        }
    }

    /** [relative] inside [root], or null when it would land outside it. */
    private fun safeTarget(root: File, relative: String): File? {
        if (relative.startsWith('/') || relative.contains('\u0000')) return null
        if (relative.split('/').any { it == ".." }) return null
        val target = File(root, relative)
        val resolved = runCatching { target.canonicalPath }.getOrNull() ?: return null
        return target.takeIf { resolved == root.path || resolved.startsWith(root.path + File.separator) }
    }

    /** A relative link that stays inside the project; anything else is left out. */
    private fun symlink(root: File, target: File, linkName: String) {
        if (linkName.isEmpty() || linkName.startsWith('/')) return
        val pointsAt = Paths.get(target.parent ?: return, linkName).normalize().toString()
        if (pointsAt != root.path && !pointsAt.startsWith(root.path + File.separator)) return
        target.parentFile?.mkdirs()
        runCatching { Os.symlink(linkName, target.path) }
    }

    /* ---------------------------------------------------------------- SAF */

    private suspend fun copyTree(context: Context, dir: DocumentFile, into: File, counter: Counter, depth: Int) {
        if (depth > MAX_DEPTH) throw ImportException(ImportException.Reason.TOO_LARGE, "nested deeper than $MAX_DEPTH levels")
        into.mkdirs()
        for (child in dir.listFiles()) {
            currentCoroutineContext().ensureActive()
            val name = child.name ?: continue
            if (name == "." || name == ".." || name.contains('/')) continue
            val target = File(into, name)
            if (child.isDirectory) {
                copyTree(context, child, target, counter, depth + 1)
            } else if (child.isFile) {
                counter.add(child.length())
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                } ?: throw ImportException(ImportException.Reason.UNREADABLE, name)
            }
        }
    }

    /* ------------------------------------------------------------- common */

    /**
     * Run [fill] against a hidden staging directory, then move it to
     * `~/projects/<name>`. On any failure, including cancellation, the
     * staging directory is removed.
     */
    private suspend fun into(name: String, fill: suspend (File) -> Unit): File {
        val projects = layout.projects.also { it.mkdirs() }
        val staging = File(projects, ".import-" + UUID.randomUUID().toString().take(8))
        try {
            staging.mkdirs()
            fill(staging)
            val target = File(projects, uniqueName(name))
            if (!staging.renameTo(target)) throw ImportException(ImportException.Reason.FAILED, "could not rename into ${target.name}")
            return target
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** [wanted] made safe as a directory name, with a number added if it is taken. */
    private fun uniqueName(wanted: String): String {
        val base = wanted.trim().replace(Regex("""[/\\:\u0000]"""), "-").trimStart('.').ifBlank { "project" }.take(80)
        if (!File(layout.projects, base).exists()) return base
        var n = 2
        while (File(layout.projects, "$base-$n").exists()) n++
        return "$base-$n"
    }

    /** Files and bytes written, against the caps; reports progress every so often. */
    private class Counter(private val onProgress: (Progress) -> Unit) {
        private var files = 0
        private var bytes = 0L
        private var lastReport = 0L

        fun add(size: Long) {
            files++
            bytes += size.coerceAtLeast(0)
            if (files > MAX_FILES) throw ImportException(ImportException.Reason.TOO_LARGE, "more than $MAX_FILES files")
            if (bytes > MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE, "more than ${MAX_BYTES / (1024 * 1024)} MB")
            val now = System.currentTimeMillis()
            if (now - lastReport >= REPORT_INTERVAL_MS) {
                lastReport = now
                onProgress(Progress.Writing(files, bytes))
            }
        }
    }

    private class CountingInputStream(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counted(it.toLong()) }

        private fun counted(n: Long) {
            count += n
            if (count > MAX_DOWNLOAD_BYTES) {
                throw ImportException(ImportException.Reason.TOO_LARGE, "download over ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB")
            }
            val now = System.currentTimeMillis()
            if (now - lastReport >= REPORT_INTERVAL_MS) {
                lastReport = now
                onCount(count)
            }
        }
    }

    companion object {
        private const val USER_AGENT = "ApkCLI-import/1 (Android)"
        private const val MAX_FILES = 200_000
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 1L * 1024 * 1024 * 1024
        private const val MAX_DEPTH = 64
        private const val REPORT_INTERVAL_MS = 250L

        private val OWNER = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})""")
        private val REPO = Regex("""[A-Za-z0-9._-]{1,100}""")

        /**
         * `owner/repo`, `owner/repo@branch`, `https://github.com/owner/repo`,
         * `…/tree/<branch>`, `…/commit/<sha>`, `git@github.com:owner/repo.git`.
         * Null when it is none of these.
         */
        fun parseGitHub(input: String): GitHubSource? {
            var text = input.trim().removeSuffix("/")
            if (text.isEmpty()) return null
            var ref: String? = null
            text = text
                .removePrefix("git@github.com:")
                .replace(Regex("""^(?:https?://)?(?:www\.)?github\.com/"""), "")
            // owner/repo@ref
            if ('@' in text && !text.contains("://")) {
                ref = text.substringAfter('@').ifBlank { null }
                text = text.substringBefore('@')
            }
            val parts = text.split('/')
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (!OWNER.matches(owner) || !REPO.matches(repo) || repo == "." || repo == "..") return null
            // Any other page of the repository (issues, a file) stands for the
            // repository itself; only a branch or commit page names a ref.
            if (parts.size >= 4 && (parts[2] == "tree" || parts[2] == "commit")) {
                ref = parts.drop(3).joinToString("/").ifBlank { null }
            }
            return GitHubSource(owner, repo, ref?.takeIf { it.matches(Regex("""[A-Za-z0-9._/\-]{1,200}""")) && ".." !in it })
        }
    }
}
