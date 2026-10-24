package dev.apkcli.core.agent.tools

import android.util.Log
import dev.apkcli.core.agent.Schema
import dev.apkcli.core.agent.Schema.boolean
import dev.apkcli.core.agent.Schema.integer
import dev.apkcli.core.agent.Schema.string
import dev.apkcli.core.agent.Schema.stringArray
import dev.apkcli.core.agent.Tool
import dev.apkcli.core.agent.ToolContext
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.agent.ToolResult
import dev.apkcli.core.pkg.PackageException
import dev.apkcli.core.pkg.PackageManager
import dev.apkcli.core.pkg.PackageProgress
import dev.apkcli.core.pkg.PackageResolver
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.str
import dev.apkcli.core.util.strings
import kotlinx.serialization.json.JsonObject

/**
 * Package tools, backed by the app's own Termux package manager
 * ([PackageManager]). Registered only where installed binaries can run.
 */

private const val TAG = "PkgTools"

// formatBytes is the package's own, in FsTools.kt.

/** `packages: [..]` and the single `package` the old schema had, merged. */
private fun JsonObject.packageNames(): List<String> =
    (strings("packages") + listOfNotNull(str("package")))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

/**
 * Package progress into the live view under the call (downloads with their
 * percentage, unpacking with its file count), and finished steps into logcat.
 */
private fun progressInto(log: LiveLog?): PackageProgress = object : PackageProgress {
    override fun step(line: String) {
        Log.i(TAG, line)
        log?.line(line)
    }

    override fun status(line: String) {
        log?.status(line)
    }
}

private fun unavailable(): ToolResult = ToolResult.refused(
    "Package installation is not available in this build: either it targets an API level " +
        "where SELinux forbids executing anything the app writes (the Play build), or the " +
        "device's CPU has no Termux packages. Work with the commands listed by env_info.",
)

/* ------------------------------------------------------------- pkg_install */

class PkgInstallTool : Tool {
    override val name = "pkg_install"
    override val taskClass = TaskClass.BACKGROUND
    override val parallelSafe = false

    /** Tens of megabytes over mobile data, then unpacking on a phone. */
    override val timeoutMs: Long = 30 * 60_000L

    override val description = """
        Install command-line packages from the Termux repository into ${'$'}PREFIX, with
        everything they depend on: python (brings pip), nodejs (brings npm), clang,
        rust, golang, openjdk-21, make, cmake, ffmpeg and more.

        When to use it: a command the task needs is missing — install it instead of
        telling the user it cannot run. Use pkg_search when unsure of the name; there is
        no `apt` or `pkg` command. Python and Node libraries go through `pip install`
        and `npm install` with sh_exec once the runtime is in.

        Packages you name also bring what they recommend. Installed dependencies are
        upgraded to the repository's version when something new needs them;
        `upgradeAll` upgrades everything installed. Termux's install scripts are not
        run. New commands are on PATH as soon as this returns. A large toolchain can
        take minutes; the call is allowed 30 minutes.

        Output: each package installed with its version and why (requested,
        recommended, dependency, upgrade), the sizes, the new commands, unknown names
        with similar ones, and notes, such as skipped install scripts.
    """.trimIndent()

    override val parameters = Schema.obj {
        stringArray("packages", "Package names, e.g. [\"python\"] or [\"nodejs\", \"make\"].")
        string("package", "One package name; the same as packages: [name].")
        boolean(
            "upgradeAll",
            "Also upgrade every other installed package to the repository's version.",
            default = false,
        )
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val names = args.packageNames()
        val upgradeAll = args.bool("upgradeAll", false)
        if (names.isEmpty() && !upgradeAll) {
            return ToolResult.error("Name at least one package in 'packages', or set upgradeAll.")
        }
        val packages = ctx.packages ?: return unavailable()

        val report = try {
            withLiveLog(
                ctx.onOutput,
                echo = ctx.echo,
                // How it would read at a Termux prompt.
                title = "pkg install " + (names.joinToString(" ").ifEmpty { "--upgrade-all" }),
                cwd = ctx.layout.shorten(ctx.workspace),
            ) { log -> packages.install(names, upgradeAll, progressInto(log)) }
        } catch (e: PackageException) {
            return ToolResult.error(e.message ?: "The install failed.")
        }

        val text = describe(report, packages)
        val headline = when {
            report.failure != null && report.installed.isEmpty() -> "Not installed"
            report.failure != null -> "Partly installed: ${report.installed.size} package(s)"
            report.installed.isEmpty() -> "Nothing to install"
            else -> "Installed " + report.installed.joinToString(", ") { "${it.name} ${it.version}" }
        }
        return ToolResult(
            content = text,
            isError = report.failure != null || (report.installed.isEmpty() && report.unknown.isNotEmpty()),
            display = ToolDisplay.Plain("$headline\n\n$text"),
        )
    }

