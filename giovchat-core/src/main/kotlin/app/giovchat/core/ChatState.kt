package app.giovchat.core

/**
 * `READY`: the chat can be used but no conversation exists yet. Per the visitor protocol a native client starts
 * one when the visitor sends the first message (or submits the pre-chat form), so opening the screen never
 * creates an empty conversation. `IDLE`: hidden with nothing to resume.
 */
public enum class GiovChatPhase { IDLE, LOADING, PRE_CHAT, READY, CONNECTING, ACTIVE, ERROR }

public enum class GiovConnectionStatus { CONNECTING, CONNECTED, DISCONNECTED }

/** A file being uploaded; [progress] is 0 to 1. */
public data class GiovUploadProgress(val name: String, val progress: Double)

/**
 * Everything the chat screen draws, as one immutable snapshot. Collect [GiovChatController.state]; a new
 * value is published after every change.
 */
public data class GiovChatUiState(
    val phase: GiovChatPhase = GiovChatPhase.LOADING,
    /** Null until the configuration has loaded. */
    val config: GiovWidgetConfig? = null,
    /** What to draw, oldest first: saved messages, sending ones, and streaming drafts. Use [GiovMessage.key] as the list key. */
    val messages: List<GiovMessage> = emptyList(),
    /** Who the header shows (person, bot, team); null until the configuration has loaded. */
    val header: GiovHeaderIdentity? = null,
    /** The teammate who has the conversation, once known. */
    val agentName: String? = null,
    val agentAvatar: String? = null,
    /** The agent side is typing. When [agentTypingIsBot] is true, show the bot's indicator. */
    val agentTyping: Boolean = false,
    val agentTypingIsBot: Boolean? = null,
    /** Replies the visitor has not seen because the chat was not visible; 0 while visible. */
    val unreadCount: Int = 0,
    /** The last failure worth showing; use [GiovChatStrings.describeError]. Cleared when sending resumes. */
    val error: GiovChatException? = null,
    /** Files are uploading before their message is queued. */
    val sending: Boolean = false,
    val uploads: List<GiovUploadProgress> = emptyList(),
    /** Pre-chat fields still to ask for (when [phase] is [GiovChatPhase.PRE_CHAT]). */
    val requiredFields: GiovRequestUserInfo = GiovRequestUserInfo(),
    /** A conversation exists (so attachments can be uploaded). */
    val hasConversation: Boolean = false,
    val connectionStatus: GiovConnectionStatus = GiovConnectionStatus.DISCONNECTED,
    val visible: Boolean = true,
    /** The conversation id, once started. */
    val conversationId: String? = null,
    /** The open request to rate the conversation (not replaced, before its `closesAt`); `score` is set once answered. */
    val ratingRequest: GiovRatingRequest? = null,
    /** False once the server showed it has no rating route (an older server): hide the rating card. */
    val ratingsAvailable: Boolean = true,
    /** False once the server showed it has no answer-feedback route: hide the thumbs. */
    val feedbackAvailable: Boolean = true,
    /** The built-in chat UI draws the rating card (the host turned it off with `showsRatingCard = false` otherwise). */
    val showsRatingCard: Boolean = true,
    /** The built-in chat UI draws thumbs under GiovBot answers. */
    val showsAnswerFeedback: Boolean = true,
    /** The host asked for the rating sheet with [GiovChatController.presentRating]. */
    val ratingSheetPresented: Boolean = false,
    /** The team closed the conversation: it is shown read-only. [GiovChatController.startNewConversation] leaves it. */
    val conversationClosed: Boolean = false,
) {
    val isConnected: Boolean get() = connectionStatus == GiovConnectionStatus.CONNECTED
}

/** A reply being written. */
internal data class GiovDraft(val content: String, val createdAt: Long)

/** Contact fields the workspace requires and the host has not supplied (or that are malformed). */
internal fun missingContactFields(fields: GiovRequestUserInfo, contact: GiovContact): GiovRequestUserInfo {
    fun missing(required: Boolean, value: String?, email: Boolean = false): Boolean {
        if (!required) return false
        val text = value?.trim().orEmpty()
        return text.isEmpty() || (email && !EMAIL.matches(text))
    }
    return GiovRequestUserInfo(
        email = missing(fields.email, contact.email, email = true),
        phone = missing(fields.phone, contact.phone),
        firstName = missing(fields.firstName, contact.firstName),
        lastName = missing(fields.lastName, contact.lastName),
    )
}

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private val BY_TIME = compareBy<GiovMessage>({ it.createdAt }, { it.id })

/**
 * By server id; a copy without a local key keeps the one it was shown under, so the bubble is not redrawn.
 * Sorted by creation time.
 */
internal fun mergeMessages(current: List<GiovMessage>, incoming: List<GiovMessage>): List<GiovMessage> {
    val messages = LinkedHashMap<String, GiovMessage>()
    for (message in current) messages[message.id] = message
    for (message in incoming) {
        val existing = messages[message.id]
        messages[message.id] =
            if (existing?.clientMessageId != null && message.clientMessageId == null) message.copy(clientMessageId = existing.clientMessageId) else message
    }
    return messages.values.sortedWith(BY_TIME)
}

/**
 * What the visitor sees: saved messages, then what is still sending, then replies being written. [describe]
 * supplies names and types for files sent from this device.
 */
internal fun displayMessages(
    saved: List<GiovMessage>,
    pending: List<OutboxEntry>,
    drafts: Map<String, GiovDraft>,
    describe: ((String) -> GiovAttachment)? = null,
): List<GiovMessage> {
    fun known(url: String): GiovAttachment = describe?.invoke(url) ?: GiovAttachment(url)
    val shown = saved.mapNotNullTo(HashSet()) { it.clientMessageId }
    val list = mutableListOf<GiovMessage>()
    for (message in saved) {
        if (message.attachments.isEmpty()) {
            list += message
        } else {
            list += message.copy(
                attachments = message.attachments.map { attachment ->
                    val local = known(attachment.url)
                    GiovAttachment(
                        url = attachment.url,
                        filename = attachment.filename.ifEmpty { local.filename },
                        preview = attachment.preview ?: local.preview,
                        contentType = attachment.contentType ?: local.contentType,
                    )
                },
            )
        }
    }
    for (entry in pending) {
        if (entry.clientMessageId in shown) continue
        list += GiovMessage(
            id = entry.clientMessageId,
            clientMessageId = entry.clientMessageId,
            role = GiovRole.VISITOR,
            content = entry.content,
            createdAt = entry.createdAt,
            delivery = if (entry.state == OutboxState.FAILED) GiovDelivery.FAILED else GiovDelivery.SENDING,
            attachments = entry.attachments.map(::known),
        )
    }
    for ((streamId, draft) in drafts) {
        val key = "stream:$streamId"
        if (key in shown || draft.content.isEmpty()) continue
        list += GiovMessage(
            id = key,
            clientMessageId = key,
            role = GiovRole.AGENT,
            content = draft.content,
            createdAt = draft.createdAt,
            isBot = true,
            isAutomated = true,
            streamId = streamId,
            isStreaming = true,
        )
    }
    return list.sortedWith(BY_TIME)
}
