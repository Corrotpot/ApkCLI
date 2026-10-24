package dev.apkcli.core.pkg

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A package as installed, with everything removal and upgrades need. */
@Serializable
data class InstalledPackage(
    val name: String,
    val version: String,
    val architecture: String = "",
    /** Asked for by name, as opposed to pulled in as a dependency. Drives auto-removal. */
    val explicit: Boolean = false,
    val installedAt: Long = 0,
    val depends: List<List<String>> = emptyList(),
    /** Kept so auto-removal treats them as needed, as apt does by default. */
    val recommends: List<List<String>> = emptyList(),
    val provides: List<String> = emptyList(),
    /** Files and symlinks it put in `$PREFIX`, relative to it. Directories are not listed. */
    val files: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    /** Where its hardcoded paths were pointed, e.g. `alias:/data/data/<pkg>/usr`. */
    val relocation: String = "",
    val description: String = "",
)

@Serializable
private data class PackageDbFile(
    val schema: Int = 1,
    val packages: List<InstalledPackage> = emptyList(),
)

/**
 * The installed-package database: one JSON file inside `$PREFIX`.
 *
 * Inside the prefix rather than the disposable state directory, because it
 * describes files in the prefix: deleting one without the other would leave
 * either untracked files or records of files that are gone. Written by
 * rename, so a crash mid-write leaves the previous version.
 */
class PackageStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun load(): Map<String, InstalledPackage> {
        if (!file.isFile) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        val parsed = runCatching { json.decodeFromString(PackageDbFile.serializer(), text) }.getOrNull()
            ?: return emptyMap()
        return parsed.packages.associateBy { it.name }
    }

    fun save(packages: Map<String, InstalledPackage>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(
            json.encodeToString(
                PackageDbFile.serializer(),
                PackageDbFile(packages = packages.values.sortedBy { it.name }),
            ),
        )
        if (!tmp.renameTo(file)) {
            // renameTo does not replace on every filesystem; fall back to
            // delete-then-rename rather than leave the old file in place.
            file.delete()
            check(tmp.renameTo(file)) { "could not write ${file.path}" }
        }
    }
}
