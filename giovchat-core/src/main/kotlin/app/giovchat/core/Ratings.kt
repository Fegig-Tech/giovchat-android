package app.giovchat.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Conversation ratings and thumbs on GiovBot answers (docs/protocol/README.md, "Conversation ratings"). Everything
// here is plain data and pure functions: parsing the `rating` block and frame, patching messages, the card's state
// machine and the mapping of refusals. The controller drives them; the Compose card only draws a
// [GiovRatingCardState].

/** The five faces of the rating card, `1` Very bad to `5` Great. */
public val GIOV_RATING_SCORES: List<Int> = listOf(1, 2, 3, 4, 5)

/** The longest comment the server takes. */
public const val GIOV_RATING_COMMENT_MAX_LENGTH: Int = 1000

/** The longest reason the server takes with a thumbs-down. */
public const val GIOV_FEEDBACK_REASON_MAX_LENGTH: Int = 300

/** A visitor's thumb on a GiovBot answer. No vote is `null`; [GiovChatController.submitAnswerFeedback] takes `null` to take a vote back. */
public enum class GiovAnswerVote(internal val wire: String) {
    UP("up"),
    DOWN("down"),
    ;

    internal companion object {
        fun fromWire(value: String?): GiovAnswerVote? = entries.firstOrNull { it.wire == value }
    }
}

/** What tapping [tapped] sends when the current vote is [current]: the same thumb again takes the vote back. */
public fun giovNextVote(current: GiovAnswerVote?, tapped: GiovAnswerVote): GiovAnswerVote? = if (current == tapped) null else tapped

/** Where a rating card is in its life. */
public enum class GiovRatingPhase {
    /** Replaced by a newer request: draw nothing. */
    HIDDEN,

    /** Can be answered or changed. */
    OPEN,

    /** Read-only. */
    CLOSED,
}

/** A `rating` block of a message: the request to rate the conversation, with the visitor's answer once given. */
public data class GiovRatingBlock(
    val requestId: String,
    /**
     * When the card stops accepting answers or changes, Unix milliseconds. The server decides; the client clock only
     * picks the read-only look.
     */
    val closesAt: Long,
    /** A small hosted page where the same rating can be given, for clients that cannot draw the card. */
    val url: String? = null,
    /** 1 to 5 once the visitor has answered. */
    val score: Int? = null,
    val comment: String? = null,
    /** A newer request took this one's place: draw nothing. */
    val replaced: Boolean = false,
) {
    /** `HIDDEN` (replaced), `OPEN` (can be answered or changed) or `CLOSED` (read-only) at [now]. */
    public fun phaseAt(now: Long): GiovRatingPhase = when {
        replaced -> GiovRatingPhase.HIDDEN
        now < closesAt -> GiovRatingPhase.OPEN
        else -> GiovRatingPhase.CLOSED
    }

    internal companion object {
        /** The block from a message's `blocks` entry; null when it is not a usable rating block. */
        fun fromJson(json: JsonElement?): GiovRatingBlock? {
            val map = json.asObject()
            if (map["type"].asString() != "rating") return null
            val requestId = map["requestId"].asNonEmpty() ?: return null
            val closesAt = map["closesAt"].asNumber()?.toLong() ?: return null
            val score = validRatingScore(map["score"])
            return GiovRatingBlock(
                requestId = requestId,
                closesAt = closesAt,
                url = safeHttpUrl(map["url"]),
                score = score,
                comment = if (score == null) null else map["comment"].asNonEmpty(),
                replaced = map["state"].asString() == "replaced",
            )
        }
    }
}

internal fun validRatingScore(value: JsonElement?): Int? {
    val number = value.asNumber() ?: return null
    return if (number == Math.rint(number) && number in 1.0..5.0) number.toInt() else null
}

/** The open request to rate the conversation, for hosts that draw their own UI. */
public data class GiovRatingRequest(
    /** The message that carries the card. */
    val messageId: String,
    val requestId: String,
    val closesAt: Long,
    /** The hosted rating page, when the API knows its public origin. */
    val url: String? = null,
    /** The visitor's answer so far. */
    val score: Int? = null,
    val comment: String? = null,
) {
    /** The visitor has answered (the answer may still be changed until [closesAt]). */
    val isAnswered: Boolean get() = score != null
}

/** What the server stored for an answer (`POST /conversations/{id}/rating`). */
public data class GiovRatingResult(
    val requestId: String,
    val score: Int,
    val comment: String?,
    val closesAt: Long,
    /** Unix milliseconds. */
    val ratedAt: Long,
) {
    internal companion object {
        fun fromJson(json: JsonObject, now: Long): GiovRatingResult? {
            val requestId = json["requestId"].asNonEmpty() ?: return null
            val score = validRatingScore(json["score"]) ?: return null
            val closesAt = json["closesAt"].asNumber()?.toLong() ?: return null
            return GiovRatingResult(requestId, score, json["comment"].asNonEmpty(), closesAt, json["ratedAt"].asNumber()?.toLong() ?: now)
        }
    }
}

