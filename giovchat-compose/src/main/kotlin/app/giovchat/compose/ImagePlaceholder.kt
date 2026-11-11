package app.giovchat.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The tiny placeholder other viewers see while an image loads (docs/protocol/README.md, "Attachments"): about 32 px on
 * the long side, JPEG, as a `data:` URI of at most 6000 characters. Use it in your attachment picker for
 * [app.giovchat.core.GiovPickedFile.thumbnail].
 */
public object GiovImagePlaceholder {
    /** Long side of the placeholder, in pixels. */
    public const val SIZE: Int = 32

    private const val MAX_LENGTH = 6000

    /**
     * A `data:image/jpeg;base64,...` placeholder of [image] (any size; decode it downsampled to about 64 px first),
     * or null when it does not fit. Transparent pixels are drawn over white, as the web widget does: JPEG has no
     * transparency, and encoded as is they turn black.
     */
    public fun create(image: Bitmap): String? {
        if (image.width <= 0 || image.height <= 0) return null
        val scale = SIZE.toFloat() / maxOf(image.width, image.height)
        val width = maxOf(1, (image.width * scale).roundToInt())
        val height = maxOf(1, (image.height * scale).roundToInt())
        val opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(image, Rect(0, 0, image.width, image.height), Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val bytes = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, 50, it) }.toByteArray()
        opaque.recycle()
        val encoded = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        return encoded.takeIf { it.length <= MAX_LENGTH }
    }
}
