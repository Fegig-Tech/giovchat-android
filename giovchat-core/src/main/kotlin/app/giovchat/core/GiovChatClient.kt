package app.giovchat.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

// The visitor protocol client (docs/protocol/README.md): configuration, starting and resuming a conversation,
// visitor tokens, the conversation socket with its heartbeat and reconnects, history, streaming replies,
// typing, uploads and push registration. No UI.
//
// The client is transport only. It reports what happens to a [ClientListener] and leaves the protocol's
// catch-up order (open, history, merge, resend) to its owner, normally GiovChatController: construct it with
// `flushOnOpen = false`, call [connect], load [fetchHistory], merge, then [flush].
//
// Threading: everything here runs on the single-threaded dispatcher of the [scope] it is given; socket and
// HTTP callbacks are moved onto it. Do not call it from other threads.

/** Connection timings from the visitor protocol. */
internal const val SOCKET_OPEN_TIMEOUT_MS: Long = 10_000
internal const val SOCKET_PING_INTERVAL_MS: Long = 25_000
internal const val SOCKET_PONG_TIMEOUT_MS: Long = 10_000
internal const val REQUEST_TIMEOUT_MS: Long = 15_000
internal const val UPLOAD_TIMEOUT_MS: Long = 120_000
private const val WATCHDOG_INTERVAL_MS: Long = 3_000
private const val RATE_LIMIT_PAUSE_MS: Long = 3_000

/** The API origin the SDK talks to unless told otherwise. */
public const val GIOV_DEFAULT_BASE_URL: String = "https://api.giovchat.app"

/**
 * Retry waits: `min(1500 x 2^attempt, 15000)` ms plus a little jitter (0 to 400 ms, [jitter] in 0..1); the
 * attempt counter resets after a successful connect.
 */
public fun giovReconnectDelayMs(attempt: Int, jitter: Double = 0.0): Long {
    val base = if (attempt >= 4) 15_000L else minOf(1500L * (1L shl attempt), 15_000L)
    return base + (jitter.coerceIn(0.0, 1.0) * 400).toLong()
}

/** A GiovBot reply arriving word by word: the whole text so far, then an end once it is saved or dropped. */
internal sealed interface StreamEvent {
    /** The visitor message this reply answers. */
    val streamId: String

    /** The WHOLE reply so far, not a delta. */
    class Update(override val streamId: String, val content: String) : StreamEvent

    class End(override val streamId: String) : StreamEvent
}

/** What the client reports. Called on the client's dispatcher, synchronously, in the order things happen. */
internal interface ClientListener {
    /** Chat messages from the socket. A saved GiovBot reply carries `clientMessageId == "stream:<streamId>"`. */
    fun onMessage(message: GiovMessage) {}

    /** The server stored a visitor message; [message] replaces the optimistic copy, in place. */
    fun onAck(clientMessageId: String, message: GiovMessage) {}

    fun onStream(event: StreamEvent) {}

    /**
     * The agent side started or stopped typing. [isBot] is null when the server did not say; on a stop it names whose
     * indicator ends (a person's stop must not hide the bot's, and the reverse).
     */
    fun onTyping(active: Boolean, isBot: Boolean?) {}

    /** True when a socket opens, false when it closes or the client disconnects. */
    fun onConnection(connected: Boolean) {}

    /** Failures that are not the answer to a call: refused messages, a dead session, a deleted conversation. */
    fun onError(error: GiovChatException) {}

    /** A newer attachment download token arrived (with a visitor token, an upload or history). */
    fun onAttachmentAccess(access: AttachmentAccess) {}
}

/** `ios` and `android` are accepted aliases (APNs for a hex token, FCM otherwise); use `APNS` or `FCM` to be explicit. */
public enum class GiovPushPlatform(internal val wire: String) { IOS("ios"), ANDROID("android"), APNS("apns"), FCM("fcm") }

