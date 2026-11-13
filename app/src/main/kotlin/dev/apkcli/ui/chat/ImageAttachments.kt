package dev.apkcli.ui.chat

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.apkcli.core.media.ModelImage
import dev.apkcli.core.model.Block
import kotlin.math.max

/**
 * Photos and screenshots the user attaches, made ready for the model the way
 * every picture is ([ModelImage]: upright, at most 1568 px, JPEG), with the
 * small copy the composer shows.
 */
internal object ImageAttachments {

    /** At most this many per message: each one is re-sent with every request. */
    const val MAX_PER_MESSAGE = 4

    private const val THUMBNAIL_EDGE = 192

    /** An image ready to send, and the small copy the composer shows. */
    class Prepared(val image: Block.Image, val thumbnail: ImageBitmap)

    /** Null when the content is not an image this device can decode. */
    fun prepare(resolver: ContentResolver, uri: Uri): Prepared? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Opened and decoded separately: a bounds-only decode returns null by
        // design, so `?.use { decode } ?: return null` refused every image.
        val probe = resolver.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decoded at a power-of-two fraction no smaller than needed, so a
        // large photo never sits in memory at full size.
        val sample = ModelImage.sampleFor(bounds.outWidth, bounds.outHeight)
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val opaque = ModelImage.prepared(decoded, orientation)
        return Prepared(
            image = ModelImage.encode(opaque, displayName(resolver, uri)),
            thumbnail = ModelImage.fit(opaque, THUMBNAIL_EDGE).asImageBitmap(),
        )
    }

    private val thumbnails = LruCache<String, ImageBitmap>(48)

    /**
     * The small copy of an image already in the transcript, decoded once.
     * [key] names it (message id and position): hashing the base64 of every
     * image on every scroll would cost more than the decode it saves.
     */
    fun thumbnail(key: String, image: Block.Image): ImageBitmap? {
        thumbnails.get(key)?.let { return it }
        val bytes = runCatching { Base64.decode(image.data, Base64.DEFAULT) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_EDGE * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return ModelImage.fit(bitmap, THUMBNAIL_EDGE * 2).asImageBitmap().also { thumbnails.put(key, it) }
    }

    /** The name the provider gives the file, e.g. `Screenshot_20261022.png`. */
    fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