    private fun describe(report: PackageManager.InstallReport, packages: PackageManager): String = buildString {
        report.failure?.let { appendLine(it).appendLine() }

        if (report.installed.isNotEmpty()) {
            appendLine(
                "Installed ${report.installed.size} package(s): ${formatBytes(report.downloadBytes)} " +
                    "downloaded, about ${formatBytes(report.installedBytes)} on disk.",
            )
            for (change in report.installed) {
                val why = when (change.reason) {
                    PackageResolver.Reason.REQUESTED -> "requested"
                    PackageResolver.Reason.RECOMMENDED -> "recommended"
                    PackageResolver.Reason.DEPENDENCY -> "dependency"
                    PackageResolver.Reason.UPGRADE -> "upgrade"
                }
                val from = change.previousVersion?.let { ", was $it" }.orEmpty()
                appendLine("  ${change.name} ${change.version} ($why$from)")
            }

            // Name the new commands, so the next step does not start with a `which`.
            val changed = report.installed.map { it.name }.toSet()
            val commands = packages.installed()
                .filter { it.name in changed }
                .flatMap { pkg -> pkg.files.filter { it.startsWith("bin/") && it.count { c -> c == '/' } == 1 } }
                .map { it.removePrefix("bin/") }
                .sorted()
            if (commands.isNotEmpty()) {
                val shown = commands.take(MAX_COMMANDS)
                append("New commands: ").append(shown.joinToString(" "))
                if (commands.size > shown.size) append(" … and ${commands.size - shown.size} more")
                appendLine()
            }
        }

        if (report.alreadyInstalled.isNotEmpty()) {
            appendLine("Already installed and current: ${report.alreadyInstalled.joinToString(", ")}")
        }
        for (name in report.unknown) {
            val similar = report.suggestions[name].orEmpty()
            appendLine(
                "No package named '$name'" +
                    if (similar.isNotEmpty()) " — similar: ${similar.joinToString(", ")}" else " — try pkg_search",
            )
        }
        if (report.missing.isNotEmpty()) {
            appendLine("Unsatisfiable dependencies: ${report.missing.joinToString("; ")}")
        }
        if (report.skipped.isNotEmpty()) {
            appendLine(
                "Not installed by design: ${report.skipped.joinToString(", ")} — Termux's own package " +
                    "management and app integration, which this app replaces.",
            )
        }
        if (report.scriptsSkipped.isNotEmpty()) {
            appendLine(
                "Install scripts not run: ${report.scriptsSkipped.joinToString(", ")}. They are written " +
                    "for the Termux app; for most packages they only print notes or pre-compile files.",
            )
        }
        if (report.keptConfig.isNotEmpty()) {
            appendLine("Kept existing configuration: ${report.keptConfig.joinToString(", ")}")
        }
        if (report.warnings.isNotEmpty()) {
            appendLine("Notes:")
            report.warnings.take(MAX_WARNINGS).forEach { appendLine("  - $it") }
            if (report.warnings.size > MAX_WARNINGS) appendLine("  - … ${report.warnings.size - MAX_WARNINGS} more")
        }
        if (isEmpty()) append("Nothing to do.")
    }.trimEnd()

    private companion object {
        const val MAX_COMMANDS = 40
        const val MAX_WARNINGS = 12
    }
}

/* -------------------------------------------------------------- pkg_search */

class PkgSearchTool : Tool {
    override val name = "pkg_search"
    override val readOnly = true
    override val taskClass = TaskClass.BACKGROUND

    /** The first search downloads the index (under 1 MB). */
    override val timeoutMs: Long = 5 * 60_000L

    override val description = """
        Search the Termux package repository, or list what is installed.

        When to use it: before pkg_install when you are not sure of a package's exact
        name, or to check what is installed and at which version. It covers Termux
        packages only, not PyPI or the npm registry.

        Every word of `query` must appear in a package's name or description, so use
        one or two distinctive words; exact and prefix name matches rank first.
        `installed: true` lists installed packages instead, narrowed by name when a
        query is given.

        Output: one line per package with its name, version, download size, installed
        version if any, and description, plus the total when more than `limit` match.
        The installed listing gives the total size and marks what came in as a
        dependency.
    """.trimIndent()