public enum class GiovPushEnvironment(internal val wire: String) { SANDBOX("sandbox"), PRODUCTION("production") }

public data class GiovPushOptions(
    /** APNs only: [GiovPushEnvironment.SANDBOX] for development builds, production (the default) for the App Store. */
    val environment: GiovPushEnvironment? = null,
    /** The bundle id (iOS) or package name (Android). */
    val appId: String? = null,
    /** BCP 47 language for the generic notification text; defaults to the chat's locale. */
    val locale: String? = null,
)

public enum class GiovPushFailure {
    /** The server has no visitor push (404, 405 or 501); the client stops asking. */
    UNSUPPORTED,
    NO_CONVERSATION,
    FAILED,
}

/** The outcome of [GiovChatController.registerPushToken]; it never throws. */
public class GiovPushResult internal constructor(public val failure: GiovPushFailure?) {
    public val registered: Boolean get() = failure == null

    override fun toString(): String = if (registered) "GiovPushResult.registered" else "GiovPushResult($failure)"

    public companion object {
        internal val REGISTERED = GiovPushResult(null)
        internal fun notRegistered(failure: GiovPushFailure) = GiovPushResult(failure)
    }
}

internal class GiovUploadResult(val url: String, val filename: String, val preview: GiovAttachmentPreview?)

private class Reply(val status: Int, val body: JsonObject, val retryAfterSeconds: Long) {
    val ok: Boolean get() = status in 200..299
    val code: String? get() = body["error"].asString()
}

private const val DELETED_REASON = "Conversation deleted"

