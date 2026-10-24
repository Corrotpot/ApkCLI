package dev.apkcli.files

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.apkcli.MainActivity
import dev.apkcli.R
import dev.apkcli.core.runtime.EnvLayout
import java.io.File
import java.io.FileNotFoundException

/**
 * `~/projects` in the system file manager.
 *
 * Projects live in the app's own data directory, which is where the shell
 * needs them: real POSIX paths, exec-capable, not a document tree. The cost
 * is that nothing else on the device can see them — no copying a file in from
 * Downloads, no looking at what the agent wrote from Files, no sharing a
 * result. A `DocumentsProvider` gives exactly that back, for the projects
 * directory only.
 *
 * What is **not** exposed, and must not be: `$PREFIX`, which holds the
 * toolchain, and the state directory, which holds the conversation database
 * and so everything ever discussed. Only `home/projects` has a root here,
 * same as the `FileProvider` paths next to it.
 *
 * The provider is declared with `android:permission="android.permission.MANAGE_DOCUMENTS"`,
 * which only the system's own document UI holds: other apps cannot query it
 * directly and reach a file only through a URI the user granted them by
 * picking it.
 */
class ProjectDocuments : DocumentsProvider() {

    // Lazily, because a provider is created before Application.onCreate: this
    // only computes paths from filesDir, but nothing here may assume the app
    // has finished starting.
    private val root: File by lazy { EnvLayout(context!!).projects }

    override fun onCreate(): Boolean = true