/** The `rating` socket frame: the card changed (answered or changed on any device, or replaced). */
internal data class GiovRatingFrame(
    val requestId: String,
    val messageId: String?,
    val closesAt: Long,
    val score: Int? = null,
    val comment: String? = null,
    /** `state: "replaced"`: a newer card took this one's place; hide it at once. */
    val replaced: Boolean = false,
) {
    companion object {
        fun fromJson(json: JsonObject): GiovRatingFrame? {
            if (json["type"].asString() != "rating") return null
            val requestId = json["requestId"].asNonEmpty() ?: return null
            val closesAt = json["closesAt"].asNumber()?.toLong() ?: return null
            val score = validRatingScore(json["score"])
            return GiovRatingFrame(
                requestId = requestId,
                messageId = json["messageId"].asNonEmpty(),
                closesAt = closesAt,
                score = score,
                comment = if (score == null) null else json["comment"].asNonEmpty(),
                replaced = json["state"].asString() == "replaced",
            )
        }
    }
}

/**
 * Sets the answer and deadline on the card the frame (or the answer to our own POST) names; other messages stay as
 * they are. A frame without a score means "no answer yet" and clears the card's answer.
 */
internal fun patchRating(messages: List<GiovMessage>, frame: GiovRatingFrame): List<GiovMessage> = messages.map { message ->
    val block = message.rating
    if (block == null || block.requestId != frame.requestId || (frame.messageId != null && frame.messageId != message.id)) {
        message
    } else if (frame.replaced) {
        message.copy(rating = block.copy(replaced = true))
    } else {
        message.copy(rating = block.copy(closesAt = frame.closesAt, score = frame.score, comment = frame.comment))
    }
}

/** Sets (or, with `null`, clears) the visitor's vote on one message. */
internal fun applyFeedback(messages: List<GiovMessage>, id: String, vote: GiovAnswerVote?): List<GiovMessage> =
    messages.map { if (it.id == id && it.feedback != vote) it.copy(feedback = vote) else it }

/** A newer rating card hides every older card the visitor has not answered (one open card per conversation). */
internal fun replaceOlderRatings(messages: List<GiovMessage>): List<GiovMessage> {
    val newest = messages.indexOfLast { it.rating != null && it.rating.replaced.not() }
    if (newest < 0) return messages
    return messages.mapIndexed { index, message ->
        val block = message.rating
        if (index < newest && block != null && !block.replaced && block.score == null) message.copy(rating = block.copy(replaced = true)) else message
    }
}

/** The newest request that is not replaced and not past `closesAt`: the one the visitor can answer or change now. */
internal fun openRatingRequest(messages: List<GiovMessage>, now: Long): GiovRatingRequest? {
    for (message in messages.asReversed()) {
        val block = message.rating ?: continue
        if (block.phaseAt(now) != GiovRatingPhase.OPEN) continue
        return GiovRatingRequest(message.id, block.requestId, block.closesAt, block.url, block.score, block.comment)
    }
    return null
}

// -------------------------------------------------------------------------------------------------
// Refusals

/** What a failed rating call means for the card. */
public sealed interface GiovRatingFailure {
    /** `rating_closed`: past `closesAt`. Show the card read-only. */
    public data object Closed : GiovRatingFailure

    /** `request_replaced`: a newer card took its place. Hide it. */
    public data object Replaced : GiovRatingFailure

    /** `too_many_changes`: at most 20 changes per card. Show the card read-only. */
    public data object TooManyChanges : GiovRatingFailure

    /** `invalid_rating` / `invalid_feedback`: fix the input. */
    public data object Invalid : GiovRatingFailure

    /** `request_not_found` / `message_not_found`: nothing to answer (any more). */
    public data object NotFound : GiovRatingFailure

    /** `rate_limited`: try again after the pause. */
    public data class RateLimited(val retryAfterSeconds: Long) : GiovRatingFailure

    /** The server has no rating routes (an older server): hide the controls. */
    public data object Unsupported : GiovRatingFailure

    /** Anything else (network, server, session): the answer was not saved, let the visitor try again. */
    public data object Retry : GiovRatingFailure
}

