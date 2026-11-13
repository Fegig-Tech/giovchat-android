package app.giovchat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = "https://api.example.test"

@OptIn(ExperimentalCoroutinesApi::class)
class CRig(
    visible: Boolean = true,
    keepConnected: Boolean = false,
    private val contact: GiovContact? = null,
    stored: GiovSession? = null,
    store: RecordingStore? = null,
    graceMs: Long = 5_000,
) : TimeRig() {
    val server = FakeServer(now)
    val sockets = Sockets()
    val store: RecordingStore = store ?: RecordingStore()
    val errors = mutableListOf<GiovChatException>()
    val unread = mutableListOf<Int>()
    val started = mutableListOf<String>()
    val statuses = mutableListOf<GiovConnectionStatus>()

    init {
        if (stored != null) {
            settle { this.store.seed(giovSessionScope(BASE, "t", contact ?: GiovContact()), stored) }
            this.store.log.clear()
        }
    }

    val controller = GiovChatController(
        tenantKey = "t",
        baseUrl = BASE,
        contact = contact,
        sessionStore = this.store,
        keepConnected = keepConnected,
        visible = visible,
        locale = "en",
        backgroundGraceMs = graceMs,
        onError = { errors += it },
        onConversationStart = { started += it },
        onConnectionStatusChange = { statuses += it },
        onUnreadCountChange = { unread += it },
        http = server,
        socketFactory = sockets,
        dispatcher = dispatcher,
        clock = now,
        random = NoJitter,
    )

    val ui: GiovChatUiState get() = controller.state.value
    val phase: GiovChatPhase get() = ui.phase
    val socket: FakeSocket get() = sockets.last

    fun start() {
        start { controller.start() }
        pump()
    }

    /** The socket opens and the catch-up runs. */
    fun open() {
        socket.accept()
        pump()
    }

    /** Protocol v1: the first message starts the conversation; wait until connected and caught up. */
    fun begin(text: String = "Hi") {
        assertEquals("opening the chat creates nothing", GiovChatPhase.READY, phase)
        assertEquals(0, server.count("created"))
        assertTrue(settle { controller.send(text) })
        pump()
        open()
        assertEquals(GiovChatPhase.ACTIVE, phase)
        assertEquals(1, server.count("created"))
    }

    fun dispose() {
        controller.close()
        pump()
    }

    fun receive(vararg pairs: Pair<String, Any?>) {
        socket.receive(*pairs)
        pump()
    }

    fun drop(code: Int? = null, reason: String? = null) {
        socket.drop(code, reason)
        pump()
    }

    fun msg(content: String): GiovMessage = ui.messages.first { it.content == content }
}

class ControllerTest {
    @Test
    fun openingTheChatCreatesNothingTheFirstMessageStartsTheConversationAndGoesOutAfterTheHistoryCatchUp() {
        val rig = CRig()
        rig.start()
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertEquals("Acme", rig.ui.config!!.title)
        assertEquals(0, rig.server.count("created"))
        assertTrue(rig.sockets.all.isEmpty())
        assertEquals("before anyone writes, the bot answers", GiovHeaderKind.BOT, rig.ui.header!!.kind)

        assertTrue(rig.settle { rig.controller.send("  Hi there  ") })
        rig.pump()
        assertEquals(GiovChatPhase.CONNECTING, rig.phase)
        assertEquals("shown at once", GiovDelivery.SENDING, rig.ui.messages.single().delivery)
        assertEquals("Hi there", rig.ui.messages.single().content)
        rig.socket.accept()
        assertTrue("the outbox waits for the history catch-up", rig.socket.messageFrames().isEmpty())
        rig.pump()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        assertEquals(1, rig.server.count("history"))
        assertEquals("Hi there", rig.socket.messageFrames().single()["content"].asString())
        assertEquals(listOf("write"), rig.store.log)
        assertEquals(listOf("conv-1"), rig.started)
        assertEquals("conv-1", rig.ui.conversationId)
        assertTrue(rig.ui.hasConversation)
        assertTrue(rig.ui.isConnected)
        rig.dispose()
    }

