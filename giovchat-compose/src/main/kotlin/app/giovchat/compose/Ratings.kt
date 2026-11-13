package app.giovchat.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.giovchat.core.GIOV_RATING_SCORES
import app.giovchat.core.GiovAnswerVote
import app.giovchat.core.GiovChatException
import app.giovchat.core.GiovRatingBlock
import app.giovchat.core.GiovRatingCardState
import app.giovchat.core.GiovRatingFailure
import app.giovchat.core.GiovRatingStrings
import app.giovchat.core.ratingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The conversation rating card, the thumbs under GiovBot answers and the sheet GiovChatController.presentRating opens.
// All decisions live in app.giovchat.core.Ratings.kt and the two small models below (tested without a UI); these
// composables only draw them.

/** What the visitor chose to send. */
internal data class RatingChoice(val score: Int, val comment: String?)

/**
 * What the visitor has done to one rating card on this device: the unsent choice, "Change", the answer on its way and a
 * refusal. [card] turns it into what is drawn. Immutable: every step returns the next model.
 */
internal data class RatingCardModel(
    val selected: Int? = null,
    val comment: String = "",
    val editing: Boolean = false,
    /** An answer is on its way (the card already shows it; the faces and Send are off). */
    val sending: Boolean = false,
    /** Read-only or hidden after a refusal. */
    val lock: GiovRatingFailure? = null,
    /** The failure to show above Send (retry), if any. */
    val failure: GiovRatingFailure? = null,
) {
    fun card(block: GiovRatingBlock, now: Long): GiovRatingCardState =
        GiovRatingCardState.make(block, now, editing, selected, comment, lock)

    fun select(score: Int): RatingCardModel = if (sending || score !in GIOV_RATING_SCORES) this else copy(selected = score)

    fun withComment(text: String): RatingCardModel = copy(comment = GiovRatingCardState.clampComment(text))

    /** "Change": back to the form with the earlier answer filled in. */
    fun beginEditing(block: GiovRatingBlock): RatingCardModel =
        if (sending) this else copy(selected = block.score, comment = block.comment.orEmpty(), failure = null, editing = true)

    /** "Cancel": back to the answer. */
    fun cancelEditing(): RatingCardModel = if (sending) this else copy(editing = false, failure = null)

    /** Send: the model while it goes, and the choice to submit; null when nothing is picked or an answer is on its way. */
    fun beginSend(): Pair<RatingCardModel, RatingChoice>? {
        val score = selected
        if (sending || score == null) return null
        val text = comment.trim()
        return copy(sending = true, failure = null, editing = false) to RatingChoice(score, text.ifEmpty { null })
    }

    /** The server saved the answer. */
    fun sent(): RatingCardModel = copy(sending = false, editing = false, failure = null, selected = null, comment = "")

    /**
     * The answer was refused: read-only or hidden for `Closed`, `Replaced` and `TooManyChanges`; otherwise the form comes
     * back with the choice kept and a sentence to show.
     */
    fun failed(reason: GiovRatingFailure): RatingCardModel = when (reason) {
        GiovRatingFailure.Closed, GiovRatingFailure.Replaced, GiovRatingFailure.TooManyChanges ->
            copy(sending = false, lock = reason, editing = false, failure = null)
        GiovRatingFailure.NotFound, GiovRatingFailure.Unsupported ->
            copy(sending = false, lock = GiovRatingFailure.Closed, editing = false, failure = null)
        GiovRatingFailure.Invalid, is GiovRatingFailure.RateLimited, GiovRatingFailure.Retry ->
            copy(sending = false, editing = true, failure = reason)
    }
}

/** What tapping a thumb does and what the reason prompt shows. */
internal data class FeedbackModel(val asking: Boolean = false, val reason: String = "", val failed: Boolean = false) {
    /** Tapping [tapped] while the vote is [current]: the next model and the vote to send (null takes it back). */
    fun tap(tapped: GiovAnswerVote, current: GiovAnswerVote?): Pair<FeedbackModel, GiovAnswerVote?> {
        val next = if (current == tapped) null else tapped
        return copy(failed = false, reason = "", asking = next == GiovAnswerVote.DOWN) to next
    }

    fun withReason(text: String): FeedbackModel = copy(reason = GiovRatingCardState.clampReason(text))

