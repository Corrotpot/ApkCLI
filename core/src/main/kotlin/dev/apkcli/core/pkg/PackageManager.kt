package dev.apkcli.core.pkg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.MessageDigest

/**
 * Installs Termux packages into `$PREFIX`, without apt or dpkg.
 *
 * Termux's own tools cannot be used: apt and dpkg have Termux's paths compiled
 * in and keep their state under them. What a `.deb` needs is small enough to do
 * directly — resolve the dependency closure ([PackageResolver]), download and
 * verify each archive, unpack it with its hardcoded paths rewritten
 * ([Relocation]), and record which files it owns ([PackageStore]).
 *
 * What is deliberately not done:
 *  * Maintainer scripts are not run. They are bash scripts written for
 *    Termux's layout; for the packages people actually ask for they only
 *    print notes or byte-compile Python, which happens on first import anyway.
 *    Each report names the packages that had them.
 *  * No signature check of the repository: see [RepoIndex].
 *
 * Everything is serialised behind one lock — two installs interleaving over
 * the same prefix and database would corrupt both.
 */
class PackageManager(
    private val config: Config,
    private val files: FileOps,
    private val fetcher: PackageFetcher,
) {

    class Config(
        /** The real `$PREFIX`. */
        val prefix: File,
        val home: File,
        /** `$STATE/pkg`: the index and downloads. Disposable. */
        val stateDir: File,
        /** Termux's name for the device's ABI: `aarch64`, `arm`, `x86_64`, `i686`. */
        val arch: String,
        val repoUrl: String = DEFAULT_REPO,
        /** Where Termux's paths should point; asked once per install. */
        val relocation: () -> RelocationChoice,
        /** Bytes free on the prefix's filesystem. */
        val freeBytes: () -> Long,
        val clock: () -> Long = System::currentTimeMillis,
        val indexTtlMs: Long = RepoIndex.DEFAULT_TTL_MS,
    )

    /** @param note why the preferred alias mode was not available, when it was not. */
    class RelocationChoice(val relocation: Relocation, val note: String?)

    data class Change(
        val name: String,
        val version: String,
        val previousVersion: String?,
        val reason: PackageResolver.Reason,
    )

    class InstallReport(
        val installed: List<Change>,
        val alreadyInstalled: List<String>,
        val unknown: List<String>,
        /** For each unknown name, packages with similar names. */
        val suggestions: Map<String, List<String>>,
        val missing: List<String>,
        val skipped: List<String>,
        /** `package (postinst, prerm)` for packages whose scripts were not run. */
        val scriptsSkipped: List<String>,
        /** Configuration files kept as the user had them during an upgrade. */
        val keptConfig: List<String>,
        val warnings: List<String>,
        val relocation: String?,
        val downloadBytes: Long,
        val installedBytes: Long,
        /** Why the install stopped, when it did; [installed] lists what finished before. */
        val failure: String?,
    ) {
        val succeeded: Boolean get() = failure == null && unknown.isEmpty()
    }

    class RemoveReport(
        val removed: List<String>,
        /** Not asked for by name and depending on what was removed, so removed too (pip with python). */
        val dependentsRemoved: List<String>,
        /** Dependencies nothing else needs any more, removed with them. */
        val autoRemoved: List<String>,
        val notInstalled: List<String>,
        /** Not removed: `package -> installed packages that depend on it`. */
        val blocked: Map<String, List<String>>,
        val freedBytes: Long,
        val warnings: List<String>,
    )

    class SearchHit(
        val name: String,
        val version: String,
        val description: String,
        val installedVersion: String?,
        val downloadBytes: Long,
    )

    class SearchResult(val hits: List<SearchHit>, val totalMatches: Int, val warning: String?)

    private val mutex = Mutex()

    private val store = PackageStore(File(config.prefix, DB_PATH))

    private val index = RepoIndex(
        repoUrl = config.repoUrl,
        arch = config.arch,
        cacheDir = config.stateDir,
        fetcher = fetcher,
        ttlMs = config.indexTtlMs,
        clock = config.clock,
    )

    /** Symlink targets need no length budget, so they always get the real path. */
    private val linkRelocation = Relocation.direct(config.prefix, config.home)

    fun installed(): List<InstalledPackage> = store.load().values.sortedBy { it.name }

    /** True when anything was ever installed, i.e. there are files that depend on the relocation. */
    fun hasPackages(): Boolean = store.load().isNotEmpty()

    /**
     * Re-create the prefix alias (or the partial ones) when packages rely on
     * it. Cheap; called at startup so a deleted link repairs itself before
     * anything runs.
     */
    fun ensureRelocation(): RelocationChoice? =
        if (store.load().values.any { Relocation.usesLinks(it.relocation) }) config.relocation() else null

    /* ------------------------------------------------------------- install */

    /**
     * Install [names] and everything they need, upgrading installed
     * dependencies to the repository's versions on the way.
     *
     * @param upgradeAll also bring every other installed package up to date.
     */
    suspend fun install(
        names: List<String>,
        upgradeAll: Boolean = false,
        progress: PackageProgress = PackageProgress.NONE,
    ): InstallReport = mutex.withLock {
        withContext(Dispatchers.IO) { doInstall(names, upgradeAll, progress) }
    }

    private suspend fun doInstall(
        names: List<String>,
        upgradeAll: Boolean,
        progress: PackageProgress,
    ): InstallReport {
        val snapshot = index.load(progress = progress)
        val db = store.load().toMutableMap()
        val plan = PackageResolver(snapshot.packages, db).plan(names, withRecommends = true, upgradeInstalled = upgradeAll)
        val notes = Notes()
        snapshot.warning?.let(notes.warnings::add)
        val suggestions = plan.unknown.associateWith { suggest(it, snapshot.packages) }

        fun report(installed: List<Change>, relocation: Relocation?, failure: String?) = InstallReport(
            installed = installed,
            alreadyInstalled = plan.alreadyInstalled,
            unknown = plan.unknown,
            suggestions = suggestions,
            missing = plan.missing,
            skipped = plan.skipped,
            scriptsSkipped = notes.scriptsSkipped,
            keptConfig = notes.keptConfig,
            warnings = notes.warnings + notes.missingInterpreters(config.prefix, files),
            relocation = relocation?.describe(),
            downloadBytes = plan.downloadBytes,
            installedBytes = plan.installedBytes,
            failure = failure,
        )

        // Asking by name for something that came in as a dependency makes it explicit.
        plan.alreadyInstalled.forEach { name -> db[name]?.takeIf { !it.explicit }?.let { db[name] = it.copy(explicit = true) } }

        if (plan.missing.isNotEmpty()) {
            store.save(db)
            return report(
                emptyList(),
                null,
                "Nothing was installed: the repository has nothing that satisfies " +
                    plan.missing.joinToString("; ") + ".",
            )
        }
        if (plan.steps.isEmpty()) {
            store.save(db)
            return report(emptyList(), null, null)
        }
        plan.steps.firstOrNull { it.info.sha256.isEmpty() }?.let { step ->
            return report(emptyList(), null, "The index lists no checksum for ${step.info.name}; refusing to install it unverified.")
        }

        val needed = plan.downloadBytes + plan.installedBytes + SPACE_MARGIN
        val free = config.freeBytes()
        if (free in 0 until needed) {
            return report(
                emptyList(),
                null,
                "Not enough storage: this needs about ${formatBytes(needed)} " +
                    "(${formatBytes(plan.downloadBytes)} download, ${formatBytes(plan.installedBytes)} installed, " +
                    "plus a margin) and ${formatBytes(free)} is free.",
            )
        }

        val choice = config.relocation()
        choice.note?.let(notes.warnings::add)
        val relocation = choice.relocation
        progress.step(describePlan(plan))

        // Download everything before touching the prefix, so a dropped
        // connection cannot leave half a dependency closure installed.
        val cache = File(config.stateDir, "cache").apply { mkdirs() }
        val archives = LinkedHashMap<String, File>()
        val total = plan.steps.size
        try {
            for ((i, step) in plan.steps.withIndex()) {
                currentCoroutineContext().ensureActive()
                val info = step.info
                // No version in the live lines: Termux's run long
                // (6.6.20260307+really6.5.20250830) and pushed the percentage
                // off a phone screen. The report lists them.
                val label = "${i + 1}/$total ${info.name}"
                val size = formatBytes(info.size)
                progress.status("Downloading $label, $size")
                val dest = File(cache, debFileName(info))
                val cached = dest.isFile && dest.length() == info.size && sha256(dest) == info.sha256
                if (!cached) {
                    val startedAt = config.clock()
                    fetcher.download(packageUrl(info), dest, info.size, info.sha256) { received ->
                        progress.status("Downloading $label ${transferred(received, info.size, startedAt)}")
                    }
                }
                progress.step("Downloaded $label, $size" + if (cached) " (cached)" else "")
                archives[info.name] = dest
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return report(emptyList(), relocation, "Download failed, nothing was installed: ${e.message ?: e.javaClass.simpleName}")
        }

        val done = mutableListOf<Change>()
        for ((i, step) in plan.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val info = step.info
            val label = "${i + 1}/$total ${info.name}"
            progress.status("Unpacking $label")
            try {
                val record = unpack(step, archives.getValue(info.name), relocation, db, notes) { files, bytes ->
                    progress.status("Unpacking $label: $files files, ${formatBytes(bytes)}")
                }
                db[info.name] = record
                progress.step("Installed $label, ${record.files.size} files")
            } catch (e: Exception) {
                store.save(db)
                if (e is CancellationException) throw e
                return report(
                    done,
                    relocation,
                    "Installing ${info.name} failed: ${e.message ?: e.javaClass.simpleName}. " +
                        "${done.size} of ${plan.steps.size} packages were installed before it; running " +
                        "pkg_install again retries the rest.",
                )
            }
            store.save(db)
            archives.getValue(info.name).delete()
            done += Change(info.name, info.version, step.replacesVersion, step.reason)
        }
        return report(done, relocation, null)
    }

    /** `postgresql 18.6 and 16 more: 20.6 MB to download, about 89.8 MB on disk`. */
    private fun describePlan(plan: PackageResolver.Plan): String {
        val named = plan.steps.filter { it.reason == PackageResolver.Reason.REQUESTED }.map { "${it.info.name} ${it.info.version}" }
        val others = plan.steps.size - named.size
        val what = when {
            named.isEmpty() -> "${plan.steps.size} package(s)"
            others == 0 -> named.joinToString(", ")
            else -> "${named.joinToString(", ")} and $others more"
        }
        return "$what: ${formatBytes(plan.downloadBytes)} to download, about ${formatBytes(plan.installedBytes)} on disk"
    }

    /** `46%, 4.2 of 9.1 MB, 1.3 MB/s` for a download under way: the percentage first, where a narrow screen still shows it. */
    private fun transferred(received: Long, size: Long, startedAt: Long): String = buildString {
        if (size > 0) {
            append(received * 100 / size).append("%, ").append(formatBytes(received)).append(" of ").append(formatBytes(size))
        } else {
            append(formatBytes(received))
        }
        val seconds = (config.clock() - startedAt) / 1000.0
        if (seconds >= 1) append(", ").append(formatBytes((received / seconds).toLong())).append("/s")
    }

    /**
     * Unpack one archive over the prefix and return its database record.
     * [onFiles] hears the files written and their bytes every few entries.
     */
    private suspend fun unpack(
        step: PackageResolver.Step,
        deb: File,
        relocation: Relocation,
        db: MutableMap<String, InstalledPackage>,
        notes: Notes,
        onFiles: (files: Int, bytes: Long) -> Unit = { _, _ -> },
    ): InstalledPackage {
        val info = step.info
        val control = DebArchive.readControl(deb)
        control.fields["Package"]?.let { found ->
            if (found != info.name) throw PackageException("${deb.name} contains $found, not ${info.name}")
        }
        if (control.scripts.isNotEmpty()) notes.scriptsSkipped += "${info.name} (${control.scripts.joinToString(", ")})"

        val previous = db[info.name]
        val conffiles = control.conffiles.mapNotNull(::relativeToPrefix).toSet()
        val owners = HashMap<String, String>()
        for (pkg in db.values) if (pkg.name != info.name) pkg.files.forEach { owners[it] = pkg.name }

        val prefixRoot = config.prefix.canonicalFile
        val written = LinkedHashSet<String>()
        var bytes = 0L
        var entries = 0
        val context = currentCoroutineContext()

        try {
            DebArchive.readData(deb) { tar ->
                while (true) {
                    context.ensureActive()
                    val entry = tar.next() ?: break
                    val rel = relativeToPrefix(entry.name)
                    if (rel == null) {
                        if (entry.type != TarEntry.Type.DIRECTORY) notes.warnings += "${info.name}: skipped ${entry.name}, which is outside the prefix"
                        continue
                    }
                    if (rel.isEmpty()) continue
                    if (isProtected(rel)) {
                        notes.warnings += "${info.name}: kept the app's own \$PREFIX/$rel"
                        continue
                    }
                    val dest = File(config.prefix, rel)
                    requireInside(dest.parentFile ?: config.prefix, prefixRoot, rel)

                    when (entry.type) {
                        TarEntry.Type.DIRECTORY -> {
                            if (files.isSymlink(dest)) continue // another package made it a link; keep that
                            if (!dest.isDirectory && !dest.mkdirs()) throw IOException("cannot create directory $rel")
                            files.chmod(dest, (entry.mode and 0x1ff) or 0x1c0)
                        }

                        TarEntry.Type.FILE -> {
                            if (rel in conffiles && previous != null && dest.exists()) {
                                // An upgrade keeps the user's configuration, as dpkg would.
                                notes.keptConfig += rel
                            } else {
                                bytes += writeFile(tar.data(), dest, rel, entry.mode, relocation, notes)
                            }
                            written += rel
                        }

                        TarEntry.Type.SYMLINK -> {
                            writeSymlink(dest, rel, linkRelocation.relocatePath(entry.linkName))
                            written += rel
                        }

                        TarEntry.Type.HARDLINK -> {
                            val source = relativeToPrefix(entry.linkName)?.let { File(config.prefix, it) }
                            if (source == null || !source.isFile) {
                                notes.warnings += "${info.name}: $rel links to ${entry.linkName}, which is not in the package"
                                continue
                            }
                            bytes += source.inputStream().use { writeFile(it, dest, rel, entry.mode, relocation = null, notes = notes) }
                            written += rel
                        }

                        TarEntry.Type.OTHER -> notes.warnings += "${info.name}: skipped special file $rel"
                    }

                    // Overwriting another package's file takes it over, as
                    // dpkg does for a package that declares Replaces.
                    owners.remove(rel)?.let { other ->
                        db[other]?.let { db[other] = it.copy(files = it.files - rel) }
                    }
                    if (++entries % ENTRIES_PER_UPDATE == 0) onFiles(written.size, bytes)
                }
            }
        } catch (e: Exception) {
            // Record whatever is on disk now, under a version that never
            // matches the repository's, so a retry reinstalls the package and
            // a removal finds every file. A new package that wrote nothing
            // leaves no record at all.
            if (previous == null && written.isEmpty()) {
                db.remove(info.name)
            } else {
                db[info.name] = record(info, step, previous, relocation, (previous?.files.orEmpty() + written).distinct(), bytes)
                    .copy(version = PARTIAL_VERSION)
            }
            throw e
        }

        // Files the old version had and this one does not.
        previous?.files?.filter { it !in written }?.let { stale -> deletePaths(stale, prefixRoot) }

        return record(info, step, previous, relocation, written.toList(), bytes)
    }

    private fun record(
        info: PackageInfo,
        step: PackageResolver.Step,
        previous: InstalledPackage?,
        relocation: Relocation,
        paths: List<String>,
        bytes: Long,
    ) = InstalledPackage(
        name = info.name,
        version = info.version,
        architecture = info.architecture,
        explicit = step.reason == PackageResolver.Reason.REQUESTED || previous?.explicit == true,
        installedAt = config.clock(),
        depends = info.depends,
        recommends = info.recommends,
        provides = info.provides,
        files = paths,
        sizeBytes = bytes,
        relocation = relocation.describe(),
        description = info.description,
    )

    /**
     * Write one file through a temporary name and rename it into place, so a
     * running program never sees half a binary.
     *
     * @param relocation null to copy verbatim (hard links repeat a file that
     *   was already rewritten).
     */
    private fun writeFile(
        input: InputStream,
        dest: File,
        rel: String,
        mode: Int,
        relocation: Relocation?,
        notes: Notes,
    ): Long {
        val parent = dest.parentFile ?: config.prefix
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create ${parent.path}")
        if (dest.isDirectory && !files.isSymlink(dest)) throw IOException("$rel: a directory is in the way")

        val head = ByteArray(ContentKind.SNIFF_BYTES)
        val headLength = readUpTo(input, head)
        val kind = ContentKind.classify(rel, head, headLength)
        if (kind == ContentKind.TEXT) shebangInterpreter(head, headLength)?.let { notes.interpreters.getOrPut(it) { mutableListOf() } += rel }

        val tmp = File(parent, ".${dest.name}.apkcli-tmp")
        tmp.delete()
        try {
            val stream = SequenceInputStream(ByteArrayInputStream(head, 0, headLength), input)
            FileOutputStream(tmp).buffered(64 * 1024).use { out ->
                val replacer = when {
                    relocation == null -> null
                    kind == ContentKind.TEXT -> relocation.replacer
                    kind == ContentKind.ELF || kind == ContentKind.BINARY -> relocation.binaryReplacer
                    else -> null
                }
                if (replacer != null) replacer.copy(stream, out) else stream.copyTo(out, 64 * 1024)
            }
            if (relocation != null && kind == ContentKind.ELF && !relocation.inPlace) {
                val patched = ElfRunpath.relocate(tmp, rel)
                if (patched is ElfRunpath.Result.Skipped) notes.warnings += "$rel: library path left as is (${patched.reason})"
            }
            // Owner read/write always: removal and upgrades must be able to replace it.
            files.chmod(tmp, (mode and 0x1ff) or 0x180)
            val size = tmp.length()
            files.rename(tmp, dest)
            return size
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun writeSymlink(dest: File, rel: String, target: String) {
        val parent = dest.parentFile ?: config.prefix
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create ${parent.path}")
        if (dest.isDirectory && !files.isSymlink(dest)) throw IOException("$rel: a directory is in the way")
        val tmp = File(parent, ".${dest.name}.apkcli-tmp")
        tmp.delete()
        files.symlink(target, tmp)
        files.rename(tmp, dest)
    }

    /* -------------------------------------------------------------- remove */

    suspend fun remove(
        names: List<String>,
        autoremove: Boolean = true,
        progress: PackageProgress = PackageProgress.NONE,
    ): RemoveReport = mutex.withLock {
        withContext(Dispatchers.IO) { doRemove(names, autoremove, progress) }
    }

    private fun doRemove(names: List<String>, autoremove: Boolean, progress: PackageProgress): RemoveReport {
        val db = store.load().toMutableMap()
        val resolver = PackageResolver(emptyMap(), db)
        val targets = LinkedHashSet<String>()
        val notInstalled = mutableListOf<String>()
        for (name in names.map(String::trim).filter(String::isNotEmpty).distinct()) {
            val concrete = db[name]?.name ?: db.values.firstOrNull { name in it.provides }?.name
            if (concrete == null) notInstalled += name else targets += concrete
        }

        // What only came along with a target goes with it: removing python
        // takes pip and its wheels, which depend on python and were never
        // asked for by name. Something asked for by name is never removed
        // implicitly: it blocks the removal instead. Blocking one target can
        // expose another, hence the loop.
        val requested = LinkedHashSet(targets)
        val blocked = LinkedHashMap<String, List<String>>()
        var plan: Set<String>
        while (true) {
            val cascades = requested.associateWith { cascade(it, resolver, db) }
            plan = cascades.values.flatten().toSet()
            val stopped = requested.filter { target ->
                val breaks = cascades.getValue(target)
                    .flatMap { resolver.dependents(it, excluding = plan) }
                    .distinct()
                    .sorted()
                if (breaks.isNotEmpty()) blocked[target] = breaks
                breaks.isNotEmpty()
            }
            if (stopped.isEmpty()) break
            requested.removeAll(stopped.toSet())
        }
        targets.clear()
        targets.addAll(plan)
        val alsoRemoved = (plan - requested).sorted()

        // Only what this removal orphans, not whatever was already loose.
        val autoRemoved = if (autoremove && targets.isNotEmpty()) {
            (resolver.orphans(targets) - resolver.orphans(emptySet()) - targets).sorted()
        } else {
            emptyList()
        }

        val prefixRoot = config.prefix.canonicalFile
        val warnings = mutableListOf<String>()
        var freed = 0L
        val doomed = (targets + autoRemoved).toList()
        for ((i, name) in doomed.withIndex()) {
            val pkg = db[name] ?: continue
            val label = "${i + 1}/${doomed.size} $name"
            progress.status("Removing $label, ${pkg.files.size} files")
            warnings += deletePaths(pkg.files, prefixRoot)
            freed += pkg.sizeBytes
            db.remove(name)
            store.save(db)
            progress.step("Removed $label")
        }
        return RemoveReport(
            removed = targets.filter { it in requested },
            dependentsRemoved = alsoRemoved,
            autoRemoved = autoRemoved,
            notInstalled = notInstalled,
            blocked = blocked,
            freedBytes = freed,
            warnings = warnings,
        )
    }

    /** [seed] and, transitively, the installed packages that depend on it without having been asked for. */
    private fun cascade(seed: String, resolver: PackageResolver, db: Map<String, InstalledPackage>): Set<String> {
        val out = linkedSetOf(seed)
        do {
            var grew = false
            for (member in out.toList()) {
                for (dependent in resolver.dependents(member, excluding = out)) {
                    if (db[dependent]?.explicit == false && out.add(dependent)) grew = true
                }
            }
        } while (grew)
        return out
    }

    /** Delete files and links under the prefix, then the directories they leave empty. */
    private fun deletePaths(paths: List<String>, prefixRoot: File): List<String> {
        val warnings = mutableListOf<String>()
        val parents = HashSet<File>()
        for (rel in paths) {
            if (isProtected(rel)) continue
            val file = File(config.prefix, rel)
            val inside = runCatching { requireInside(file.parentFile ?: config.prefix, prefixRoot, rel) }.isSuccess
            if (!inside) {
                warnings += "left $rel: it resolves outside the prefix"
                continue
            }
            val isLink = files.isSymlink(file)
            if (!isLink && file.isDirectory) continue
            if ((isLink || file.exists()) && !file.delete()) warnings += "could not delete $rel"
            file.parentFile?.let(parents::add)
        }
        // Deepest first, so a chain of emptied directories goes in one pass.
        for (dir in parents.sortedByDescending { it.path.length }) pruneEmpty(dir)
        return warnings
    }

    private fun pruneEmpty(start: File) {
        val root = config.prefix.absoluteFile
        var dir: File? = start.absoluteFile
        while (dir != null && dir != root) {
            val rel = dir.path.removePrefix(root.path).trimStart('/', '\\').replace('\\', '/')
            if (rel in KEPT_DIRECTORIES || files.isSymlink(dir)) return
            val children = dir.list() ?: return
            if (children.isNotEmpty() || !dir.delete()) return
            dir = dir.parentFile
        }
    }

    /* -------------------------------------------------------------- search */

    suspend fun search(
        query: String,
        limit: Int = 20,
        progress: PackageProgress = PackageProgress.NONE,
    ): SearchResult = withContext(Dispatchers.IO) {
        val snapshot = mutex.withLock { index.load(progress = progress) }
        val db = store.load()
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val scored = snapshot.packages.values.mapNotNull { info ->
            val name = info.name.lowercase()
            val text = info.description.lowercase()
            if (words.isEmpty()) return@mapNotNull null
            if (!words.all { it in name || it in text || info.provides.any { p -> it in p.lowercase() } }) return@mapNotNull null
            val first = words.first()
            val score = when {
                name == first -> 0
                name.startsWith(first) -> 1
                first in name -> 2
                else -> 3
            }
            score to info
        }.sortedWith(compareBy<Pair<Int, PackageInfo>> { it.first }.thenBy { it.second.name.length }.thenBy { it.second.name })
        SearchResult(
            hits = scored.take(limit).map { (_, info) ->
                SearchHit(info.name, info.version, info.description, db[info.name]?.version, info.size)
            },
            totalMatches = scored.size,
            warning = snapshot.warning,
        )
    }

    private fun suggest(name: String, packages: Map<String, PackageInfo>): List<String> {
        val needle = name.lowercase()
        return packages.keys
            .filter { it.contains(needle) || needle.contains(it) && it.length >= 3 }
            .sortedWith(compareBy<String> { !it.startsWith(needle) }.thenBy { it.length })
            .take(5)
    }

    /* ------------------------------------------------------------- helpers */

    /** Termux's archive path → path under the prefix; `""` for the prefix itself, null outside it. */
    private fun relativeToPrefix(archivePath: String): String? {
        val path = archivePath.removePrefix("./").trimStart('/').trimEnd('/')
        val base = Relocation.TERMUX_PREFIX.trimStart('/')
        if (path == base) return ""
        if (!path.startsWith("$base/")) return null
        val rel = path.removePrefix("$base/")
        if (rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw PackageException("refusing unsafe path in package: $archivePath")
        }
        return rel
    }

    /** The canonical [dir] must stay under the prefix: a symlinked directory must not carry writes out. */
    private fun requireInside(dir: File, prefixRoot: File, rel: String) {
        val canonical = dir.canonicalFile
        val root = prefixRoot.path
        if (canonical.path != root && !canonical.path.startsWith(root + File.separator)) {
            throw PackageException("refusing $rel: its directory resolves to ${canonical.path}, outside the prefix")
        }
    }

    private fun packageUrl(info: PackageInfo): String = config.repoUrl.trimEnd('/') + "/" + info.filename.trimStart('/')

    private class Notes {
        val warnings = mutableListOf<String>()
        val scriptsSkipped = mutableListOf<String>()
        val keptConfig = mutableListOf<String>()

        /** Interpreter path under the prefix (`bin/bash`) -> scripts that use it. */
        val interpreters = LinkedHashMap<String, MutableList<String>>()

        /** Scripts whose interpreter is not installed: a Termux package may assume bash is. */
        fun missingInterpreters(prefix: File, files: FileOps): List<String> = interpreters
            .filter { (interpreter, _) -> File(prefix, interpreter).let { !it.exists() && !files.isSymlink(it) } }
            .map { (interpreter, scripts) ->
                "${scripts.size} installed script(s) run \$PREFIX/$interpreter, which is not installed " +
                    "(e.g. ${scripts.first()}); install the package that provides `${interpreter.substringAfterLast('/')}` to use them."
            }
    }

    /** `#!/data/data/com.termux/files/usr/bin/bash -e` -> `bin/bash`; null for anything outside the prefix. */
    private fun shebangInterpreter(head: ByteArray, length: Int): String? {
        if (length < 3 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return null
        var end = 2
        while (end < length && head[end] != '\n'.code.toByte()) end++
        val line = String(head, 2, end - 2, Charsets.UTF_8).trim()
        val interpreter = line.split(' ', '\t').firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { relativeToPrefix(interpreter) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val DEFAULT_REPO = "https://packages.termux.dev/apt/termux-main"

        /** Inside the prefix, next to the files it describes; see [PackageStore]. */
        const val DB_PATH = "var/lib/apkcli/packages.json"

        /** Never more than this close to a full disk; SQLite and the editor need room too. */
        const val SPACE_MARGIN = 50L * 1024 * 1024

        private const val PARTIAL_VERSION = "(partial)"

        /** The app's own files under the prefix, which no package may replace. */
        private val PROTECTED = setOf("etc/profile", "etc/tool-env.sh")

        private fun isProtected(rel: String): Boolean =
            rel in PROTECTED || rel == "var/lib/apkcli" || rel.startsWith("var/lib/apkcli/")

        /** Archive entries between two progress updates while unpacking. */
        private const val ENTRIES_PER_UPDATE = 25

        /** Directories the bootstrap creates; never pruned, even when a removal empties them. */
        private val KEPT_DIRECTORIES = setOf("bin", "lib", "etc", "share", "tmp", "var", "var/lib", "var/lib/apkcli")

        /** Termux's architecture names, from Android's ABI names. */
        fun termuxArch(abi: String): String? = when (abi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> null
        }

        private fun debFileName(info: PackageInfo): String =
            "${info.name}_${info.version}_${info.architecture}.deb".replace(Regex("[^A-Za-z0-9._+-]"), "_")

        private fun readUpTo(input: InputStream, into: ByteArray): Int {
            var read = 0
            while (read < into.size) {
                val n = input.read(into, read, into.size - read)
                if (n < 0) break
                read += n
            }
            return read
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