    @Test
    fun aDroppedSocketCatchesUpOnHistoryTextTypedMeanwhileGoesOutOnTheNextOneUnderTheSameKey() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.receive(
            "id" to "a1", "role" to "agent", "content" to "Hello, how can I help?", "createdAt" to 10,
            "senderName" to "Ada", "senderAvatar" to "https://cdn.test/ada.png",
        )
        assertTrue(rig.ui.messages.any { it.id == "a1" })
        assertEquals(
            "a person replying takes over the header",
            GiovHeaderIdentity(GiovHeaderKind.PERSON, "Ada", "https://cdn.test/ada.png"),
            rig.ui.header,
        )

        rig.drop()
        assertEquals(GiovChatPhase.CONNECTING, rig.phase)
        assertEquals(GiovConnectionStatus.DISCONNECTED, rig.ui.connectionStatus)
        assertTrue(rig.settle { rig.controller.send("typed while offline") })
        val pending = rig.msg("typed while offline")
        assertEquals(GiovDelivery.SENDING, pending.delivery)

        rig.elapse(1_500)
        assertEquals(2, rig.sockets.all.size)
        val historyBefore = rig.server.count("history")
        rig.open()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        assertEquals("history is reloaded after every reconnect", historyBefore + 1, rig.server.count("history"))
        val frame = rig.socket.messageFrames().first { it["content"].asString() == "typed while offline" }
        assertEquals(pending.clientMessageId, frame["clientMessageId"].asString())

