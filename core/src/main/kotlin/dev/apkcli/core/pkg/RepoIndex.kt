package dev.apkcli.core.pkg

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Downloads, so the package logic can run against local files off-device. */
interface PackageFetcher {
    /**
     * Download [url] to [dest], replacing it. When given, the size and SHA-256
     * are checked before [dest] appears; a mismatch deletes the download and
     * throws [java.io.IOException]. [onBytes] is told the bytes received so
     * far as they arrive.
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long? = null,
        expectedSha256: String? = null,
        onBytes: (Long) -> Unit = {},
    )
}

/** A failure worth showing as it is: no network, no space, a corrupt download. */
class PackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The repository's package list, cached in the state directory.
 *
 * `Release` is fetched first and the index is checked against the SHA-256 it
 * lists, so a truncated or mangled download is caught. That is integrity, not
 * authenticity: `Release` is signed with Termux's key in `InRelease`, and that
 * signature is not verified here. HTTPS to the repository is what stands in
 * for it.
 */
class RepoIndex(
    private val repoUrl: String,
    private val arch: String,
    private val cacheDir: File,
    private val fetcher: PackageFetcher,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    class Snapshot(
        val packages: Map<String, PackageInfo>,
        val fetchedAt: Long,
        /** Set when the network failed and an older copy is being used. */
        val warning: String?,
    )

    private val indexFile = File(cacheDir, "Packages-$arch")
    private val stampFile = File(cacheDir, "Packages-$arch.fetched")

    @Volatile
    private var memory: Snapshot? = null

    /** The index, fetched when the cached copy is older than the TTL or [refresh] is set. */
    suspend fun load(refresh: Boolean = false, progress: PackageProgress = PackageProgress.NONE): Snapshot {
        val cachedAt = stampFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val haveCopy = cachedAt != null && indexFile.isFile
        val age = if (cachedAt != null) clock() - cachedAt else Long.MAX_VALUE
        if (!refresh && haveCopy && age in 0 until ttlMs) {
            memory?.takeIf { it.fetchedAt == cachedAt }?.let { return it }
            progress.status("Reading the package index")
            return parse(cachedAt, null).also { memory = it }
        }
        return try {
            fetch(progress)
            val at = clock()
            stampFile.writeText(at.toString())
            parse(at, null).also {
                memory = it
                progress.step("Package index updated: ${it.packages.size} packages")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (!haveCopy) {
                throw PackageException("Could not download the package index from $repoUrl: ${e.message ?: e.javaClass.simpleName}", e)
            }
            val hours = (age / 3_600_000L).coerceAtLeast(0)
            val since = if (hours < 1) "under an hour" else "$hours h"
            progress.step("Package index not updated; using the copy from $since ago")
            parse(
                cachedAt,
                "Could not refresh the package index (${e.message ?: e.javaClass.simpleName}); " +
                    "using the copy from $since ago.",
            ).also { memory = it }
        }
    }

    private suspend fun fetch(progress: PackageProgress) {
        cacheDir.mkdirs()
        val base = repoUrl.trimEnd('/')
        val releaseFile = File(cacheDir, "Release.tmp")
        progress.status("Updating the package index")
        fetcher.download("$base/dists/$SUITE/Release", releaseFile)
        val listed = parseReleaseSha256(releaseFile.readText())
        releaseFile.delete()

        val path = "$COMPONENT/binary-$arch/Packages.gz"
        val entry = listed[path]
            ?: throw IOException("the repository has no packages for $arch (no $path in its Release file)")

        val compressed = File(cacheDir, "Packages-$arch.gz.tmp")
        val total = PackageManager.formatBytes(entry.size)
        fetcher.download("$base/dists/$SUITE/$path", compressed, entry.size, entry.sha256) { done ->
            progress.status("Updating the package index: ${PackageManager.formatBytes(done)} of $total")
        }
        progress.status("Reading the package index")
        val unpacked = File(cacheDir, "Packages-$arch.tmp")
        try {
            GZIPInputStream(compressed.inputStream().buffered(), 64 * 1024).use { input ->
                unpacked.outputStream().buffered().use { input.copyTo(it, 64 * 1024) }
            }
        } finally {
            compressed.delete()
        }
        if (!unpacked.renameTo(indexFile)) {
            indexFile.delete()
            if (!unpacked.renameTo(indexFile)) throw IOException("could not store the package index")
        }
    }

    private fun parse(fetchedAt: Long, warning: String?): Snapshot {
        val packages = DebControl.parseIndex(indexFile.readText())
            .filter { it.architecture == arch || it.architecture == "all" }
            .associateBy { it.name }
        return Snapshot(packages, fetchedAt, warning)
    }

    internal class Listed(val sha256: String, val size: Long)

    companion object {
        const val SUITE = "stable"
        const val COMPONENT = "main"
        const val DEFAULT_TTL_MS = 6L * 3_600_000L

        /** The `SHA256:` section of a `Release` file: `path -> (hash, size)`. */
        internal fun parseReleaseSha256(text: String): Map<String, Listed> {
            val out = HashMap<String, Listed>()
            var inSection = false
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                if (!line[0].isWhitespace()) {
                    inSection = line.trim() == "SHA256:"
                    continue
                }
                if (!inSection) continue
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size != 3) continue
                val size = parts[1].toLongOrNull() ?: continue
                out[parts[2]] = Listed(parts[0].lowercase(), size)
            }
            return out
        }
    }
}
