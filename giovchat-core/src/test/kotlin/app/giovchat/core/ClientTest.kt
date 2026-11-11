package app.giovchat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class ClientRig(flushOnOpen: Boolean = true, outbox: Outbox? = null, baseUrl: String = "https://api.example.test") : TimeRig() {
    val server = FakeServer(now)
    val sockets = Sockets()
    val outbox: Outbox = outbox ?: Outbox(now)
    val events = RecordingListener()
    val client = GiovChatClient(
        tenantKey = "t",
        baseUrl = baseUrl,
        outbox = this.outbox,
        flushOnOpen = flushOnOpen,
        http = server,
        socketFactory = sockets,
        scope = scope,
        clock = now,
        random = NoJitter,
    ).also { it.listener = events }

    /** connect() and open the socket. */
    fun connect() {
        val done = start { client.connect(testSession) }
        pump()
        sockets.last.accept()
        await(done)
    }

    fun ids(frames: List<JsonObject>): List<String?> = frames.map { it["clientMessageId"].asString() }
}

class ClientTest {
    @Test
    fun configRequestNamesTheSdkNoOtherRequestDoes() {
        val rig = ClientRig()
        val config = rig.settle { rig.client.fetchWidgetConfig() }
        assertEquals("Acme", config.title)
        assertEquals("Atlas", config.botName)
        assertEquals("android/${GiovChatVersion.NAME}", rig.server.requests.single().headers["X-Giov-Client"])
        assertEquals("https://api.example.test/widget/config?tenantId=t", rig.server.requests.single().url)
        rig.settle { rig.client.startConversation() }
        assertFalse(rig.server.requests.last().headers.containsKey("X-Giov-Client"))
    }

    @Test
    fun startSendsContactFieldsWithUserIdAndHashResumeSendsSessionSecretAsBearer() {
        val rig = ClientRig()
        val hash = "a".repeat(64)
        val started = rig.settle { rig.client.startConversation(GiovContact(userId = "u1", userHash = hash, email = "a@b.co")) }
        assertEquals(testSession, started)
        val start = rig.server.requests.last()
        assertEquals(json("tenantId" to "t", "userId" to "u1", "userHash" to hash, "email" to "a@b.co"), start.json)
        assertFalse(start.headers.containsKey("Authorization"))

        rig.settle { rig.client.resumeConversation(testSession) }
        val resume = rig.server.requests.last()
        assertEquals("Bearer ${testSession.sessionToken}", resume.headers["Authorization"])
        assertEquals("conv-1", resume.json["conversationId"].asString())
    }

    @Test
    fun refusalsMapToTypedFailures() {
        val rig = ClientRig()
        rig.server.resumeStatus = 409
        rig.assertFails<GiovConversationClosedException> { rig.client.resumeConversation(testSession) }
        rig.server.resumeStatus = 401
        rig.assertFails<GiovSessionExpiredException> { rig.client.resumeConversation(testSession) }
        rig.server.resumeStatus = 404
        assertEquals(404, rig.assertFails<GiovServerException> { rig.client.resumeConversation(testSession) }.status)
        rig.server.startStatus = 503
        assertEquals(30L, rig.assertFails<GiovWorkspaceBusyException> { rig.client.startConversation() }.retryAfterSeconds)
        rig.server.startStatus = 401
        rig.server.startError = "invalid_user_hash"
        rig.assertFails<GiovIdentityRejectedException> { rig.client.startConversation() }
        rig.server.startStatus = 429
        rig.server.startError = "rate_limited"
        rig.assertFails<GiovRateLimitedException> { rig.client.startConversation() }
    }

    @Test
    fun invalidConfigAndSessionResponsesAreRejected() {
        val rig = ClientRig()
        rig.server.config = mapOf("oops" to true)
        rig.assertFails<GiovInvalidResponseException> { rig.client.fetchWidgetConfig() }
    }

