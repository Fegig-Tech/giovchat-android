package app.giovchat.compose

import android.text.format.DateFormat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.giovchat.core.GiovAnswerVote
import app.giovchat.core.GiovDelivery
import app.giovchat.core.GiovMessage
import app.giovchat.core.GiovRatingBlock
import app.giovchat.core.GiovRole
import app.giovchat.core.messageSenderLabel
import java.util.Date

private val BubbleRadius = 16.dp
internal val AgentLabelLight = Color(0xFF5F5F5F)
internal val AgentTimeLight = Color(0xFF666666)
internal val AgentLabelDark = Color(0xFFA3A3A3)
internal val AgentTimeDark = Color(0xFF9A9A9A)
private val TailRadius = 4.dp

/** The compact GiovBot avatar shown beside AI replies and in the header while the bot answers. */
@Composable
internal fun GiovBotMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.giovbot_mark),
        contentDescription = null,
        modifier = modifier.padding(top = 2.dp).size(size).testTag("giovbot-mark"),
        contentScale = ContentScale.Fit,
    )
}

/** A row that leaves the bubble at most 78% of the width, aligned to its side. */
@Composable
private fun BubbleRow(
    visitor: Boolean,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    bubble: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        val maxBubble = maxWidth * 0.78f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (visitor) Alignment.End else Alignment.Start) {
            Row {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.widthIn(max = maxBubble)) { bubble() }
            }
            below?.invoke()
        }
    }
}

private fun agentShape() = RoundedCornerShape(BubbleRadius, BubbleRadius, BubbleRadius, TailRadius)

@Composable
internal fun GreetingBubble(text: String) {
    val look = LocalGiovLook.current
    BubbleRow(visitor = false, modifier = Modifier.padding(top = 16.dp).testTag("giov-greeting")) {
        Box(Modifier.background(look.agentBubble, agentShape()).padding(12.dp)) {
            BasicText(text, style = look.style.text(TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = look.text), look.style.agentText))
        }
    }
}

@Composable
internal fun TypingIndicator(isBot: Boolean?) {
    val look = LocalGiovLook.current
    val ai = isBot == true
    val label = if (ai) look.strings.botTyping(look.botName?.trim()?.takeIf { it.isNotEmpty() } ?: "GiovBot") else look.strings.teamTyping
    val dotColor = look.style.agentText?.color ?: if (look.dark) Color(0xFF8A8A8A) else Color(0xFF999999)
    BubbleRow(
        visitor = false,
        modifier = Modifier.semantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        leading = if (ai) ({ GiovBotMark() }) else null,
    ) {
        Row(
            Modifier.testTag("giov-typing").background(look.agentBubble, agentShape()).padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (look.reduceMotion) {
                repeat(3) { Dot(dotColor) { 0.7f } }
            } else {
                val transition = rememberInfiniteTransition(label = "giov-typing")
                val phase: State<Float> = transition.animateFloat(
                    0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "giov-typing-phase",
                )
                repeat(3) { dot -> Dot(dotColor) { 0.35f + 0.65f * wave(((phase.value - dot * 0.18f) % 1f + 1f) % 1f) } }
            }
        }
    }
}

private fun wave(t: Float): Float = if (t < 0.5f) t * 2 else (1 - t) * 2

@Composable
private fun Dot(color: Color, alpha: () -> Float) {
    Box(Modifier.size(7.dp).drawBehind { drawCircle(color.copy(alpha = alpha())) })
}

