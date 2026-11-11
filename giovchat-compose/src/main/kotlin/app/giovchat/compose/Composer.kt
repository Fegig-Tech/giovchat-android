package app.giovchat.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.giovchat.core.GiovPickedFile
import app.giovchat.core.GiovUploadProgress

/** The longest message the server takes, in UTF-16 units (JavaScript's `length`, Kotlin's [String.length]). */
internal const val MAX_MESSAGE_CHARS = 2000

/**
 * [text] cut to [MAX_MESSAGE_CHARS], so a paste over the limit keeps what fits instead of being dropped; never in the
 * middle of a surrogate pair (an emoji is kept whole or left out).
 */
internal fun limitMessage(text: String): String {
    if (text.length <= MAX_MESSAGE_CHARS) return text
    var end = MAX_MESSAGE_CHARS
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}

/** The composer row: attach, multiline input and send. A null [onAttach] hides the attach button. */
@Composable
internal fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onAttach: (() -> Unit)?,
    attachEnabled: Boolean,
) {
    val look = LocalGiovLook.current
    val strings = look.strings
    val hairline = look.divider
    Row(
        Modifier
            .fillMaxWidth()
            .background(look.composerColor)
            .topHairline(hairline)
            .padding(10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onAttach != null) {
            val label = strings.attachFile
            Box(
                Modifier
                    .testTag("giov-attach")
                    .size(48.dp)
                    .alpha(if (attachEnabled) 1f else 0.4f)
                    .clip(CircleShape)
                    .border(1.dp, look.inputBorder, CircleShape)
                    .semantics { contentDescription = label }
                    .clickable(enabled = attachEnabled, role = Role.Button, onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                GiovIcon(GiovGlyph.PLUS, look.secondaryText, 22.dp)
            }
        }
        val inputStyle = look.style.text(TextStyle(fontSize = 14.sp, color = look.text), look.style.input)
        val fieldShape = RoundedCornerShape(24.dp)
        val inputLabel = strings.messageInput
        BasicTextField(
            value = text,
            onValueChange = { onTextChange(limitMessage(it)) },
            enabled = enabled,
            minLines = 1,
            maxLines = 5,
            textStyle = inputStyle,
            cursorBrush = SolidColor(look.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = KeyboardType.Text),
            modifier = Modifier
                .weight(1f)
                .testTag("giov-input")
                .semantics { contentDescription = inputLabel },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .background(look.inputFill, fieldShape)
                        .border(1.dp, look.inputBorder, fieldShape)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) BasicText(placeholder, style = look.hintStyle)
                    inner()
                }
            },
        )
        val sendLabel = strings.sendMessage
        Box(
            Modifier
                .testTag("giov-send")
                .size(48.dp)
                .alpha(if (canSend) 1f else 0.35f)
                .clip(CircleShape)
                .background(look.buttonColor)
                .semantics { contentDescription = sendLabel }
                .clickable(enabled = canSend, role = Role.Button, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            GiovIcon(GiovGlyph.ARROW_UP, look.buttonTextColor, 22.dp)
        }
    }
}

/** Files picked and waiting to be sent, with upload progress while they go, and the reason a pick was refused. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingFiles(
    files: List<GiovPickedFile>,
    uploads: List<GiovUploadProgress>,
    sending: Boolean,
    problem: String?,
    onRemove: (Int) -> Unit,
) {
    val look = LocalGiovLook.current
    val strings = look.strings
    val hairline = look.divider
    Column(
        Modifier
            .fillMaxWidth()
            .background(look.background)
            .topHairline(hairline)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
    ) {
        if (problem != null) {
            BasicText(
                problem,
                style = look.errorStyle.copy(fontSize = 12.sp),
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .testTag("giov-file-problem")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEachIndexed { index, file ->
                Row(
                    Modifier
                        .testTag("giov-chip-$index")
                        .widthIn(max = 240.dp)
                        .defaultMinSize(minHeight = 48.dp)
                        .background(look.inputFill, RoundedCornerShape(24.dp))
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        if (index < uploads.size) strings.uploading((uploads[index].progress * 100).toInt()) else file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = look.style.text(TextStyle(fontSize = 12.sp, color = look.text), look.style.hint).let {
                            if (look.style.hint?.color != null) it else it.copy(color = look.text)
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!sending) {
                        val label = strings.removeAttachment(file.name)
                        Box(
                            Modifier
                                .testTag("giov-remove-$index")
                                .size(48.dp)
                                .semantics { contentDescription = label }
                                .clickable(role = Role.Button) { onRemove(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            GiovIcon(GiovGlyph.CLOSE, look.secondaryText, 18.dp)
                        }
                    } else {
                        Box(Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

/** A 0.5 dp line along the top edge. */
internal fun Modifier.topHairline(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx())
}