    @Test
    fun messagesWrittenBeforeTheSocketOpensGoOutFirstInOrderUnderStableIds() {
        val rig = ClientRig()
        val first = rig.client.send("written offline")
        val second = rig.client.send("and another")
        assertEquals(2, rig.outbox.size)
        rig.connect()
        val socket = rig.sockets.last
        assertEquals("wss://api.example.test/conversations/conv-1/ws?role=user&token=visitor-token-1", socket.url)
        assertEquals("Bearer ${testSession.sessionToken}", rig.server.to("/auth/widget-token").single().headers["Authorization"])
        assertEquals(listOf(first, second), rig.ids(socket.messageFrames()))
        assertEquals(listOf("written offline", "and another"), socket.messageFrames().map { it["content"].asString() })

        socket.receive(
            "type" to "message_ack", "clientMessageId" to first,
            "message" to mapOf("id" to "srv-1", "role" to "visitor", "content" to "written offline", "createdAt" to 5),
        )
        rig.pump()
        assertEquals(listOf(first to "srv-1"), rig.events.acks.map { it.first to it.second.id })
        assertEquals(GiovRole.VISITOR, rig.events.acks.single().second.role)
        assertEquals(listOf(second), rig.outbox.list().map { it.clientMessageId })
        rig.client.dispose()
    }

    @Test
    fun wsIsUsedForALocalHttpApi() {
        val rig = ClientRig(baseUrl = "http://127.0.0.1:8787")
        val done = rig.start { rig.client.connect(testSession) }
        rig.pump()
        assertTrue(rig.sockets.last.url.startsWith("ws://127.0.0.1:8787/conversations/conv-1/ws?role=user&token="))
        rig.sockets.last.accept()
        rig.await(done)
        rig.client.dispose()
    }

    @Test
    fun anAttemptThatHasNotOpenedAfterTenSecondsIsGivenUp() {
        val rig = ClientRig()
        val done = rig.start { rig.client.connect(testSession) }
        rig.elapse(9_000)
        assertNull(rig.failure(done))
        rig.elapse(2_000)
        val failure = rig.failure(done) as GiovNetworkException
        assertTrue(failure.timedOut)
        assertTrue(rig.sockets.last.closedByClient)
        assertFalse(rig.client.isConnected)
    }

    @Test
    fun pingEvery25sAPongKeepsTheSocketAMissingPongWithin10sReconnectsWithAFreshToken() {
        val rig = ClientRig()
        rig.sockets.autoPong = false
        rig.connect()
        val first = rig.sockets.last
        rig.elapse(24_000)
        assertTrue(first.frames("ping").isEmpty())
        rig.elapse(1_000)
        assertEquals(1, first.frames("ping").size)
        first.receive("type" to "pong")
        rig.elapse(11_000)
        assertFalse("the pong arrived", first.closedByClient)
        rig.elapse(14_000) // t = 50 s: second ping
        assertEquals(2, first.frames("ping").size)
        rig.elapse(9_000)
        assertFalse(first.closedByClient)
        rig.elapse(2_000) // t = 61 s; the deadline was 60 s
        assertTrue("no pong within 10 s means the socket is dead", first.closedByClient)
        assertFalse(rig.client.isConnected)
        assertEquals(1, rig.sockets.all.size)
        rig.elapse(499) // 60 s + 1499 ms
        assertEquals("first retry waits 1500 ms", 1, rig.sockets.all.size)
        rig.elapse(2)
        assertEquals(2, rig.sockets.all.size)
        assertTrue("a new visitor token before every connect", rig.sockets.last.url.endsWith("token=visitor-token-2"))
        rig.client.dispose()
    }

    @Test
    fun reconnectBackoffIsMin1500TimesTwoToTheNAnd15000PlusJitter() {
        assertEquals(listOf(1500L, 3000L, 6000L, 12000L, 15000L, 15000L, 15000L), (0 until 7).map { giovReconnectDelayMs(it) })
        assertEquals(1700L, giovReconnectDelayMs(0, 0.5))
        assertEquals(15399L, giovReconnectDelayMs(30, 0.999))
        assertEquals(10_000L, SOCKET_OPEN_TIMEOUT_MS)
        assertEquals(25_000L, SOCKET_PING_INTERVAL_MS)
        assertEquals(10_000L, SOCKET_PONG_TIMEOUT_MS)
    }

