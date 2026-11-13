package dev.apkcli.ui.chat

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import dev.apkcli.ui.chat.components.fenced
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * What another app handed over through Share: words, files, or both.
 *
 * Only `content:` URIs are kept. A `file:` one would be opened with this
 * app's own rights, so a share could name the conversation database and
 * have it land in the input box.
 */
data class IncomingShare(val text: String?, val streams: List<Uri>) {

    companion object {

        /** Null when [intent] is not a share, or carries nothing to take. */
        fun from(intent: Intent?): IncomingShare? {
            val action = intent?.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
            val single = action == Intent.ACTION_SEND
            val streams = if (single) {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct()
            // SEND_MULTIPLE may carry one text per item instead of one text.
            val words = if (single) {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            } else {
                intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n\n")
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
            val text = words?.trim()?.takeIf { it.isNotEmpty() }
            if (text == null && streams.isEmpty()) return null
            return IncomingShare(text, streams)
        }
    }
}

/**
 * A shared file sorted for the next message: an image is attached, text is
 * pasted into the input box as a code block under the file's name, and
 * anything else is named as not added.
 */
internal object SharedFiles {

    /**
     * The most one file puts into the input box. A longer one is a job for
     * the agent's tools on a file in the project, not for a message that
     * every later request in the chat carries again.
     */
    const val MAX_TEXT_BYTES = 100 * 1024

    sealed interface Taken {
        data class Image(val uri: Uri) : Taken
        data class Text(val pasted: String) : Taken
        data class Skipped(val name: String) : Taken
    }

    /** What [uri] holds. Off the main thread: a provider may be slow, or remote. */
    fun take(resolver: ContentResolver, uri: Uri): Taken {
        val name = ImageAttachments.displayName(resolver, uri) ?: uri.lastPathSegment ?: uri.toString()
        val type = typeOf(resolver, uri, name)
        // An SVG is text to every model: none takes it as an image.
        if (type.startsWith("image/") && type != "image/svg+xml") return Taken.Image(uri)
        val bytes = resolver.openInputStream(uri)?.use { readUpTo(it, MAX_TEXT_BYTES + 1) }
            ?: return Taken.Skipped(name)
        if (bytes.size > MAX_TEXT_BYTES) return Taken.Skipped(name)
        val text = textOf(bytes) ?: return Taken.Skipped(name)
        return Taken.Text(pasted(name, text))
    }

    /**
     * [bytes] as text, or null when they are not: not UTF-8, or holding a
     * NUL, which text never holds and nearly every binary format does.
     */
    fun textOf(bytes: ByteArray): String? {
        if (bytes.contains(0.toByte())) return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
        return text.removePrefix("\uFEFF")
    }

    /** A text file as it goes into the input box: its name, then its text, fenced. */
    fun pasted(name: String, text: String): String = "$name:\n${fenced(text)}"

    /**
     * The provider's type, or the one the name's extension implies when the
     * provider gives none or only `application/octet-stream`.
     */
    private fun typeOf(resolver: ContentResolver, uri: Uri, name: String): String {
        val declared = runCatching { resolver.getType(uri) }.getOrNull()
        if (declared != null && declared != "application/octet-stream") return declared
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: declared.orEmpty()
    }

    /** At most [limit] bytes: enough to tell a file is too long without reading all of it. */
    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
