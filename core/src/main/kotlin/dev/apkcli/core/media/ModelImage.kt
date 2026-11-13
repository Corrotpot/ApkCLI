package dev.apkcli.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import dev.apkcli.core.model.Block
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures as the models take them: turned upright, scaled down and sent as
 * JPEG. The composer's attachments and the agent's `fs_read` of an image both
 * go through here, so a screenshot costs the same either way.
 *
 * Scaled because the providers scale anyway — Anthropic to about 1568 pixels
 * on the long edge, OpenAI to 2048 and then 768 on the short one — and a
 * 12-megapixel photo would be several megabytes of base64 in every request of
 * the session for no detail the model ever sees. JPEG because every provider
 * takes it; transparency is flattened onto white first, since JPEG has no
 * alpha and would turn it black.
 */
object ModelImage {

    /** The long edge sent; Anthropic's own working size. */
    const val MAX_EDGE = 1568

    private const val JPEG_QUALITY = 85

    /** The power-of-two fraction to decode a [width] x [height] picture at: never below [MAX_EDGE]. */
    fun sampleFor(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    /** [decoded], turned as its EXIF [orientation] says, scaled to [MAX_EDGE] and opaque. */
    fun prepared(decoded: Bitmap, orientation: Int): Bitmap {
        val scaled = fit(upright(decoded, orientation), MAX_EDGE)
        return if (scaled.hasAlpha()) onWhite(scaled) else scaled
    }

    /** [bitmap] as the block a request carries. */
    fun encode(bitmap: Bitmap, label: String?): Block.Image {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        return Block.Image(
            mimeType = "image/jpeg",
            data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            label = label,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    /** A picture read from disk, with the size it had there. */
    class FromFile(val image: Block.Image, val width: Int, val height: Int)

    /** [file]'s width and height in pixels, read from its header alone; null when it is not a picture. */
    fun sizeOf(file: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /** [file] ready to send; null when this device cannot decode it as a picture. */
    fun fromFile(file: File): FromFile? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) },
        ) ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return FromFile(encode(prepared(decoded, orientation), file.name), bounds.outWidth, bounds.outHeight)
    }

    fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
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

    /** [bitmap] with its long edge at most [edge]. */
    fun fit(bitmap: Bitmap, edge: Int): Bitmap {
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
}