    @Test
    fun failedReconnectAttemptsKeepBackingOffTheCounterResetsAfterASuccessfulConnect() {
        val rig = ClientRig()
        rig.connect()
        rig.sockets.last.drop()
        rig.pump()
        val times = mutableListOf<Long>()
        var seen = 1
        var i = 0
        while (i < 400 && times.size < 4) {
            rig.elapse(100)
            if (rig.sockets.all.size > seen) {
                seen = rig.sockets.all.size
                times += rig.elapsedMs
            }
            i++
        }
        val gaps = times.zipWithNext { a, b -> b - a }
        // gap = open timeout (10 s) + next backoff wait (3000, 6000, ...), measured at 100 ms resolution
        assertEquals(13_000.0, gaps[0].toDouble(), 150.0)
        assertEquals(16_000.0, gaps[1].toDouble(), 150.0)
        rig.client.dispose()
    }

    @Test
    fun aMessageWithNoAckWithin10sMeansADeadSocketReconnectAndResendUnderTheSameId() {
        val rig = ClientRig()
        rig.connect()
        val id = rig.client.send("are you there?")
        val first = rig.sockets.last
        assertEquals(1, first.messageFrames().size)
        rig.elapse(9_000)
        assertFalse(first.closedByClient)
        rig.elapse(4_000)
        assertTrue(first.closedByClient)
        assertEquals("unacknowledged stays in the outbox", OutboxState.QUEUED, rig.outbox[id]!!.state)
        rig.elapse(2_000)
        rig.sockets.last.accept()
        rig.pump()
        assertEquals(2, rig.sockets.all.size)
        assertEquals(listOf(id), rig.ids(rig.sockets.last.messageFrames()))
        rig.client.dispose()
    }

    @Test
    fun closeCode1000ConversationDeletedEndsTheConnectionWithAClosedFailureAndNoRetry() {
        val rig = ClientRig()
        rig.connect()
        rig.sockets.last.drop(1000, "Conversation deleted")
        rig.elapse(60_000)
        assertTrue(rig.events.errors.single() is GiovConversationClosedException)
        assertEquals(1, rig.sockets.all.size)
    }

    @Test
    fun aRefusedUpgradeCountsAsDeletedOnlyForHttp410NeverBySearchingTheErrorText() {
        // 410 on the upgrade: the conversation is gone.
        val gone = ClientRig()
        val done = gone.start { gone.client.connect(testSession) }
        gone.pump()
        gone.sockets.last.fail(410, "Expected HTTP 101 response but was '410 Gone'")
        assertTrue(gone.failure(done) is GiovConversationClosedException)
        gone.elapse(60_000)
        assertEquals("no retry for a deleted conversation", 1, gone.sockets.all.size)

        // Any other failure whose text contains 410 (a conversation id does) is just a dropped connection.
        val id = "9c410f2e-4100-4abc-8410-000000000410"
        val other = ClientRig()
        val attempt = other.start { other.client.connect(testSession) }
        other.pump()
        other.sockets.last.fail(null, "Failed to connect to /conversations/$id/ws: Connection reset (410)")
        assertTrue(other.failure(attempt) is GiovNetworkException)
        assertTrue(other.events.errors.isEmpty())
        // 401 / 500 are not deletions either, and the failed first attempt does not retry on its own.
        val refused = ClientRig()
        val third = refused.start { refused.client.connect(testSession) }
        refused.pump()
        refused.sockets.last.fail(401)
        assertTrue(refused.failure(third) is GiovNetworkException)
    }

    @Test
    fun anOpenSocketThatFailsLaterReconnects() {
        val rig = ClientRig()
        rig.connect()
        rig.sockets.last.fail(null)
        rig.elapse(1_600)
        assertEquals(2, rig.sockets.all.size)
        rig.sockets.last.accept()
        rig.pump()
        // OkHttp reports the 101 upgrade response for a failure after the open.
        rig.sockets.last.fail(101)
        rig.elapse(1_600)
        assertEquals("a failure after the open is a drop", 3, rig.sockets.all.size)
        rig.client.dispose()
    }