@Composable
internal fun MessageBubble(
    message: GiovMessage,
    agentName: String?,
    onRetry: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
    /** The rating card to draw under the text; null draws none. */
    rating: GiovRatingBlock? = null,
    onRate: (suspend (Int, String?) -> Unit)? = null,
    /** Thumbs under a GiovBot answer (the visitor's vote is [GiovMessage.feedback]); false draws none. */
    thumbs: Boolean = false,
    onVote: (suspend (GiovAnswerVote?, String?) -> Unit)? = null,
) {
    val look = LocalGiovLook.current
    val strings = look.strings
    val context = LocalContext.current
    val visitor = message.role == GiovRole.VISITOR
    val isBot = message.role == GiovRole.AGENT && message.isBot
    val failed = visitor && message.delivery == GiovDelivery.FAILED
    val sending = visitor && message.delivery == GiovDelivery.SENDING
    val overrideText = if (visitor) look.style.visitorText else look.style.agentText
    val textColor = if (visitor) look.onVisitorBubble else look.onAgentBubble
    val darkBubble = if (visitor) look.onVisitorBubble.luminance() > 0.5f else look.dark
    val label = messageSenderLabel(message, strings, look.botName, agentName)
    // Sender label and time on the default agent bubbles keep at least 4.5:1 (WCAG AA for small text).
    val subtle = if (visitor) textColor.copy(alpha = 0.72f) else if (look.dark) AgentLabelDark else AgentLabelLight
    val faint = if (visitor) textColor.copy(alpha = 0.55f) else if (look.dark) AgentTimeDark else AgentTimeLight
    val contentStyle = look.style.text(TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = textColor), overrideText)
    val accent = if (darkBubble) Color(0x80FFFFFF) else Color(0x33000000)
    val code = if (darkBubble) Color(0x2EFFFFFF) else Color(0x0F000000)
    val time = if (sending) strings.sending else remember(message.createdAt) {
        DateFormat.getTimeFormat(context).format(Date(message.createdAt))
    }
    val shape = RoundedCornerShape(
        topStart = BubbleRadius,
        topEnd = BubbleRadius,
        bottomStart = if (visitor) BubbleRadius else TailRadius,
        bottomEnd = if (visitor) TailRadius else BubbleRadius,
    )

    BubbleRow(
        visitor = visitor,
        modifier = Modifier.testTag("bubble:${message.key}").semantics { this[GiovFillKey] = (if (visitor) look.visitorBubble else look.agentBubble).toArgb() },
        leading = if (isBot) ({ GiovBotMark() }) else null,
        below = if (failed || (rating != null && onRate != null) || (thumbs && onVote != null)) ({
            if (failed) Row(verticalAlignment = Alignment.CenterVertically) {
                val retryText = if (onRetry != null) "${strings.notSent} · ${strings.tapToRetry}" else strings.notSent
                val retryModifier = Modifier
                    .testTag("giov-retry")
                    .defaultMinSize(minHeight = 48.dp)
                    .let { if (onRetry != null) it.clickable(role = Role.Button, onClick = onRetry) else it }
                    .padding(horizontal = 6.dp)
                Box(retryModifier, contentAlignment = Alignment.CenterStart) {
                    BasicText(retryText, style = TextStyle(color = look.error, fontSize = 12.sp))
                }
                if (onDiscard != null) {
                    Box(
                        Modifier
                            .testTag("giov-discard")
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable(role = Role.Button, onClick = onDiscard)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(strings.discard, style = TextStyle(color = look.secondaryText, fontSize = 12.sp))
                    }
                }
            }
            if (rating != null && onRate != null) {
                RatingCard(
                    rating,
                    onRate,
                    Modifier.padding(top = 6.dp).background(look.agentBubble, RoundedCornerShape(BubbleRadius)).padding(12.dp),
                )
            }
            if (thumbs && onVote != null) AnswerThumbs(message.feedback, onVote)
        }) else null,
    ) {
        Column(
            Modifier
                .alpha(if (sending) 0.75f else 1f)
                .background(if (visitor) look.visitorBubble else look.agentBubble, shape)
                .padding(12.dp),
        ) {
            BasicText(
                label,
                style = look.style.text(TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = subtle), overrideText),
                modifier = Modifier.padding(bottom = 3.dp),
            )
            if (message.attachments.isNotEmpty()) AttachmentList(message.attachments, textColor, darkBubble)
            if (message.content.isNotEmpty()) {
                ChatMarkdownView(message.content, contentStyle, accent, code, { href -> openGiovLink(look.openUrl, href) })
            }
            if (!message.isStreaming) {
                BasicText(
                    time,
                    style = look.style.text(TextStyle(fontSize = 10.sp, color = faint), overrideText),
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                )
            }
        }
    }
}