    /** Send on the reason prompt: the next model and the reason to send, or null when it is blank (nothing to send). */
    fun submitReason(): Pair<FeedbackModel, String?> = copy(asking = false) to reason.trim().ifEmpty { null }

    fun skip(): FeedbackModel = copy(asking = false)

    fun fail(): FeedbackModel = copy(asking = false, failed = true)
}

private fun Throwable.asRatingFailure(): GiovRatingFailure = (this as? GiovChatException)?.ratingFailure ?: GiovRatingFailure.Retry

/** The mouth of face 1 (very bad) to 5 (great) on a 24 x 24 grid, as in the web widget. */
private fun DrawScope.faceMouth(score: Int, color: Color, line: Float) {
    val k = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    val path = Path().apply {
        when (score) {
            1 -> { moveTo(8f * k, 16.4f * k); quadraticTo(12f * k, 12.4f * k, 16f * k, 16.4f * k) }
            2 -> { moveTo(8.5f * k, 15.8f * k); quadraticTo(12f * k, 13.4f * k, 15.5f * k, 15.8f * k) }
            3 -> { moveTo(8.5f * k, 15.4f * k); lineTo(15.5f * k, 15.4f * k) }
            4 -> { moveTo(8.5f * k, 14.4f * k); quadraticTo(12f * k, 16.8f * k, 15.5f * k, 14.4f * k) }
            else -> { moveTo(7.8f * k, 13.6f * k); quadraticTo(12f * k, 18.8f * k, 16.2f * k, 13.6f * k); close() }
        }
    }
    drawPath(path, color, style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (score == 5) drawPath(path, color, style = Fill)
    drawCircle(color, radius = line * 0.9f, center = p(8.6f, 9.6f))
    drawCircle(color, radius = line * 0.9f, center = p(15.4f, 9.6f))
}

/** One face, drawn with lines in [color]. */
@Composable
internal fun RatingFace(score: Int, color: Color, size: Dp = 30.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val line = maxOf(1.4f, this.size.minDimension / 18f)
        drawCircle(color, radius = this.size.minDimension / 2f - line / 2f, style = Stroke(width = line))
        faceMouth(score, color, line)
    }
}

/**
 * The rating card: five faces, an optional comment and Send; "Thanks" with the chosen face afterwards, which the visitor
 * may change until the window closes. [onRate] answers the card and throws the controller's [GiovChatException].
 */
@Composable
internal fun RatingCard(block: GiovRatingBlock, onRate: suspend (Int, String?) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalGiovLook.current
    val strings = look.strings.rating
    var model by remember(block.requestId) { mutableStateOf(RatingCardModel()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    // Read-only from `closesAt` on, without waiting for another update.
    LaunchedEffect(block.closesAt) {
        while (true) {
            val wait = block.closesAt - System.currentTimeMillis()
            if (wait <= 0) {
                now = System.currentTimeMillis()
                break
            }
            delay(minOf(wait, 3_600_000L))
            now = System.currentTimeMillis()
        }
    }

    fun send() {
        val (next, choice) = model.beginSend() ?: return
        model = next
        scope.launch {
            try {
                onRate(choice.score, choice.comment)
                model = model.sent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model = model.failed(e.asRatingFailure())
            }
        }
    }

    Box(modifier.testTag("giov-rating-card")) {
        when (val card = model.card(block, now)) {
            GiovRatingCardState.Hidden -> Unit
            GiovRatingCardState.Closed -> BasicText(strings.closed, style = look.hintStyle.copy(fontSize = 13.sp), modifier = Modifier.testTag("giov-rating-closed"))
            is GiovRatingCardState.Answered -> RatingAnswered(card, strings, onChange = { model = model.beginEditing(block) }, changeEnabled = !model.sending)
            is GiovRatingCardState.Form -> RatingForm(
                card = card,
                strings = strings,
                failure = model.failure,
                sending = model.sending,
                onSelect = { model = model.select(it) },
                onComment = { model = model.withComment(it) },
                onSend = ::send,
                onCancel = { model = model.cancelEditing() },
            )
        }
    }
}

@Composable
private fun RatingAnswered(card: GiovRatingCardState.Answered, strings: GiovRatingStrings, onChange: () -> Unit, changeEnabled: Boolean) {
    val look = LocalGiovLook.current
    Column(
        Modifier.semantics { contentDescription = strings.yourRating },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatingFace(card.score, look.primary, 24.dp)
            BasicText(
                strings.thanks(strings.face(card.score)),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = look.onAgentBubble),
                modifier = Modifier.testTag("giov-rating-thanks"),
            )
        }
        val comment = card.comment
        if (!comment.isNullOrEmpty()) BasicText(comment, style = TextStyle(fontSize = 13.sp, color = look.secondaryText))
        if (card.canChange) {
            Box(
                Modifier
                    .testTag("giov-rating-change")
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(enabled = changeEnabled, role = Role.Button, onClick = onChange),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(strings.change, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = look.primary))
            }
        }
    }
}

