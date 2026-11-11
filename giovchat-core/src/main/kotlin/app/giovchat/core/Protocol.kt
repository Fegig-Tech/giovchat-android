package app.giovchat.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Data the visitor protocol carries (docs/protocol/visitor-api.openapi.yaml and visitor-realtime.asyncapi.yaml),
// parsed defensively: unknown fields are ignored and malformed values fall back instead of throwing.

/** The widget's default brand colour when the configured one cannot be parsed. */
public const val DEFAULT_PRIMARY_COLOR: String = "#7c3aed"

/** `light`, `dark`, or `auto` (follow the device). */
public enum class GiovThemeMode { LIGHT, DARK, AUTO }

/** Fields the workspace asks for in a pre-chat form before the first message. */
public data class GiovRequestUserInfo(
    val email: Boolean = false,
    val phone: Boolean = false,
    val firstName: Boolean = false,
    val lastName: Boolean = false,
) {
    /** At least one field is asked for. */
    val any: Boolean get() = email || phone || firstName || lastName

    internal companion object {
        fun fromJson(json: JsonElement?): GiovRequestUserInfo {
            val map = json.asObject()
            return GiovRequestUserInfo(
                email = map["email"].isTrue(),
                phone = map["phone"].isTrue(),
                firstName = map["firstName"].isTrue(),
                lastName = map["lastName"].isTrue(),
            )
        }
    }
}

/** `GET /widget/config`: everything the business set up for its chat. */
public data class GiovWidgetConfig(
    /** The team's name, for the header when neither a person nor the bot answers. */
    val title: String,
    /** Brand colour as sent, usually `#rrggbb`. */
    val primaryColor: String = DEFAULT_PRIMARY_COLOR,
    /** Shown before the visitor writes. Plain text. */
    val greetingText: String = "Hi! How can we help?",
    /** Composer placeholder. */
    val textArea: String? = null,
    val theme: GiovThemeMode = GiovThemeMode.AUTO,
    val botEnabled: Boolean = false,
    val botName: String? = null,
    val botAvatarUrl: String? = null,
    val avatarUrl: String? = null,
    /** The answering GiovBot's attachment rules; null when no bot is published (then images only). */
    val attachments: GiovAttachmentPolicy? = null,
    val showBranding: Boolean = true,
    val privacyPolicyUrl: String? = null,
    val requestUserInfo: GiovRequestUserInfo = GiovRequestUserInfo(),
    /** `bubble` or `pill`. */
    val launcherStyle: String = "bubble",
) {
    /** What a visitor may attach right now: the bot's policy, or the server's default (images). */
    val attachmentPolicy: GiovAttachmentPolicy get() = attachments ?: GiovAttachmentPolicy.DEFAULT

    internal companion object {
        fun fromJson(json: JsonObject): GiovWidgetConfig = GiovWidgetConfig(
            title = json["title"].asNonEmpty() ?: "Support",
            primaryColor = json["primaryColor"].asString() ?: DEFAULT_PRIMARY_COLOR,
            greetingText = json["greetingText"].asString() ?: "Hi! How can we help?",
            textArea = json["textArea"].asNonEmpty(),
            theme = GiovThemeMode.entries.firstOrNull { it.name.lowercase() == json["theme"].asString() } ?: GiovThemeMode.AUTO,
            botEnabled = json["botEnabled"].isTrue(),
            botName = json["botName"].asNonEmpty(),
            botAvatarUrl = safeHttpUrl(json["botAvatarUrl"]),
            avatarUrl = safeHttpUrl(json["avatarUrl"]),
            attachments = GiovAttachmentPolicy.fromJson(json["attachments"]),
            // Only an explicit false removes the branding (a server-resolved plan policy).
            showBranding = !json["showBranding"].isFalse(),
            privacyPolicyUrl = safeHttpUrl(json["privacyPolicyUrl"]),
            requestUserInfo = GiovRequestUserInfo.fromJson(json["requestUserInfo"]),
            launcherStyle = if (json["launcherStyle"].asString() == "pill") "pill" else "bubble",
        )
    }
}

/**
 * Contact details the host app knows. All untrusted except a [userId] with its [userHash]; see
 * "Identity" in docs/protocol/README.md.
 */
public data class GiovContact(
    /** Your stable customer id. Trusted only together with [userHash]. */
    val userId: String? = null,
    /** `HMAC-SHA256(identity secret, userId)`, lowercase hex, computed on YOUR server. Never ship the identity secret in an app. */
    val userHash: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
) {
    /** These details with the non-null fields of [other] on top. */
    public fun merge(other: GiovContact): GiovContact = GiovContact(
        userId = other.userId ?: userId,
        userHash = other.userHash ?: userHash,
        email = other.email ?: email,
        phone = other.phone ?: phone,
        firstName = other.firstName ?: firstName,
        lastName = other.lastName ?: lastName,
    )

    /** The fields `POST /conversations` accepts; empty values are left out and the rest is trimmed. */
    internal fun toStartFields(): Map<String, String> = buildMap {
        for ((key, value) in listOf(
            "userId" to userId, "userHash" to userHash, "email" to email,
            "phone" to phone, "firstName" to firstName, "lastName" to lastName,
        )) {
            if (value != null && value.isNotBlank()) put(key, value.trim())
        }
    }
}

/** The private proof that this device owns a conversation. Store it securely; never log it. */
public data class GiovSession(val conversationId: String, val sessionToken: String) {
    override fun toString(): String = "GiovSession($conversationId)"
}