    @Test
    fun saveFailuresRetryAfter2sAnd4sQuietlyThenAreReportedOnceAVisitorRetrySendsItAgain() {
        val rig = ClientRig()
        rig.connect()
        val socket = rig.sockets.last
        val id = rig.client.send("hello")
        fun failed() = socket.receive("type" to "error", "error" to "message_failed", "clientMessageId" to id).also { rig.pump() }
        failed()
        assertEquals(OutboxState.QUEUED, rig.outbox[id]!!.state)
        rig.elapse(1_999)
        assertEquals(1, socket.messageFrames().size)
        rig.elapse(2)
        assertEquals("resent after 2 s", 2, socket.messageFrames().size)
        failed()
        rig.elapse(4_001)
        assertEquals("resent after 4 s", 3, socket.messageFrames().size)
        assertTrue("failures are retried quietly", rig.events.errors.isEmpty())
        failed()
        assertEquals(OutboxState.FAILED, rig.outbox[id]!!.state)
        assertTrue(rig.events.errors.single() is GiovSendFailedException)
        rig.elapse(20_000)
        assertEquals("not sent again on its own", 3, socket.messageFrames().size)
        assertTrue(rig.client.retryMessage(id))
        assertEquals(4, socket.messageFrames().size)
        assertEquals(setOf<String?>(id), rig.ids(socket.messageFrames()).toSet())
        rig.client.dispose()
    }

    @Test
    fun refusalsFailTheOldestMessageOnTheSocketRateLimitsOnlyDelay() {
        val rig = ClientRig()
        rig.connect()
        val socket = rig.sockets.last
        val a = rig.client.send("a")
        val b = rig.client.send("b")
        socket.receive("type" to "error", "error" to "invalid_message")
        rig.pump()
        assertEquals(OutboxState.FAILED, rig.outbox[a]!!.state)
        assertEquals(OutboxState.SENDING, rig.outbox[b]!!.state)
        socket.receive("type" to "error", "error" to "rate_limited")
        rig.pump()
        assertEquals("rate limited: sent again a little later", OutboxState.QUEUED, rig.outbox[b]!!.state)
        rig.elapse(3_000)
        assertEquals(2, rig.ids(socket.messageFrames()).count { it == b })
        rig.client.dispose()
    }

    @Test
    fun streamedRepliesArriveAsWholeTextSoFarTheSavedReplyTakesTheDraftKeyStreamEndAloneDropsADraft() {
        val rig = ClientRig()
        rig.connect()
        val socket = rig.sockets.last
        socket.receive("type" to "stream", "streamId" to "s1", "content" to "Hel")
        socket.receive("type" to "stream", "streamId" to "s1", "content" to "Hello **there**")
        socket.receive(
            "id" to "m1", "role" to "agent", "content" to "Hello **there**", "createdAt" to 3,
            "isBot" to true, "isAutomated" to true, "streamId" to "s1",
        )
        socket.receive("type" to "stream_end", "streamId" to "s2")
        rig.pump()
        assertEquals(listOf("s1:Hel", "s1:Hello **there**", "s1:end", "s2:end"), rig.events.streams)
        val message = rig.events.messages.single()
        assertEquals("stream:s1", message.clientMessageId)
        assertTrue(message.isBot)
        assertEquals("stream:s1", message.key)
        rig.client.dispose()
    }

    @Test
    fun typingAgentFramesAreReportedTheVisitorSendsTypingAndTypingEndOnceTheSocketIsOpen() {
        val rig = ClientRig()
        rig.client.sendTyping()
        rig.connect()
        val socket = rig.sockets.last
        rig.client.sendTyping()
        rig.client.sendTypingEnd()
        assertEquals("nothing is sent before the socket opens", listOf<String?>("typing", "typing_end"), socket.types())
        socket.receive("type" to "typing", "role" to "agent", "isBot" to true)
        socket.receive("type" to "typing_end", "role" to "agent")
        socket.receive("type" to "typing", "role" to "visitor")
        rig.pump()
        assertEquals(listOf("true/true", "false/null"), rig.events.typing)
        rig.client.dispose()
    }