    /* ----------------------------------------------------------- queries */

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_COLUMNS)
        val context = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, documentId(root))
            add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context.getString(R.string.documents_root_summary))
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_COLUMNS)
        cursor.addFile(fileOf(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = fileOf(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_COLUMNS)
        // Directories first, then by name, as a file manager would.
        parent.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { cursor.addFile(it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { fileOf(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { fileOf(documentId) }.getOrNull() ?: return false
        return child.canonicalPath.startsWith(parent.canonicalPath + File.separator)
    }

    /**
     * Where a document sits, from the root (or [parentDocumentId]) down to
     * it. The file manager asks this to open at a folder it was handed
     * (`ACTION_VIEW` on a document URI, see [browseIntents]); a provider that
     * cannot say lands the user at the file manager's own default location.
     */
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val child = fileOf(childDocumentId)
        val top = fileOf(parentDocumentId ?: ROOT_ID)
        val ids = ArrayList<String>()
        var at: File? = child
        while (at != null && at != top) {
            ids += documentId(at)
            at = at.parentFile
        }
        if (at == null) throw FileNotFoundException("$childDocumentId is not under ${parentDocumentId ?: ROOT_ID}")
        ids += documentId(top)
        ids.reverse()
        // The contract: a root id when the path starts at the root, none when
        // it starts at a parent the caller named.
        return DocumentsContract.Path(if (parentDocumentId == null) ROOT_ID else null, ids)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileOf(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a directory")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    /* ----------------------------------------------------------- changes */

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        val name = displayName.substringAfterLast('/').trim()
        if (name.isEmpty() || name == "." || name == "..") throw FileNotFoundException("bad name '$displayName'")

        // A name already taken gets a suffix, as the framework expects: the
        // picker's caller is told the id it actually got.
        var candidate = File(parent, name)
        var n = 1
        while (candidate.exists()) {
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "")
            candidate = File(parent, base + " (" + ++n + ")" + if (extension.isEmpty()) "" else ".$extension")
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) candidate.mkdirs() else candidate.createNewFile()
        if (!created) throw FileNotFoundException("could not create ${candidate.name}")
        notify(parent)
        return documentId(candidate)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileOf(documentId)
        if (file == root) throw FileNotFoundException("the projects directory itself is not deletable")
        if (!file.deleteRecursively()) throw FileNotFoundException("could not delete ${file.name}")
        file.parentFile?.let(::notify)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        if (file == root) throw FileNotFoundException("the projects directory itself is not renamable")
        val name = displayName.substringAfterLast('/').trim()
        if (name.isEmpty()) throw FileNotFoundException("bad name '$displayName'")
        val target = File(file.parentFile, name)
        if (target.exists()) throw FileNotFoundException("${target.name} already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("could not rename ${file.name}")
        file.parentFile?.let(::notify)
        return documentId(target)
    }

    private fun notify(dir: File) {
        val context = context ?: return
        context.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority(context), documentId(dir)),
            null,
        )
    }

    /* ----------------------------------------------------------- mapping */

    /** [root] with its links resolved, which is what [fileOf] hands back paths under. */
    private val canonicalRoot: File by lazy { runCatching { root.canonicalFile }.getOrDefault(root) }

    /** `projects` for the root, `projects/app/src/main.kt` below it. */
    private fun documentId(file: File): String {
        if (file == root || file == canonicalRoot) return ROOT_ID
        // Either spelling of the root: the data directory is reached through
        // a symlink on some devices (/data/user/0 → /data/data).
        val base = if (file.path.startsWith(canonicalRoot.path + File.separator)) canonicalRoot else root
        val relative = file.absolutePath.removePrefix(base.absolutePath).trimStart(File.separatorChar)
        return "$ROOT_ID${File.separatorChar}$relative"
    }

    /**
     * The file [documentId] names, refusing anything outside [root].
     *
     * The canonical path is what is checked, so neither `../..` nor a symlink
     * planted in a project can be used to read the conversation database.
     */
    private fun fileOf(documentId: String): File {
        val relative = documentId.removePrefix(ROOT_ID).trimStart(File.separatorChar, '/')
        val file = if (relative.isEmpty()) root else File(root, relative)
        val canonical = runCatching { file.canonicalFile }.getOrElse { throw FileNotFoundException(documentId) }
        val top = runCatching { root.canonicalFile }.getOrElse { throw FileNotFoundException(documentId) }
        if (canonical != top && !canonical.path.startsWith(top.path + File.separator)) {
            throw FileNotFoundException("$documentId is outside the projects directory")
        }
        if (!canonical.exists()) throw FileNotFoundException(documentId)
        return canonical
    }

    private fun MatrixCursor.addFile(file: File) {
        val directory = file.isDirectory
        var flags = if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (file != root) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId(file))
            add(Document.COLUMN_DISPLAY_NAME, if (file == root) file.name else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_SIZE, if (directory) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    companion object {
        /** Also the root document's id; see [documentId]. */
        const val ROOT_ID = "projects"

        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /**
         * Extensions `MimeTypeMap` does not know but a text viewer can open.
         * Without this a source file is `application/octet-stream` and the
         * file manager offers nothing to open it with.
         */
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "rs", "go", "py", "rb", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "c", "h", "cc", "cpp", "hpp", "cs", "swift", "m", "mm", "php", "lua", "pl", "sh",
            "bash", "zsh", "fish", "ps1", "bat", "sql", "toml", "yaml", "yml", "ini", "cfg",
            "conf", "properties", "gradle", "pro", "md", "markdown", "rst", "adoc", "tex",
            "env", "lock", "gitignore", "editorconfig", "dockerfile", "makefile", "cmake",
            "log", "diff", "patch", "csv", "tsv",
        )

        fun authority(context: Context): String = "${context.packageName}.documents"

        /** What a file is, for an app that may be asked to open it. */
        fun mimeTypeOf(file: File): String {
            if (file.isDirectory) return Document.MIME_TYPE_DIR
            val extension = file.name.substringAfterLast('.', "").lowercase()
            val name = file.name.lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
            if (extension in TEXT_EXTENSIONS || name in TEXT_EXTENSIONS || '.' !in file.name) return "text/plain"
            return "application/octet-stream"
        }

        /**
         * Ways to open the system file manager on [dir], best first, to be
         * tried in order.
         *
         * A document URI opens at that folder, which is what was asked for:
         * `DocumentsUI` answers `ACTION_VIEW` for a directory and asks
         * [findDocumentPath] where it is. A file manager that does not answer
         * for a folder makes the caller try the root URI next, which lands at
         * the top of the projects directory.
         */
        fun browseIntents(context: Context, dir: File? = null): List<Intent> = buildList {
            val authority = authority(context)
            val top = EnvLayout(context).projects
            if (dir != null && dir != top) {
                val relative = dir.absolutePath.removePrefix(top.absolutePath).trimStart(File.separatorChar)
                if (relative.isNotEmpty() && !relative.startsWith("..")) {
                    add(
                        view(
                            DocumentsContract.buildDocumentUri(authority, "$ROOT_ID${File.separatorChar}$relative"),
                            Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
            add(view(DocumentsContract.buildRootUri(authority, ROOT_ID), Root.MIME_TYPE_ITEM))
        }

        private fun view(uri: android.net.Uri, mimeType: String): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /**
         * Opens one file in another app that handles its type, picked by the
         * user from the system's chooser.
         *
         * Through the `FileProvider` beside this one rather than through a
         * document URI: a document URI of a provider guarded by
         * `MANAGE_DOCUMENTS` is for the picker to hand out, while this is the
         * app sharing one of its own files on purpose.
         *
         * This app is left out of the chooser: it takes every text type
         * itself, and where it was the only taker the file opened right back
         * here.
         */
        fun viewIntent(context: Context, file: File): Intent? {
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull() ?: return null
            val target = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, viewTypeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The grant travels with the chooser: createChooser copies it.
            return Intent.createChooser(target, null)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /**
         * The type another app is asked to open [file] as: its own for what
         * viewers declare by type (an image, a PDF, a web page), `text/plain`
         * for source and other text, which almost no app declares by its own
         * type (`application/javascript`, `text/x-kotlin`) and every text
         * editor takes as plain text.
         */
        fun viewTypeOf(file: File): String {
            val type = mimeTypeOf(file)
            val text = when {
                type == "text/html" -> false
                type.startsWith("text/") -> true
                type in TEXT_APPLICATION_TYPES -> true
                else -> file.name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
            }
            return if (text) "text/plain" else type
        }

        /** Types that are text under an `application/` name. */
        private val TEXT_APPLICATION_TYPES = setOf(
            "application/javascript", "application/x-javascript", "application/json", "application/xml",
            "application/x-sh", "application/x-yaml", "application/yaml", "application/toml", "application/sql",
        )
    }
}
