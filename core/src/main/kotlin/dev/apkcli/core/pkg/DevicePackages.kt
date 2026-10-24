package dev.apkcli.core.pkg

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import dev.apkcli.core.llm.Http
import dev.apkcli.core.runtime.EnvLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The Android side of the package manager: real symlinks and modes through
 * `android.system.Os`, downloads through OkHttp. Kept apart from the rest of
 * the package code, which has no Android dependency and runs off-device.
 */
object DevicePackages {

    /** Null when the device's ABI has no Termux build. */
    fun create(context: Context, layout: EnvLayout): PackageManager? {
        val arch = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { PackageManager.termuxArch(it) } ?: return null
        val alias = PrefixAlias(context.dataDir, context.packageName, layout.prefix, layout.home)
        val config = PackageManager.Config(
            prefix = layout.prefix,
            home = layout.home,
            stateDir = File(layout.state, "pkg"),
            arch = arch,
            relocation = {
                when (val status = alias.ensure(AndroidFileOps)) {
                    is PrefixAlias.Status.Ready -> PackageManager.RelocationChoice(status.relocation, null)
                    is PrefixAlias.Status.Unavailable -> PackageManager.RelocationChoice(
                        status.relocation,
                        directNote(status.reason, status.relocation),
                    )
                }
            },
            freeBytes = layout::freeBytes,
        )
        val client = Http.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        return PackageManager(config, AndroidFileOps, OkHttpPackageFetcher(client))
    }

    /** What the agent is told when packages go in without the prefix alias. */
    private fun directNote(reason: String, relocation: Relocation): String = buildString {
        append("Installed with direct paths rather than the prefix alias ($reason). Scripts and library paths are rewritten. ")
        val aliased = relocation.binaryAliases.keys.map { path ->
            if (path == Relocation.TERMUX_HOME) "\$HOME" else "\$PREFIX" + path.removePrefix(Relocation.TERMUX_PREFIX)
        }
        if (Relocation.TERMUX_TMP in relocation.binaryAliases) {
            append("Paths compiled into binaries under ${aliased.joinToString(", ")} are patched to same-length links ")
            append("in the app's data directory, so semaphores, shared memory, sockets (PostgreSQL) and helpers run ")
            append("by absolute path work; other compiled-in paths still point at Termux's, so a few lookups rely on ")
            append("SSL_CERT_FILE, TERMINFO and similar.")
        } else {
            append("Paths compiled into binaries still point at Termux's, so a few lookups rely on SSL_CERT_FILE, ")
            append("TERMINFO and similar, and programs that keep semaphores, shared memory or sockets in ")
            append("\$PREFIX/tmp (PostgreSQL) fail or hang at startup.")
        }
    }
}

object AndroidFileOps : FileOps {
    override fun symlink(target: String, link: File) = errno("symlink ${link.path}") { Os.symlink(target, link.path) }

    override fun readlink(link: File): String? = try {
        Os.readlink(link.path)
    } catch (notALink: ErrnoException) {
        null
    }

    override fun chmod(file: File, mode: Int) = errno("chmod ${file.path}") { Os.chmod(file.path, mode) }

    override fun rename(from: File, to: File) = errno("rename ${from.path}") { Os.rename(from.path, to.path) }

    private inline fun errno(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: ErrnoException) {
            throw IOException("$what: ${e.message}", e)
        }
    }
}

/** Streams to `<dest>.part`, hashing as it goes; renamed into place only once it checks out. */
class OkHttpPackageFetcher(private val client: OkHttpClient) : PackageFetcher {

    override suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long?,
        expectedSha256: String?,
        onBytes: (Long) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            part.delete()
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            // A cancelled tool call must not leave a download running.
            val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    response.body.byteStream().use { input ->
                        part.outputStream().buffered(64 * 1024).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                currentCoroutineContext().ensureActive()
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                total += n
                                if (expectedSize != null && total > expectedSize) {
                                    throw IOException("${dest.name} is larger than the index says ($expectedSize bytes)")
                                }
                                onBytes(total)
                            }
                        }
                    }
                    if (expectedSize != null && total != expectedSize) {
                        throw IOException("${dest.name} is $total bytes, the index says $expectedSize")
                    }
                    if (expectedSha256 != null) {
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expectedSha256, ignoreCase = true)) {
                            throw IOException("${dest.name} failed its SHA-256 check")
                        }
                    }
                }
                if (!part.renameTo(dest)) throw IOException("could not move ${part.name} into place")
            } catch (e: Throwable) {
                part.delete()
                throw e
            } finally {
                cancel?.dispose()
            }
        }

    private companion object {
        const val USER_AGENT = "ApkCLI-pkg/1 (Android)"
    }
}
