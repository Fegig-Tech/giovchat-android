package app.giovchat.core

/**
 * Typed failures. The SDK never shows a server's `error` code to people; the UI maps each type to a
 * translated sentence with [GiovChatStrings.describeError].
 *
 * [message] is for logs, in English. [status] is the HTTP status when the failure came from a response and
 * [code] the server's `error` field when it sent one.
 */
public sealed class GiovChatException(
    message: String,
    public val status: Int? = null,
    public val code: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String =
        "${this::class.simpleName}: $message${status?.let { " ($it${code?.let { c -> " $c" } ?: ""})" } ?: ""}"
}

/** The network could not be reached, or a request timed out. */
public class GiovNetworkException(
    message: String,
    public val timedOut: Boolean = false,
    cause: Throwable? = null,
) : GiovChatException(message, cause = cause)

/** The stored session is gone (expired, foreign, deleted). The SDK forgets it; the next message starts a new conversation. */
public class GiovSessionExpiredException(message: String, status: Int? = null, code: String? = null) :
    GiovChatException(message, status, code)

/** The team closed the conversation (or it was deleted). The next message starts a new one. */
public class GiovConversationClosedException(message: String, status: Int? = null, code: String? = null) :
    GiovChatException(message, status, code)

/** `429 rate_limited`: wait [retryAfterSeconds] before trying again. */
public class GiovRateLimitedException(message: String, public val retryAfterSeconds: Long) :
    GiovChatException(message, 429, "rate_limited")

/** `503 workspace_busy`: the workspace is at its plan's limit of open conversations. */
public class GiovWorkspaceBusyException(message: String, public val retryAfterSeconds: Long) :
    GiovChatException(message, 503, "workspace_busy")

/** `401 invalid_user_hash`: the workspace requires a signed identity and the one given is wrong. */
public class GiovIdentityRejectedException(message: String) : GiovChatException(message, 401, "invalid_user_hash")

/** An upload was refused (type, size, or the bot's attachment policy). */
public class GiovAttachmentRejectedException(message: String, status: Int? = null, code: String? = null) :
    GiovChatException(message, status, code)

/** A message could not be delivered (refused frame, or three failed saves). */
public class GiovSendFailedException(message: String, code: String? = null) : GiovChatException(message, code = code)

/** The server answered something the protocol does not allow. */
public class GiovInvalidResponseException(message: String, status: Int? = null) : GiovChatException(message, status)

/** The chat was closed (or the client disposed) while a call was in flight. */
public class GiovDisconnectedException(message: String = "Chat closed.") : GiovChatException(message)

/** The [GiovSessionStore] failed to read, write or delete the stored session. */
public class GiovStorageException(message: String, cause: Throwable? = null) : GiovChatException(message, cause = cause)

/** Any other refusal (`404 tenant_not_found`, `403 origin_not_allowed`, `5xx`, ...). */
public class GiovServerException(message: String, status: Int? = null, code: String? = null) :
    GiovChatException(message, status, code)

internal fun Throwable.toGiovException(): GiovChatException =
    this as? GiovChatException ?: GiovNetworkException(message ?: toString(), cause = this)
