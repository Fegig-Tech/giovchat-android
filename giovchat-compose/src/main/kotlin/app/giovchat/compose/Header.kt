package app.giovchat.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.giovchat.core.GiovHeaderKind

/** Where the privacy link goes when the workspace set no policy of its own (as in the web widget). */
internal const val GIOV_PRIVACY_URL = "https://giovchat.app/privacy"

/** The chat header: avatar (photo, GiovBot mark or initial), who is answering and a status line. */
@Composable
internal fun ChatHeader(
    title: String,
    subtitle: String?,
    avatarUrl: String?,
    kind: GiovHeaderKind?,
    onClose: (() -> Unit)?,
    privacyUrl: String = GIOV_PRIVACY_URL,
) {
    val look = LocalGiovLook.current
    val onHeader = look.style.headerTitle?.color ?: look.on(look.headerColor)
    val initial = title.trim().let { if (it.isEmpty()) "" else String(Character.toChars(it.codePointAt(0))).uppercase() }
    val photo by rememberRemoteImage(look.imageLoader, avatarUrl, avatarUrl.orEmpty(), 120)

    Row(
        Modifier.fillMaxWidth().background(look.headerColor).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(onHeader.copy(alpha = 0.22f)).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            val state = photo
            when {
                state is RemoteImage.Ready -> Image(state.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                avatarUrl != null && state is RemoteImage.Loading -> Unit
                kind == GiovHeaderKind.BOT -> GiovBotMark(26.dp)
                else -> BasicText(
                    initial,
                    style = look.style.text(TextStyle(color = onHeader, fontWeight = FontWeight.Bold, fontSize = 17.sp), look.style.headerTitle),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = look.style.text(TextStyle(color = onHeader, fontWeight = FontWeight.Bold, fontSize = 16.sp), look.style.headerTitle),
                modifier = Modifier.semantics { heading() }.testTag("giov-header-title"),
            )
            if (!subtitle.isNullOrEmpty()) {
                BasicText(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = look.style.text(TextStyle(color = onHeader.copy(alpha = 0.78f), fontSize = 12.sp), look.style.headerSubtitle),
                    modifier = Modifier.padding(top = 2.dp).testTag("giov-header-status"),
                )
            }
        }
        // The web widget's "GDPR and privacy" menu entry: the workspace's policy, else Giov's.
        val privacy = look.strings.privacy
        Box(
            Modifier
                .testTag("giov-privacy")
                .defaultMinSize(48.dp, 48.dp)
                .semantics { contentDescription = privacy }
                .clickable(role = Role.Button, onClickLabel = privacy) {
                    try {
                        look.openUrl(privacyUrl)
                    } catch (_: Exception) {
                        // Nothing can open it.
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            GiovIcon(GiovGlyph.SHIELD, onHeader, 20.dp)
        }
        if (onClose != null) {
            val label = look.strings.closeChat
            Box(
                Modifier
                    .testTag("giov-close")
                    .defaultMinSize(48.dp, 48.dp)
                    .semantics { contentDescription = label }
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                GiovIcon(GiovGlyph.CLOSE, onHeader, 22.dp)
            }
        }
    }
}