    @Test
    fun agentRepliesKeepTheirPhotoAndAttachmentsUnknownFramesAreIgnored() {
        val rig = ClientRig()
        rig.connect()
        val socket = rig.sockets.last
        socket.receive("type" to "something_new", "x" to 1)
        socket.receiveRaw("not json")
        socket.receive(
            "id" to "m2", "role" to "agent", "content" to "", "createdAt" to 4, "senderName" to "Ada",
            "senderAvatar" to "https://cdn.test/ada.png",
            "attachmentId" to JsonArray(listOf(jsonValue("https://api.example.test/conversations/conv-1/attachments/a.png"))).toString(),
            "attachmentPreviews" to listOf(mapOf("width" to 400, "height" to 300)),
        )
        rig.pump()
        val message = rig.events.messages.single()
        assertEquals("https://cdn.test/ada.png", message.senderAvatar)
        assertEquals("https://api.example.test/conversations/conv-1/attachments/a.png", message.attachments.single().url)
        assertEquals(4.0 / 3, message.attachments.single().preview!!.aspectRatio!!, 0.001)
        rig.client.dispose()
    }

    @Test
    fun visitorTokensAreRenewedWhenLessThanAMinuteIsLeftKeptOtherwise() {
        val rig = ClientRig()
        rig.connect()
        assertEquals(1, rig.server.count("token"))
        rig.settle { rig.client.fetchHistory() }
        assertEquals("the cached token is reused", 1, rig.server.count("token"))
        rig.elapse(14 * 60_000L + 10_000) // 50 s left
        rig.settle { rig.client.fetchHistory() }
        assertEquals(2, rig.server.count("token"))
        assertEquals("Bearer visitor-token-2", rig.server.to("/conversations/conv-1/history").last().headers["Authorization"])
        rig.client.dispose()
    }

    @Test
    fun a401RenewsTheTokenOnceAndRetriesASecond401MeansTheSessionIsGone() {
        val rig = ClientRig()
        rig.connect()
        rig.server.historyStatuses += 401
        rig.settle { rig.client.fetchHistory() }
        assertEquals(2, rig.server.count("token"))
        assertEquals("one rejected, one retried", 2, rig.server.count("history"))
        rig.server.historyStatuses += listOf(401, 401)
        rig.assertFails<GiovSessionExpiredException> { rig.client.fetchHistory() }
        rig.client.dispose()
    }

    @Test
    fun aTokenRequestInFlightIsSharedNotRepeated() {
        val rig = ClientRig()
        rig.connect()
        rig.elapse(14 * 60_000L + 10_000)
        val before = rig.server.count("token")
        val a = rig.start { rig.client.fetchHistory() }
        val b = rig.start { rig.client.fetchHistory() }
        rig.await(a)
        rig.await(b)
        assertEquals(before + 1, rig.server.count("token"))
        rig.client.dispose()
    }

    @Test
    fun history404SurfacesAs404HistoryParsesMessagesAssigneeAndAccessToken() {
        val rig = ClientRig()
        rig.server.assignedAgentName = "Ada"
        rig.server.assignedAgentAvatar = "https://cdn.test/ada.png"
        rig.server.history = listOf(
            mapOf("id" to "h1", "role" to "user", "content" to "Hi", "created_at" to 1),
            mapOf("id" to "h2", "role" to "agent", "content" to "Hello!", "created_at" to 2, "is_bot" to 0, "is_automated" to 0),
        )
        rig.connect()
        val history = rig.settle { rig.client.fetchHistory() }
        assertEquals(listOf("h1", "h2"), history.messages.map { it.id })
        assertEquals(GiovRole.VISITOR, history.messages.first().role)
        assertEquals("history rows carry no sender; the assignee is used", "Ada", history.messages.last().senderName)
        assertEquals("https://cdn.test/ada.png", history.assignedAgentAvatar)
        assertNotNull(rig.events.access.lastOrNull())
        rig.server.historyStatuses += 404
        assertEquals(404, rig.assertFails<GiovServerException> { rig.client.fetchHistory() }.status)
        rig.client.dispose()
    }

