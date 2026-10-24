package dev.apkcli.core.pkg

/**
 * Works out what to download, in what order.
 *
 * The repository is rolling: it carries one version of each package, built
 * against the other packages in the same snapshot. So instead of evaluating
 * version constraints, the rule is "everything in the closure of what is being
 * installed is brought to the repository's version". A dependency that is
 * installed but older is upgraded along with the package that needs it, which
 * is what keeps a newly installed Python from loading last year's OpenSSL.
 *
 * Recommended packages are followed for the packages asked for by name only —
 * that is how `python` brings `pip` — not transitively, which in Debian
 * metadata quickly reaches documentation and GUI toolkits.
 */
class PackageResolver(
    private val index: Map<String, PackageInfo>,
    private val installed: Map<String, InstalledPackage>,
) {

    enum class Reason { REQUESTED, DEPENDENCY, RECOMMENDED, UPGRADE }

    data class Step(
        val info: PackageInfo,
        val reason: Reason,
        /** The installed version this replaces, or null for a new package. */
        val replacesVersion: String?,
    )

    data class Plan(
        /** Dependencies before the packages that need them. */
        val steps: List<Step>,
        /** Asked for, and already at the repository's version. */
        val alreadyInstalled: List<String>,
        /** Package-management plumbing that does not apply here; see [NEVER_INSTALLED]. */
        val skipped: List<String>,
        /** Asked for, but neither a package nor something a package provides. */
        val unknown: List<String>,
        /** `name (needed by owner)` for dependencies nothing in the repository satisfies. */
        val missing: List<String>,
    ) {
        val downloadBytes: Long get() = steps.sumOf { it.info.size }
        val installedBytes: Long get() = steps.sumOf { it.info.installedSizeKb * 1024 }
    }

    private val providers: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (info in index.values) {
            for (virtual in info.provides) getOrPut(virtual) { mutableListOf() } += info.name
        }
    }

    /**
     * The real package a name refers to: itself, or a provider — an installed
     * one first, so asking for `python3` when `python` is installed is a no-op.
     */
    fun resolveName(name: String): String? {
        if (name in index) return name
        val candidates = providers[name] ?: return installed[name]?.name
            ?: installed.values.firstOrNull { name in it.provides }?.name
        return candidates.firstOrNull { it in installed } ?: candidates.first()
    }

    fun plan(
        requested: List<String>,
        withRecommends: Boolean = true,
        upgradeInstalled: Boolean = false,
    ): Plan = PlanBuilder(withRecommends).run {
        for (name in requested.map(String::trim).filter(String::isNotEmpty).distinct()) {
            request(name)
        }
        if (upgradeInstalled) {
            installed.keys.filter { it in index }.sorted().forEach { visit(it, Reason.UPGRADE) }
        }
        build()
    }

    /**
     * One planning pass. A class rather than local functions because visiting
     * a package and visiting one of its dependency clauses recurse into each
     * other, and local functions cannot refer forwards.
     */
    private inner class PlanBuilder(private val withRecommends: Boolean) {
        private val steps = LinkedHashMap<String, Step>()
        private val done = HashSet<String>()
        private val visiting = HashSet<String>()
        private val alreadyInstalled = LinkedHashSet<String>()
        private val skipped = LinkedHashSet<String>()
        private val unknown = mutableListOf<String>()
        private val missing = LinkedHashSet<String>()

        fun request(name: String) {
            if (name in NEVER_INSTALLED) {
                skipped += name
                return
            }
            val concrete = resolveName(name)
            when {
                concrete == null -> unknown += name
                concrete in index -> visit(concrete, Reason.REQUESTED)
                // Installed, but the repository dropped it: nothing to fetch.
                concrete in installed -> alreadyInstalled += concrete
                else -> unknown += name
            }
        }

        /** Depth first, so each package lands after everything it needs. */
        fun visit(name: String, reason: Reason) {
            if (name in NEVER_INSTALLED) {
                skipped += name
                return
            }
            // `visiting` breaks dependency cycles, which Debian metadata allows.
            if (name in done || name in visiting) return
            val info = index[name] ?: return
            visiting += name
            info.depends.forEach { clause -> visitClause(clause, info.name, Reason.DEPENDENCY, optional = false) }
            if (withRecommends && reason == Reason.REQUESTED) {
                info.recommends.forEach { clause -> visitClause(clause, info.name, Reason.RECOMMENDED, optional = true) }
            }
            visiting -= name
            done += name

            val current = installed[name]
            if (current == null || current.version != info.version) {
                steps[name] = Step(info, reason, current?.version)
            } else if (reason == Reason.REQUESTED) {
                alreadyInstalled += name
            }
        }

        /** `a | b | c`: the first alternative that is already on the way, installed, or available. */
        private fun visitClause(clause: List<String>, owner: String, reason: Reason, optional: Boolean) {
            if (clause.all { it in NEVER_INSTALLED }) {
                skipped += clause
                return
            }
            val concrete = clause.flatMap { alternative ->
                buildList {
                    if (alternative in index || alternative in installed) add(alternative)
                    providers[alternative]?.let(::addAll)
                    installed.values.filter { alternative in it.provides }.forEach { add(it.name) }
                }
            }.distinct().filter { it !in NEVER_INSTALLED }

            val satisfiedBy = concrete.firstOrNull { it in done || it in visiting || it in steps }
                ?: concrete.firstOrNull { it in installed && it in index }
                ?: concrete.firstOrNull { it in index }
                ?: concrete.firstOrNull { it in installed }

            when {
                satisfiedBy == null -> if (!optional) missing += "${clause.joinToString(" | ")} (needed by $owner)"
                // Installed but no longer in the repository: keep what is there.
                satisfiedBy !in index -> Unit
                else -> visit(satisfiedBy, reason)
            }
        }

        fun build() = Plan(
            steps = steps.values.toList(),
            alreadyInstalled = alreadyInstalled.toList(),
            skipped = skipped.toList(),
            unknown = unknown,
            missing = missing.toList(),
        )
    }

    /**
     * Installed packages that depend on [name] (or on something it provides),
     * other than those in [excluding]. Used to refuse a removal that would
     * break them.
     */
    fun dependents(name: String, excluding: Set<String>): List<String> {
        val target = installed[name] ?: return emptyList()
        val satisfies = setOf(name) + target.provides
        val gone = excluding + name
        return installed.values
            .filter { it.name !in gone }
            .filter { pkg ->
                pkg.depends.any { clause ->
                    // A clause another remaining package also satisfies is not
                    // broken by this removal.
                    clause.any { it in satisfies } &&
                        clause.none { alternative -> isInstalledOrProvided(alternative, gone) }
                }
            }
            .map { it.name }
            .sorted()
    }

    /**
     * Installed packages nothing explicit needs any more, after [removing] is
     * gone: the dependencies that came with it.
     *
     * Recommends count as needs here, as they do for apt's autoremove: `pip`
     * came with `python` and stays as long as `python` does.
     */
    fun orphans(removing: Set<String>): Set<String> {
        val remaining = installed.filterKeys { it !in removing }
        val needed = HashSet<String>()
        val queue = ArrayDeque(remaining.values.filter { it.explicit }.map { it.name })
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!needed.add(name)) continue
            val pkg = remaining[name] ?: continue
            for (clause in pkg.depends + pkg.recommends) {
                val provider = clause.firstNotNullOfOrNull { alternative -> providerAmong(alternative, remaining) } ?: continue
                queue += provider
            }
        }
        return remaining.keys - needed
    }

    private fun isInstalledOrProvided(name: String, excluding: Set<String>): Boolean =
        installed.values.any { it.name !in excluding && (it.name == name || name in it.provides) }

    private fun providerAmong(name: String, among: Map<String, InstalledPackage>): String? =
        among[name]?.name ?: among.values.firstOrNull { name in it.provides }?.name

    companion object {
        /**
         * Never installed, whoever depends on them.
         *
         * They manage packages or integrate with the Termux app itself: `apt`
         * and `dpkg` would keep a second, conflicting database; `termux-exec`
         * is a preload library whose job the app's own exec shim already does;
         * `termux-tools` is the Termux app's command set (`pkg`, `termux-*`),
         * and as the dependency of `bash` it would otherwise drag in some
         * thirty packages, the `am` reimplementations among them, which only
         * talk to a running Termux app.
         */
        val NEVER_INSTALLED: Set<String> = setOf(
            "apt", "dpkg", "termux-exec", "termux-keyring",
            "termux-tools", "termux-am", "termux-am-socket",
        )
    }
}
