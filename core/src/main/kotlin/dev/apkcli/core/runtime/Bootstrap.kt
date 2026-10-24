package dev.apkcli.core.runtime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import dev.apkcli.core.pkg.PackageManager
import dev.apkcli.core.pkg.PackageStore
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.long
import dev.apkcli.core.util.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Builds `$PREFIX` out of the APK on first run.
 *
 * The central trick: `$PREFIX/bin` is a farm of symlinks into
 * `nativeLibraryDir`. The links live in the writable data directory (so `PATH`
 * is a stable, friendly path), but every one of them resolves to a file in the
 * APK's library directory, which is where SELinux permits exec. Pointing
 * `PATH` directly at `nativeLibraryDir` would also work, but then every command
 * is spelled `libgit.so`, and the path changes on every app update.
 *
 * Re-runs when the version code changes, and — importantly — when
 * `nativeLibraryDir` changes, because an app update relocates it and leaves
 * every existing symlink dangling.
 */
class Bootstrap(
    private val context: Context,
    private val layout: EnvLayout,
    private val policy: ExecPolicy,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    data class Report(
        val performed: Boolean,
        val reason: String,
        val linkedCommands: List<String>,
        val appletCount: Int,
        val execWorks: Boolean,
        val execDetail: String,
        val shell: String,
        val tier: ExecPolicy.Tier,
        val warnings: List<String>,
    )

    fun currentVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(-1L)

    /** @param force rebuild even if the stamp says we are up to date. */
    fun run(force: Boolean = false): Report {
        layout.ensureDirectories()
        // Before the up-to-date check: installs from before this existed get
        // it too, on their next start.
        seedPipConfig()

        val stamp = readStamp()
        val version = currentVersionCode()
        val nativeDir = layout.nativeLibDir.absolutePath

        val reason = when {
            force -> "forced"
            stamp == null -> "first run"
            stamp.long("versionCode", -1) != version ->
                "app updated (${stamp.long("versionCode", -1)} -> $version)"
            stamp.str("nativeLibDir") != nativeDir ->
                "native library directory moved"
            !File(layout.bin, "sh").exists() && !File(layout.bin, "busybox").exists() ->
                "prefix incomplete"
            else -> null
        }

        if (reason == null) {
            val probe = policy.probe()
            return Report(
                performed = false,
                reason = "up to date",
                linkedCommands = (layout.bin.list() ?: emptyArray()).sorted(),
                appletCount = stamp?.long("appletCount", 0)?.toInt() ?: 0,
                execWorks = probe.works,
                execDetail = probe.detail,
                shell = probe.shell.absolutePath,
                tier = policy.tier,
                warnings = emptyList(),
            )
        }

        Log.i(TAG, "bootstrapping prefix: $reason")
        val warnings = mutableListOf<String>()

        // A stale farm is worse than no farm: dangling links make `which` lie.
        // Only the farm goes, though: installed packages and the scripts pip
        // and npm write also live in $PREFIX/bin, and must survive an update.
        clearFarmLinks(layout.bin)

        val linked = linkBundledBinaries(warnings)
        val applets = linkBusyboxApplets(linked, warnings)
        ensureShellLink(linked, warnings)

        writeProfile()
        writeHomeReadme()

        val probe = policy.probe()
        if (!probe.works) {
            warnings += "exec probe failed: ${probe.detail}"
        }

        writeStamp(
            jsonOf(
                "versionCode" to version,
                "nativeLibDir" to nativeDir,
                "builtAt" to System.currentTimeMillis(),
                "tier" to policy.tier.name.lowercase(),
                "linkedCommands" to linked.size,
                "appletCount" to applets,
                "execWorks" to probe.works,
                "execDetail" to probe.detail,
            ),
        )

        return Report(
            performed = true,
            reason = reason,
            linkedCommands = linked.sorted(),
            appletCount = applets,
            execWorks = probe.works,
            execDetail = probe.detail,
            shell = probe.shell.absolutePath,
            tier = policy.tier,
            warnings = warnings,
        )
    }

    /* ------------------------------------------------------- symlink farm */

    private fun linkBundledBinaries(warnings: MutableList<String>): List<String> {
        val linked = mutableListOf<String>()
        for (binary in policy.bundledBinaries()) {
            if (!binary.executable) {
                // Happens when extractNativeLibs is false or legacy packaging
                // was turned off: the file is in the APK but never landed on
                // disk as an executable.
                warnings += "${binary.command}: present but not executable " +
                    "(check useLegacyPackaging and extractNativeLibs)"
                continue
            }
            val link = File(layout.bin, binary.command)
            when {
                symlink(binary.file, link) -> linked += binary.command
                // An installed package has its own; the user chose it, it stays.
                isOccupied(link) -> Unit
                else -> warnings += "${binary.command}: symlink failed"
            }
        }
        return linked
    }

    /**
     * busybox dispatches on `argv[0]`, so each applet needs its own name
     * pointing at the same binary.
     *
     * The list is explicit rather than discovered by running `busybox --list`,
     * because at bootstrap time we may not be able to execute anything yet —
     * and discovering the list by exec'ing the thing whose exec-ability we are
     * still establishing is the wrong order.
     *
     * Applets already provided by a bundled standalone binary (a real `git`,
     * a real `grep`) are skipped so the better tool wins.
     */
    private fun linkBusyboxApplets(
        alreadyLinked: List<String>,
        warnings: MutableList<String>,
    ): Int {
        val busybox = File(layout.nativeLibDir, "libbusybox.so")
        if (!busybox.exists()) return 0
        if (!busybox.canExecute()) {
            warnings += "busybox present but not executable"
            return 0
        }
        var count = 0
        for (applet in BUSYBOX_APPLETS) {
            if (applet in alreadyLinked) continue
            if (symlink(busybox, File(layout.bin, applet))) count++
        }
        return count
    }

    /**
     * `sh` must always resolve to something. Without a bundled shell we link to
     * the system mksh, which is present on every device.
     */
    private fun ensureShellLink(linked: List<String>, warnings: MutableList<String>) {
        val sh = File(layout.bin, "sh")
        // A package's own sh (dash) counts: scripts only need one to exist.
        if (!sh.exists()) {
            val source = when {
                File(layout.nativeLibDir, "libbusybox.so").canExecute() ->
                    File(layout.nativeLibDir, "libbusybox.so")
                else -> policy.systemShell
            }
            if (!symlink(source, sh)) {
                warnings += "could not create \$PREFIX/bin/sh"
            }
        }
        // `env` is what every `#!/usr/bin/env python` shebang needs; toybox
        // provides it when busybox does not.
        if (!File(layout.bin, "env").exists() && "env" !in linked) {
            val toyboxEnv = File("/system/bin/env")
            if (toyboxEnv.exists()) symlink(toyboxEnv, File(layout.bin, "env"))
        }
    }

    /**
     * Point [link] at [target], replacing an older farm link but nothing else:
     * a file or link a package or the user put there wins, and false is
     * returned.
     */
    private fun symlink(target: File, link: File): Boolean {
        return try {
            when {
                policy.isFarmLink(link) -> link.delete()
                isOccupied(link) -> return false
            }
            Os.symlink(target.absolutePath, link.absolutePath)
            true
        } catch (e: ErrnoException) {
            Log.w(TAG, "symlink ${link.name} -> ${target.name} failed: ${e.message}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "symlink ${link.name} failed", t)
            false
        }
    }

    /** Something other than a farm link is there. `exists()` alone misses dangling links. */
    private fun isOccupied(file: File): Boolean =
        file.exists() || runCatching { Os.readlink(file.absolutePath) != null }.getOrDefault(false)

    /**
     * Delete the links earlier bootstraps made, which point into an APK's
     * library directory or /system — never a path an installed package
     * records as its own, even if it happens to look like one.
     */
    private fun clearFarmLinks(dir: File) {
        dir.mkdirs()
        val owned = runCatching {
            PackageStore(File(layout.prefix, PackageManager.DB_PATH)).load().values
                .flatMapTo(HashSet()) { pkg -> pkg.files.filter { it.startsWith("bin/") } }
        }.getOrDefault(emptySet())
        dir.list()?.forEach { name ->
            val file = File(dir, name)
            if ("bin/$name" !in owned && policy.isFarmLink(file)) file.delete()
        }
    }

    /* ------------------------------------------------------- shell profile */

    /**
     * `$PREFIX/etc/profile` is sourced by interactive shells. It exists so the
     * terminal behaves like a terminal: a prompt that shows the project, an
     * editor that is present on the device, and aliases that paper over
     * toybox's missing flags.
     */
    private fun writeProfile() {
        File(layout.etc, "profile").writeText(
            """
            # Generated by ApkCLI. Edited copies are overwritten on app update —
            # put your own settings in ${'$'}HOME/.profile instead.

            export PREFIX="${layout.prefix.absolutePath}"
            export HOME="${layout.home.absolutePath}"
            export TMPDIR="${layout.tmp.absolutePath}"
            export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
            # Not ${'$'}PREFIX/lib: system binaries would load package libraries
            # (OpenSSL in place of BoringSSL). Packages use their own RUNPATH.
            export LD_LIBRARY_PATH="${layout.nativeLibDir.absolutePath}"
            $PACKAGE_ENV_LINES
            export LANG=en_US.UTF-8
            export PAGER=cat
            export EDITOR=vi

            # Keep history in the state directory, not next to the user's code.
            export HISTFILE="${layout.state.absolutePath}/sh_history"
            export HISTSIZE=5000

            # Show the project directory, not the full /data/data path.
            __apkcli_pwd() {
              case "${'$'}PWD" in
                "${'$'}HOME") printf '~' ;;
                "${'$'}HOME"/*) printf '~%s' "${'$'}{PWD#${'$'}HOME}" ;;
                *) printf '%s' "${'$'}PWD" ;;
              esac
            }
            # The directory in green, as the agent's commands show it. Each
            # shell marks the colour codes as taking no room its own way, so
            # its line editor still knows where the cursor is: bash with
            # \[ \], mksh (Android's sh) with a non-printing character that
            # PS1 starts with, followed by a carriage return. The characters
            # come from printf, which every one of these shells has.
            if [ -n "${'$'}BASH_VERSION" ]; then
              PS1='\[\e[32m\]$(__apkcli_pwd)\[\e[0m\] ${'$'} '
            elif [ -n "${'$'}KSH_VERSION" ]; then
              __apkcli_e=${'$'}(printf '\033'); __apkcli_n=${'$'}(printf '\001'); __apkcli_r=${'$'}(printf '\r')
              PS1="${'$'}{__apkcli_n}${'$'}{__apkcli_r}${'$'}{__apkcli_n}${'$'}{__apkcli_e}[32m${'$'}{__apkcli_n}\${'$'}(__apkcli_pwd)${'$'}{__apkcli_n}${'$'}{__apkcli_e}[0m${'$'}{__apkcli_n} \${'$'} "
              unset __apkcli_e __apkcli_n __apkcli_r
            else
              PS1='$(__apkcli_pwd) ${'$'} '
            fi

            alias ll='ls -lh'
            alias la='ls -lha'
            alias ..='cd ..'

            [ -f "${'$'}HOME/.profile" ] && . "${'$'}HOME/.profile"
            """.trimIndent() + "\n",
        )

        // A non-interactive copy for tool calls: same environment, no prompt,
        // no history, no user dotfiles (so a broken .profile cannot break the
        // agent's shell tool).
        File(layout.etc, "tool-env.sh").writeText(
            """
            export PREFIX="${layout.prefix.absolutePath}"
            export HOME="${layout.home.absolutePath}"
            export TMPDIR="${layout.tmp.absolutePath}"
            export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
            export LD_LIBRARY_PATH="${layout.nativeLibDir.absolutePath}"
            $PACKAGE_ENV_LINES
            export LANG=en_US.UTF-8
            export TERM=dumb
            """.trimIndent() + "\n",
        )

        val userProfile = File(layout.home, ".profile")
        if (!userProfile.exists()) {
            userProfile.writeText(
                "# Your settings. Not touched by app updates.\n",
            )
        }
    }

    /**
     * pip's user config, written once: a deleted or edited file is the
     * user's decision and stays.
     *
     * PyPI publishes almost no Android wheels, so on a phone pip compiles
     * every package with native parts. For C that is a minute; for the Rust
     * ones (pydantic-core under pydantic, aiogram and FastAPI; tokenizers)
     * it is hundreds of crates and an hour or a timeout. The Termux User
     * Repository publishes prebuilt Android wheels for the worst of them,
     * built for the same Python as the `python` package. Its extension
     * modules need only libpython (loaded already) and bionic, so they load
     * from this prefix although they were built for Termux's.
     *
     * `only-binary` for those few makes pip pick an older release that has a
     * wheel over a newer one it would have to compile — pydantic 2.12.5
     * rather than a two-hour build of the latest — and fail fast where none
     * has one.
     */
    private fun seedPipConfig() = runCatching {
        // Where installed packages cannot run there is no pip to configure.
        if (!policy.dataDirExecAllowed) return@runCatching
        val marker = File(layout.state, "pip-conf-seeded")
        if (marker.exists()) return@runCatching
        val config = File(layout.home, ".config/pip/pip.conf")
        if (!config.exists()) {
            config.parentFile?.mkdirs()
            config.writeText(
                """
                # Written once by ApkCLI; yours to edit or delete, it is not rewritten.
                #
                # PyPI has almost no Android builds of packages with native code, so
                # pip compiles them here: minutes for C, an hour or more for Rust
                # (pydantic-core, tokenizers). The Termux User Repository, a community
                # project, publishes prebuilt Android wheels for some of them. Remove
                # these lines to install from PyPI alone.
                [global]
                extra-index-url = $TUR_PYPI_INDEX
                only-binary = ${ONLY_BINARY_FROM_TUR.joinToString(",")}
                """.trimIndent() + "\n",
            )
        }
        marker.writeText("${System.currentTimeMillis()}\n")
    }.onFailure { Log.w(TAG, "pip config not written: ${it.message}") }

    private fun writeHomeReadme() {
        val readme = File(layout.home, "README.md")
        if (readme.exists()) return
        readme.writeText(
            """
            # ApkCLI environment

            Everything lives in three directories, all inside this app's private
            storage. Nothing is scattered across external storage or cache dirs
            that the system can delete.

            | Path | Variable | What it is |
            | --- | --- | --- |
            | `${layout.home.name}/` | `${'$'}HOME` | Yours. Projects, dotfiles. Backed up, never auto-deleted. |
            | `${layout.prefix.name}/` | `${'$'}PREFIX` | The toolchain: links into the APK, plus packages you install. The links are rebuilt on every update; deleting it also removes the packages. |
            | `${layout.state.name}/` | — | Database, logs, index. Disposable; the app rebuilds it. |

            Projects go in `${'$'}HOME/projects/<name>`.

            ## Why `${'$'}PREFIX/bin` is full of symlinks

            Since Android 10, SELinux forbids executing any file inside an app's
            data directory. The only exec-permitted location available without
            root is the APK's own native library directory, so every bundled tool
            ships as `lib<name>.so` and `${'$'}PREFIX/bin/<name>` is a symlink
            pointing there. Those links are recreated after each app update,
            because the library directory path changes.

            Writing a new executable into `${'$'}PREFIX/bin` yourself will not
            work on the Play-compliant build. Shell *scripts* are fine — the
            interpreter is what gets executed, and the script is only read.
            """.trimIndent() + "\n",
        )
    }

    /* --------------------------------------------------------------- stamp */

    private fun readStamp(): JsonObject? = runCatching {
        if (!layout.stamp.exists()) return null
        json.parseToJsonElement(layout.stamp.readText()).jsonObject
    }.getOrNull()

    private fun writeStamp(obj: JsonObject) = runCatching {
        layout.stamp.writeText(json.encodeToString(JsonObject.serializer(), obj))
    }

    /** True if the last recorded probe succeeded; cheap enough for a UI badge. */
    fun lastProbeSucceeded(): Boolean = readStamp()?.bool("execWorks", false) ?: false

    companion object {
        private const val TAG = "Bootstrap"

        /** Prebuilt Android wheels; see [seedPipConfig]. */
        const val TUR_PYPI_INDEX = "https://termux-user-repository.github.io/pypi/"

        /**
         * Rust packages the index has Android wheels of (checked October
         * 2026, cp313 and cp314), whose source build takes far longer than a
         * tool call may.
         */
        val ONLY_BINARY_FROM_TUR = listOf("pydantic-core", "tokenizers", "tiktoken", "watchfiles")

        /**
         * The shell form of [ExecPolicy.packageEnvironment]: decided when the
         * profile is sourced, since packages come and go between app updates.
         * One line, because it is spliced into trimIndent()ed templates.
         */
        private const val PACKAGE_ENV_LINES =
            "[ -f \"\$PREFIX/etc/tls/cert.pem\" ] && export SSL_CERT_FILE=\"\$PREFIX/etc/tls/cert.pem\"; " +
                "[ -d \"\$PREFIX/etc/tls/certs\" ] && export SSL_CERT_DIR=\"\$PREFIX/etc/tls/certs\"; " +
                "[ -d \"\$PREFIX/share/terminfo\" ] && export TERMINFO=\"\$PREFIX/share/terminfo\""

        /**
         * busybox applets worth exposing. Not the full list (busybox ships
         * ~400); these are the ones a build or a shell session actually reaches
         * for, and each costs one symlink.
         */
        private val BUSYBOX_APPLETS = listOf(
            // shell + core
            "ash", "sh", "env", "true", "false", "echo", "printf", "test", "sleep", "yes",
            // files
            "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "ln", "touch", "stat",
            "chmod", "chown", "readlink", "realpath", "basename", "dirname", "du", "df",
            "find", "xargs", "tree",
            // text
            "grep", "egrep", "fgrep", "sed", "awk", "cut", "sort", "uniq", "head", "tail",
            "wc", "tr", "rev", "paste", "join", "comm", "diff", "patch", "expr", "seq",
            "fold", "split", "nl", "od", "strings",
            // archives
            "tar", "gzip", "gunzip", "zcat", "bzip2", "bunzip2", "xz", "unxz", "unzip", "cpio",
            // net
            "wget", "nc", "ping", "nslookup", "telnet",
            // hashes
            "md5sum", "sha1sum", "sha256sum", "sha512sum", "base64", "cksum",
            // process
            "ps", "kill", "killall", "pkill", "pgrep", "top", "nice", "timeout", "nohup",
            "time", "watch",
            // misc
            "date", "id", "whoami", "uname", "hostname", "which", "tee", "mktemp",
            "dd", "sync", "vi", "less", "more", "clear", "reset", "stty", "tty",
            "ipcs", "free", "uptime",
        )
    }
}
