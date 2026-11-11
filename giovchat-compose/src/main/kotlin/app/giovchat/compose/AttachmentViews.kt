package app.giovchat.compose

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.giovchat.core.GiovAttachment
import app.giovchat.core.GiovAttachmentPreview
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_IMAGE_WIDTH = 200
private const val MAX_IMAGE_HEIGHT = 150

/** The box (in dp) an image takes before it loads: its final shape, or 200 x 150 when the shape is unknown. */
internal fun previewBoxSize(preview: GiovAttachmentPreview?): Pair<Int, Int> {
    val width = preview?.width
    val height = preview?.height
    if (width == null || height == null || width <= 0 || height <= 0) return MAX_IMAGE_WIDTH to MAX_IMAGE_HEIGHT
    val scale = min(1.0, min(MAX_IMAGE_WIDTH.toDouble() / width, MAX_IMAGE_HEIGHT.toDouble() / height))
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}

/** The blurred placeholder carried in the message, or null when it is missing or cannot be decoded. */
internal fun decodeThumbnail(preview: GiovAttachmentPreview?): ImageBitmap? {
    val encoded = preview?.thumbBase64 ?: return null
    return try {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/** Attachments inside a bubble: images in a box of their final shape, other files as rows. */
@Composable
internal fun AttachmentList(attachments: List<GiovAttachment>, textColor: Color, onDarkSurface: Boolean) {
    val look = LocalGiovLook.current
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (attachment in attachments) {
            val url = look.attachmentUrl(attachment.url)
            val name = attachment.displayName.ifEmpty { if (attachment.isImage) look.strings.image else look.strings.attachment }
            if (attachment.isImage) {
                AttachmentImage(attachment, url, name, onDarkSurface)
            } else {
                FileRow(url, name, textColor, onDarkSurface)
            }
        }
    }
}

private fun openAttachment(look: GiovLook, url: String) {
    if (!isHttpUrl(url)) return
    try {
        look.openUrl(url)
    } catch (_: Exception) {
        // Nothing can open it.
    }
}

@Composable
private fun FileRow(url: String, name: String, color: Color, dark: Boolean) {
    val look = LocalGiovLook.current
    val label = look.strings.openAttachment(name)
    Row(
        Modifier
            .testTag("giov-file")
            .widthIn(max = 240.dp)
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (dark) Color(0x2EFFFFFF) else Color(0x1F000000), RoundedCornerShape(10.dp))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClickLabel = label) { openAttachment(look, url) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GiovIcon(GiovGlyph.FILE, color, 18.dp)
        Box(Modifier.size(8.dp))
        BasicText(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 13.sp, color = color, textDecoration = TextDecoration.Underline),
        )
    }
}

@Composable
private fun AttachmentImage(attachment: GiovAttachment, url: String, name: String, dark: Boolean) {
    val look = LocalGiovLook.current
    val (width, height) = previewBoxSize(attachment.preview)
    val thumb = remember(attachment.preview) { decodeThumbnail(attachment.preview) }
    val fill = if (dark) Color(0xFF1F1F1F) else Color(0xFFECECEC)
    val loaded by rememberRemoteImage(look.imageLoader, url.takeIf(::isHttpUrl), attachment.url, MAX_IMAGE_WIDTH * 3)
    val label = look.strings.openAttachment(name)
    Box(
        Modifier
            .testTag("giov-image")
            .size(width.dp, height.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClickLabel = label) { openAttachment(look, url) },
    ) {
        if (thumb != null) {
            Image(thumb, null, Modifier.fillMaxSize().blur(6.dp), contentScale = ContentScale.Crop)
        }
        when (val state = loaded) {
            is RemoteImage.Ready -> {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (look.reduceMotion) 0 else 150), label = "giov-image-fade")
                Image(state.bitmap, null, Modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop)
            }
            RemoteImage.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GiovIcon(GiovGlyph.BROKEN_IMAGE, if (dark) Color(0xFF8A8A8A) else Color(0xFF777777), 28.dp)
            }
            RemoteImage.Loading -> Unit
        }
    }
}

