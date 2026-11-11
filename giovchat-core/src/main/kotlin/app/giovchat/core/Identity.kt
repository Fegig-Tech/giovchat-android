package app.giovchat.core

import kotlinx.serialization.json.JsonObject

// Who the visitor is talking to and who wrote each message (docs/protocol/README.md, "Identity (who wrote it,
// who is in the header)"), as the web widget shows it.

public enum class GiovHeaderKind { PERSON, BOT, TEAM }

/** What the chat header shows. */
public data class GiovHeaderIdentity(
    val kind: GiovHeaderKind,
    val title: String,
    /** An http(s) image URL, or null (show the initial). */
    val avatar: String? = null,
) {
    /** The status line under the title. */
    public fun status(strings: GiovChatStrings): String = when (kind) {
        GiovHeaderKind.PERSON -> strings.statusPerson
        GiovHeaderKind.BOT -> strings.statusBot
        GiovHeaderKind.TEAM -> strings.statusTeam
    }
}

/**
 * First match wins: a person once one has written or history names an assigned teammate; else the bot
 * while it answers (`botEnabled` and `botName`); else the team (`title`, `avatarUrl`).
 */
public fun headerIdentity(config: GiovWidgetConfig, agentName: String? = null, agentAvatar: String? = null): GiovHeaderIdentity {
    if (!agentName.isNullOrEmpty()) return GiovHeaderIdentity(GiovHeaderKind.PERSON, agentName, safeHttpUrl(agentAvatar))
    val botName = config.botName
    if (config.botEnabled && !botName.isNullOrEmpty()) {
        return GiovHeaderIdentity(GiovHeaderKind.BOT, botName, safeHttpUrl(config.botAvatarUrl))
    }
    return GiovHeaderIdentity(GiovHeaderKind.TEAM, config.title, safeHttpUrl(config.avatarUrl))
}

/**
 * The label above a bubble. AI replies are also automated internally; the AI identity wins. [agentName] is
 * the assigned teammate, used for people's messages that carry no name (history).
 */
public fun messageSenderLabel(message: GiovMessage, strings: GiovChatStrings, botName: String? = null, agentName: String? = null): String {
    if (message.role != GiovRole.AGENT) return strings.you
    if (message.isBot) return botName?.trim()?.takeIf { it.isNotEmpty() } ?: "GiovBot"
    if (message.isAutomated) return strings.automated
    message.senderName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return agentName?.trim()?.takeIf { it.isNotEmpty() } ?: strings.agent
}

/** Whether the agent-side typing indicator shows and whether it is the bot's. */
internal data class GiovTypingState(
    val visible: Boolean = false,
    /** Null when the server did not say; only explicit metadata brands the indicator as the AI. */
    val isBot: Boolean? = null,
)

/** Applies a `typing` / `typing_end` frame. A `typing_end` for the other kind (bot vs team) keeps the current indicator. */
internal fun reduceAgentTyping(current: GiovTypingState, frame: JsonObject): GiovTypingState {
    if (frame["role"].asString() != "agent") return current
    val isBot = frame["isBot"]?.let { if (it.isTrue()) true else if (it.isFalse()) false else null }
    return when (frame["type"].asString()) {
        "typing" -> GiovTypingState(visible = true, isBot = isBot)
        "typing_end" -> if (isBot != null && current.isBot != null && isBot != current.isBot) current else GiovTypingState()
        else -> current
    }
}