    @Test
    fun uploadReportsProgressSendsTheFileAndAPlaceholderA401RetriesWithANewToken() {
        val rig = ClientRig()
        rig.connect()
        val file = GiovPickedFile.bytes(
            "a.png", "image/png", ByteArray(1000) { 7 }, width = 40, height = 30,
            thumbnail = "data:image/jpeg;base64,AAAA",
        )
        val progress = mutableListOf<Double>()
        rig.server.uploadStatuses += 401
        val result = rig.settle { rig.client.uploadAttachment(file) { progress += it } }
        assertTrue(result.url.endsWith("/attachments/a.png"))
        assertEquals("a.png", result.filename)
        assertTrue(progress.isNotEmpty())
        assertEquals(1.0, progress.last(), 0.0)
        val uploads = rig.server.to("/conversations/conv-1/upload-visitor")
        assertEquals(2, uploads.size)
        assertEquals("Bearer visitor-token-1", uploads.first().headers["Authorization"])
        assertEquals("Bearer visitor-token-2", uploads.last().headers["Authorization"])
        val body = uploads.last().upload!!
        assertEquals("image/png", body.file.mimeType)
        assertEquals(listOf(40, 30), listOf(body.width, body.height))
        assertEquals("data:image/jpeg;base64,AAAA", body.thumbnail)
        rig.client.dispose()
    }

    @Test
    fun uploadAPlaceholderIsSentOnlyWhenAllThreeFieldsAreValid() {
        val rig = ClientRig()
        rig.connect()
        rig.settle { rig.client.uploadAttachment(GiovPickedFile.bytes("a.png", "image/png", ByteArray(4), width = 40, thumbnail = "data:image/jpeg;base64,AAAA")) }
        assertNull(rig.server.requests.last().upload!!.thumbnail)
        rig.settle { rig.client.uploadAttachment(GiovPickedFile.bytes("a.pdf", "application/pdf", ByteArray(4), width = 1, height = 1, thumbnail = "data:image/jpeg;base64,AAAA")) }
        assertNull(rig.server.requests.last().upload!!.thumbnail)
        rig.client.dispose()
    }

    @Test
    fun uploadARefusalIsAnAttachmentRejection() {
        val rig = ClientRig()
        rig.connect()
        val file = GiovPickedFile.bytes("a.exe", "application/x-msdownload", ByteArray(4))
        rig.server.uploadStatuses += 400
        rig.assertFails<GiovAttachmentRejectedException> { rig.client.uploadAttachment(file) }
        rig.client.dispose()
    }

    @Test
    fun pushRegistrationSendsTokenPlatformAndApnsEnvironmentWithTheVisitorToken() {
        val rig = ClientRig()
        val hex = "ab".repeat(32)
        assertEquals(
            "nothing to register for before a conversation",
            GiovPushFailure.NO_CONVERSATION,
            rig.settle { rig.client.registerPushToken(hex, GiovPushPlatform.IOS) }.failure,
        )
        rig.connect()
        val result = rig.settle {
            rig.client.registerPushToken(hex, GiovPushPlatform.IOS, GiovPushOptions(GiovPushEnvironment.SANDBOX, "com.acme.app", "fr"))
        }
        assertTrue(result.registered)
        val request = rig.server.to("/conversations/conv-1/push-token", "POST").single()
        assertEquals("Bearer visitor-token-1", request.headers["Authorization"])
        assertEquals("the device is stored with the SDK version", "android/${GiovChatVersion.NAME}", request.headers["X-Giov-Client"])
        assertEquals(
            json("token" to hex, "platform" to "ios", "environment" to "sandbox", "appId" to "com.acme.app", "locale" to "fr"),
            request.json,
        )
        assertTrue(rig.settle { rig.client.unregisterPushToken(hex) })
        assertEquals(json("token" to hex), rig.server.to("/conversations/conv-1/push-token", "DELETE").single().json)
        rig.client.dispose()
    }