public enum class GiovRole { VISITOR, AGENT }

/** Local delivery state of a visitor message. */
public enum class GiovDelivery { SENDING, SENT, FAILED }

/** One message in the conversation, from the socket, history or this device. */
public data class GiovMessage(
    /** The stored id, or the client id while the message is still on its way. */
    val id: String,
    val role: GiovRole,
    /** Chat markdown; render it with [app.giovchat.core.markdown.ChatMarkdown]. */
    val content: String,
    /** Unix milliseconds. */
    val createdAt: Long,
    /** Written by GiovBot. */
    val isBot: Boolean = false,
    /** An automatic notice (away message, bot failure notice); not a person. */
    val isAutomated: Boolean = false,
    /** The person who wrote it, for team messages; null for the bot and notices. */
    val senderName: String? = null,
    val senderAvatar: String? = null,
    val attachments: List<GiovAttachment> = emptyList(),
    val delivery: GiovDelivery = GiovDelivery.SENT,
    /** The id this message was shown under before the server confirmed it. Use [key] for list keys. */
    val clientMessageId: String? = null,
    /** For a bot reply: the visitor message it answers. */
    val streamId: String? = null,
    /** A GiovBot reply still being written. */
    val isStreaming: Boolean = false,
) {
    /**
     * A stable list key: it is the same before and after the server stores the message and when a streamed
     * draft becomes the saved reply, so a lazy list does not rebuild the bubble (or reload its images).
     */
    val key: String get() = clientMessageId ?: if (streamId != null && isBot) "stream:$streamId" else id

    /** Written by a person on the team (the header identity's "person" signal). */
    val isFromPerson: Boolean get() = role == GiovRole.AGENT && !isBot && !isAutomated

    internal companion object {
        /**
         * A message from a socket frame (camelCase) or a history row (camelCase, with the deprecated snake_case
         * names as a fallback). The visitor's role is `user` in history and `visitor` on the socket. Returns
         * null for anything that is not a message.
         */
        fun fromJson(json: JsonElement?): GiovMessage? {
            val map = json.asObject()
            val role = when (map["role"].asString()) {
                "user", "visitor" -> GiovRole.VISITOR
                "agent" -> GiovRole.AGENT
                else -> return null
            }
            val id = map["id"].asNonEmpty() ?: return null
            val content = map["content"].asString() ?: return null
            val createdAt = (map["createdAt"] ?: map["created_at"]).asNumber() ?: return null
            val agent = role == GiovRole.AGENT
            val isBot = agent && (map["isBot"].isTrue() || map["is_bot"].asNumber() == 1.0)
            val isAutomated = agent && (map["isAutomated"].isTrue() || map["is_automated"].asNumber() == 1.0)
            val person = agent && !isBot && !isAutomated
            return GiovMessage(
                id = id,
                role = role,
                content = content,
                createdAt = createdAt.toLong(),
                isBot = isBot,
                isAutomated = isAutomated,
                senderName = if (agent) map["senderName"].asNonEmpty() else null,
                senderAvatar = if (person) safeHttpUrl(map["senderAvatar"]) else null,
                attachments = parseAttachments(map["attachmentId"] ?: map["attachment_id"], map["attachmentPreviews"] ?: map["attachment_previews"]),
                streamId = map["streamId"].asNonEmpty(),
            )
        }
    }
}

/** `GET /conversations/{id}/history`, parsed. */
public data class GiovHistory(
    val messages: List<GiovMessage>,
    /** The team closed the conversation. */
    val closed: Boolean,
    val assignedAgentName: String? = null,
    val assignedAgentAvatar: String? = null,
    val attachmentAccess: AttachmentAccess? = null,
) {
    internal companion object {
        fun fromJson(json: JsonObject): GiovHistory {
            val assignedName = json["assignedAgentName"].asNonEmpty()
            val messages = (json["messages"] as? JsonArray).orEmpty().mapNotNull { row ->
                val message = GiovMessage.fromJson(row) ?: return@mapNotNull null
                // History rows carry no sender; a person's message is shown under the assigned teammate.
                if (message.isFromPerson && message.senderName == null && assignedName != null) message.copy(senderName = assignedName) else message
            }
            return GiovHistory(
                messages = messages,
                closed = json["status"].asString() == "closed",
                assignedAgentName = assignedName,
                assignedAgentAvatar = if (assignedName != null) safeHttpUrl(json["assignedAgentAvatar"]) else null,
                attachmentAccess = AttachmentAccess.fromJson(json["attachmentAccess"]),
            )
        }
    }
}

/** `POST /auth/widget-token`: the 15-minute visitor token. */
internal class VisitorToken(val token: String, val expiresAt: Long, val attachmentAccess: AttachmentAccess?) {
    /** Renew before every connect and when less than a minute is left. */
    fun usableAt(nowMs: Long): Boolean = expiresAt - nowMs > 60_000L

    companion object {
        /** `expiresAt` is Unix **seconds**, the one time in seconds in the protocol; assume a little under 15 minutes when absent. */
        fun fromJson(json: JsonObject, nowMs: Long): VisitorToken? {
            val token = json["token"].asNonEmpty() ?: return null
            val seconds = json["expiresAt"].asNumber()
            val expiresAt = if (seconds != null) (seconds * 1000).toLong() else nowMs + 14 * 60_000L
            return VisitorToken(token, expiresAt, AttachmentAccess.fromJson(json["attachmentAccess"]))
        }
    }
}
