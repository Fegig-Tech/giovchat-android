package app.giovchat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Regressions found in the pre-publish review (and ported from the Flutter and iOS fixes).
class ReviewFixesTest {
    /** The server stores the first message, so nothing is left in the outbox. */
    private fun CRig.ackFirst() {
        val frame = socket.messageFrames().first()
        receive(
            "type" to "message_ack", "clientMessageId" to frame["clientMessageId"].asString(),
            "message" to mapOf("id" to "srv-1", "role" to "visitor", "content" to frame["content"].asString(), "createdAt" to 5),
        )
    }

    @Test
    fun aTypingEndForTheOtherKindKeepsTheIndicator() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.receive("type" to "typing", "role" to "agent", "isBot" to true)
        assertTrue(rig.ui.agentTyping)
        rig.receive("type" to "typing_end", "role" to "agent", "isBot" to false)
        assertTrue("a person's stop does not hide GiovBot's indicator", rig.ui.agentTyping)
        assertEquals(true, rig.ui.agentTypingIsBot)
        rig.receive("type" to "typing_end", "role" to "agent", "isBot" to true)
        assertFalse(rig.ui.agentTyping)
        rig.receive("type" to "typing", "role" to "agent", "isBot" to false)
        rig.receive("type" to "typing_end", "role" to "agent")
        assertFalse("a stop that does not say whose ends any indicator", rig.ui.agentTyping)
        rig.dispose()
    }

    @Test
    fun anUnknownSocketErrorFailsTheOldestMessageInsteadOfResendingForEver() {
        val rig = ClientRig()
        rig.connect()
        val socket = rig.sockets.last
        val a = rig.client.send("a")
        val b = rig.client.send("b")
        socket.receive("type" to "error", "error" to "content_blocked")
        rig.pump()
        assertEquals(OutboxState.FAILED, rig.outbox[a]!!.state)
        assertEquals(OutboxState.SENDING, rig.outbox[b]!!.state)
        assertEquals("content_blocked", (rig.events.errors.single() as GiovSendFailedException).code)
        rig.elapse(11_000)
        assertEquals("the refused message is not resent", 1, rig.ids(socket.messageFrames()).count { it == a })
        rig.client.dispose()
    }

    @Test
    fun aFailedCatchUpAfterAReconnectStillResendsTheOutbox() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.ackFirst()
        rig.drop()
        assertTrue(rig.settle { rig.controller.send("typed while offline") })
        rig.server.historyStatuses += 500
        rig.elapse(1_500)
        rig.open()
        assertTrue(rig.ui.error is GiovServerException)
        assertEquals("typed while offline", rig.socket.messageFrames().single()["content"].asString())
        rig.dispose()
    }

    @Test
    fun aDeadSessionOnTheCatchUpEndsTheChatAndForgetsTheStoredSession() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.store.log.clear()
        rig.drop()
        rig.server.historyStatuses += listOf(401, 401)
        rig.elapse(1_500)
        rig.open()
        assertEquals(GiovChatPhase.ERROR, rig.phase)
        assertTrue(rig.ui.error is GiovSessionExpiredException)
        assertEquals(listOf("delete"), rig.store.log)
        assertTrue(rig.socket.closedByClient)
        rig.dispose()
    }

    @Test
    fun uploadFailuresKeepTheirMeaning() {
        val rig = ClientRig()
        rig.connect()
        val file = GiovPickedFile.bytes("a.png", "image/png", ByteArray(4))
        rig.server.uploadStatuses += 404
        rig.assertFails<GiovConversationClosedException> { rig.client.uploadAttachment(file) }
        rig.server.uploadStatuses += 429
        rig.assertFails<GiovRateLimitedException> { rig.client.uploadAttachment(file) }
        rig.server.uploadStatuses += 503
        rig.assertFails<GiovServerException> { rig.client.uploadAttachment(file) }
        rig.server.uploadStatuses += 403
        rig.assertFails<GiovSessionExpiredException> { rig.client.uploadAttachment(file) }
        rig.server.uploadStatuses += 413
        rig.assertFails<GiovAttachmentRejectedException> { rig.client.uploadAttachment(file) }
        rig.client.dispose()
    }

    @Test
    fun anUploadToAConversationThatIsGoneForgetsIt() {
        val rig = CRig()
        rig.start()
        rig.begin()
        rig.store.log.clear()
        rig.server.uploadStatuses += 404
        assertFalse(rig.settle { rig.controller.send("", listOf(GiovPickedFile.bytes("a.png", "image/png", ByteArray(4)))) })
        rig.pump()
        assertEquals(GiovChatPhase.READY, rig.phase)
        assertTrue(rig.ui.error is GiovConversationClosedException)
        assertEquals(listOf("delete"), rig.store.log)
        assertTrue("onError gets the original kind", rig.errors.last() is GiovConversationClosedException)
        rig.dispose()
    }

    @Test
    fun startingAStartedControllerAgainKeepsTheConnection() {
        val rig = CRig(stored = testSession)
        rig.start()
        rig.open()
        rig.receive("type" to "stream", "streamId" to "m1", "content" to "Half a repl")
        rig.start()
        assertEquals("no new socket", 1, rig.sockets.all.size)
        assertFalse(rig.socket.closedByClient)
        assertEquals("the streaming draft stays", "Half a repl", rig.ui.messages.single { it.isStreaming }.content)
        rig.start { rig.controller.retry() }
        rig.pump()
        assertEquals("retry() is the explicit restart", 2, rig.sockets.all.size)
        rig.dispose()
    }

    @Test
    fun theLastPushTokenIsRegisteredAgainForTheVisitorsNextConversation() {
        val rig = CRig()
        rig.server.createdIds += listOf("conv-1", "conv-2")
        rig.start()
        rig.begin()
        assertTrue(rig.settle { rig.controller.registerPushToken("fcm-token-0123456789") }.registered)
        assertEquals(1, rig.server.to("/conversations/conv-1/push-token", "POST").size)
        // The team closes it; the visitor writes again and a new conversation starts.
        rig.drop(1000, "Conversation deleted")
        assertTrue(rig.settle { rig.controller.send("again") })
        rig.pump()
        rig.open()
        val again = rig.server.to("/conversations/conv-2/push-token", "POST").single()
        assertEquals("fcm-token-0123456789", again.json["token"].asString())
        assertEquals("en", again.json["locale"].asString())

        rig.settle { rig.controller.reset() }
        rig.server.createdIds += "conv-3"
        assertTrue(rig.settle { rig.controller.send("new visitor") })
        rig.pump()
        rig.open()
        assertTrue("reset forgets the token", rig.server.to("/conversations/conv-3/push-token").isEmpty())
        rig.dispose()
    }

    @Test
    fun aPushRegistrationWaitingForAConversationResolvesOnReset() {
        val rig = CRig()
        rig.start()
        val pending = rig.start { rig.controller.registerPushToken("fcm-token-0123456789") }
        rig.pump()
        assertFalse(pending.isCompleted)
        rig.settle { rig.controller.reset() }
        assertEquals(GiovPushFailure.NO_CONVERSATION, rig.await(pending).failure)
        rig.dispose()
    }

    @Test
    fun aDoubleSubmitOfThePreChatFormStartsOneConversation() {
        val rig = CRig()
        rig.server.config = rig.server.config + ("requestUserInfo" to mapOf("email" to true))
        rig.start()
        assertEquals(GiovChatPhase.PRE_CHAT, rig.phase)
        rig.server.startDelayMs = 100
        val first = rig.start { rig.controller.submitContact(GiovContact(email = "a@b.co")) }
        rig.pump()
        // The second tap lands while the first start request is on its way.
        val second = rig.start { rig.controller.submitContact(GiovContact(email = "a@b.co")) }
        rig.pump()
        rig.elapse(100)
        rig.open()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(1, rig.server.count("created"))
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        rig.dispose()
    }

    @Test
    fun anExpiredDownloadTokenIsRenewedWhileConnected() {
        val rig = CRig()
        rig.server.accessLifetimeMs = 6 * 60_000L
        rig.start()
        rig.begin()
        rig.ackFirst()
        val url = "https://api.example.test/conversations/conv-1/attachments/a.png"
        assertTrue(rig.controller.attachmentUrl(url).endsWith("?at=access-token-abcdefgh"))
        val tokens = rig.server.count("token")
        rig.elapse(2 * 60_000L)
        assertTrue("past its margin: the visitor token stands in", rig.controller.attachmentUrl(url).contains("?token="))
        rig.pump()
        assertEquals("renewed once", tokens + 1, rig.server.count("token"))
        assertTrue(rig.controller.attachmentUrl(url).endsWith("?at=access-token-abcdefgh"))
        rig.controller.attachmentUrl(url)
        rig.pump()
        assertEquals(tokens + 1, rig.server.count("token"))
        rig.dispose()
    }

    @Test
    fun theDefaultOkHttpClientIsShared() {
        val http = OkHttpGiovHttp()
        assertSame(GiovOkHttp.client, http.client)
        assertSame(http.client, OkHttpGiovHttp().client)
        val sockets = OkHttpGiovSocketFactory()
        assertSame(GiovOkHttp.client.connectionPool, sockets.client.connectionPool)
        assertSame(GiovOkHttp.client.dispatcher, sockets.client.dispatcher)
    }

    @Test
    fun theStoredSessionIsKeyedByTheCustomerNotTheirContactDetails() {
        val base = giovSessionScope("https://api.test", "t", GiovContact(userId = "u1"))
        assertEquals(base, giovSessionScope("https://api.test", "t", GiovContact(userId = "u1", email = "a@b.co", firstName = "Ada", phone = "1")))
        assertNotEquals(base, giovSessionScope("https://api.test", "t", GiovContact(userId = "u2")))
        assertEquals(giovSessionScope("https://api.test", "t"), giovSessionScope("https://api.test", "t", GiovContact(email = "a@b.co")))
    }

    @Test
    fun aFirstConnectionThatFailsRetriesWithTheBackoff() {
        val rig = CRig(stored = testSession)
        rig.start()
        rig.socket.fail(null)
        rig.pump()
        assertEquals("not the error screen", GiovChatPhase.CONNECTING, rig.phase)
        assertEquals(GiovConnectionStatus.DISCONNECTED, rig.ui.connectionStatus)
        assertTrue(rig.errors.single() is GiovNetworkException)
        rig.elapse(1_499)
        assertEquals(1, rig.sockets.all.size)
        rig.elapse(1)
        assertEquals(2, rig.sockets.all.size)
        rig.socket.fail(null)
        rig.pump()
        rig.elapse(2_999)
        assertEquals(2, rig.sockets.all.size)
        rig.elapse(1)
        assertEquals(3, rig.sockets.all.size)
        rig.open()
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        assertTrue("the conversation is kept", rig.store.log.isEmpty())
        rig.dispose()
    }
}
