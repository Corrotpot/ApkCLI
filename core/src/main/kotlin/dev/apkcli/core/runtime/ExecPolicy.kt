package dev.apkcli.core.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import dev.apkcli.core.pkg.PrefixAlias
import java.io.File

/**
 * What this app is allowed to execute, and why.
 *
 * This is the single most consequential piece of the project, so the reasoning
 * is written down rather than implied.
 *
 * Android 10 removed `app_data_file:file execute_no_trans` from the
 * `untrusted_app` SELinux domains used by apps targeting API 29 and above. The
 * effect is absolute: a file under `/data/data/<pkg>/` cannot be `execve()`d,
 * no matter what its mode bits say, and `chmod +x` does nothing about it. It is
 * mandatory access control, not discretionary permissions.
 *
 * Two places remain executable:
 *
 *   1. `nativeLibraryDir` — populated by the installer from `lib/<abi>/` in the
 *      APK, labelled `apk_data_file`, read-only. Requires
 *      `android:extractNativeLibs="true"` and `useLegacyPackaging = true`,
 *      otherwise the libraries are never written to disk at all and are mapped
 *      straight out of the (compressed) APK.
 *   2. `/system/bin` and `/system/xbin` — mksh as `sh`, plus toybox, which on a
 *      current device provides on the order of 150 applets.
 *
 * Apps targeting API 28 or lower land in `untrusted_app_27`, which *does* still
 * have `execute_no_trans`. That is the entire reason the `power` flavour exists
 * and why Termux pinned its targetSdk for years. Android 15 and 16 refuse to
 * install only `targetSdk < 24`, so a 28-targeting APK is still sideloadable in
 * 2026 — it just cannot go on Play.
 *
 * The policy is derived from the *installed* target SDK rather than a
 * `BuildConfig` flag, so it stays correct if the flavour, the manifest and the
 * build file ever disagree.
 */