/** This failure as the card sees it; use it to decide what to show after a rating or feedback call failed. */
public val GiovChatException.ratingFailure: GiovRatingFailure
    get() = when (code) {
        "rating_closed" -> GiovRatingFailure.Closed
        "request_replaced" -> GiovRatingFailure.Replaced
        "too_many_changes" -> GiovRatingFailure.TooManyChanges
        "invalid_rating", "invalid_feedback" -> GiovRatingFailure.Invalid
        "request_not_found", "message_not_found" -> GiovRatingFailure.NotFound
        else -> when {
            this is GiovRateLimitedException -> GiovRatingFailure.RateLimited(retryAfterSeconds)
            status == 404 -> GiovRatingFailure.Unsupported
            else -> GiovRatingFailure.Retry
        }
    }

/** Thumbs go under a finished GiovBot answer, not under a draft and not under the rating card message GiovBot sends. */
public val GiovMessage.allowsAnswerFeedback: Boolean
    get() = role == GiovRole.AGENT && isBot && !isStreaming && rating == null

// -------------------------------------------------------------------------------------------------
// The card

/**
 * What the rating card draws, worked out from the block, the clock and the visitor's local choices. Pure, so the whole
 * life of a card (pending, answered, changed, closed, replaced) is tested without a UI.
 */
public sealed interface GiovRatingCardState {
    /** Nothing is drawn (replaced). */
    public data object Hidden : GiovRatingCardState

    /** The five faces and the comment field. [canCancel] when an earlier answer exists to go back to. */
    public data class Form(val selected: Int?, val comment: String, val canCancel: Boolean) : GiovRatingCardState

    /** "Thanks" with the chosen face and comment; [canChange] while the window is open. */
    public data class Answered(val score: Int, val comment: String?, val canChange: Boolean) : GiovRatingCardState

    /** Read-only without an answer: "This rating request has closed." */
    public data object Closed : GiovRatingCardState

    public companion object {
        /**
         * @param block the card's block (with any optimistic answer already applied).
         * @param now the clock, Unix milliseconds.
         * @param editing the visitor tapped "Change" (or the last send failed) and is choosing again.
         * @param selected the visitor's unsent choice in the form.
         * @param comment the visitor's unsent comment in the form.
         * @param lock a refusal that made the card read-only or hidden since it was drawn.
         */
        public fun make(
            block: GiovRatingBlock,
            now: Long,
            editing: Boolean = false,
            selected: Int? = null,
            comment: String = "",
            lock: GiovRatingFailure? = null,
        ): GiovRatingCardState {
            if (lock == GiovRatingFailure.Replaced || block.replaced) return Hidden
            val locked = lock == GiovRatingFailure.Closed || lock == GiovRatingFailure.TooManyChanges
            val open = block.phaseAt(now) == GiovRatingPhase.OPEN && !locked
            val score = block.score
            if (score != null && (!editing || !open)) return Answered(score, block.comment, canChange = open)
            if (!open) return Closed
            return Form(selected, comment, canCancel = score != null)
        }

        /** The face to focus after an arrow key (or an accessibility adjustment): wraps around. */
        public fun step(from: Int?, by: Int): Int = ((from ?: 1) - 1 + by + 5) % 5 + 1

        /** [text] cut to the server's limit (UTF-16 units, as the server counts), never splitting a character. */
        public fun clampComment(text: String): String = clampUtf16(text, GIOV_RATING_COMMENT_MAX_LENGTH)

        /** The reason cut to the server's limit. */
        public fun clampReason(text: String): String = clampUtf16(text, GIOV_FEEDBACK_REASON_MAX_LENGTH)
    }
}

private fun clampUtf16(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max
    // Never end between the two halves of a surrogate pair.
    if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
    return text.substring(0, end)
}