    @Test
    fun unregisteringWithoutAnOpenConnectionUsesTheStoredSessionAndAFreshVisitorToken() {
        val rig = ClientRig()
        assertFalse("nothing known, nothing to unregister", rig.settle { rig.client.unregisterPushToken("fcm-token") })
        assertTrue(rig.server.requests.isEmpty())
        assertTrue(rig.settle { rig.client.unregisterPushToken("fcm-token", testSession) })
        val token = rig.server.to("/auth/widget-token").single()
        assertEquals("Bearer ${testSession.sessionToken}", token.headers["Authorization"])
        val request = rig.server.to("/conversations/conv-1/push-token", "DELETE").single()
        assertEquals("Bearer visitor-token-1", request.headers["Authorization"])
        assertEquals(json("token" to "fcm-token"), request.json)
        assertTrue("no socket was opened for it", rig.sockets.all.isEmpty())
        rig.client.dispose()
    }

    @Test
    fun pushRegistrationDegradesCleanlyOnAServerWithoutItAndNeverAsksAgain() {
        val rig = ClientRig()
        rig.server.pushStatus = 404
        rig.connect()
        assertEquals(GiovPushFailure.UNSUPPORTED, rig.settle { rig.client.registerPushToken("device-token", GiovPushPlatform.ANDROID) }.failure)
        val before = rig.server.requests.size
        assertEquals(GiovPushFailure.UNSUPPORTED, rig.settle { rig.client.registerPushToken("device-token", GiovPushPlatform.ANDROID) }.failure)
        assertEquals("an unsupported server is not asked again", before, rig.server.requests.size)
        assertFalse(rig.settle { rig.client.unregisterPushToken("device-token") })
        for (status in listOf(405, 501)) {
            val other = ClientRig()
            other.server.pushStatus = status
            other.connect()
            assertEquals(GiovPushFailure.UNSUPPORTED, other.settle { other.client.registerPushToken("t", GiovPushPlatform.FCM) }.failure)
            other.client.dispose()
        }
        val failing = ClientRig()
        failing.server.pushStatus = 500
        failing.connect()
        assertEquals(GiovPushFailure.FAILED, failing.settle { failing.client.registerPushToken("t", GiovPushPlatform.FCM) }.failure)
        assertEquals(GiovPushFailure.FAILED, failing.settle { failing.client.registerPushToken("", GiovPushPlatform.FCM) }.failure)
        rig.client.dispose()
        failing.client.dispose()
    }

    @Test
    fun disconnectKeepsUnsentMessagesANewConnectionSendsThemUnderTheSameIds() {
        val rig = ClientRig()
        rig.connect()
        val id = rig.client.send("hello")
        assertEquals(1, rig.sockets.last.messageFrames().size)
        rig.client.disconnect()
        assertEquals(OutboxState.QUEUED, rig.outbox[id]!!.state)
        val next = GiovChatClient("t", "https://api.example.test", rig.outbox, true, null, rig.server, rig.sockets, rig.scope, rig.now)
        val done = rig.start { next.connect(testSession) }
        rig.pump()
        rig.sockets.last.accept()
        rig.await(done)
        assertEquals(listOf(id), rig.ids(rig.sockets.last.messageFrames()))
        next.dispose()
    }

    @Test
    fun aDroppedSocketOfAnOpenedConnectionIsReportedAndRetriedByTheClient() {
        val rig = ClientRig()
        rig.connect()
        rig.sockets.last.drop()
        rig.pump()
        assertEquals(listOf(true, false), rig.events.connection.takeLast(2))
        rig.elapse(1_500)
        assertEquals(2, rig.sockets.all.size)
        rig.sockets.last.accept()
        rig.pump()
        assertTrue(rig.client.isConnected)
        assertEquals(true, rig.events.connection.last())
        rig.client.dispose()
    }

    @Test
    fun reconnectNowSkipsTheBackoff() {
        val rig = ClientRig()
        rig.connect()
        rig.sockets.last.drop()
        rig.pump()
        assertEquals(1, rig.sockets.all.size)
        rig.client.reconnectNow()
        rig.pump()
        assertEquals(2, rig.sockets.all.size)
        rig.client.dispose()
    }

    @Test
    fun baseUrlsAreValidated() {
        assertEquals("https://api.example.test", normalizeBaseUrl("https://api.example.test/"))
        for (bad in listOf("ftp://x.test", "https://u:p@x.test", "https://x.test?a=1", "not a url", "https://x.test#f")) {
            try {
                normalizeBaseUrl(bad)
                throw AssertionError("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
