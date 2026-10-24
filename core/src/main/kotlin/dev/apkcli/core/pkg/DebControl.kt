package dev.apkcli.core.pkg

/**
 * One entry of a repository's `Packages` index.
 *
 * Relations are kept as clauses of alternatives — `a, b | c` is
 * `[[a], [b, c]]` — with version constraints and architecture qualifiers
 * dropped. The repository is a rolling one with a single version of each
 * package, so a constraint can only ever be satisfied by that version; see
 * [PackageResolver] for how that is used instead.
 */
data class PackageInfo(
    val name: String,
    val version: String,
    val architecture: String,
    /** `Pre-Depends` and `Depends` together: both must be present before use. */
    val depends: List<List<String>>,
    val recommends: List<List<String>>,
    val provides: List<String>,
    /** Path of the `.deb` relative to the repository root. */
    val filename: String,
    val size: Long,
    val installedSizeKb: Long,
    val sha256: String,
    /** First line of the description. */
    val description: String,
    val essential: Boolean,
)

/** Parsing for Debian control-file syntax: `Packages` indexes and `control` members. */
object DebControl {

    /**
     * Split into stanzas of `field -> value`.
     *
     * Continuation lines (leading space or tab) are folded into the previous
     * field. A lone ` .` stands for an empty line inside a long description.
     */
    fun parseStanzas(text: String): List<Map<String, String>> {
        val stanzas = ArrayList<Map<String, String>>(4096)
        var fields = LinkedHashMap<String, String>()
        var key: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) {
                if (fields.isNotEmpty()) stanzas += fields
                fields = LinkedHashMap()
                key = null
                continue
            }
            if (line[0] == ' ' || line[0] == '\t') {
                val current = key ?: continue
                val continuation = line.trim().let { if (it == ".") "" else it }
                fields[current] = fields[current] + "\n" + continuation
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            key = line.substring(0, colon).trim()
            fields[key] = line.substring(colon + 1).trim()
        }
        if (fields.isNotEmpty()) stanzas += fields
        return stanzas
    }

    fun parseIndex(text: String): List<PackageInfo> = parseStanzas(text).mapNotNull(::toPackageInfo)

    fun toPackageInfo(fields: Map<String, String>): PackageInfo? {
        val name = fields["Package"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PackageInfo(
            name = name,
            version = fields["Version"].orEmpty(),
            architecture = fields["Architecture"].orEmpty(),
            depends = parseRelations(fields["Pre-Depends"]) + parseRelations(fields["Depends"]),
            recommends = parseRelations(fields["Recommends"]),
            provides = parseProvides(fields["Provides"]),
            filename = fields["Filename"].orEmpty(),
            size = fields["Size"]?.trim()?.toLongOrNull() ?: 0L,
            installedSizeKb = fields["Installed-Size"]?.trim()?.toLongOrNull() ?: 0L,
            sha256 = fields["SHA256"]?.trim()?.lowercase().orEmpty(),
            description = fields["Description"]?.lineSequence()?.firstOrNull()?.trim().orEmpty(),
            essential = fields["Essential"]?.trim().equals("yes", ignoreCase = true),
        )
    }

    /** `a (>= 1.2), b | c:any [!arm]` -> `[[a], [b, c]]`. */
    fun parseRelations(value: String?): List<List<String>> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',').mapNotNull { clause ->
            clause.split('|')
                .map(::bareName)
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
        }
    }

    /** `python3, python3-dev (= 3.14)` -> `[python3, python3-dev]`. */
    fun parseProvides(value: String?): List<String> =
        value?.split(',')?.map(::bareName)?.filter { it.isNotEmpty() }.orEmpty()

    private fun bareName(alternative: String): String = alternative
        .substringBefore('(')
        .substringBefore('[')
        .substringBefore('<')
        .trim()
        .substringBefore(':')
        .trim()
}