class ExecPolicy(
    context: Context,
    private val layout: EnvLayout,
) {

    private val targetSdk: Int = context.applicationInfo.targetSdkVersion

    /** Directories from which exec is expected to work. */
    val executableRoots: List<File> = buildList {
        add(layout.nativeLibDir)
        add(File("/system/bin"))
        add(File("/system/xbin"))
        // untrusted_app_27 may exec anything in the data directory: installed
        // packages (which keep helpers in libexec/ and lib/), and what the
        // user builds under $HOME.
        if (targetSdk <= 28) {
            add(layout.prefix)
            add(layout.home)
        }
    }

    /**
     * True when this build can execute binaries it wrote itself, which is what
     * makes a runtime package manager and a proot rootfs possible.
     */
    val dataDirExecAllowed: Boolean = targetSdk <= 28

    val tier: Tier = if (dataDirExecAllowed) Tier.POWER else Tier.PLAY

    enum class Tier {
        /** Exec restricted to the APK + /system. Play-compliant. */
        PLAY,

        /** Exec from `$PREFIX/bin` works. Sideload only. */
        POWER,
    }

    /** The system shell. Present on every Android device; it is mksh. */
    val systemShell = File("/system/bin/sh")

    /**
     * Preferred shell: busybox/bash from the APK if it was bundled, otherwise
     * the system mksh.
     *
     * Resolution goes through the symlink farm rather than straight to
     * `nativeLibraryDir` so that `$SHELL` is a stable path across app updates.
     *
     * Only the farm's own links count. A package can put its own `bash` or
     * `sh` (dash) in `$PREFIX/bin`, and the tool shell must not change under
     * the agent because of that — nor stop working when that package's
     * libraries are upgraded or removed.
     */
    fun resolveShell(): File {
        for (name in listOf("bash", "ash", "busybox", "sh")) {
            val link = File(layout.bin, name)
            if (isFarmLink(link) && link.exists() && link.canExecute()) return link
        }
        return systemShell
    }

    /** A link [Bootstrap] made: into an APK's library directory or /system. */
    fun isFarmLink(file: File): Boolean {
        val target = readLink(file.absolutePath) ?: return false
        return isFarmTarget(target)
    }

    /**
     * Where farm links point. Old library directories count too: after an app
     * update the previous install's links dangle until the bootstrap replaces
     * them.
     */
    fun isFarmTarget(target: String): Boolean =
        target.startsWith(layout.nativeLibDir.absolutePath + "/") ||
            target.startsWith("/data/app/") ||
            ADOPTED_APP_DIR.containsMatchIn(target) ||
            target.startsWith("/system/")

    /**
     * Can [file] be executed under the current policy?
     *
     * Checks the *canonical* target, because every entry in `$PREFIX/bin` is a
     * symlink: SELinux evaluates the label of the resolved file, so a link in
     * the data directory pointing at `nativeLibraryDir` executes fine, while a
     * real file at the same path does not.
     */
    fun canExecute(file: File): Boolean {
        val resolved = runCatching { file.canonicalFile }.getOrNull() ?: return false
        if (!resolved.exists()) return false
        val underExecutableRoot = executableRoots
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .any { resolved.path.startsWith(it.path) }
        return underExecutableRoot && resolved.canExecute()
    }

    /**
     * Explain a refusal in terms a user can act on. Shown verbatim in the UI
     * and handed to the model, because "Permission denied" sends an agent into
     * a chmod loop that cannot possibly work.
     */
    fun explainRefusal(file: File): String {
        val resolved = runCatching { file.canonicalFile }.getOrNull() ?: file
        return when {
            !resolved.exists() ->
                "${resolved.path} does not exist. Android has no /bin or /usr/bin; " +
                    "binaries live in \$PREFIX/bin (symlinks into the APK) or /system/bin."

            layout.isInsideSandbox(resolved) && !dataDirExecAllowed ->
                "${resolved.path} is inside the app data directory. This build targets " +
                    "API $targetSdk, and since API 29 SELinux denies exec there (W^X). " +
                    "Ship the binary in the APK under jniLibs as lib<name>.so, or install " +
                    "the 'power' flavour, which targets API 28 and does allow it."

            !resolved.canExecute() ->
                "${resolved.path} is not marked executable."

            else ->
                "${resolved.path} is outside the permitted exec roots " +
                    "(${executableRoots.joinToString { it.path }})."
        }
    }

    /**
     * Discover the executables shipped in the APK.
     *
     * The installer only unpacks files matching `*.so`, so every bundled tool
     * is named `lib<tool>.so` and that prefix/suffix is stripped here to
     * recover the command name.
     */
    fun bundledBinaries(): List<BundledBinary> {
        val dir = layout.nativeLibDir
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.startsWith("lib") && it.name.endsWith(".so") }
            .mapNotNull { file ->
                val command = file.name.removePrefix("lib").removeSuffix(".so")
                // The Rust core and the exec shim are libraries, not commands.
                if (command in RESERVED_LIBRARIES) return@mapNotNull null
                BundledBinary(
                    command = command,
                    file = file,
                    sizeBytes = file.length(),
                    executable = file.canExecute(),
                )
            }
            .sortedBy { it.command }
    }

    data class BundledBinary(
        val command: String,
        val file: File,
        val sizeBytes: Long,
        val executable: Boolean,
    )

    /**
     * Empirical check that exec actually works, run once at bootstrap.
     *
     * Deriving the policy from `targetSdkVersion` is correct in theory, but
     * vendors ship modified SELinux policies and the failure mode (every tool
     * call returning EACCES) is miserable to diagnose from a bug report. So we
     * find out at startup and record it.
     */
    fun probe(): ProbeResult {
        val shell = resolveShell()
        if (!canExecute(shell)) {
            return ProbeResult(false, explainRefusal(shell), shell)
        }
        return try {
            val process = ProcessBuilder(shell.absolutePath, "-c", "echo apkcli-ok")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = process.waitFor()
            if (code == 0 && output.contains("apkcli-ok")) {
                ProbeResult(true, "ok via ${shell.path}", shell)
            } else {
                ProbeResult(false, "exit $code, output '$output'", shell)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "exec probe failed", t)
            ProbeResult(false, "${t.javaClass.simpleName}: ${t.message}", shell)
        }
    }

    data class ProbeResult(val works: Boolean, val detail: String, val shell: File)

    /**
     * Environment for every child process.
     *
     * Notable entries:
     *   `PATH`            — `$PREFIX/bin` first so a bundled GNU tool beats
     *                       toybox's reduced applet.
     *   `LD_LIBRARY_PATH` — the APK's lib dir, because the bundled binaries are
     *                       dynamically linked against siblings there and their
     *                       original RUNPATH was zeroed at packaging time.
     *                       Deliberately *not* `$PREFIX/lib`: bionic searches
     *                       LD_LIBRARY_PATH before anything else, for every
     *                       process, so `/system/bin/toybox` would load
     *                       Termux's OpenSSL 3 `libcrypto.so` in place of the
     *                       system's BoringSSL and die on missing symbols.
     *                       Installed packages find their libraries through
     *                       their own RUNPATH, exactly as in Termux.
     *   `LD_PRELOAD`      — the exec shim, which rewrites `/bin/sh` style paths
     *                       and resolves shebangs. Omitted if absent.
     *   `TMPDIR`          — inside `$PREFIX`, never the system `/tmp` (which
     *                       does not exist) nor `cacheDir` (which the OS can
     *                       delete mid-build).
     *   `SSL_CERT_FILE`, `TERMINFO` — set once a package has installed them.
     *                       Packages relocated through the prefix alias find
     *                       both on their own; ones installed with direct
     *                       paths still have Termux's compiled in and need
     *                       telling. Pointing at the same files costs nothing.
     *                       Without the alias, `GIT_EXEC_PATH` and the curl/git
     *                       CA variables are added too; see [packageEnvironment].
     */
    fun baseEnvironment(
        workingDir: File,
        term: String = "xterm-256color",
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val shell = resolveShell()
        val shim = File(layout.nativeLibDir, "libexecshim.so")

        val env = linkedMapOf(
            "HOME" to layout.home.absolutePath,
            "PREFIX" to layout.prefix.absolutePath,
            "PWD" to workingDir.absolutePath,
            "TMPDIR" to layout.tmp.absolutePath,
            "TMP" to layout.tmp.absolutePath,
            "PATH" to listOf(
                layout.bin.absolutePath,
                "/system/bin",
                "/system/xbin",
            ).joinToString(":"),
            "LD_LIBRARY_PATH" to layout.nativeLibDir.absolutePath,
            "SHELL" to shell.absolutePath,
            "TERM" to term,
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "en_US.UTF-8",
            "COLORTERM" to "truecolor",
            // Consumed by libexecshim.so.
            "APKCLI_PREFIX" to layout.prefix.absolutePath,
            "APKCLI_NATIVE_BIN" to layout.nativeLibDir.absolutePath,
            "APKCLI_TIER" to tier.name.lowercase(),
            // Tools that detect CI tend to disable colour and interactivity,
            // which is what we want for captured (non-PTY) execution.
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
        )
        if (shim.exists()) {
            env["LD_PRELOAD"] = shim.absolutePath
        }
        env.putAll(packageEnvironment())
        env.putAll(extra)
        return env
    }

    /** Variables pointing at files that installed packages provide, for those that exist. */
    fun packageEnvironment(): Map<String, String> = buildMap {
        val certs = File(layout.etc, "tls/cert.pem").takeIf { it.isFile }
        certs?.let { put("SSL_CERT_FILE", it.absolutePath) }
        File(layout.etc, "tls/certs").takeIf { it.isDirectory }?.let { put("SSL_CERT_DIR", it.absolutePath) }
        File(layout.share, "terminfo").takeIf { it.isDirectory }?.let { put("TERMINFO", it.absolutePath) }
        // Without the prefix alias, paths compiled into binaries still name
        // Termux's prefix. These are the ones that have an override: libcurl
        // (so git over HTTPS) keeps its own CA path, and git looks for its
        // helpers, `git-remote-https` among them, in its compiled exec path.
        if (certs != null && !prefixAlias.isActive()) {
            put("CURL_CA_BUNDLE", certs.absolutePath)
            put("GIT_SSL_CAINFO", certs.absolutePath)
            File(layout.prefix, "libexec/git-core").takeIf { it.isDirectory }?.let { put("GIT_EXEC_PATH", it.absolutePath) }
            File(layout.share, "git-core/templates").takeIf { it.isDirectory }?.let { put("GIT_TEMPLATE_DIR", it.absolutePath) }
        }
    }

    private val prefixAlias = PrefixAlias(context.dataDir, context.packageName, layout.prefix, layout.home)

    fun describe(): String = buildString {
        append("tier=").append(tier.name.lowercase())
        append(" targetSdk=").append(targetSdk)
        append(" api=").append(Build.VERSION.SDK_INT)
        append(" dataDirExec=").append(dataDirExecAllowed)
        append(" nativeLibDir=").append(layout.nativeLibDir.path)
    }

    companion object {
        private const val TAG = "ExecPolicy"

        /** An APK installed on adopted storage: `/mnt/expand/<uuid>/app/...`. */
        private val ADOPTED_APP_DIR = Regex("^/mnt/expand/[^/]+/app/")

        /** Shipped as `lib*.so` but not commands. */
        private val RESERVED_LIBRARIES = setOf(
            "apkcli_core",
            "execshim",
            "c++_shared",
            "z", "crypto", "ssl", "curl", "pcre2", "pcre2-8", "iconv", "charset",
            "andoid-support", "android-support", "termux-exec",
            // JNI libraries that AndroidX dependencies ship.
            "androidx.graphics.path", "datastore_shared_counter",
        )

        /**
         * Read a symlink without depending on NIO's `Files.readSymbolicLink`,
         * which is not available at our minSdk on all devices.
         */
        fun readLink(path: String): String? =
            runCatching { Os.readlink(path) }.getOrNull()
    }
}
