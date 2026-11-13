package dev.apkcli.ui.preview

import java.io.File
import java.net.URI

/**
 * Addresses for the preview: the ones dev servers print, what the user types
 * into the address field, and the project's own files, which are served under
 * [PROJECT_HOST] by the preview itself (PreviewWebView), so a page needs no
 * server at all.
 */
internal object PreviewUrls {

    /** The project's files, as a site of their own. `.invalid` never resolves, so nothing else answers there. */
    const val PROJECT_HOST = "project.apkcli.invalid"
    const val PROJECT_ROOT = "https://$PROJECT_HOST/"

    /**
     * A local address as servers print it: Vite's `Local: http://localhost:5173/`,
     * Python's `(http://0.0.0.0:8000/)`, Flask's `http://127.0.0.1:5000`.
     */
    private val LOCAL = Regex("""(https?)://(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?])(:\d{2,5})(/[^\s'"<>()\[\]{}`]*)?""")

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val LOCAL_INPUT = Regex("""^(localhost|127\.0\.0\.1|0\.0\.0\.0)(:\d{2,5})?(/.*)?$""")
    private val DOMAIN_INPUT = Regex("""^[\w-]+(\.[\w-]+)+(:\d{2,5})?(/.*)?$""")

    /** What a name ending so is in the project, not a site: `index.html`, `app.js`. */
    private val FILE_EXTENSIONS = setOf(
        "html", "htm", "xhtml", "svg", "js", "mjs", "css", "json", "md", "txt", "xml",
        "png", "jpg", "jpeg", "gif", "webp",
    )

    /**
     * The local addresses in [text], each once, in the order they were last
     * printed: the newest last. `0.0.0.0` and `[::]` are where a server
     * listens, reached as `localhost`.
     */
    fun localUrls(text: String): List<String> {
        val found = LOCAL.findAll(text).map { match ->
            val (scheme, _, port, path) = match.destructured
            "$scheme://localhost$port" + path.trimEnd('.', ',', ';', ':', '!', '?').ifEmpty { "/" }
        }.toList()
        return found.asReversed().distinct().asReversed()
    }

    /**
     * [input] as an address: a URL as it is; `localhost:3000`, `:3000` or
     * `3000` as a local server; a domain as a site; anything else as a path
     * in the project. Null for nothing.
     */
    fun fromInput(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val port = text.removePrefix(":")
        return when {
            SCHEME.containsMatchIn(text) -> text
            port.length in 2..5 && port.all(Char::isDigit) -> "http://localhost:$port/"
            LOCAL_INPUT.matches(text) -> "http://" + text.replaceFirst("0.0.0.0", "localhost").replaceFirst("127.0.0.1", "localhost")
            DOMAIN_INPUT.matches(text) && text.substringBefore('/').substringAfterLast('.').lowercase() !in FILE_EXTENSIONS -> {
                // A server on the network (`192.168.1.5:8080`) speaks plain HTTP; a site, HTTPS.
                val authority = text.substringBefore('/')
                val plain = ':' in authority || authority.all { it.isDigit() || it == '.' }
                (if (plain) "http://" else "https://") + text
            }
            else -> PROJECT_ROOT + text.removePrefix("./").trimStart('/')
        }
    }

    fun isProject(url: String?): Boolean = url != null && hostOf(url) == PROJECT_HOST

    /** What the address field shows: `localhost:5173/app`, the path of a project file, the URL otherwise. */
    fun label(url: String): String = when {
        isProject(url) -> url.removePrefix(PROJECT_ROOT).ifEmpty { "index.html" }
        else -> url.removePrefix("http://").removePrefix("https://").removeSuffix("/")
    }

    /**
     * The file under [root] that a request's (decoded) [path] names, a
     * folder's `index.html` for a folder; null when the path leads outside
     * [root], through `..` or a link, or there is no such file.
     */
    fun projectFile(root: File, path: String): File? {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        var file = runCatching { File(base, path.trimStart('/')).canonicalFile }.getOrNull() ?: return null
        if (file != base && !file.path.startsWith(base.path + File.separator)) return null
        if (file.isDirectory) file = File(file, "index.html")
        return file.takeIf { it.isFile }
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
}