/** The text of the rating card and the thumbs, in the same five languages as [GiovChatStrings]. */
public class GiovRatingStrings(
    /** The card's accessible name. */
    public val title: String,
    /** The five faces' labels (and accessibility labels), `1` first. */
    faces: List<String>,
    /** Accessible name of the group of faces. */
    public val yourRating: String,
    public val commentPlaceholder: String,
    public val send: String,
    public val cancel: String,
    public val change: String,
    /** "Thanks" next to the chosen face; takes the face's label. */
    public val thanks: (String) -> String,
    public val closed: String,
    /** Shown when an answer could not be saved. */
    public val couldNotSend: String,
    public val goodAnswer: String,
    public val badAnswer: String,
    /** Accessible name of the group of thumbs. */
    public val rateAnswer: String,
    public val reasonPlaceholder: String,
    public val skip: String,
    public val couldNotSave: String,
) {
    public val faces: List<String> = if (faces.size == 5) faces else listOf("Very bad", "Bad", "Okay", "Good", "Great")

    /** The label of face [score] (1 to 5). */
    public fun face(score: Int): String = faces.getOrNull(score - 1).orEmpty()

    /** The sentence for a failed answer; null when the card just changes look. */
    public fun describe(failure: GiovRatingFailure): String? = when (failure) {
        GiovRatingFailure.Closed, GiovRatingFailure.TooManyChanges, GiovRatingFailure.Replaced,
        GiovRatingFailure.Unsupported, GiovRatingFailure.NotFound,
        -> null
        GiovRatingFailure.Invalid, is GiovRatingFailure.RateLimited, GiovRatingFailure.Retry -> couldNotSend
    }

    public companion object {
        public val EN: GiovRatingStrings = GiovRatingStrings(
            title = "Rate this conversation",
            faces = listOf("Very bad", "Bad", "Okay", "Good", "Great"),
            yourRating = "Your rating",
            commentPlaceholder = "Add a comment (optional)",
            send = "Send",
            cancel = "Cancel",
            change = "Change",
            thanks = { face -> "Thanks · $face" },
            closed = "This rating request has closed.",
            couldNotSend = "Couldn't send. Please try again.",
            goodAnswer = "Good answer",
            badAnswer = "Bad answer",
            rateAnswer = "Rate this answer",
            reasonPlaceholder = "What went wrong? (optional)",
            skip = "Skip",
            couldNotSave = "Couldn't save",
        )

        public val FR: GiovRatingStrings = GiovRatingStrings(
            title = "Évaluer cette conversation",
            faces = listOf("Très mauvais", "Mauvais", "Moyen", "Bien", "Excellent"),
            yourRating = "Votre note",
            commentPlaceholder = "Ajouter un commentaire (facultatif)",
            send = "Envoyer",
            cancel = "Annuler",
            change = "Modifier",
            thanks = { face -> "Merci · $face" },
            closed = "Cette demande d’évaluation est terminée.",
            couldNotSend = "Envoi impossible. Réessayez.",
            goodAnswer = "Bonne réponse",
            badAnswer = "Mauvaise réponse",
            rateAnswer = "Évaluer cette réponse",
            reasonPlaceholder = "Qu’est-ce qui n’allait pas ? (facultatif)",
            skip = "Passer",
            couldNotSave = "Enregistrement impossible",
        )

        public val ES: GiovRatingStrings = GiovRatingStrings(
            title = "Valora esta conversación",
            faces = listOf("Muy mal", "Mal", "Regular", "Bien", "Excelente"),
            yourRating = "Tu valoración",
            commentPlaceholder = "Añade un comentario (opcional)",
            send = "Enviar",
            cancel = "Cancelar",
            change = "Cambiar",
            thanks = { face -> "Gracias · $face" },
            closed = "Esta solicitud de valoración ha terminado.",
            couldNotSend = "No se pudo enviar. Inténtalo de nuevo.",
            goodAnswer = "Buena respuesta",
            badAnswer = "Mala respuesta",
            rateAnswer = "Valora esta respuesta",
            reasonPlaceholder = "¿Qué salió mal? (opcional)",
            skip = "Omitir",
            couldNotSave = "No se pudo guardar",
        )

        public val PT: GiovRatingStrings = GiovRatingStrings(
            title = "Avalie esta conversa",
            faces = listOf("Muito ruim", "Ruim", "Regular", "Bom", "Ótimo"),
            yourRating = "Sua avaliação",
            commentPlaceholder = "Adicione um comentário (opcional)",
            send = "Enviar",
            cancel = "Cancelar",
            change = "Alterar",
            thanks = { face -> "Obrigado · $face" },
            closed = "Este pedido de avaliação foi encerrado.",
            couldNotSend = "Não foi possível enviar. Tente novamente.",
            goodAnswer = "Boa resposta",
            badAnswer = "Resposta ruim",
            rateAnswer = "Avalie esta resposta",
            reasonPlaceholder = "O que deu errado? (opcional)",
            skip = "Pular",
            couldNotSave = "Não foi possível salvar",
        )

        public val DE: GiovRatingStrings = GiovRatingStrings(
            title = "Diese Unterhaltung bewerten",
            faces = listOf("Sehr schlecht", "Schlecht", "Okay", "Gut", "Sehr gut"),
            yourRating = "Deine Bewertung",
            commentPlaceholder = "Kommentar hinzufügen (optional)",
            send = "Senden",
            cancel = "Abbrechen",
            change = "Ändern",
            thanks = { face -> "Danke · $face" },
            closed = "Diese Bewertungsanfrage ist beendet.",
            couldNotSend = "Senden fehlgeschlagen. Bitte versuche es erneut.",
            goodAnswer = "Gute Antwort",
            badAnswer = "Schlechte Antwort",
            rateAnswer = "Diese Antwort bewerten",
            reasonPlaceholder = "Was ist schiefgelaufen? (optional)",
            skip = "Überspringen",
            couldNotSave = "Speichern fehlgeschlagen",
        )
    }
}
