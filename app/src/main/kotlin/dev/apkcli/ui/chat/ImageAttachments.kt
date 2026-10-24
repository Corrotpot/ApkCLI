package dev.apkcli.ui.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.apkcli.core.model.Block
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos and screenshots for the model: read, turned upright, scaled down and
 * sent as JPEG.
 *
 * Scaled because the providers scale anyway — Anthropic to about 1568 pixels
 * on the long edge, OpenAI to 2048 and then 768 on the short one — and a
 * 12-megapixel photo would be several megabytes of base64 in every request of
 * the session for no detail the model ever sees. JPEG because every provider
 * takes it; transparent PNGs are flattened onto white first, since JPEG has
 * no alpha and would turn the transparency black.
 */
internal object ImageAttachments {

    /** The long edge sent; Anthropic's own working size. */
    const val MAX_EDGE = 1568

    /** At most this many per message: each one is re-sent with every request. */
    const val MAX_PER_MESSAGE = 4

    private const val JPEG_QUALITY = 85
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
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val upright = upright(decoded, orientation)
        val scaled = fit(upright, MAX_EDGE)
        val opaque = if (scaled.hasAlpha()) onWhite(scaled) else scaled

        val bytes = ByteArrayOutputStream().use { out ->
            opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        val thumbnail = fit(opaque, THUMBNAIL_EDGE).asImageBitmap()
        return Prepared(
            image = Block.Image(
                mimeType = "image/jpeg",
                data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                label = displayName(resolver, uri),
                width = opaque.width,
                height = opaque.height,
            ),
            thumbnail = thumbnail,
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
        return fit(bitmap, THUMBNAIL_EDGE * 2).asImageBitmap().also { thumbnails.put(key, it) }
    }

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun fit(bitmap: Bitmap, edge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= edge) return bitmap
        val scale = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun onWhite(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