@Composable
private fun RatingForm(
    card: GiovRatingCardState.Form,
    strings: GiovRatingStrings,
    failure: GiovRatingFailure?,
    sending: Boolean,
    onSelect: (Int) -> Unit,
    onComment: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val look = LocalGiovLook.current
    Column(
        Modifier.semantics { contentDescription = strings.title },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.selectableGroup().semantics { contentDescription = strings.yourRating },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (score in GIOV_RATING_SCORES) {
                val on = card.selected == score
                val label = strings.face(score)
                Box(
                    Modifier
                        .testTag("giov-rating-face-$score")
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (on) look.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .semantics { contentDescription = label }
                        .selectable(selected = on, enabled = !sending, role = Role.RadioButton, onClick = { onSelect(score) }),
                    contentAlignment = Alignment.Center,
                ) {
                    RatingFace(score, if (on) look.primary else look.secondaryText)
                }
            }
        }
        val inputStyle = look.style.text(TextStyle(fontSize = 14.sp, color = look.text), look.style.input)
        val fieldShape = RoundedCornerShape(12.dp)
        val placeholder = strings.commentPlaceholder
        BasicTextField(
            value = card.comment,
            onValueChange = onComment,
            minLines = 2,
            maxLines = 4,
            textStyle = inputStyle,
            cursorBrush = SolidColor(look.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("giov-rating-comment")
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(look.inputFill, fieldShape)
                        .border(1.dp, look.inputBorder, fieldShape)
                        .padding(10.dp),
                ) {
                    if (card.comment.isEmpty()) BasicText(placeholder, style = look.hintStyle)
                    inner()
                }
            },
        )
        val problem = failure?.let { strings.describe(it) }
        if (problem != null) {
            BasicText(
                problem,
                style = look.errorStyle.copy(fontSize = 13.sp),
                modifier = Modifier.testTag("giov-rating-error").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val canSend = card.selected != null && !sending
            Box(
                Modifier
                    .testTag("giov-rating-send")
                    .defaultMinSize(minHeight = 48.dp)
                    .alpha(if (canSend) 1f else 0.4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(look.buttonColor)
                    .clickable(enabled = canSend, role = Role.Button, onClick = onSend)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(strings.send, style = look.buttonTextStyle)
            }
            if (card.canCancel) {
                Box(
                    Modifier
                        .testTag("giov-rating-cancel")
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(strings.cancel, style = TextStyle(fontSize = 14.sp, color = look.secondaryText))
                }
            }
        }
    }
}

