package app.giovchat.sample

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.giovchat.compose.GiovAttachmentPicker
import app.giovchat.compose.GiovImagePlaceholder
import app.giovchat.core.GiovPickedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An attachment picker for [app.giovchat.compose.GiovChat]: the system document picker through an ActivityResult
 * contract. The chat calls it with the types the bot accepts and how many more files fit; return an empty list when
 * the visitor cancels.
 */
@Composable
fun rememberAttachmentPicker(): GiovAttachmentPicker {
    val context = LocalContext.current
    val pending = remember { Pending() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pending.result?.complete(uris)
    }
    return remember(launcher, context) {
        val picker: GiovAttachmentPicker = { request ->
            val result = CompletableDeferred<List<Uri>>()
            pending.result = result
            launcher.launch(request.allowedTypes.toTypedArray())
            val uris = result.await().take(request.maxFiles)
            withContext(Dispatchers.IO) { uris.mapNotNull { describe(context, it) } }
        }
        picker
    }
}

private class Pending {
    var result: CompletableDeferred<List<Uri>>? = null
}

/** Reads the name, type, size and (for images) shape of [uri]; the bytes are read later, when the upload starts. */
private fun describe(context: Context, uri: Uri): GiovPickedFile? {
    val resolver = context.contentResolver
    var name = "file"
    var size = -1L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
        }
    }
    val mime = resolver.getType(uri) ?: return null
    if (size < 0) return null
    var width: Int? = null
    var height: Int? = null
    var thumbnail: String? = null
    if (mime.startsWith("image/")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            width = bounds.outWidth
            height = bounds.outHeight
            thumbnail = thumbnailOf(context, uri, bounds.outWidth, bounds.outHeight)
        }
    }
    return GiovPickedFile(
        name = name,
        mimeType = mime,
        size = size,
        width = width,
        height = height,
        thumbnail = thumbnail,
        open = { resolver.openInputStream(uri) ?: error("The file is no longer available.") },
    )
}

/**
 * A ~32 px JPEG placeholder (`data:image/jpeg;base64,...`, at most 6000 characters) other viewers see while the image
 * loads. [GiovImagePlaceholder] draws transparent images over white, so a PNG with transparency does not turn black.
 */
private fun thumbnailOf(context: Context, uri: Uri, width: Int, height: Int): String? {
    var sample = 1
    while (width / (sample * 2) >= 64 && height / (sample * 2) >= 64) sample *= 2
    val small = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    return GiovImagePlaceholder.create(small).also { small.recycle() }
}
