package app.giovchat.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import app.giovchat.core.GiovOkHttp
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Loads the pictures the chat shows: avatars and image attachments. The default [OkHttpGiovImageLoader] needs
 * nothing else; implement this interface to route images through the app's own loader (Coil, Glide).
 */
public interface GiovImageLoader {
    /**
     * Returns the picture at [url], decoded to no more than about [maxPixels] on its longer side, or null when it
     * cannot be loaded. [cacheKey] is stable for the same picture; [url] may carry a credential that changes.
     */
    public suspend fun load(url: String, cacheKey: String, maxPixels: Int): ImageBitmap?
}

/** Downloads with OkHttp (the client the core already depends on), downsamples while decoding and keeps recent pictures in memory. */
public class OkHttpGiovImageLoader(
    private val client: OkHttpClient = GiovOkHttp.client,
    cacheKilobytes: Int = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceIn(2_048, 32_768),
) : GiovImageLoader {
    private val cache = object : LruCache<String, Bitmap>(cacheKilobytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    override suspend fun load(url: String, cacheKey: String, maxPixels: Int): ImageBitmap? {
        if (!isHttpUrl(url)) return null
        val key = "$cacheKey@$maxPixels"
        cache.get(key)?.let { return it.asImageBitmap() }
        return try {
            val bytes = download(url) ?: return null
            val bitmap = withContext(Dispatchers.Default) { decode(bytes, maxPixels) } ?: return null
            cache.put(key, bitmap)
            bitmap.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun download(url: String): ByteArray? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bytes = try {
                    response.use { if (it.isSuccessful) it.body?.bytes() else null }
                } catch (_: IOException) {
                    null
                }
                if (continuation.isActive) continuation.resume(bytes)
            }
        })
    }
}

/** Decodes [bytes], halving the size while the picture stays at least [maxPixels] on both sides. */
internal fun decode(bytes: ByteArray, maxPixels: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPixels && bounds.outHeight / (sample * 2) >= maxPixels) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** The state of a picture being fetched. */
internal sealed interface RemoteImage {
    data object Loading : RemoteImage
    data object Failed : RemoteImage
    class Ready(val bitmap: ImageBitmap) : RemoteImage
}

/**
 * Loads [url] (null: nothing to load, reported as failed) per [cacheKey], and again when [url] changes: a picture that
 * failed without a usable credential loads once a renewed one arrives (the loader's cache makes a loaded one instant).
 */
@Composable
internal fun rememberRemoteImage(loader: GiovImageLoader, url: String?, cacheKey: String, maxPixels: Int): State<RemoteImage> =
    produceState<RemoteImage>(if (url == null) RemoteImage.Failed else RemoteImage.Loading, loader, cacheKey, url, maxPixels) {
        value = if (url == null) {
            RemoteImage.Failed
        } else {
            val bitmap = loader.load(url, cacheKey, maxPixels)
            if (bitmap != null) RemoteImage.Ready(bitmap) else RemoteImage.Failed
        }
    }