/** Thumbs under a GiovBot answer: one vote, changeable; a thumbs-down offers an optional reason. */
@Composable
internal fun AnswerThumbs(vote: GiovAnswerVote?, onVote: suspend (GiovAnswerVote?, String?) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalGiovLook.current
    val strings = look.strings.rating
    var model by remember { mutableStateOf(FeedbackModel()) }
    val scope = rememberCoroutineScope()

    fun submit(value: GiovAnswerVote?, reason: String?) {
        scope.launch {
            try {
                onVote(value, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model = model.fail()
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.semantics { contentDescription = strings.rateAnswer },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (kind in GiovAnswerVote.entries) {
                val active = vote == kind
                val label = if (kind == GiovAnswerVote.UP) strings.goodAnswer else strings.badAnswer
                Box(
                    Modifier
                        .testTag(if (kind == GiovAnswerVote.UP) "giov-thumb-up" else "giov-thumb-down")
                        .size(48.dp)
                        .semantics {
                            contentDescription = label
                            selected = active
                        }
                        .clickable(role = Role.Button) {
                            val (next, send) = model.tap(kind, vote)
                            model = next
                            submit(send, null)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Thumb(kind == GiovAnswerVote.UP, active, if (active) look.primary else look.secondaryText)
                }
            }
            if (model.failed) {
                BasicText(
                    strings.couldNotSave,
                    style = look.errorStyle.copy(fontSize = 12.sp),
                    modifier = Modifier.padding(start = 4.dp).testTag("giov-thumb-error").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (model.asking) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val placeholder = strings.reasonPlaceholder
                val fieldShape = RoundedCornerShape(10.dp)
                BasicTextField(
                    value = model.reason,
                    onValueChange = { model = model.withReason(it) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = look.text),
                    cursorBrush = SolidColor(look.primary),
                    modifier = Modifier.weight(1f).testTag("giov-thumb-reason").semantics { contentDescription = placeholder },
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 40.dp)
                                .background(look.inputFill, fieldShape)
                                .border(1.dp, look.inputBorder, fieldShape)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (model.reason.isEmpty()) BasicText(placeholder, style = look.hintStyle.copy(fontSize = 13.sp))
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .testTag("giov-thumb-send")
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(role = Role.Button) {
                            val (next, reason) = model.submitReason()
                            model = next
                            if (reason != null) submit(GiovAnswerVote.DOWN, reason)
                        }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(strings.send, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = look.primary))
                }
                Box(
                    Modifier
                        .testTag("giov-thumb-skip")
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(role = Role.Button) { model = model.skip() }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(strings.skip, style = TextStyle(fontSize = 13.sp, color = look.secondaryText))
                }
            }
        }
    }
}

/** A thumb, drawn on a canvas (up, or the same turned over for down); filled while it is the visitor's vote. */
@Composable
private fun Thumb(up: Boolean, filled: Boolean, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.minDimension
        fun x(v: Float) = v * s
        fun y(v: Float) = if (up) v * s else (1f - v) * s
        val hand = Path().apply {
            moveTo(x(0.34f), y(0.46f))
            lineTo(x(0.48f), y(0.1f))
            cubicTo(x(0.64f), y(0.1f), x(0.7f), y(0.3f), x(0.62f), y(0.46f))
            lineTo(x(0.84f), y(0.46f))
            cubicTo(x(0.94f), y(0.46f), x(0.98f), y(0.56f), x(0.95f), y(0.66f))
            lineTo(x(0.88f), y(0.86f))
            cubicTo(x(0.85f), y(0.94f), x(0.8f), y(0.96f), x(0.72f), y(0.96f))
            lineTo(x(0.34f), y(0.96f))
            close()
        }
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (filled) drawPath(hand, color, style = Fill) else drawPath(hand, color, style = stroke)
        // The cuff on the wrist side.
        drawPath(
            Path().apply {
                moveTo(x(0.08f), y(0.46f))
                lineTo(x(0.34f), y(0.46f))
                lineTo(x(0.34f), y(0.96f))
                lineTo(x(0.08f), y(0.96f))
                close()
            },
            color,
            style = stroke,
        )
    }
}

/** The notice and the button that replace the composer once the team closed the conversation. */
@Composable
internal fun ClosedBar(onStart: () -> Unit) {
    val look = LocalGiovLook.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(look.composerColor)
            .padding(12.dp)
            .testTag("giov-closed-bar"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(look.strings.conversationEnded, style = look.hintStyle.copy(fontSize = 13.sp), modifier = Modifier.testTag("giov-closed-notice"))
        Box(
            Modifier
                .testTag("giov-start-new")
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(look.buttonColor)
                .clickable(role = Role.Button, onClick = onStart)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(look.strings.startNewConversation, style = look.buttonTextStyle)
        }
    }
}

/** The sheet [app.giovchat.core.GiovChatController.presentRating] opens: the open request's card on its own. */
@Composable
internal fun RatingSheet(block: GiovRatingBlock, onRate: suspend (Int, String?) -> Unit, onClose: () -> Unit) {
    val look = LocalGiovLook.current
    val strings = look.strings.rating
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .testTag("giov-rating-sheet")
                .background(look.background, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(
                    strings.title,
                    style = look.style.text(TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = look.text), look.style.formTitle),
                )
                Box(
                    Modifier
                        .testTag("giov-rating-sheet-close")
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(look.strings.closeChat, style = TextStyle(fontSize = 14.sp, color = look.secondaryText))
                }
            }
            RatingCard(block, onRate)
        }
    }
}