internal class GiovChatClient(
    /** The workspace key (public). */
    val tenantKey: String,
    baseUrl: String = GIOV_DEFAULT_BASE_URL,
    /** Messages waiting for the server; pass one that outlives this client. */
    val outbox: Outbox,
    /** Send the outbox as soon as a socket opens. The controller turns this off and calls [flush] after the history catch-up. */
    private val flushOnOpen: Boolean = true,
    /** BCP 47 language sent with push registrations. */
    private val locale: String? = null,
    private val http: GiovHttp,
    private val socketFactory: GiovSocketFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val clientMarker: String = "android/${GiovChatVersion.NAME}",
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    var listener: ClientListener? = null

    private var socket: GiovSocket? = null
    private var dropSocket: (() -> Unit)? = null
    private var cancelHandshake: (() -> Unit)? = null
    private var reconnectJob: Job? = null
    private var heartbeat: Job? = null
    private var watchdog: Job? = null
    private val resendJobs = mutableSetOf<Job>()
    private var generation = 0
    private var desired = false
    private var disposed = false
    private var attempt = 0
    private var currentSession: GiovSession? = null
    @Volatile private var currentToken: VisitorToken? = null // read by attachmentUrl from any thread
    private var tokenRequest: Deferred<VisitorToken>? = null
    private var pushUnsupported = false

    val isConnected: Boolean get() = socket?.isOpen ?: false

    /** The conversation this client is connected (or connecting) to. */
    val session: GiovSession? get() = currentSession

    /** The current visitor token, a fallback credential for attachment downloads. */
    val visitorToken: String? get() = currentToken?.token

    // ---------------------------------------------------------------------------------------------
    // HTTP

    private suspend fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        json: JsonObject? = null,
    ): Reply {
        val request = GiovHttpRequest(
            method = method,
            url = baseUrl + path,
            headers = if (json != null) headers + ("Content-Type" to "application/json") else headers,
            jsonBody = json?.toString(),
        )
        val response = try {
            withTimeout(REQUEST_TIMEOUT_MS) { http.send(request) }
        } catch (error: TimeoutCancellationException) {
            throw GiovNetworkException("Chat request timed out.", timedOut = true, cause = error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GiovChatException) {
            throw error
        } catch (error: Exception) {
            throw GiovNetworkException(error.message ?: error.toString(), cause = error)
        }
        return reply(response)
    }

    private fun reply(response: GiovHttpResponse): Reply =
        Reply(response.status, parseJsonObject(response.body), response.headers["retry-after"]?.trim()?.toLongOrNull() ?: 30L)

    /**
     * Maps an unsuccessful reply to the SDK's typed failures (README, "Errors, limits and headers"). On routes
     * that take the session, 401/403 mean the session is gone.
     */
    private fun failure(reply: Reply, sessionRoute: Boolean = false): GiovChatException {
        val code = reply.code
        val message = reply.body["message"].asString() ?: code ?: "HTTP ${reply.status}"
        return when {
            code == "invalid_user_hash" -> GiovIdentityRejectedException(message)
            reply.status == 429 -> GiovRateLimitedException(message, reply.retryAfterSeconds)
            reply.status == 503 && code == "workspace_busy" -> GiovWorkspaceBusyException(message, reply.retryAfterSeconds)
            reply.status == 409 || code == "conversation_closed" -> GiovConversationClosedException(message, reply.status, code)
            sessionRoute && (reply.status == 401 || reply.status == 403) -> GiovSessionExpiredException(message, reply.status, code)
            else -> GiovServerException(message, reply.status, code)
        }
    }

    /** `GET /widget/config`, with the client marker header. */
    suspend fun fetchWidgetConfig(): GiovWidgetConfig {
        val reply = request("GET", "/widget/config?tenantId=${encodeComponent(tenantKey)}", mapOf("X-Giov-Client" to clientMarker))
        if (!reply.ok) throw failure(reply)
        if (reply.body["title"].asString() == null) throw GiovInvalidResponseException("Invalid widget configuration.")
        return GiovWidgetConfig.fromJson(reply.body)
    }

    /**
     * `POST /conversations` without a session: starts a new conversation. Call it when the visitor sends the
     * first message or submits the pre-chat form, never just for opening the screen.
     */
    suspend fun startConversation(contact: GiovContact? = null): GiovSession = conversation(contact, null)

    /**
     * `POST /conversations` with the stored session: resumes it. Throws [GiovSessionExpiredException] /
     * [GiovConversationClosedException] (or a 404 [GiovServerException]) when the stored pair must be forgotten.
     */
    suspend fun resumeConversation(session: GiovSession, contact: GiovContact? = null): GiovSession = conversation(contact, session)

    private suspend fun conversation(contact: GiovContact?, existing: GiovSession?): GiovSession {
        val reply = request(
            "POST",
            "/conversations",
            headers = if (existing != null) mapOf("Authorization" to "Bearer ${existing.sessionToken}") else emptyMap(),
            json = buildJsonObject {
                put("tenantId", tenantKey)
                contact?.toStartFields()?.forEach { (key, value) -> put(key, value) }
                if (existing != null) put("conversationId", existing.conversationId)
            },
        )
        if (!reply.ok) throw failure(reply, sessionRoute = existing != null)
        val id = reply.body["conversationId"].asString()
        val token = reply.body["sessionToken"].asString()
        if (id.isNullOrEmpty() || token == null || !SESSION_TOKEN.matches(token)) throw GiovInvalidResponseException("Invalid chat session response.")
        return GiovSession(id, token)
    }

    private suspend fun fetchToken(session: GiovSession): VisitorToken {
        val reply = request(
            "POST",
            "/auth/widget-token",
            headers = mapOf("Authorization" to "Bearer ${session.sessionToken}"),
            json = buildJsonObject { put("tenantId", tenantKey); put("conversationId", session.conversationId) },
        )
        if (!reply.ok) throw failure(reply, sessionRoute = true)
        return VisitorToken.fromJson(reply.body, clock()) ?: throw GiovInvalidResponseException("Invalid chat token response.")
    }

    /** The visitor token; renewed when less than a minute is left. One request at a time. */
    private suspend fun token(force: Boolean = false): VisitorToken {
        val session = currentSession ?: throw GiovDisconnectedException()
        val cached = currentToken
        if (!force && cached != null && cached.usableAt(clock())) return cached
        val pending = tokenRequest ?: run {
            lateinit var created: Deferred<VisitorToken>
            created = scope.async(start = CoroutineStart.LAZY) {
                try {
                    fetchToken(session).also { value ->
                        if (currentSession === session) {
                            currentToken = value
                            value.attachmentAccess?.let { listener?.onAttachmentAccess(it) }
                        }
                    }
                } finally {
                    if (tokenRequest === created) tokenRequest = null
                }
            }
            tokenRequest = created
            created
        }
        return pending.await()
    }

    /** A call with the visitor token: a 401 renews the token once and retries; a second 401 means the session is gone. */
    private suspend fun authed(method: String, path: String, json: JsonObject? = null, headers: Map<String, String> = emptyMap()): Reply {
        var token = token()
        var reply = request(method, path, headers + ("Authorization" to "Bearer ${token.token}"), json)
        if (reply.status == 401) {
            token = token(force = true)
            reply = request(method, path, headers + ("Authorization" to "Bearer ${token.token}"), json)
        }
        return reply
    }

    private fun conversationPath(): String = "/conversations/${encodeComponent(currentSession!!.conversationId)}"

    /**
     * `GET /conversations/{id}/history` for the connected session. A 404 is a [GiovServerException] with
     * `status == 404`: the conversation is gone, forget it.
     */
    suspend fun fetchHistory(): GiovHistory {
        if (currentSession == null) throw GiovDisconnectedException()
        val reply = authed("GET", "${conversationPath()}/history")
        if (!reply.ok) throw failure(reply, sessionRoute = true)
        if (reply.body["messages"] !is kotlinx.serialization.json.JsonArray) throw GiovInvalidResponseException("Invalid conversation history.")
        val history = GiovHistory.fromJson(reply.body)
        history.attachmentAccess?.let { listener?.onAttachmentAccess(it) }
        return history
    }

    // ---------------------------------------------------------------------------------------------
    // Socket

    private fun socketUrl(session: GiovSession, token: String): String =
        "ws" + baseUrl.removePrefix("http") + "/conversations/${encodeComponent(session.conversationId)}/ws?role=user&token=${encodeComponent(token)}"

    /**
     * Uses [session] and opens the socket, returning once it is open. Fails with a typed exception (no
     * automatic retry for this first attempt); after it has opened once, a dropped socket is retried with
     * backoff by the client itself.
     */
    suspend fun connect(session: GiovSession) {
        // Messages already waiting in the outbox are kept: they go out on this socket.
        teardown()
        desired = true
        currentSession = session
        attempt = 0
        open(generation)
    }

    private suspend fun open(generation: Int) {
        if (!desired || currentSession == null || generation != this.generation) return
        val token = token(force = true).token
        val session = currentSession
        if (!desired || generation != this.generation || session == null) throw GiovDisconnectedException()
        val link = SocketLink(generation)
        link.start(socketUrl(session, token))
        cancelHandshake = { link.finish(GiovDisconnectedException()) }
        link.done.await()
    }

    /** One socket's callbacks and timers. */
    private inner class SocketLink(val generation: Int) : GiovSocketListener {
        val done = CompletableDeferred<Unit>()
        private lateinit var socket: GiovSocket
        private var opened = false
        private var openTimer: Job? = null
        private var pongDeadline: Job? = null

        fun start(url: String) {
            openTimer = scope.launch {
                delay(SOCKET_OPEN_TIMEOUT_MS)
                finish(GiovNetworkException("Chat connection timed out.", timedOut = true))
                drop()
            }
            socket = socketFactory.create(url, this)
            this@GiovChatClient.socket = socket
        }

        fun finish(error: GiovChatException? = null) {
            if (done.isCompleted) return
            openTimer?.cancel()
            if (error == null) done.complete(Unit) else done.completeExceptionally(error)
        }

        private fun current(): Boolean = generation == this@GiovChatClient.generation && this@GiovChatClient.socket === socket

        fun drop() {
            socket.close()
            closed(null, null)
        }

        // Socket callbacks arrive on any thread; the client handles them on its own dispatcher, in order.
        override fun onOpen() {
            scope.launch { handleOpen() }
        }

        override fun onMessage(text: String) {
            scope.launch { handleMessage(text) }
        }

        override fun onClosed(code: Int?, reason: String?) {
            scope.launch { closed(code, reason) }
        }

        override fun onFailure(error: Throwable, httpStatus: Int?) {
            scope.launch {
                // Only the HTTP status of the refused upgrade is looked at, never the error text.
                if (httpStatus == 410) {
                    finish(GiovConversationClosedException("This conversation has ended.", 410))
                    closed(1000, DELETED_REASON)
                } else {
                    finish(GiovNetworkException("Unable to connect to chat.", cause = error))
                    closed(null, null)
                }
            }
        }

        private fun handleOpen() {
            if (generation != this@GiovChatClient.generation || !desired || done.isCompleted || !current()) {
                socket.close()
                return
            }
            opened = true
            attempt = 0
            dropSocket = ::drop
            listener?.onConnection(true)
            // A ping every 25 s; no pong within 10 s means the socket is dead.
            heartbeat = scope.launch {
                while (isActive) {
                    delay(SOCKET_PING_INTERVAL_MS)
                    if (!socket.send(PING_FRAME)) {
                        drop()
                        return@launch
                    }
                    pongDeadline?.cancel()
                    pongDeadline = scope.launch {
                        delay(SOCKET_PONG_TIMEOUT_MS)
                        drop()
                    }
                }
            }
            // A message on a socket that never answered means the socket is dead: reconnect and resend.
            watchdog = scope.launch {
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    if (outbox.stale(clock())) drop()
                }
            }
            finish()
            if (flushOnOpen) flush()
        }

        private fun handleMessage(text: String) {
            if (!current()) return
            val data = parseJson(text) as? JsonObject ?: return
            receive(data) {
                pongDeadline?.cancel()
                pongDeadline = null
            }
        }

        fun closed(code: Int?, reason: String?) {
            pongDeadline?.cancel()
            if (!current()) return
            this@GiovChatClient.socket = null
            dropSocket = null
            outbox.socketClosed()
            heartbeat?.cancel()
            watchdog?.cancel()
            heartbeat = null
            watchdog = null
            listener?.onConnection(false)
            finish(GiovNetworkException("Chat connection closed."))
            if (code == 1000 && reason == DELETED_REASON) {
                // The conversation is gone: no reconnect; the owner forgets the stored session.
                desired = false
                listener?.onError(GiovConversationClosedException("This conversation has ended."))
                return
            }
            if (opened) reconnect(generation)
        }
    }

    private fun receive(data: JsonObject, pong: () -> Unit) {
        val type = data["type"].asString()
        if (type == "pong") {
            pong()
            return
        }
        if (type == "typing" || type == "typing_end") {
            if (data["role"].asString() == "agent") {
                val isBot = data["isBot"]?.let { if (it.isTrue()) true else if (it.isFalse()) false else null }
                listener?.onTyping(type == "typing", isBot)
            }
            return
        }
        val streamId = data["streamId"].asString()
        if (type == "stream" && streamId != null) {
            listener?.onStream(StreamEvent.Update(streamId, data["content"].asString() ?: ""))
            return
        }
        if (type == "stream_end" && streamId != null) {
            listener?.onStream(StreamEvent.End(streamId))
            return
        }
        if (type == "error") {
            serverError(data)
            return
        }
        val clientMessageId = data["clientMessageId"].asString()
        if (type == "message_ack" && clientMessageId != null) {
            val stored = GiovMessage.fromJson(JsonObject(data["message"].asObject() + ("role" to JsonPrimitive("visitor"))))
            outbox.acknowledge(clientMessageId)
            if (stored != null) listener?.onAck(clientMessageId, stored)
            return
        }
        // Unknown frame types are ignored (protocol versioning).
        if (type != null && type != "message") return
        var message = GiovMessage.fromJson(data) ?: return
        // The saved reply takes the place of its streamed draft, under the draft's key so it is not redrawn.
        if (streamId != null) message = message.copy(clientMessageId = "stream:$streamId")
        listener?.onMessage(message)
        if (streamId != null) listener?.onStream(StreamEvent.End(streamId))
    }

    /** Error frames: a save failure names its message; refusals and rate limits apply to what is on the socket. */
    private fun serverError(data: JsonObject) {
        val socket = this.socket
        val id = data["clientMessageId"].asString()
        val error = data["error"].asString()
        if (error == "message_failed") {
            if (id == null) return
            val outcome = outbox.rejected(id) ?: return
            if (outcome.failed) {
                listener?.onError(GiovSendFailedException("Message could not be sent. Please try again.", "message_failed"))
            } else {
                later(outcome.retryAfterMs) { if (this.socket === socket) flush() }
            }
            return
        }
        if (error == "rate_limited") {
            // Too fast: everything on the socket goes again a little later, with the same ids.
            outbox.socketClosed()
            later(RATE_LIMIT_PAUSE_MS) { if (this.socket === socket) flush() }
            return
        }
        // `invalid_message`, `invalid_attachment`, and any code this version does not know (the asyncapi document says
        // to treat an unknown one as `invalid_message`). Ignoring it would leave the message "sending" until the ack
        // watchdog dropped the socket, and the resend would be refused again, for ever. Frames are checked in order,
        // so the refusal is for the oldest message still waiting.
        if (error == null) return
        outbox.refuseOldestSending()
        listener?.onError(GiovSendFailedException("Message could not be sent. Please try again.", error))
    }

    private fun later(delayMs: Long, run: () -> Unit) {
        lateinit var job: Job
        job = scope.launch {
            delay(delayMs)
            resendJobs.remove(job)
            run()
        }
        resendJobs += job
    }

    private fun reconnect(generation: Int) {
        if (!desired || generation != this.generation || reconnectJob != null) return
        val wait = giovReconnectDelayMs(attempt++, random.nextDouble())
        reconnectJob = scope.launch {
            delay(wait)
            reconnectJob = null
            retryOpen(generation)
        }
    }

    private fun retryOpen(generation: Int) {
        scope.launch {
            try {
                open(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!desired || generation != this@GiovChatClient.generation) return@launch
                val failure = error.toGiovException()
                if (failure is GiovSessionExpiredException) disconnect() else reconnect(generation)
                listener?.onError(failure)
            }
        }
    }

    /**
     * Renews the visitor token now, which also brings a fresh attachment access token (reported through
     * [ClientListener.onAttachmentAccess]). For long sessions whose download token is about to expire. Never throws.
     */
    suspend fun renewToken() {
        if (currentSession == null) return
        try {
            token(force = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The next connect or history load renews it.
        }
    }

    /** Reconnect now instead of waiting for the backoff, e.g. when the network comes back. */
    fun reconnectNow() {
        val job = reconnectJob
        if (!desired || isConnected || job == null) return
        job.cancel()
        reconnectJob = null
        attempt = 0
        retryOpen(generation)
    }

    /** Puts everything waiting in the outbox on the socket, oldest first. */
    fun flush() {
        val socket = this.socket
        if (socket == null || !socket.isOpen) return
        for (item in outbox.takeQueued(clock())) {
            if (!socket.send(item.frame)) {
                dropSocket?.invoke()
                return
            }
        }
    }

    /**
     * Queues a visitor message and sends it as soon as a socket is open; returns its client message id at once.
     * Delivery is tracked in [outbox] and confirmed through [ClientListener.onAck]. Throws
     * [GiovSendFailedException] for an empty or over-long message.
     */
    fun send(text: String, attachments: List<String> = emptyList()): String {
        val entry = outbox.enqueue(text, attachments, clock())
        flush()
        return entry.clientMessageId
    }

    /** Sends a message that failed again, in its original place in the queue. */
    fun retryMessage(clientMessageId: String): Boolean {
        val retried = outbox.retry(clientMessageId)
        if (retried) flush()
        return retried
    }

    /** The visitor started typing (send once per burst). */
    fun sendTyping() = sendFrame("""{"type":"typing"}""")

    fun sendTypingEnd() = sendFrame("""{"type":"typing_end"}""")

    private fun sendFrame(frame: String) {
        val socket = this.socket
        if (socket == null || !socket.isOpen) return
        socket.send(frame) // A failure is recovered by the next reconnect.
    }

    // ---------------------------------------------------------------------------------------------
    // Uploads and push

    /**
     * Uploads one file to this conversation and returns its URL for [send]. [onProgress] gets 0 to 1 as the bytes
     * leave the device. Retries once with a new token on 401. Throws [GiovAttachmentRejectedException] when the
     * server refuses the file, [GiovConversationClosedException] (404) when the conversation is gone,
     * [GiovSessionExpiredException] on a second 401 or a 403, [GiovRateLimitedException] on 429, [GiovServerException]
     * on 5xx and [GiovNetworkException] when nothing came back (including the [UPLOAD_TIMEOUT_MS] bound on the whole
     * exchange).
     */
    suspend fun uploadAttachment(file: GiovPickedFile, onProgress: ((Double) -> Unit)? = null): GiovUploadResult {
        if (currentSession == null) throw GiovDisconnectedException()
        val url = "$baseUrl${conversationPath()}/upload-visitor"
        var token = token().token
        var reply = upload(url, token, file, onProgress)
        if (reply.status == 401) {
            token = token(force = true).token
            reply = upload(url, token, file, onProgress)
        }
        val uploaded = reply.body["url"].asString()
        if (!reply.ok || uploaded == null) {
            val message = reply.body["message"].asString() ?: reply.code ?: "File upload failed."
            if (reply.status == 401 || reply.status == 403) throw GiovSessionExpiredException(message, reply.status, reply.code)
            if (reply.status == 0) throw GiovNetworkException(message)
            // Only a refusal of the file itself is an attachment rejection; the route also answers 404 for a
            // conversation that is gone, 429 when rate limited and 5xx, which mean what they mean everywhere else.
            if (reply.status == 404) throw GiovConversationClosedException(message, 404, reply.code)
            if (reply.status == 429 || reply.status >= 500) throw failure(reply)
            throw GiovAttachmentRejectedException(message, reply.status, reply.code)
        }
        AttachmentAccess.fromJson(reply.body["attachmentAccess"])?.let { listener?.onAttachmentAccess(it) }
        return GiovUploadResult(
            url = uploaded,
            filename = reply.body["filename"].asString() ?: file.name,
            preview = GiovAttachmentPreview.sanitize(reply.body["preview"]),
        )
    }

    private suspend fun upload(url: String, token: String, file: GiovPickedFile, onProgress: ((Double) -> Unit)?): Reply {
        return try {
            val preview = uploadPreview(file)
            val response = withTimeout(UPLOAD_TIMEOUT_MS) {
                http.send(
                    GiovHttpRequest(
                        method = "POST",
                        url = url,
                        headers = mapOf("Authorization" to "Bearer $token"),
                        upload = GiovUploadBody(file, preview?.thumb, preview?.width, preview?.height, onProgress),
                    ),
                )
            }
            reply(response).also { if (it.ok) onProgress?.invoke(1.0) }
        } catch (error: TimeoutCancellationException) {
            Reply(0, EMPTY_OBJECT, 0)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Reply(0, EMPTY_OBJECT, 0)
        }
    }

    /**
     * Registers this device for visitor push notifications about this conversation (README, "Push notifications").
     * Never throws: a server without visitor push answers 404/405/501, after which the client stops asking and
     * reports [GiovPushFailure.UNSUPPORTED].
     */
    suspend fun registerPushToken(token: String, platform: GiovPushPlatform, options: GiovPushOptions = GiovPushOptions()): GiovPushResult {
        if (pushUnsupported) return GiovPushResult.notRegistered(GiovPushFailure.UNSUPPORTED)
        if (currentSession == null) return GiovPushResult.notRegistered(GiovPushFailure.NO_CONVERSATION)
        if (token.isBlank() || token.length > 4096) return GiovPushResult.notRegistered(GiovPushFailure.FAILED)
        return try {
            // The client marker is stored with the device, so the dashboard can tell which SDK versions receive pushes.
            val reply = authed("POST", "${conversationPath()}/push-token", buildJsonObject {
                put("token", token)
                put("platform", platform.wire)
                options.environment?.let { put("environment", it.wire) }
                options.appId?.takeIf { it.isNotEmpty() }?.let { put("appId", it) }
                (options.locale ?: locale)?.let { put("locale", it) }
            }, mapOf("X-Giov-Client" to clientMarker))
            when {
                reply.status == 404 || reply.status == 405 || reply.status == 501 -> {
                    pushUnsupported = true
                    GiovPushResult.notRegistered(GiovPushFailure.UNSUPPORTED)
                }
                reply.ok -> GiovPushResult.REGISTERED
                else -> GiovPushResult.notRegistered(GiovPushFailure.FAILED)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            GiovPushResult.notRegistered(GiovPushFailure.FAILED)
        }
    }

    /**
     * Stops push notifications about this conversation on this device (sign-out, notifications off). Never throws; true
     * when the server confirmed. Without an open connection it uses [session] (the stored one) and a fresh visitor
     * token, since sign-out usually happens while the chat is closed.
     */
    suspend fun unregisterPushToken(token: String, session: GiovSession? = null): Boolean {
        val target = currentSession ?: session
        if (target == null || pushUnsupported || token.isEmpty()) return false
        val body = buildJsonObject { put("token", token) }
        return try {
            if (currentSession != null) {
                authed("DELETE", "${conversationPath()}/push-token", body).ok
            } else {
                val visitor = fetchToken(target)
                val path = "/conversations/${encodeComponent(target.conversationId)}/push-token"
                request("DELETE", path, mapOf("Authorization" to "Bearer ${visitor.token}"), body).ok
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Teardown

    /** Closes the connection. Unsent messages stay in the outbox. */
    fun disconnect() {
        teardown()
    }

    private fun teardown() {
        desired = false
        generation++
        reconnectJob?.cancel()
        heartbeat?.cancel()
        watchdog?.cancel()
        for (job in resendJobs) job.cancel()
        resendJobs.clear()
        reconnectJob = null
        heartbeat = null
        watchdog = null
        dropSocket = null
        cancelHandshake?.invoke()
        cancelHandshake = null
        val old = socket
        socket = null
        old?.close()
        // Unsent messages stay in the outbox; a later connection sends them.
        outbox.socketClosed()
        currentSession = null
        currentToken = null
        tokenRequest = null
        listener?.onConnection(false)
    }

    /** Disconnects and stops reporting. The client cannot be used afterwards. */
    fun dispose() {
        if (disposed) return
        disposed = true
        disconnect()
        listener = null
    }

    private companion object {
        const val PING_FRAME = """{"type":"ping"}"""
        val SESSION_TOKEN = Regex("^[a-f0-9]{64}$")
    }
}
