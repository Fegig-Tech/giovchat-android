package app.giovchat.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** How long after the last keystroke the agent stops seeing "typing" (the widget's value). */
private const val TYPING_IDLE_MS: Long = 2500

/**
 * The state holder the chat UI listens to. It runs the whole visitor session on top of the protocol client: it
 * loads the configuration, resumes a stored conversation, starts one when the visitor writes, keeps the socket up,
 * catches up on history after every reconnect, resends the outbox, shows streaming replies, counts unread
 * replies and registers push tokens.
 *
 * ```kotlin
 * val chat = GiovChatController(tenantKey = "ws_123", contact = GiovContact(userId = id, userHash = hash))
 * lifecycleScope.launch { chat.start() }
 * val ui by chat.state.collectAsState()        // Compose; or chat.state.collect { render(it) }
 * chat.send("Hello")                           // suspend
 * ```
 *
 * Forward the app's lifecycle with [onForeground] / [onBackground] (a `ProcessLifecycleOwner` observer), tell it
 * whether the chat screen is showing with [setVisible], and call [close] when the controller is no longer needed.
 *
 * **Threading.** All work runs on one internal single-threaded [dispatcher]. Every method may be called from any
 * thread (the main thread is fine): `suspend` methods suspend until their work is done, the others return at
 * once. [state] is a [StateFlow], safe to collect anywhere. The `on...` callbacks run on the internal dispatcher.
 *
 * @param tenantKey the workspace key (public; it is in every embed snippet).
 * @param baseUrl the API origin; leave the default for Giov Cloud.
 * @param contact what the host knows about the visitor; see [GiovContact] for the signed identity.
 * @param sessionKey separates stored conversations of the same visitor, e.g. per account.
 * @param sessionStore where the session secret lives between launches; use an encrypted store on Android.
 * @param keepConnected stay connected while the chat is not visible (app in the foreground) to count unread
 *   replies. Never starts a conversation.
 * @param visible whether the chat screen is showing now.
 * @param locale BCP 47 language for the SDK's text and push notifications; defaults to the device language.
 * @param overrideStrings replaces individual interface strings, e.g. `{ it.withOverrides(sendMessage = "Mandar") }`.
 * @param backgroundGraceMs how long the app may be in the background before the socket is closed (so pushes
 *   arrive).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class GiovChatController(
    public val tenantKey: String,
    baseUrl: String = GIOV_DEFAULT_BASE_URL,
    contact: GiovContact? = null,
    sessionKey: String? = null,
    sessionStore: GiovSessionStore = InMemoryGiovSessionStore(),
    public val keepConnected: Boolean = false,
    visible: Boolean = true,
    locale: String? = null,
    overrideStrings: ((GiovChatStrings) -> GiovChatStrings)? = null,
    public val backgroundGraceMs: Long = 5_000,
    private val onError: ((GiovChatException) -> Unit)? = null,
    private val onConversationStart: ((conversationId: String) -> Unit)? = null,
    private val onConnectionStatusChange: ((GiovConnectionStatus) -> Unit)? = null,
    private val onUnreadCountChange: ((Int) -> Unit)? = null,
    http: GiovHttp = OkHttpGiovHttp(),
    socketFactory: GiovSocketFactory = OkHttpGiovSocketFactory(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val clock: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
) {
    /** The API origin in use, without a trailing slash. */
    public val baseUrl: String = normalizeBaseUrl(baseUrl)

    /** The interface text for the resolved language. */
    public val strings: GiovChatStrings = resolveGiovStrings(locale, overrideStrings)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val baseContact: GiovContact? = contact
    private val store: GiovSessionStore = sessionStore
    private val scopeKey: String = giovSessionScope(this.baseUrl, tenantKey, contact ?: GiovContact(), sessionKey)
    private val outbox = Outbox(clock)
    private val client: GiovChatClient
    private val outboxStop: () -> Unit

    // Everything below is touched only on [dispatcher].
    private var contact: GiovContact = contact ?: GiovContact()
    private var session: GiovSession? = null
    private var loadedStore = false
    private var startNow = false
    private var started = false
    private var disposed = false
    private var visible: Boolean = visible
    private var foreground = true
    private var wanted = false
    private var generation = 0
    private var forgottenGeneration = -1
    private var graceJob: Job? = null
    private var typingJob: Job? = null
    private var typingSent = false
    private var catchUp: (suspend () -> Unit)? = null
    private var pushRequest: PushRequest? = null
    /** The last token registered, registered again for each new conversation of this visitor. */
    private var lastPush: PushRegistration? = null
    private var pushedConversation: String? = null
    /** The pass started by [start], and the one starting a conversation; joined instead of restarted. */
    private var startPass: Job? = null
    private var startingPass: Job? = null
    private var retryJob: Job? = null
    private var connectAttempt = 0
    @Volatile private var accessRenewedAt = 0L
    private val jitter: Random = random
    private val localFiles = HashMap<String, GiovAttachment>()

    private var phase = GiovChatPhase.LOADING
    private var config: GiovWidgetConfig? = null
    private var messages: List<GiovMessage> = emptyList()
    private val drafts = LinkedHashMap<String, GiovDraft>()
    private var agentTyping = false
    private var agentTypingIsBot: Boolean? = null
    private var agentName: String? = null
    private var agentAvatar: String? = null
    @Volatile private var access: AttachmentAccess? = null
    private var unread = 0
    private var error: GiovChatException? = null
    private var sending = false
    private var uploads: List<GiovUploadProgress> = emptyList()
    private var requiredFields = GiovRequestUserInfo()
    private var hasConversation = false
    private var status = GiovConnectionStatus.DISCONNECTED
    private var conversationIdValue: String? = null

    private class PushRegistration(val token: String, val platform: GiovPushPlatform, val options: GiovPushOptions)

    private class PushRequest(
        val token: String,
        val platform: GiovPushPlatform,
        val options: GiovPushOptions,
        val done: CompletableDeferred<GiovPushResult>,
    )

    private val mutableState = MutableStateFlow(GiovChatUiState(visible = visible))

    /** Everything the chat screen draws. A new value is published after every change. */
    public val state: StateFlow<GiovChatUiState> = mutableState.asStateFlow()

    init {
        client = GiovChatClient(
            tenantKey = tenantKey,
            baseUrl = this.baseUrl,
            outbox = outbox,
            flushOnOpen = false,
            locale = resolveGiovLocale(locale ?: giovDeviceLocale()),
            http = http,
            socketFactory = socketFactory,
            scope = scope,
            clock = clock,
            random = random,
        )
        outboxStop = outbox.subscribe(::changed)
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle

    private val wantConnection: Boolean get() = foreground && (visible || keepConnected)

    /**
     * Loads the configuration, resumes a stored conversation (connecting at once) and otherwise waits for the
     * visitor. Returns when that first pass has settled. Calling it again on a started controller does nothing (it
     * waits for the first pass), so a screen that starts it each time it appears keeps the connection; use [retry]
     * to force a fresh one.
     */
    public suspend fun start() {
        withContext(dispatcher) {
            if (disposed) return@withContext
            if (started) {
                startPass?.join()
                return@withContext
            }
            started = true
            startPass = restart()
            startPass?.join()
        }
    }

    /**
     * Shows or hides the chat. Showing clears unread replies; a hidden chat that is not [keepConnected]
     * disconnects.
     */
    public fun setVisible(value: Boolean) {
        scope.launch { applyVisible(value) }
    }

    private fun applyVisible(value: Boolean) {
        if (value == visible || disposed) return
        visible = value
        if (value) {
            unread = 0
            publishUnread()
            if (keepConnected && phase == GiovChatPhase.IDLE && started) {
                restart()
                return
            }
        }
        sync()
        changed()
    }

    /**
     * The app went to the background. The socket is closed after [backgroundGraceMs] so the server can push
     * replies (a socket that keeps pinging counts as watching).
     */
    public fun onBackground() {
        scope.launch {
            if (disposed || !foreground || graceJob != null) return@launch
            graceJob = scope.launch {
                delay(backgroundGraceMs)
                graceJob = null
                foreground = false
                sync()
            }
        }
    }

    /**
     * The app returned to the foreground: reconnects if the socket was closed, otherwise reloads the history so
     * nothing sent meanwhile is missed.
     */
    public fun onForeground() {
        scope.launch {
            if (disposed) return@launch
            graceJob?.cancel()
            graceJob = null
            if (!foreground) {
                foreground = true
                sync()
                return@launch
            }
            if (phase == GiovChatPhase.ACTIVE) {
                val refresh = catchUp
                if (refresh != null) scope.launch { refresh() }
            } else if (retryJob != null) {
                // A first connection waiting for its backoff: try now.
                retryJob?.cancel()
                retryJob = null
                restart()
            } else {
                client.reconnectNow()
            }
        }
    }

    /** Starts over after an error ([GiovChatPhase.ERROR]) or to force a fresh connection. */
    public suspend fun retry() {
        withContext(dispatcher) { restart()?.join() }
    }

    /** Forgets the conversation, the stored session and the outbox (sign-out, "new chat"). */
    public suspend fun reset() {
        withContext(dispatcher) {
            generation++
            stopTyping()
            cancelSubs()
            client.disconnect()
            session = null
            conversationIdValue = null
            contact = baseContact ?: GiovContact()
            loadedStore = true
            outbox.clear()
            localFiles.clear()
            // The next visitor's conversations are not registered for push with this device's last token.
            lastPush = null
            pushedConversation = null
            pushRequest?.done?.complete(GiovPushResult.notRegistered(GiovPushFailure.NO_CONVERSATION))
            pushRequest = null
            startNow = false
            hasConversation = false
            messages = emptyList()
            drafts.clear()
            agentTyping = false
            agentTypingIsBot = null
            agentName = null
            agentAvatar = null
            access = null
            unread = 0
            publishUnread()
            error = null
            try {
                store.delete(scopeKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(GiovStorageException("Saved chat session could not be cleared.", e))
            }
            if (started) restart()?.join()
        }
    }

    /**
     * The pre-chat form was submitted: stores the contact details and starts the conversation. A second submit while
     * the first is still starting (a double tap) waits for it instead of starting another conversation.
     */
    public suspend fun submitContact(info: GiovContact) {
        withContext(dispatcher) {
            contact = contact.merge(info)
            val pending = startingPass
            if (startNow && pending != null && pending.isActive) {
                pending.join()
                return@withContext
            }
            startNow = true
            startingPass = restart()
            startingPass?.join()
        }
    }

    /** Stops the controller and releases its connection. Unsent messages are lost. The controller cannot be used afterwards. */
    public fun close() {
        scope.launch {
            if (disposed) return@launch
            disposed = true
            generation++
            graceJob?.cancel()
            typingJob?.cancel()
            cancelSubs()
            pushRequest?.done?.complete(GiovPushResult.notRegistered(GiovPushFailure.NO_CONVERSATION))
            pushRequest = null
            outboxStop()
            GiovUnread.forget(this@GiovChatController)
            client.dispose()
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The session

    private fun alive(generation: Int): Boolean = !disposed && generation == this.generation

    private fun sync() {
        if (!started || disposed) return
        if (wantConnection != wanted) restart()
    }

    private fun restart(): Job? {
        if (disposed) return null
        val generation = ++this.generation
        stopTyping()
        cancelSubs()
        client.disconnect()
        wanted = wantConnection
        if (!wanted) {
            agentTyping = false
            agentTypingIsBot = null
            drafts.clear()
            setStatus(GiovConnectionStatus.DISCONNECTED)
            changed()
            return null
        }
        return scope.launch { run(generation) }
    }

    private suspend fun run(generation: Int) {
        try {
            begin(generation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!alive(generation)) return
            cancelSubs()
            client.disconnect()
            val failure = e.toGiovException()
            if (failure is GiovConversationClosedException) {
                forget(generation)
                return
            }
            if (failure is GiovSessionExpiredException) clearSession()
            if (!alive(generation)) return
            if (failure is GiovNetworkException && config != null && (session != null || startNow)) {
                // The network failed before the first connection: retry with the protocol backoff, like the web
                // widget, instead of stopping at "Try again". Queued messages wait in the outbox meanwhile.
                report(failure)
                phase = GiovChatPhase.CONNECTING
                setStatus(GiovConnectionStatus.DISCONNECTED)
                changed()
                val wait = giovReconnectDelayMs(connectAttempt++, jitter.nextDouble())
                retryJob = scope.launch {
                    delay(wait)
                    retryJob = null
                    if (alive(generation)) restart()
                }
                return
            }
            error = failure
            phase = GiovChatPhase.ERROR
            setStatus(GiovConnectionStatus.DISCONNECTED)
            report(failure)
            changed()
        }
    }

    private suspend fun clearSession() {
        session = null
        conversationIdValue = null
        hasConversation = false
        messages = emptyList()
        try {
            store.delete(scopeKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(GiovStorageException("Chat session could not be saved or restored.", e))
        }
    }

    /** The conversation was closed or deleted: forget it; the visitor's next message starts a new one. */
    private suspend fun forget(generation: Int) {
        if (!alive(generation) || forgottenGeneration == generation) return
        forgottenGeneration = generation
        cancelSubs()
        client.disconnect()
        clearSession()
        if (!alive(generation)) return
        agentName = null
        agentAvatar = null
        drafts.clear()
        agentTyping = false
        agentTypingIsBot = null
        phase = GiovChatPhase.READY
        setStatus(GiovConnectionStatus.DISCONNECTED)
        error = GiovConversationClosedException("This conversation has ended.")
        changed()
    }

    private suspend fun begin(generation: Int) {
        fun alive() = alive(generation)
        phase = if (config == null) GiovChatPhase.LOADING else if (session != null) GiovChatPhase.CONNECTING else GiovChatPhase.READY
        error = null
        agentTyping = false
        agentTypingIsBot = null
        sending = false
        setStatus(GiovConnectionStatus.CONNECTING)
        changed()
        val loaded = config ?: client.fetchWidgetConfig()
        if (!alive()) return
        config = loaded
        changed()
        if (!loadedStore) {
            loadedStore = true
            try {
                val stored = store.read(scopeKey)
                if (!alive()) return
                if (isValidStoredSession(stored)) session = stored
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(GiovStorageException("Chat session could not be saved or restored.", e))
            }
        }
        if (!alive()) return
        val existing = session
        if (existing != null) {
            try {
                session = client.resumeConversation(existing, contact)
            } catch (failure: GiovChatException) {
                if (!alive()) return
                if (failure !is GiovSessionExpiredException && failure !is GiovConversationClosedException && failure.status != 404) throw failure
                clearSession()
            }
        }
        if (!alive()) return
        // Hidden and nothing to resume: wait until the chat is opened instead of starting a conversation.
        if (!visible && session == null) {
            phase = GiovChatPhase.IDLE
            setStatus(GiovConnectionStatus.DISCONNECTED)
            changed()
            return
        }
        requiredFields = missingContactFields(loaded.requestUserInfo, contact)
        if (session == null && requiredFields.any) {
            phase = GiovChatPhase.PRE_CHAT
            setStatus(GiovConnectionStatus.DISCONNECTED)
            changed()
            return
        }
        if (session == null && !startNow) {
            phase = GiovChatPhase.READY
            setStatus(GiovConnectionStatus.DISCONNECTED)
            changed()
            return
        }
        phase = GiovChatPhase.CONNECTING
        changed()
        if (session == null) {
            val created = client.startConversation(contact)
            if (!alive()) return
            startNow = false
            session = created
            try {
                store.write(scopeKey, created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(GiovStorageException("Chat session could not be saved or restored.", e))
            }
            if (!alive()) return
            try {
                onConversationStart?.invoke(created.conversationId)
            } catch (_: Exception) {
                // Host callbacks are isolated.
            }
        }
        val current = session
        if (!alive() || current == null) return
        hasConversation = true
        conversationIdValue = current.conversationId
        changed()

        var openedOnce = false

        // Catch-up: history merged by id with what arrived meanwhile, then the outbox is resent.
        suspend fun refreshHistory() {
            val history = try {
                client.fetchHistory()
            } catch (failure: GiovChatException) {
                if (failure.status == 404) {
                    forget(generation)
                    return
                }
                throw failure
            }
            if (!alive()) return
            if (history.closed) {
                forget(generation)
                return
            }
            captureAccess(history.attachmentAccess)
            agentName = history.assignedAgentName
            agentAvatar = history.assignedAgentAvatar
            messages = mergeMessages(messages, history.messages)
            changed()
            client.flush()
        }

        // A catch-up while connected (after a reconnect or on return to the foreground) that fails: a dead session
        // ends the chat; anything else is shown, and the outbox is still resent, since resends are idempotent and
        // nothing else would flush it on this socket.
        suspend fun catchUpNow() {
            try {
                refreshHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!alive()) return
                val failure = e.toGiovException()
                report(failure)
                error = failure
                if (failure is GiovSessionExpiredException) {
                    cancelSubs()
                    client.disconnect()
                    phase = GiovChatPhase.ERROR
                    setStatus(GiovConnectionStatus.DISCONNECTED)
                    clearSession()
                } else {
                    client.flush()
                }
                changed()
            }
        }

        client.listener = object : ClientListener {
            override fun onMessage(message: GiovMessage) {
                if (!alive()) return
                messages = mergeMessages(messages, listOf(message))
                if (message.role == GiovRole.AGENT) {
                    agentTyping = false
                    agentTypingIsBot = null
                    val name = message.senderName?.trim()
                    if (message.isFromPerson && !name.isNullOrEmpty()) {
                        agentName = name
                        agentAvatar = message.senderAvatar
                    }
                    if (!visible) {
                        unread += 1
                        publishUnread()
                    }
                }
                changed()
            }

            override fun onAck(clientMessageId: String, message: GiovMessage) {
                if (!alive()) return
                messages = mergeMessages(messages, listOf(message.copy(clientMessageId = clientMessageId)))
                changed()
            }

            override fun onStream(event: StreamEvent) {
                if (!alive()) return
                when (event) {
                    is StreamEvent.End -> if (drafts.remove(event.streamId) == null) return
                    is StreamEvent.Update -> {
                        drafts[event.streamId] = GiovDraft(event.content, drafts[event.streamId]?.createdAt ?: clock())
                        if (event.content.isNotEmpty()) agentTyping = false
                    }
                }
                changed()
            }

            override fun onAttachmentAccess(access: AttachmentAccess) {
                if (alive()) captureAccess(access)
            }

            override fun onTyping(active: Boolean, isBot: Boolean?) {
                if (!alive()) return
                // A stop for the other kind keeps the indicator (a person's typing_end must not hide the bot's).
                val otherKind = isBot != null && agentTypingIsBot != null && isBot != agentTypingIsBot
                if (!active && agentTyping && otherKind) return
                agentTyping = active
                agentTypingIsBot = if (active) isBot else null
                changed()
            }

            override fun onError(error: GiovChatException) {
                if (!alive()) return
                report(error)
                if (error is GiovConversationClosedException) {
                    scope.launch { forget(generation) }
                    return
                }
                this@GiovChatController.error = error
                if (error is GiovSessionExpiredException) {
                    phase = GiovChatPhase.ERROR
                    scope.launch { clearSession() }
                }
                changed()
            }

            override fun onConnection(connected: Boolean) {
                if (!alive()) return
                if (!connected) {
                    agentTyping = false
                    agentTypingIsBot = null
                    // A reply being written is lost with the socket; the saved copy arrives with the catch-up.
                    drafts.clear()
                }
                setStatus(
                    when {
                        connected -> GiovConnectionStatus.CONNECTED
                        openedOnce -> GiovConnectionStatus.DISCONNECTED
                        else -> GiovConnectionStatus.CONNECTING
                    },
                )
                phase = if (connected) GiovChatPhase.ACTIVE else GiovChatPhase.CONNECTING
                if (connected) {
                    this@GiovChatController.error = null
                    // Catch up on whatever arrived while the socket was down.
                    if (openedOnce) scope.launch { catchUpNow() }
                    openedOnce = true
                    connectAttempt = 0
                    val push = pushRequest
                    val last = lastPush
                    if (push != null) {
                        pushRequest = null
                        scope.launch { push.done.complete(registerPush(PushRegistration(push.token, push.platform, push.options), generation)) }
                    } else if (last != null && pushedConversation != current.conversationId) {
                        // A new conversation of the same visitor: the device was registered for the previous one.
                        scope.launch { registerPush(last, generation) }
                    }
                }
                changed()
            }
        }
        catchUp = ::catchUpNow
        client.connect(current)
        if (!alive()) return
        refreshHistory()
    }

    private fun cancelSubs() {
        client.listener = null
        catchUp = null
    }

    // ---------------------------------------------------------------------------------------------
    // Sending

    /** What a file picked now may be: the answering bot's policy against the server's limits. */
    public fun checkFiles(files: List<GiovPickedFile>, alreadyAttached: Int = 0): AttachmentCheck =
        validateAttachments(files, (state.value.config ?: GiovWidgetConfig(title = "")).attachmentPolicy, alreadyAttached)

    /**
     * Sends text and files. Files upload first (they need the conversation); the message then waits in the
     * outbox until a socket takes it, so text typed while reconnecting is never lost. Several files go as one
     * message each after the text, as in the widget. Returns false when nothing was queued (empty, wrong phase,
     * a message over 2000 characters, or an upload failed; see [GiovChatUiState.error]).
     */
    public suspend fun send(text: String, files: List<GiovPickedFile> = emptyList()): Boolean = withContext(dispatcher) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && files.isEmpty()) return@withContext false
        if (phase != GiovChatPhase.ACTIVE && phase != GiovChatPhase.CONNECTING && phase != GiovChatPhase.READY) return@withContext false
        // Uploads need a live conversation; the attach button only appears once one exists.
        if (files.isNotEmpty() && session == null) return@withContext false
        val generation = this@GiovChatController.generation
        stopTyping()
        error = null
        val urls = mutableListOf<String>()
        if (files.isNotEmpty()) {
            if (!client.isConnected && client.session == null) {
                error = GiovNetworkException("Chat is reconnecting. Please try again.")
                changed()
                return@withContext false
            }
            sending = true
            uploads = files.map { GiovUploadProgress(it.name, 0.0) }
            changed()
            try {
                for ((index, file) in files.withIndex()) {
                    val result = client.uploadAttachment(file) { progress ->
                        if (this@GiovChatController.generation != generation || disposed) return@uploadAttachment
                        uploads = uploads.mapIndexed { i, upload -> if (i == index) GiovUploadProgress(upload.name, progress) else upload }
                        changed()
                    }
                    val preview = result.preview
                        ?: if (file.width != null && file.height != null) GiovAttachmentPreview(width = file.width, height = file.height) else null
                    localFiles[result.url] = GiovAttachment(
                        url = result.url,
                        filename = result.filename.ifEmpty { file.name },
                        contentType = file.mimeType,
                        preview = preview,
                    )
                    urls += result.url
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@GiovChatController.generation == generation && !disposed) {
                    val cause = e.toGiovException()
                    report(cause)
                    when (cause) {
                        // The conversation is gone (404): forget it like a closed one; the next message starts anew.
                        is GiovConversationClosedException -> scope.launch { forget(generation) }
                        is GiovSessionExpiredException -> {
                            error = cause
                            phase = GiovChatPhase.ERROR
                            scope.launch { clearSession() }
                        }
                        // The visitor sees "File upload failed"; onError got the original kind (rate limit, server,
                        // network) with its status and code.
                        else -> error = cause as? GiovAttachmentRejectedException
                            ?: GiovAttachmentRejectedException("File upload failed.", cause.status, cause.code)
                    }
                }
                return@withContext false
            } finally {
                if (this@GiovChatController.generation == generation && !disposed) {
                    sending = false
                    uploads = emptyList()
                    changed()
                }
            }
            if (this@GiovChatController.generation != generation) return@withContext false
        }
        val parts = mutableListOf<Pair<String, List<String>>>()
        if (urls.size > 1) {
            if (trimmed.isNotEmpty()) parts += trimmed to emptyList()
            for (url in urls) parts += "" to listOf(url)
        } else {
            parts += trimmed to urls.toList()
        }
        try {
            for ((content, attachments) in parts) outbox.enqueue(content, attachments, clock())
        } catch (failed: GiovChatException) {
            report(failed)
            error = failed
            changed()
            return@withContext false
        }
        if (session != null) {
            client.flush()
        } else if (!startNow) {
            // The first message starts the conversation; it goes out once the socket is open.
            startNow = true
            startingPass = restart()
        }
        changed()
        true
    }

    /** Tries a failed message again ("tap to retry"); it is sent in its original place in the queue. */
    public fun retryMessage(clientMessageId: String) {
        scope.launch { if (outbox.retry(clientMessageId)) client.flush() }
    }

    /** Removes a failed message the visitor does not want to retry. */
    public fun discardMessage(clientMessageId: String) {
        scope.launch { outbox.remove(clientMessageId) }
    }

    /** Call on every keystroke: the agent sees "typing" once per burst and it ends after a pause. */
    public fun notifyTyping() {
        scope.launch {
            if (!client.isConnected) return@launch
            if (!typingSent) {
                client.sendTyping()
                typingSent = true
            }
            typingJob?.cancel()
            typingJob = scope.launch {
                delay(TYPING_IDLE_MS)
                typingJob = null
                typingSent = false
                client.sendTypingEnd()
            }
        }
    }

    private fun stopTyping() {
        typingJob?.cancel()
        typingJob = null
        if (typingSent) client.sendTypingEnd()
        typingSent = false
    }

    /**
     * A download URL for an attachment with exactly one credential (the attachment access token, else the
     * visitor token). Load files from here with the app's image loader.
     */
    public fun attachmentUrl(url: String): String {
        val now = clock()
        val current = access
        val usable = if (current != null && current.usableAt(now)) current.token else null
        if (usable == null && now - accessRenewedAt > 60_000L) {
            // A long session outlives the download token (and the 15-minute visitor token behind the fallback): renew
            // it, at most once a minute, like the web widget. The new token republishes the state, so images whose URL
            // changes load again.
            accessRenewedAt = now
            scope.launch { if (!disposed && client.isConnected) client.renewToken() }
        }
        return attachmentDisplayUrl(url, accessToken = usable, visitorToken = client.visitorToken)
    }

    /**
     * Registers the device for push now when connected, or as soon as the conversation connects. Never throws.
     * Call again whenever the platform hands out a new token. [platform] is `FCM` for Android;
     * [environment] matters for APNs only; [locale] defaults to the chat's language.
     *
     * Before any conversation exists the call waits for the visitor's first one. It resolves when that conversation
     * connects, when a newer call replaces it ([GiovPushFailure.FAILED]), or on [reset] / [close]
     * ([GiovPushFailure.NO_CONVERSATION]); launch it rather than awaiting it on a path that must finish. The token
     * registered last is registered again, automatically, for every later conversation of the same visitor; [reset]
     * forgets it.
     */
    public suspend fun registerPushToken(
        token: String,
        platform: GiovPushPlatform = GiovPushPlatform.FCM,
        environment: GiovPushEnvironment? = null,
        appId: String? = null,
        locale: String? = null,
    ): GiovPushResult {
        val options = GiovPushOptions(environment, appId, locale)
        return withContext(dispatcher) {
            if (disposed) return@withContext GiovPushResult.notRegistered(GiovPushFailure.NO_CONVERSATION)
            if (client.isConnected) {
                registerPush(PushRegistration(token, platform, options), generation)
            } else {
                pushRequest?.done?.complete(GiovPushResult.notRegistered(GiovPushFailure.FAILED))
                val done = CompletableDeferred<GiovPushResult>()
                pushRequest = PushRequest(token, platform, options, done)
                done.await()
            }
        }
    }

    /** Registers [push] for the connected conversation and remembers it for the visitor's next conversations. */
    private suspend fun registerPush(push: PushRegistration, generation: Int): GiovPushResult {
        val conversation = conversationIdValue
        val result = client.registerPushToken(push.token, push.platform, push.options)
        if (result.registered && alive(generation)) {
            lastPush = push
            pushedConversation = conversation
        }
        return result
    }

    /**
     * Stops push notifications about this conversation on this device. Never throws; true when the server confirmed.
     * Works while the chat is closed (sign-out), as long as the controller has started and knows the conversation.
     */
    public suspend fun unregisterPushToken(token: String): Boolean = withContext(dispatcher) {
        if (disposed) false else client.unregisterPushToken(token, session)
    }

    // ---------------------------------------------------------------------------------------------
    // Plumbing

    private fun describe(url: String): GiovAttachment = localFiles[url] ?: GiovAttachment(url)

    private fun captureAccess(incoming: AttachmentAccess?) {
        val next = AttachmentAccess.newer(access, incoming)
        if (next !== access) {
            access = next
            changed()
        }
    }

    private fun publishUnread() {
        val count = if (visible) 0 else unread
        GiovUnread.publish(this, tenantKey, count)
        try {
            onUnreadCountChange?.invoke(count)
        } catch (_: Exception) {
            // Host callbacks are isolated.
        }
    }

    private fun setStatus(value: GiovConnectionStatus) {
        status = value
        try {
            onConnectionStatusChange?.invoke(value)
        } catch (_: Exception) {
            // Host callbacks are isolated.
        }
    }

    private fun report(failure: GiovChatException) {
        try {
            onError?.invoke(failure)
        } catch (_: Exception) {
            // Host callbacks are isolated.
        }
    }

    private fun changed() {
        if (disposed) return
        val config = config
        mutableState.value = GiovChatUiState(
            phase = phase,
            config = config,
            messages = displayMessages(messages, outbox.list(), drafts, ::describe),
            header = config?.let { headerIdentity(it, agentName, agentAvatar) },
            agentName = agentName,
            agentAvatar = agentAvatar,
            agentTyping = agentTyping,
            agentTypingIsBot = agentTypingIsBot,
            unreadCount = if (visible) 0 else unread,
            error = error,
            sending = sending,
            uploads = uploads,
            requiredFields = requiredFields,
            hasConversation = hasConversation,
            connectionStatus = status,
            visible = visible,
            conversationId = conversationIdValue,
        )
    }
}