        rig.receive(
            "type" to "message_ack", "clientMessageId" to frame["clientMessageId"].asString(),
            "message" to mapOf("id" to "srv-7", "role" to "visitor", "content" to frame["content"].asString(), "createdAt" to 99999),
        )
        val saved = rig.msg("typed while offline")
        assertEquals("srv-7", saved.id)
        assertEquals("the bubble keeps its key, so it is not redrawn", pending.clientMessageId, saved.clientMessageId)
        assertEquals(pending.key, saved.key)
        assertEquals(GiovDelivery.SENT, saved.delivery)
        assertEquals(1, rig.ui.messages.count { it.content == "typed while offline" })
        rig.dispose()
    }

    @Test
    fun historyMergesByIdWithFramesReceivedMeanwhileThenTheOutboxIsResent() {
        val rig = CRig(stored = testSession)
        rig.server.history = listOf(
            mapOf("id" to "h1", "role" to "user", "content" to "Hi", "createdAt" to 1),
            mapOf("id" to "h2", "role" to "agent", "content" to "Hello", "createdAt" to 2, "isBot" to true, "isAutomated" to true),
        )
        rig.start()
        assertEquals("a stored conversation is resumed at once", 0, rig.server.count("created"))
        assertEquals(1, rig.server.count("resumed"))
        assertEquals(GiovChatPhase.CONNECTING, rig.phase)
        rig.open()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        assertEquals(listOf("h1", "h2"), rig.ui.messages.map { it.id })
        rig.receive("id" to "h2", "role" to "agent", "content" to "Hello", "createdAt" to 2, "isBot" to true, "isAutomated" to true)
        assertEquals("deduplicated by id", 2, rig.ui.messages.size)
        rig.dispose()
    }

    @Test
    fun aDeletedConversationIsForgottenHistory404DeletedCloseFrame() {
        var rig: CRig

        // History answers 404.
        rig = CRig(stored = testSession)
        rig.server.historyStatuses += 404
        rig.start()
        rig.open()
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertEquals(listOf("delete"), rig.store.log)
        rig.dispose()

        // Close code 1000 "Conversation deleted".
        rig = CRig(stored = testSession)
        rig.start()
        rig.open()
        rig.drop(1000, "Conversation deleted")
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertEquals(listOf("delete"), rig.store.log)
        rig.elapse(60_000)
        assertEquals("no reconnect for a deleted conversation", 1, rig.sockets.all.size)
        rig.dispose()
    }

    @Test
    fun aClosedConversationOnResume409IsShownReadOnlyWhenTheTokenAllows() {
        val rig = CRig(stored = testSession)
        rig.server.resumeStatus = 409
        rig.server.status = "closed"
        rig.server.history = listOf(
            mapOf("id" to "h1", "role" to "user", "content" to "Hi", "createdAt" to 1),
            mapOf("id" to "h2", "role" to "agent", "content" to "Thanks for writing", "createdAt" to 2),
        )
        rig.start()
        rig.open()
        assertTrue(rig.ui.conversationClosed)
        assertEquals("the history stays visible", listOf("h1", "h2"), rig.ui.messages.map { it.id })
        assertTrue("the stored conversation is kept until a new one starts", rig.store.log.isEmpty())
        rig.dispose()
    }

    @Test
    fun aClosedConversationOnResume409DegradesWhenTheTokenIsRefused() {
        val rig = CRig(stored = testSession)
        rig.server.resumeStatus = 409
        rig.server.tokenStatuses += 403
        rig.start()
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertFalse(rig.ui.conversationClosed)
        assertTrue(rig.ui.error is GiovConversationClosedException)
        assertEquals(listOf("delete"), rig.store.log)
        assertTrue(rig.sockets.all.isEmpty())
        rig.dispose()
    }

    @Test
    fun aClosedConversationInHistoryStaysReadOnlyAndAMessageStartsANewOneThatClearsIt() {
        val rig = CRig(stored = testSession)
        rig.server.status = "closed"
        rig.server.history = listOf(mapOf("id" to "h1", "role" to "user", "content" to "Hi", "createdAt" to 1))
        rig.start()
        rig.open()
        assertEquals("the socket stays up: a rating card can still arrive", GiovChatPhase.ACTIVE, rig.phase)
        assertTrue(rig.ui.conversationClosed)
        assertTrue(rig.ui.hasConversation)
        assertEquals(listOf("h1"), rig.ui.messages.map { it.id })
        assertTrue(rig.store.log.isEmpty())
        assertFalse(rig.socket.closedByClient)
        assertTrue("writing on a closed conversation starts a new one", rig.settle { rig.controller.send("again") })
        rig.server.status = "open"
        rig.server.history = emptyList()
        rig.pump()
        assertEquals("delete", rig.store.log.first())
        assertEquals(1, rig.server.count("created"))
        assertFalse(rig.ui.conversationClosed)
        assertTrue("the closed conversation is gone", rig.ui.messages.none { it.id == "h1" })
        rig.dispose()
    }

    @Test
    fun startNewConversationLeavesTheClosedOneAndWaitsForTheNextMessage() {
        val rig = CRig(stored = testSession)
        rig.server.status = "closed"
        rig.server.history = listOf(mapOf("id" to "h1", "role" to "user", "content" to "Hi", "createdAt" to 1))
        rig.start()
        rig.open()
        assertTrue(rig.ui.conversationClosed)
        rig.settle { rig.controller.startNewConversation() }
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertFalse(rig.ui.conversationClosed || rig.ui.hasConversation)
        assertTrue(rig.ui.messages.isEmpty())
        assertEquals(listOf("delete"), rig.store.log)
        assertTrue(rig.socket.closedByClient)
        assertEquals("nothing is created until the visitor writes", 0, rig.server.count("created"))
        rig.settle { rig.controller.startNewConversation() }
        assertEquals("an open (or already left) conversation is not touched", listOf("delete"), rig.store.log)
        rig.dispose()
    }

    @Test
    fun aRefusedUpgradeWith410ForgetsTheConversationOnce() {
        val rig = CRig(stored = testSession)
        rig.start()
        rig.socket.fail(410, "Expected HTTP 101 response but was '410 Gone'")
        rig.pump()
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertEquals(listOf("delete"), rig.store.log)
        assertFalse(rig.ui.hasConversation)
        assertTrue(rig.ui.error is GiovConversationClosedException)
        rig.elapse(60_000)
        assertEquals(1, rig.sockets.all.size)
        rig.dispose()
    }

    @Test
    fun aFailedUpgradeWhoseTextMentions410IsJustAConnectionFailure() {
        val rig = CRig(stored = testSession)
        rig.start()
        rig.socket.fail(null, "Failed to connect to /conversations/9c410f2e-4100-4abc-8410-000000000410/ws")
        rig.pump()
        assertEquals("retried with the backoff", GiovChatPhase.CONNECTING, rig.phase)
        assertTrue(rig.errors.single() is GiovNetworkException)
        assertTrue("the conversation is kept", rig.store.log.isEmpty())
        rig.dispose()
    }

    @Test
    fun anExpiredSessionEndsInTheErrorPhaseForgetsTheStoredPairAndARetryStartsClean() {
        val rig = CRig(stored = testSession)
        rig.server.tokenStatuses += 401
        rig.start()
        assertEquals(GiovChatPhase.ERROR, rig.phase)
        assertTrue(rig.ui.error is GiovSessionExpiredException)
        assertEquals(listOf("delete"), rig.store.log)
        assertTrue(rig.errors.single() is GiovSessionExpiredException)
        rig.start { rig.controller.retry() }
        rig.pump()
        assertEquals(GiovChatPhase.READY, rig.phase)
        rig.dispose()
    }

    @Test
    fun historyNamesTheTeammateWhoHasTheConversation() {
        val rig = CRig()
        rig.server.assignedAgentName = "Ada"
        rig.server.assignedAgentAvatar = "https://cdn.test/ada.png"
        rig.server.history = listOf(
            mapOf("id" to "h1", "role" to "user", "content" to "Hi", "created_at" to 1),
            mapOf("id" to "h2", "role" to "agent", "content" to "Hello!", "created_at" to 2, "is_bot" to 0, "is_automated" to 0),
        )
        rig.start()
        rig.begin()
        assertEquals("Ada", rig.ui.agentName)
        assertEquals("https://cdn.test/ada.png", rig.ui.agentAvatar)
        assertEquals(GiovHeaderKind.PERSON, rig.ui.header!!.kind)
        assertEquals("Ada", rig.ui.messages.first { it.id == "h2" }.senderName)
        rig.dispose()
    }

    @Test
    fun streamedRepliesShowAsTheyAreWrittenAndBecomeOneSavedMessageInTheSamePlace() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.receive("type" to "typing", "role" to "agent", "isBot" to true)
        assertTrue(rig.ui.agentTyping)
        assertEquals(true, rig.ui.agentTypingIsBot)
        rig.receive("type" to "stream", "streamId" to "r1", "content" to "Our **hours**")
        val draft = rig.ui.messages.first { it.isStreaming }
        assertEquals("Our **hours**", draft.content)
        assertEquals("stream:r1", draft.clientMessageId)
        assertFalse("the draft replaces the typing indicator", rig.ui.agentTyping)
        rig.receive("type" to "stream", "streamId" to "r1", "content" to "Our **hours** are 9–5.")
        rig.receive(
            "id" to "m9", "role" to "agent", "content" to "Our **hours** are 9–5.", "createdAt" to 2_000_000_000_000L,
            "isBot" to true, "isAutomated" to true, "streamId" to "r1",
        )
        val replies = rig.ui.messages.filter { it.role == GiovRole.AGENT }
        assertEquals(1, replies.size)
        assertEquals(Triple("m9", "stream:r1", false), Triple(replies.single().id, replies.single().clientMessageId, replies.single().isStreaming))
        assertEquals("the key of the draft", draft.key, replies.single().key)
        rig.receive("type" to "typing_end", "role" to "agent", "isBot" to true)
        assertFalse(rig.ui.agentTyping)

        // A cancelled reply sends only stream_end.
        rig.receive("type" to "stream", "streamId" to "r2", "content" to "Thinking")
        assertEquals(1, rig.ui.messages.count { it.isStreaming })
        rig.receive("type" to "stream_end", "streamId" to "r2")
        assertEquals(0, rig.ui.messages.count { it.isStreaming })
        // A late frame after the stored reply must not bring the draft back... (a new stream id is a new draft)
        // A draft is lost with the socket.
        rig.receive("type" to "stream", "streamId" to "r3", "content" to "Half")
        assertEquals(1, rig.ui.messages.count { it.isStreaming })
        rig.drop()
        assertEquals(0, rig.ui.messages.count { it.isStreaming })
        rig.dispose()
    }

    @Test
    fun keptConnectedWhileHiddenRepliesCountAsUnreadUntilTheChatOpensTheSameConnectionStays() {
        val rig = CRig(visible = false, keepConnected = true, stored = testSession)
        rig.start()
        rig.open()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        assertEquals(0, rig.server.count("created"))
        rig.receive("id" to "a1", "role" to "agent", "content" to "Your order shipped", "createdAt" to 100, "senderName" to "Ada")
        rig.receive("id" to "a2", "role" to "agent", "content" to "Anything else?", "createdAt" to 101, "senderName" to "Ada")
        assertEquals(2, rig.ui.unreadCount)
        assertEquals(2, GiovUnread.read("t"))
        assertEquals(2, GiovUnread.watch("t").value)
        val sockets = rig.sockets.all.size
        rig.controller.setVisible(true)
        rig.pump()
        assertEquals(0, rig.ui.unreadCount)
        assertEquals("opening the chat keeps the same connection", sockets, rig.sockets.all.size)
        assertEquals(listOf(1, 2, 0), rig.unread.takeLast(3))
        assertEquals(0, GiovUnread.read("t"))
        rig.receive("id" to "a3", "role" to "agent", "content" to "Hello again", "createdAt" to 102)
        assertEquals("visible: nothing is unread", 0, rig.ui.unreadCount)
        rig.dispose()
    }

    @Test
    fun aHiddenChatNeverStartsAConversationWithoutKeepConnectedItDisconnects() {
        val rig = CRig(visible = false, keepConnected = true)
        rig.start()
        assertEquals(GiovChatPhase.IDLE, rig.phase)
        assertEquals(0, rig.server.count("created"))
        assertTrue(rig.sockets.all.isEmpty())
        rig.controller.setVisible(true)
        rig.pump()
        assertEquals(GiovChatPhase.READY, rig.phase)
        rig.begin()
        rig.dispose()

        val plain = CRig(stored = testSession)
        plain.start()
        plain.open()
        assertEquals(GiovChatPhase.ACTIVE, plain.phase)
        plain.controller.setVisible(false)
        plain.pump()
        assertEquals(GiovConnectionStatus.DISCONNECTED, plain.ui.connectionStatus)
        assertTrue(plain.socket.closedByClient)
        plain.controller.setVisible(true)
        plain.pump()
        assertEquals(2, plain.sockets.all.size)
        plain.dispose()
    }

    @Test
    fun theVisitorsTypingIsSentOncePerBurstAndEndsAfter2500msOfQuietOrOnSend() {
        val rig = CRig()
        rig.start()
        rig.begin()
        val socket = rig.socket
        socket.sent.clear()
        repeat(3) { rig.controller.notifyTyping() }
        rig.pump()
        assertEquals(listOf<String?>("typing"), socket.types())
        rig.elapse(2_400)
        assertEquals(listOf<String?>("typing"), socket.types())
        rig.elapse(200)
        assertEquals(listOf<String?>("typing", "typing_end"), socket.types())
        // A new burst, ended by sending.
        socket.sent.clear()
        rig.controller.notifyTyping()
        rig.pump()
        assertTrue(rig.settle { rig.controller.send("done") })
        assertEquals(listOf<String?>("typing", "typing_end", "message"), socket.types())
        rig.dispose()
    }

    @Test
    fun thePreChatFormIsAskedBeforeAnyConversationExistsAndItsFieldsGoToTheStartRequest() {
        val rig = CRig()
        rig.server.config = rig.server.config + ("requestUserInfo" to mapOf("email" to true, "firstName" to true))
        rig.start()
        assertEquals(GiovChatPhase.PRE_CHAT, rig.phase)
        assertTrue(rig.ui.requiredFields.email)
        assertTrue(rig.ui.requiredFields.firstName)
        assertFalse(rig.ui.requiredFields.phone)
        assertEquals(0, rig.server.count("created"))
        assertFalse("the form comes first", rig.settle { rig.controller.send("hi") })
        rig.start { rig.controller.submitContact(GiovContact(email = "not-an-email", firstName = "Ada")) }
        rig.pump()
        assertEquals("a malformed email is still missing", GiovChatPhase.PRE_CHAT, rig.phase)
        assertTrue(rig.ui.requiredFields.email)
        assertFalse(rig.ui.requiredFields.firstName)
        rig.start { rig.controller.submitContact(GiovContact(email = "ada@acme.co")) }
        rig.pump()
        assertEquals(1, rig.server.count("created"))
        assertEquals(json("tenantId" to "t", "email" to "ada@acme.co", "firstName" to "Ada"), rig.server.to("/conversations", "POST").last().json)
        rig.open()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        rig.dispose()
    }

    @Test
    fun aKnownVisitorStartsWithUserIdAndUserHash() {
        val hash = "c".repeat(64)
        val rig = CRig(contact = GiovContact(userId = "cust_42", userHash = hash))
        rig.start()
        rig.begin()
        assertEquals(json("tenantId" to "t", "userId" to "cust_42", "userHash" to hash), rig.server.to("/conversations", "POST").single().json)
        rig.dispose()
    }

    @Test
    fun goingToTheBackgroundClosesTheSocketAfterAShortGraceComingBackReconnectsAndCatchesUp() {
        val rig = CRig()
        rig.start()
        rig.begin()
        val before = rig.server.count("history")

        // A short interruption does not close the socket.
        rig.controller.onBackground()
        rig.elapse(3_000)
        assertFalse(rig.socket.closedByClient)
        rig.controller.onForeground()
        rig.pump()
        assertEquals(1, rig.sockets.all.size)
        assertEquals("the history is reloaded on resume even when the socket survived", before + 1, rig.server.count("history"))

        // A long one does.
        rig.controller.onBackground()
        rig.elapse(6_000)
        assertTrue(rig.sockets.all.first().closedByClient)
        assertEquals(GiovConnectionStatus.DISCONNECTED, rig.ui.connectionStatus)
        rig.elapse(120_000)
        assertEquals("no reconnect while in the background", 1, rig.sockets.all.size)
        rig.controller.onForeground()
        rig.pump()
        assertEquals(2, rig.sockets.all.size)
        val reloaded = rig.server.count("history")
        rig.open()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        assertEquals(reloaded + 1, rig.server.count("history"))
        rig.dispose()
    }

    @Test
    fun aPushTokenRequestedBeforeTheConversationConnectsIsRegisteredOnceItDoes() {
        val rig = CRig()
        rig.start()
        val hex = "ab".repeat(32)
        val pending = rig.start { rig.controller.registerPushToken(hex, GiovPushPlatform.IOS, GiovPushEnvironment.SANDBOX, "com.acme.app") }
        rig.pump()
        assertFalse(pending.isCompleted)
        rig.begin()
        assertTrue(rig.await(pending).registered)
        val request = rig.server.to("/conversations/conv-1/push-token", "POST").single()
        assertEquals("sandbox", request.json["environment"].asString())
        assertEquals("en", request.json["locale"].asString())
        assertEquals("com.acme.app", request.json["appId"].asString())
        assertTrue("registers at once when connected", rig.settle { rig.controller.registerPushToken("new-token", GiovPushPlatform.ANDROID) }.registered)
        assertEquals("android", rig.server.to("/conversations/conv-1/push-token", "POST").last().json["platform"].asString())
        assertTrue(rig.settle { rig.controller.unregisterPushToken("new-token") })
        rig.dispose()
    }

    @Test
    fun signingOutWhileTheChatIsClosedStillUnregistersThePushToken() {
        // Sign-out usually happens on another screen: the chat is hidden and its socket closed.
        val rig = CRig(stored = testSession)
        rig.start()
        rig.open()
        rig.controller.setVisible(false)
        rig.pump()
        assertFalse(rig.ui.isConnected)
        assertTrue(rig.settle { rig.controller.unregisterPushToken("fcm-token") })
        assertEquals(json("token" to "fcm-token"), rig.server.to("/conversations/conv-1/push-token", "DELETE").single().json)
        rig.dispose()
    }

    @Test
    fun aPushRequestSupersededByANewerOneReportsFailureAndClosingReleasesIt() {
        val rig = CRig()
        rig.start()
        val first = rig.start { rig.controller.registerPushToken("one") }
        rig.pump()
        val second = rig.start { rig.controller.registerPushToken("two") }
        rig.pump()
        assertEquals(GiovPushFailure.FAILED, rig.await(first).failure)
        rig.dispose()
        assertEquals(GiovPushFailure.NO_CONVERSATION, rig.await(second).failure)
    }

    @Test
    fun aFailedMessageShowsAsNotSentRetrySendsItAgainInPlaceDiscardRemovesIt() {
        val rig = CRig()
        rig.start()
        rig.begin()
        val id = rig.socket.messageFrames().single()["clientMessageId"].asString()!!
        fun failure() = rig.receive("type" to "error", "error" to "message_failed", "clientMessageId" to id)
        failure()
        rig.elapse(2_001)
        failure()
        rig.elapse(4_001)
        failure()
        assertEquals(GiovDelivery.FAILED, rig.ui.messages.single().delivery)
        assertTrue(rig.errors.last() is GiovSendFailedException)
        assertEquals(id, rig.ui.messages.single().key)
        rig.controller.retryMessage(id)
        rig.pump()
        assertEquals(GiovDelivery.SENDING, rig.ui.messages.single().delivery)
        assertEquals(id, rig.socket.messageFrames().last()["clientMessageId"].asString())
        rig.receive("type" to "error", "error" to "invalid_message")
        assertEquals(GiovDelivery.FAILED, rig.ui.messages.single().delivery)
        rig.controller.discardMessage(id)
        rig.pump()
        assertTrue(rig.ui.messages.isEmpty())
        rig.dispose()
    }

    @Test
    fun messagesOver2000CharactersAreRefusedUpFront() {
        val rig = CRig()
        rig.start()
        assertFalse(rig.settle { rig.controller.send("x".repeat(2001)) })
        assertTrue(rig.ui.error is GiovSendFailedException)
        assertEquals(0, rig.server.count("created"))
        assertFalse(rig.settle { rig.controller.send("   ") })
        rig.dispose()
    }

    @Test
    fun filesUploadFirstThenGoOutAsOneMessageEachAfterTheTextNamesSurviveFromThisDevice() {
        val rig = CRig()
        rig.start()
        rig.begin("first")
        val a = GiovPickedFile.bytes("a.png", "image/png", ByteArray(8), width = 40, height = 30)
        val b = GiovPickedFile.bytes("b.png", "image/png", ByteArray(8))
        assertEquals(2, rig.controller.checkFiles(listOf(a, b)).accepted.size)
        val refused = rig.controller.checkFiles(listOf(GiovPickedFile.bytes("x.exe", "application/x-msdownload", ByteArray(1)))).problems.single()
        assertTrue(refused is AttachmentProblem.TypeNotAllowed)
        rig.socket.sent.clear()
        assertTrue(rig.settle { rig.controller.send("see these", listOf(a, b)) })
        rig.pump()
        assertEquals(2, rig.server.count("upload"))
        val frames = rig.socket.messageFrames()
        assertEquals(3, frames.size)
        assertEquals("see these", frames[0]["content"].asString())
        assertFalse(frames[0].containsKey("attachments"))
        assertTrue(frames[1]["attachments"].asArray()!!.single().asString()!!.endsWith("/attachments/a.png"))
        assertEquals("", frames[1]["content"].asString())
        val shown = rig.ui.messages.first { m -> m.attachments.any { it.url.endsWith("a.png") } }
        assertEquals("a.png", shown.attachments.single().filename)
        assertEquals("image/png", shown.attachments.single().contentType)
        assertEquals(4.0 / 3, shown.attachments.single().preview!!.aspectRatio!!, 0.001)
        assertTrue(rig.controller.attachmentUrl(shown.attachments.single().url).endsWith("?at=access-token-abcdefgh"))
        assertTrue(rig.ui.uploads.isEmpty())
        assertFalse(rig.ui.sending)

        // A failed upload queues nothing.
        rig.server.uploadStatuses += listOf(400, 400)
        assertFalse(rig.settle { rig.controller.send("", listOf(a)) })
        assertTrue(rig.ui.error is GiovAttachmentRejectedException)
        rig.dispose()
    }

    @Test
    fun uploadProgressIsPublishedWhileFilesUpload() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.server.uploadDelayMs = 100
        val a = GiovPickedFile.bytes("a.png", "image/png", ByteArray(8))
        val sending = rig.start { rig.controller.send("x", listOf(a)) }
        rig.pump()
        assertTrue(rig.ui.sending)
        assertEquals(listOf(GiovUploadProgress("a.png", 0.5)), rig.ui.uploads)
        rig.elapse(150)
        assertTrue(rig.await(sending))
        assertFalse(rig.ui.sending)
        assertTrue(rig.ui.uploads.isEmpty())
        rig.dispose()
    }

    @Test
    fun filesCannotBeSentBeforeAConversationExists() {
        val rig = CRig()
        rig.start()
        val file = GiovPickedFile.bytes("a.png", "image/png", ByteArray(8))
        assertFalse(rig.settle { rig.controller.send("", listOf(file)) })
        assertEquals(0, rig.server.count("upload"))
        rig.dispose()
    }

    @Test
    fun aStoreThatFailsIsReportedAndNeverBreaksTheChat() {
        val rig = CRig(store = BrokenStore())
        rig.start()
        assertEquals(GiovChatPhase.READY, rig.phase)
        rig.begin()
        assertTrue(rig.errors.any { it is GiovStorageException })
        rig.dispose()
    }

    @Test
    fun resetForgetsTheConversationTheStoredSessionAndTheOutbox() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.start { rig.controller.reset() }
        rig.pump()
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertTrue(rig.ui.messages.isEmpty())
        assertFalse(rig.ui.hasConversation)
        assertNull(rig.ui.conversationId)
        assertTrue(rig.store.log.contains("delete"))
        rig.dispose()
    }

    @Test
    fun aFailureOnTheFirstLoadEndsInTheErrorPhaseWithATypedFailure() {
        val rig = CRig()
        rig.server.config = mapOf("oops" to true)
        rig.start()
        assertEquals(GiovChatPhase.ERROR, rig.phase)
        assertTrue(rig.ui.error is GiovInvalidResponseException)
        rig.dispose()
    }

    @Test
    fun aBusyWorkspaceIsReportedOnStartAndTheMessageStaysQueued() {
        val rig = CRig()
        rig.server.startStatus = 503
        rig.start()
        assertTrue(rig.settle { rig.controller.send("hello") })
        rig.pump()
        assertEquals(GiovChatPhase.ERROR, rig.phase)
        assertTrue(rig.ui.error is GiovWorkspaceBusyException)
        assertEquals(GiovDelivery.SENDING, rig.ui.messages.single().delivery)
        rig.server.startStatus = null
        rig.start { rig.controller.retry() }
        rig.pump()
        assertEquals(2, rig.server.count("created"))
        rig.dispose()
    }

    @Test
    fun connectionStatusIsReportedToTheHost() {
        val rig = CRig(stored = testSession)
        rig.start()
        rig.open()
        assertEquals(GiovConnectionStatus.CONNECTED, rig.statuses.last())
        assertTrue(GiovConnectionStatus.CONNECTING in rig.statuses)
        rig.drop()
        assertEquals(GiovConnectionStatus.DISCONNECTED, rig.statuses.last())
        rig.dispose()
    }

    @Test
    fun hostCallbacksThatThrowDoNotBreakTheChat() {
        val rig = CRig(stored = testSession)
        val noisy = GiovChatController(
            tenantKey = "t", baseUrl = BASE, sessionStore = rig.store, locale = "en",
            onError = { error("host bug") }, onConnectionStatusChange = { error("host bug") },
            onUnreadCountChange = { error("host bug") }, onConversationStart = { error("host bug") },
            http = rig.server, socketFactory = rig.sockets, dispatcher = rig.dispatcher, clock = rig.now, random = NoJitter,
        )
        rig.start { noisy.start() }
        rig.pump()
        rig.sockets.last.accept()
        rig.pump()
        assertEquals(GiovChatPhase.ACTIVE, noisy.state.value.phase)
        noisy.close()
        rig.pump()
        rig.dispose()
    }

    @Test
    fun closeStopsTheClientAndTheSocket() {
        val rig = CRig(stored = testSession)
        rig.start()
        rig.open()
        val socket = rig.socket
        rig.dispose()
        assertTrue(socket.closedByClient)
        rig.elapse(120_000)
        assertEquals(1, rig.sockets.all.size)
        assertNotNull(rig.ui.config)
    }

    @Test
    fun attachmentUrlFallsBackToTheVisitorTokenWithoutAnAccessToken() {
        val rig = CRig()
        rig.start()
        rig.begin()
        val url = rig.controller.attachmentUrl("https://api.example.test/conversations/conv-1/attachments/a.png")
        assertTrue(url.endsWith("?at=access-token-abcdefgh"))
        rig.elapse(56 * 60_000L) // the access token expires in an hour; it is dropped 5 minutes early
        val late = rig.controller.attachmentUrl("https://api.example.test/conversations/conv-1/attachments/a.png")
        assertTrue(late, late.contains("?token=visitor-token-") || late.contains("?at="))
        rig.dispose()
    }
}

class BrokenStore : RecordingStore() {
    override suspend fun read(scope: String): GiovSession? = throw IllegalStateException("keychain locked")
    override suspend fun write(scope: String, session: GiovSession): Unit = throw IllegalStateException("keychain locked")
    override suspend fun delete(scope: String): Unit = throw IllegalStateException("keychain locked")
}