    override val parameters = Schema.obj {
        string("query", "Words to match against package names and descriptions.")
        boolean("installed", "List installed packages instead of searching.", default = false)
        integer("limit", "Most results to return.", default = 20, minimum = 1, maximum = 100)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val packages = ctx.packages ?: return unavailable()
        val limit = args.int("limit", 20).coerceIn(1, 100)

        if (args.bool("installed", false)) {
            val installed = packages.installed()
            if (installed.isEmpty()) return ToolResult.ok("No packages are installed yet.")
            val query = args.str("query")?.trim()?.lowercase().orEmpty()
            val shown = installed.filter { query.isEmpty() || query in it.name.lowercase() }
            return ToolResult.ok(
                buildString {
                    appendLine("${shown.size} installed package(s), ${formatBytes(shown.sumOf { it.sizeBytes })}:")
                    shown.forEach { pkg ->
                        append("  ${pkg.name} ${pkg.version}")
                        if (!pkg.explicit) append(" (dependency)")
                        if (pkg.description.isNotEmpty()) append(" — ${pkg.description}")
                        appendLine()
                    }
                }.trimEnd(),
            )
        }

        val query = args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.error("Give a 'query', or set installed: true.")
        val result = try {
            // The first search, and one every few hours, downloads the index.
            withLiveLog(ctx.onOutput) { log -> packages.search(query, limit, progressInto(log)) }
        } catch (e: PackageException) {
            return ToolResult.error(e.message ?: "Search failed.")
        }
        if (result.hits.isEmpty()) {
            return ToolResult.ok("No package matches '$query'." + result.warning?.let { "\n$it" }.orEmpty())
        }
        return ToolResult.ok(
            buildString {
                appendLine(
                    if (result.totalMatches > result.hits.size) {
                        "${result.hits.size} of ${result.totalMatches} matches for '$query':"
                    } else {
                        "${result.hits.size} match(es) for '$query':"
                    },
                )
                result.hits.forEach { hit ->
                    append("  ${hit.name} ${hit.version} (${formatBytes(hit.downloadBytes)})")
                    hit.installedVersion?.let { append(if (it == hit.version) " [installed]" else " [installed: $it]") }
                    if (hit.description.isNotEmpty()) append(" — ${hit.description}")
                    appendLine()
                }
                result.warning?.let { appendLine(it) }
            }.trimEnd(),
        )
    }
}

/* -------------------------------------------------------------- pkg_remove */

class PkgRemoveTool : Tool {
    override val name = "pkg_remove"
    override val taskClass = TaskClass.BACKGROUND
    override val parallelSafe = false

    override val description = """
        Remove installed packages, with what only came along with them.

        When to use it: the user wants a package gone, or one you installed turned out
        to be unnecessary. Files that pip or npm added are not tracked and stay, so
        uninstall those with pip or npm through sh_exec before removing the runtime.

        Installed packages that depend on a removed one and were not asked for by name
        go with it (pip with python). One that was asked for by name blocks the removal
        instead; name both to remove them together. With `autoremove` (the default),
        dependencies that only the removed packages needed go too.

        Output: what was removed, what went along, the unused dependencies cleared and
        the space freed; anything kept, with the packages that still need it; and names
        that were not installed.
    """.trimIndent()

    override val parameters = Schema.obj {
        stringArray("packages", "Package names to remove.")
        string("package", "One package name; the same as packages: [name].")
        boolean("autoremove", "Also remove dependencies left unused.", default = true)
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val names = args.packageNames()
        if (names.isEmpty()) return ToolResult.error("Name at least one package in 'packages'.")
        val packages = ctx.packages ?: return unavailable()

        val report = withLiveLog(
            ctx.onOutput,
            echo = ctx.echo,
            title = "pkg uninstall " + names.joinToString(" "),
            cwd = ctx.layout.shorten(ctx.workspace),
        ) { log ->
            packages.remove(names, autoremove = args.bool("autoremove", true), progress = progressInto(log))
        }
        val text = buildString {
            if (report.removed.isNotEmpty()) {
                appendLine("Removed: ${report.removed.joinToString(", ")}")
            }
            if (report.dependentsRemoved.isNotEmpty()) {
                appendLine("Removed along, as they depend on it and were not asked for: ${report.dependentsRemoved.joinToString(", ")}")
            }
            if (report.autoRemoved.isNotEmpty()) {
                appendLine("Dependencies no longer needed, removed: ${report.autoRemoved.joinToString(", ")}")
            }
            if (report.removed.isNotEmpty()) {
                appendLine("Freed about ${formatBytes(report.freedBytes)}.")
            }
            report.blocked.forEach { (name, dependents) ->
                appendLine(
                    "Kept $name: ${dependents.joinToString(", ")} still need(s) it. Remove them together " +
                        "(packages: [$name, ${dependents.joinToString(", ")}]) or leave it.",
                )
            }
            if (report.notInstalled.isNotEmpty()) {
                appendLine("Not installed: ${report.notInstalled.joinToString(", ")}")
            }
            report.warnings.take(10).forEach { appendLine("Note: $it") }
            if (isEmpty()) append("Nothing was removed.")
        }.trimEnd()
        return ToolResult(
            content = text,
            isError = report.removed.isEmpty(),
            display = ToolDisplay.Plain(text),
        )
    }
}
