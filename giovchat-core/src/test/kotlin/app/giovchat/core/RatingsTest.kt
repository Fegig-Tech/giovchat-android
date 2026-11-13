package app.giovchat.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SOON = EPOCH + 3_600_000L
private const val LATER = EPOCH + 86_400_000L
private const val TEXT = "How did we do? Please rate this conversation."

private typealias Answer = Triple<Int, Map<String, Any?>, Map<String, String>>

private fun card(
    id: String = "r1",
    request: String = "req-1",
    closesAt: Long = SOON,
    createdAt: Long = 20,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = mapOf(
    "id" to id,
    "role" to "agent",
    "content" to TEXT,
    "createdAt" to createdAt,
    "senderName" to "Ada",
    "blocks" to listOf(mapOf("type" to "rating", "requestId" to request, "closesAt" to closesAt) + extra),
)

private fun saved(request: String = "req-1", score: Int, comment: String? = null, closesAt: Long = LATER): Answer =
    Triple(200, mapOf("requestId" to request, "score" to score, "comment" to comment, "closesAt" to closesAt, "ratedAt" to EPOCH), emptyMap())

private fun message(id: String, request: String, score: Int? = null, replaced: Boolean = false) =
    GiovMessage(id, GiovRole.AGENT, TEXT, 1, rating = GiovRatingBlock(request, SOON, score = score, replaced = replaced))

private fun CRig.deliver(message: Map<String, Any?>) {
    socket.receiveRaw(jsonValue(message).toString())
    pump()
}

private fun CRig.block(id: String = "r1"): GiovRatingBlock? = ui.messages.firstOrNull { it.id == id }?.rating

class RatingParsingTest {
    @Test
    fun aRatingBlockIsReadFromAMessageAndOtherBlocksAreIgnored() {
        val message = GiovMessage.fromJson(
            jsonValue(
                mapOf(
                    "id" to "m", "role" to "agent", "content" to TEXT, "createdAt" to 5,
                    "blocks" to listOf(
                        mapOf("type" to "buttons", "buttons" to emptyList<Any>()),
                        mapOf("type" to "form", "title" to "x"),
                        mapOf("type" to "rating", "requestId" to "req-1", "closesAt" to SOON, "url" to "https://giov.test/rate/req-1#k=abc"),
                        mapOf("type" to "from-the-future", "x" to 1),
                    ),
                ),
            ),
        )!!
        assertEquals(GiovRatingBlock("req-1", SOON, url = "https://giov.test/rate/req-1#k=abc"), message.rating)
        assertTrue("the fragment is kept as given, never parsed", message.rating!!.url!!.contains("#k="))
    }

    @Test
    fun aBlockWithoutRequestIdOrDeadlineAndNonRatingBlocksAreNotRatings() {
        for (block in listOf(
            mapOf("type" to "rating", "closesAt" to SOON),
            mapOf("type" to "rating", "requestId" to "r"),
            mapOf("type" to "rating", "requestId" to "", "closesAt" to SOON),
            mapOf("type" to "card", "requestId" to "r", "closesAt" to SOON),
        )) {
            assertNull(GiovRatingBlock.fromJson(jsonValue(block)))
        }
        assertNull(GiovMessage.fromJson(jsonValue(mapOf("id" to "m", "role" to "agent", "content" to "x", "createdAt" to 1)))!!.rating)
    }

    @Test
    fun theAnswerComesWithTheBlockAndAnOutOfRangeScoreIsIgnored() {
        val answered = GiovRatingBlock.fromJson(jsonValue(mapOf("type" to "rating", "requestId" to "r", "closesAt" to SOON, "score" to 4, "comment" to "Quick")))!!
        assertEquals(4, answered.score)
        assertEquals("Quick", answered.comment)
        for (bad in listOf(0, 6, -1)) {
            val block = GiovRatingBlock.fromJson(jsonValue(mapOf("type" to "rating", "requestId" to "r", "closesAt" to SOON, "score" to bad, "comment" to "x")))!!
            assertNull(block.score)
            assertNull(block.comment)
        }
        assertNull(GiovRatingBlock.fromJson(jsonValue(mapOf("type" to "rating", "requestId" to "r", "closesAt" to SOON, "score" to 3.5)))!!.score)
        assertTrue(GiovRatingBlock.fromJson(jsonValue(mapOf("type" to "rating", "requestId" to "r", "closesAt" to SOON, "state" to "replaced")))!!.replaced)
    }

    @Test
    fun aGiovBotVoteComesWithHistoryRowsOfBotAnswersOnly() {
        fun row(vararg extra: Pair<String, Any?>) = GiovMessage.fromJson(json("id" to "b", "role" to "agent", "content" to "x", "createdAt" to 1, *extra))!!
        assertEquals(GiovAnswerVote.DOWN, row("isBot" to true, "feedback" to "down").feedback)
        assertNull(row("feedback" to "up").feedback)
        assertNull(row("isBot" to true, "feedback" to "maybe").feedback)
    }

    @Test
    fun theFrameIsParsedDefensively() {
        fun frame(text: String) = GiovRatingFrame.fromJson(parseJsonObject(text))
        assertEquals(
            GiovRatingFrame("r", "m", 5, 5, "ok"),
            frame("""{"type":"rating","conversationId":"c","requestId":"r","messageId":"m","closesAt":5,"score":5,"comment":"ok","extra":1}"""),
        )
        val none = frame("""{"type":"rating","requestId":"r","closesAt":5,"comment":"orphan"}""")!!
        assertNull(none.score)
        assertNull(none.comment)
        assertNull(none.messageId)
        assertTrue(frame("""{"type":"rating","requestId":"r","closesAt":5,"state":"replaced"}""")!!.replaced)
        assertNull(frame("""{"type":"rating","closesAt":5}"""))
        assertNull(frame("""{"type":"rating","requestId":"r"}"""))
        assertNull(frame("""{"type":"message","requestId":"r","closesAt":5}"""))
    }

    @Test
    fun aFramePatchesOnlyTheCardItNames() {
        val list = listOf(message("a", "r1"), message("b", "r2"), GiovMessage("c", GiovRole.AGENT, "hi", 3))
        val answered = patchRating(list, GiovRatingFrame("r2", "b", LATER, 5, "yes"))
        assertEquals(list[0], answered[0])
        assertEquals(list[2], answered[2])
        assertEquals(5, answered[1].rating!!.score)
        assertEquals("yes", answered[1].rating!!.comment)
        assertEquals(LATER, answered[1].rating!!.closesAt)
        assertEquals("a frame for another message id leaves it alone", list, patchRating(list, GiovRatingFrame("r2", "zzz", LATER, 5)))
        assertEquals("one without a score clears the answer", listOf(null, null, null), patchRating(answered, GiovRatingFrame("r2", null, SOON)).map { it.rating?.score })
        val replaced = patchRating(list, GiovRatingFrame("r1", "a", SOON, replaced = true))
        assertTrue(replaced[0].rating!!.replaced)
        assertFalse(replaced[1].rating!!.replaced)
    }

    @Test
    fun aNewerCardHidesOlderUnansweredOnesButNotAnsweredOnes() {
        val list = listOf(message("a", "r1"), message("b", "r2", score = 4), message("c", "r3"))
        assertEquals(listOf(true, false, false), replaceOlderRatings(list).map { it.rating!!.replaced })
        assertFalse("a single card stays", replaceOlderRatings(listOf(message("a", "r1"))).single().rating!!.replaced)
    }

    @Test
    fun theOpenRequestIsTheNewestCardNotReplacedAndNotPastItsDeadline() {
        val list = listOf(message("a", "r1"), message("b", "r2", replaced = true))
        assertEquals("r1", openRatingRequest(list, EPOCH)?.requestId)
        assertNull("closed at closesAt", openRatingRequest(list, SOON))
        assertNull(openRatingRequest(emptyList(), EPOCH))
    }

    @Test
    fun thumbsGoUnderFinishedBotAnswersOnly() {
        assertTrue(GiovMessage("1", GiovRole.AGENT, "x", 1, isBot = true).allowsAnswerFeedback)
        assertFalse(GiovMessage("1", GiovRole.AGENT, "x", 1, isBot = true, isStreaming = true).allowsAnswerFeedback)
        assertFalse(GiovMessage("1", GiovRole.AGENT, "x", 1).allowsAnswerFeedback)
        assertFalse(GiovMessage("1", GiovRole.VISITOR, "x", 1).allowsAnswerFeedback)
        val botCard = GiovMessage("1", GiovRole.AGENT, TEXT, 1, isBot = true, rating = GiovRatingBlock("r", SOON))
        assertFalse("the rating card GiovBot sends gets no thumbs", botCard.allowsAnswerFeedback)
        assertEquals(null, giovNextVote(GiovAnswerVote.UP, GiovAnswerVote.UP))
        assertEquals(GiovAnswerVote.DOWN, giovNextVote(GiovAnswerVote.UP, GiovAnswerVote.DOWN))
    }
}

class RatingCardStateTest {
    private val block = GiovRatingBlock("r", 1000)

    @Test
    fun pendingAnsweredChangedClosedReplaced() {
        assertEquals(GiovRatingCardState.Form(null, "", false), GiovRatingCardState.make(block, 0))
        assertEquals(GiovRatingCardState.Form(3, "hm", false), GiovRatingCardState.make(block, 0, selected = 3, comment = "hm"))
        val answered = block.copy(score = 4, comment = "Quick")
        assertEquals(GiovRatingCardState.Answered(4, "Quick", true), GiovRatingCardState.make(answered, 0))
        assertEquals(GiovRatingCardState.Form(4, "", true), GiovRatingCardState.make(answered, 0, editing = true, selected = 4))
        assertEquals(GiovRatingCardState.Answered(4, "Quick", false), GiovRatingCardState.make(answered, 1000))
        assertEquals(GiovRatingCardState.Answered(4, "Quick", false), GiovRatingCardState.make(answered, 1000, editing = true))
        assertEquals(GiovRatingCardState.Closed, GiovRatingCardState.make(block, 1000))
        assertEquals(GiovRatingCardState.Hidden, GiovRatingCardState.make(answered.copy(replaced = true), 0))
        assertEquals(GiovRatingCardState.Hidden, GiovRatingCardState.make(block, 0, lock = GiovRatingFailure.Replaced))
    }

    @Test
    fun aRefusalLocksTheCard() {
        val answered = block.copy(score = 2)
        assertEquals(GiovRatingCardState.Answered(2, null, false), GiovRatingCardState.make(answered, 0, lock = GiovRatingFailure.Closed))
        assertEquals(GiovRatingCardState.Answered(2, null, false), GiovRatingCardState.make(answered, 0, lock = GiovRatingFailure.TooManyChanges))
        assertEquals(GiovRatingCardState.Closed, GiovRatingCardState.make(block, 0, lock = GiovRatingFailure.Closed))
        assertEquals(GiovRatingCardState.Form(null, "", false), GiovRatingCardState.make(block, 0, lock = GiovRatingFailure.Retry))
    }

    @Test
    fun facesWrapAroundAndTextIsClamped() {
        assertEquals(2, GiovRatingCardState.step(null, 1))
        assertEquals(1, GiovRatingCardState.step(5, 1))
        assertEquals(5, GiovRatingCardState.step(1, -1))
        assertEquals(2, GiovRatingCardState.step(3, -1))
        assertEquals(1000, GiovRatingCardState.clampComment("a".repeat(1200)).length)
        assertEquals("short", GiovRatingCardState.clampComment("short"))
        // an emoji is two UTF-16 units and is never split
        val cut = GiovRatingCardState.clampComment("😀".repeat(600))
        assertEquals(1000, cut.length)
        assertEquals(500, cut.codePointCount(0, cut.length))
        val odd = GiovRatingCardState.clampComment("a" + "😀".repeat(600))
        assertEquals("never ends inside a pair", 999, odd.length)
        assertEquals(300, GiovRatingCardState.clampReason("b".repeat(400)).length)
    }

    @Test
    fun refusalsMapToTheirFailure() {
        fun failure(status: Int, code: String?) = GiovServerException("x", status, code).ratingFailure
        assertEquals(GiovRatingFailure.Closed, failure(409, "rating_closed"))
        assertEquals(GiovRatingFailure.Replaced, failure(409, "request_replaced"))
        assertEquals(GiovRatingFailure.TooManyChanges, GiovRateLimitedException("x", 30, "too_many_changes").ratingFailure)
        assertEquals(GiovRatingFailure.Invalid, failure(400, "invalid_rating"))
        assertEquals(GiovRatingFailure.Invalid, failure(400, "invalid_feedback"))
        assertEquals(GiovRatingFailure.NotFound, failure(404, "request_not_found"))
        assertEquals(GiovRatingFailure.NotFound, failure(404, "message_not_found"))
        assertEquals(GiovRatingFailure.RateLimited(12), GiovRateLimitedException("x", 12).ratingFailure)
        assertEquals(GiovRatingFailure.Unsupported, failure(404, "not_found"))
        assertEquals(GiovRatingFailure.Unsupported, failure(404, null))
        assertEquals(GiovRatingFailure.Retry, failure(500, null))
        assertEquals(GiovRatingFailure.Retry, GiovNetworkException("x").ratingFailure)
        assertEquals(GiovRatingFailure.Retry, GiovSessionExpiredException("x", 401).ratingFailure)
    }

    @Test
    fun everyLanguageHasFiveFacesAndAllText() {
        for ((code, strings) in GIOV_STRINGS) {
            val r = strings.rating
            assertEquals(code, 5, r.faces.size)
            assertEquals("$code faces are distinct", 5, r.faces.toSet().size)
            assertTrue(code, r.faces.none { it.isBlank() })
            val plain = listOf(r.title, r.yourRating, r.commentPlaceholder, r.send, r.cancel, r.change, r.closed, r.couldNotSend, r.goodAnswer, r.badAnswer, r.rateAnswer, r.reasonPlaceholder, r.skip, r.couldNotSave, r.thanks(r.face(4)))
            for (text in plain) assertTrue(code, text.isNotBlank())
            assertTrue(code, r.thanks("X").contains("X"))
            assertEquals("", r.face(0))
            assertEquals("", r.face(6))
        }
        assertEquals(listOf("Very bad", "Bad", "Okay", "Good", "Great"), GIOV_STRINGS.getValue("en").rating.faces)
        assertNull(GiovRatingStrings.EN.describe(GiovRatingFailure.Closed))
        assertEquals("Couldn't send. Please try again.", GiovRatingStrings.EN.describe(GiovRatingFailure.Retry))
        assertEquals("Envoyer", GIOV_STRINGS.getValue("en").withOverrides(rating = GiovRatingStrings.FR).rating.send)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RatingControllerTest {
    private fun botAnswer(id: String = "b1") = mapOf("id" to id, "role" to "agent", "content" to "Try restarting", "createdAt" to 10, "isBot" to true)

    private fun ready(rig: CRig) {
        rig.start()
        rig.begin()
    }

    @Test
    fun aCardFromTheTeamIsReportedOnceAndKnownAsThePendingRequest() {
        val rig = CRig()
        ready(rig)
        assertNull(rig.controller.pendingRating)
        rig.deliver(card())
        assertEquals(GiovRatingRequest("r1", "req-1", SOON), rig.controller.pendingRating)
        assertEquals("r1", rig.ui.ratingRequest!!.messageId)
        assertFalse(rig.ui.ratingRequest!!.isAnswered)
        rig.deliver(card())
        rig.dispose()
    }

    @Test
    fun theRequestedCallbackFiresOncePerRequest() {
        val requested = mutableListOf<GiovRatingRequest>()
        val rig = CRig()
        val controller = GiovChatController(
            tenantKey = "t", baseUrl = "https://api.example.test", sessionStore = RecordingStore(), locale = "en",
            onRatingRequested = { requested += it },
            http = rig.server, socketFactory = rig.sockets, dispatcher = rig.dispatcher, clock = rig.now, random = NoJitter,
        )
        rig.start { controller.start() }
        rig.pump()
        assertTrue(rig.settle { controller.send("Hi") })
        rig.pump()
        rig.socket.accept()
        rig.pump()
        rig.deliver(card())
        rig.deliver(card())
        assertEquals(listOf(GiovRatingRequest("r1", "req-1", SOON)), requested)
        controller.close()
        rig.pump()
        rig.dispose()
    }

    @Test
    fun answeringShowsAtOnceSendsTheVisitorTokenAndReportsTheResult() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())
        rig.server.ratingResponder = { saved(score = 4, comment = "Quick answer") }
        val result = rig.settle { rig.controller.submitRating(4, "  Quick answer ") }
        assertEquals(GiovRatingResult("req-1", 4, "Quick answer", LATER, EPOCH), result)
        val sent = rig.server.to("/conversations/conv-1/rating", "POST").last()
        assertEquals(json("requestId" to "req-1", "score" to 4, "comment" to "Quick answer"), sent.json)
        assertTrue(sent.headers["Authorization"]!!.startsWith("Bearer visitor-token-"))
        val block = rig.block()!!
        assertEquals(4, block.score)
        assertEquals("Quick answer", block.comment)
        assertEquals(LATER, block.closesAt)
        assertTrue(rig.ui.ratingRequest!!.isAnswered)
        rig.dispose()
    }

    @Test
    fun theVisitorMayChangeTheAnswerAndTheOpenCardIsUsedWithoutARequestId() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())
        rig.server.ratingResponder = { request -> saved(score = request.json["score"].asNumber()!!.toInt()) }
        rig.settle { rig.controller.submitRating(3) }
        rig.settle { rig.controller.submitRating(5) }
        val sent = rig.server.to("/conversations/conv-1/rating", "POST")
        assertEquals(listOf(3.0, 5.0), sent.map { it.json["score"].asNumber() })
        assertTrue(sent.all { it.json["requestId"].asString() == "req-1" })
        assertTrue("no comment, none sent", sent.all { it.json["comment"] == null })
        assertEquals(5, rig.block()!!.score)
        assertNull(rig.block()!!.comment)
        rig.dispose()
    }

    @Test
    fun aFailureRollsTheAnswerBackAndTheReasonIsTypedPerCode() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())

        rig.server.ratingResponder = { Triple(500, mapOf("error" to "boom"), emptyMap()) }
        var error = rig.assertFails<GiovChatException> { rig.controller.submitRating(2) }
        assertEquals(GiovRatingFailure.Retry, error.ratingFailure)
        assertNull("rolled back", rig.block()!!.score)
        assertEquals(GiovRatingPhase.OPEN, rig.block()!!.phaseAt(rig.now()))

        rig.server.ratingResponder = { Triple(429, mapOf("error" to "too_many_changes"), mapOf("retry-after" to "30")) }
        error = rig.assertFails { rig.controller.submitRating(2) }
        assertEquals(GiovRatingFailure.TooManyChanges, error.ratingFailure)
        assertEquals("read-only after too many changes", GiovRatingPhase.CLOSED, rig.block()!!.phaseAt(rig.now()))

        rig.server.ratingResponder = { Triple(409, mapOf("error" to "rating_closed"), emptyMap()) }
        error = rig.assertFails { rig.controller.submitRating(2, requestId = "req-1") }
        assertEquals(GiovRatingFailure.Closed, error.ratingFailure)
        assertEquals(409, error.status)
        assertTrue("a 409 on this route is not 'conversation closed'", error is GiovServerException)
        assertEquals("the chat itself is untouched", GiovChatPhase.ACTIVE, rig.phase)
        rig.dispose()
    }

    @Test
    fun aRateLimitCarriesTheRetryAfterAndAReplacedCardHides() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())
        rig.server.ratingResponder = { Triple(429, mapOf("error" to "rate_limited"), mapOf("retry-after" to "12")) }
        var error = rig.assertFails<GiovChatException> { rig.controller.submitRating(1) }
        assertEquals(GiovRatingFailure.RateLimited(12), error.ratingFailure)
        assertNull(rig.block()!!.score)
        rig.server.ratingResponder = { Triple(409, mapOf("error" to "request_replaced"), emptyMap()) }
        error = rig.assertFails { rig.controller.submitRating(1) }
        assertEquals(GiovRatingFailure.Replaced, error.ratingFailure)
        assertTrue(rig.block()!!.replaced)
        assertNull(rig.ui.ratingRequest)
        rig.dispose()
    }

    @Test
    fun aBadScoreNeverLeavesTheDevice() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())
        for (score in listOf(0, 6, -3)) {
            val error = rig.assertFails<GiovChatException> { rig.controller.submitRating(score) }
            assertEquals(GiovRatingFailure.Invalid, error.ratingFailure)
            assertEquals("invalid_rating", error.code)
        }
        assertEquals(0, rig.server.count("rating"))
        rig.dispose()
    }

    @Test
    fun aServerWithoutTheRouteHidesTheCard() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())
        assertTrue(rig.ui.ratingsAvailable)
        val error = rig.assertFails<GiovChatException> { rig.controller.submitRating(3) }
        assertEquals(GiovRatingFailure.Unsupported, error.ratingFailure)
        assertFalse(rig.ui.ratingsAvailable)
        val calls = rig.server.count("rating")
        rig.assertFails<GiovChatException> { rig.controller.submitRating(3) }
        assertEquals("it stops asking", calls, rig.server.count("rating"))
        rig.dispose()
    }

    @Test
    fun aRequestNotFoundIsNotAnOldServer() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())
        rig.server.ratingResponder = { Triple(404, mapOf("error" to "request_not_found"), emptyMap()) }
        val error = rig.assertFails<GiovChatException> { rig.controller.submitRating(3, requestId = "gone") }
        assertEquals(GiovRatingFailure.NotFound, error.ratingFailure)
        assertTrue(rig.ui.ratingsAvailable)
        rig.dispose()
    }

    @Test
    fun theRatingFramePatchesTheCardLiveAndReplacementHidesIt() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card())
        rig.receive("type" to "rating", "conversationId" to "conv-1", "requestId" to "req-1", "messageId" to "r1", "closesAt" to LATER, "score" to 5, "comment" to "From my tablet")
        assertEquals(5, rig.block()!!.score)
        assertEquals("From my tablet", rig.block()!!.comment)
        assertEquals(LATER, rig.block()!!.closesAt)
        rig.receive("type" to "rating", "conversationId" to "conv-1", "requestId" to "req-1", "messageId" to "r1", "closesAt" to LATER)
        assertNull("a frame without a score means no answer", rig.block()!!.score)
        rig.receive("type" to "rating", "conversationId" to "conv-1", "requestId" to "req-1", "messageId" to "r1", "closesAt" to LATER, "state" to "replaced")
        assertTrue(rig.block()!!.replaced)
        assertNull(rig.ui.ratingRequest)
        rig.receive("type" to "rating", "requestId" to "unknown", "closesAt" to LATER, "score" to 2)
        rig.receive("type" to "rating")
        assertEquals("odd frames are ignored", GiovChatPhase.ACTIVE, rig.phase)
        rig.dispose()
    }

    @Test
    fun aNewerCardHidesTheOlderUnansweredOne() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card("r1", "req-1", createdAt = 20))
        rig.deliver(card("r2", "req-2", createdAt = 30))
        assertTrue(rig.block("r1")!!.replaced)
        assertFalse(rig.block("r2")!!.replaced)
        assertEquals("req-2", rig.controller.pendingRating?.requestId)
        rig.dispose()
    }

    @Test
    fun aCardIsReadOnlyOnceItsDeadlinePasses() {
        val rig = CRig()
        ready(rig)
        rig.deliver(card(closesAt = EPOCH + 60_000))
        assertNotNull(rig.controller.pendingRating)
        rig.elapse(61_000)
        assertNull(rig.controller.pendingRating)
        assertEquals(GiovRatingPhase.CLOSED, rig.block()!!.phaseAt(rig.now()))
        rig.dispose()
    }

    @Test
    fun historyReturnsTheCardWithTheAnswerAppliedAndTheVote() {
        val rig = CRig(stored = testSession)
        rig.server.history = listOf(
            mapOf("id" to "b1", "role" to "agent", "content" to "Try restarting", "createdAt" to 10, "isBot" to true, "isAutomated" to true, "feedback" to "up"),
            card("r1", extra = mapOf("score" to 4, "comment" to "Quick")),
        )
        rig.start()
        rig.open()
        assertEquals(4, rig.block()!!.score)
        assertEquals("Quick", rig.block()!!.comment)
        assertEquals(GiovAnswerVote.UP, rig.ui.messages.first { it.id == "b1" }.feedback)
        rig.dispose()
    }

    @Test
    fun aCardOnAClosedConversationRendersAndSubmitsThenANewConversationClearsIt() {
        val rig = CRig(stored = testSession)
        rig.server.status = "closed"
        rig.server.history = listOf(card())
        val requested = mutableListOf<GiovRatingRequest>()
        val controller = GiovChatController(
            tenantKey = "t", baseUrl = "https://api.example.test", sessionStore = rig.store, locale = "en",
            onRatingRequested = { requested += it },
            http = rig.server, socketFactory = rig.sockets, dispatcher = rig.dispatcher, clock = rig.now, random = NoJitter,
        )
        rig.start { controller.start() }
        rig.pump()
        rig.open()
        assertTrue("the conversation is read-only", controller.state.value.conversationClosed)
        assertEquals(GiovChatPhase.ACTIVE, controller.state.value.phase)
        assertEquals("but the open card is kept and interactive", "req-1", controller.pendingRating?.requestId)
        assertTrue(controller.state.value.ratingRequest != null && controller.state.value.showsRatingCard)
        assertEquals(1, requested.size)
        rig.server.ratingResponder = { saved(score = 5) }
        rig.settle { controller.submitRating(5) }
        val sent = rig.server.to("/conversations/conv-1/rating", "POST").last()
        assertTrue(sent.headers["Authorization"]!!.startsWith("Bearer visitor-token-"))
        assertEquals(5, controller.state.value.messages.first { it.id == "r1" }.rating!!.score)
        rig.settle { controller.startNewConversation() }
        assertFalse(controller.state.value.conversationClosed)
        assertTrue("the old card goes with the old conversation", controller.state.value.messages.isEmpty() && controller.pendingRating == null)
        controller.close()
        rig.pump()
        rig.dispose()
    }

    @Test
    fun theConversationStateFrameSwitchesTheClosedViewAtOnce() {
        val rig = CRig()
        ready(rig)
        assertFalse(rig.ui.conversationClosed)
        rig.receive("type" to "conversation_state", "handedOff" to true, "assignedAgentName" to "Ada", "status" to "closed")
        assertTrue("read-only straight away, no history reload needed", rig.ui.conversationClosed)
        assertEquals(GiovChatPhase.ACTIVE, rig.phase)
        rig.deliver(card())
        assertNotNull("a card after the close renders", rig.controller.pendingRating)
        rig.receive("type" to "conversation_state", "handedOff" to true, "assignedAgentName" to "Ada", "status" to "open")
        assertFalse("reopened", rig.ui.conversationClosed)
        rig.receive("type" to "conversation_state", "handedOff" to false)
        rig.receive("type" to "conversation_state", "status" to "weird")
        assertFalse("no or unknown status changes nothing", rig.ui.conversationClosed)
        rig.receive("type" to "conversation_state", "status" to "closed")
        rig.receive("type" to "conversation_state", "handedOff" to true)
        assertTrue("a frame without status leaves the state alone", rig.ui.conversationClosed)
        rig.dispose()
    }

    @Test
    fun aCardThatArrivesAfterTheCloseRenders() {
        val rig = CRig(stored = testSession)
        rig.server.status = "closed"
        rig.server.history = listOf(mapOf("id" to "h1", "role" to "user", "content" to "Hi", "createdAt" to 1))
        rig.start()
        rig.open()
        assertTrue(rig.ui.conversationClosed)
        assertNull(rig.controller.pendingRating)
        rig.deliver(card("r9", "req-9", createdAt = 50))
        assertEquals("req-9", rig.controller.pendingRating?.requestId)
        assertNotNull("the card is in the list the screen draws", rig.block("r9"))
        assertTrue("still read-only", rig.ui.conversationClosed)
        rig.server.ratingResponder = { saved(request = "req-9", score = 4) }
        rig.settle { rig.controller.submitRating(4) }
        assertEquals(4, rig.block("r9")!!.score)
        rig.dispose()
    }

    @Test
    fun presentingTheSheetNeedsAnOpenRequestAndClosesWhenItEnds() {
        val rig = CRig()
        ready(rig)
        assertFalse(rig.controller.presentRating())
        rig.deliver(card())
        assertTrue(rig.controller.presentRating())
        rig.pump()
        assertTrue(rig.ui.ratingSheetPresented)
        rig.controller.dismissRating()
        rig.pump()
        assertFalse(rig.ui.ratingSheetPresented)
        assertTrue(rig.controller.presentRating())
        rig.pump()
        rig.receive("type" to "rating", "requestId" to "req-1", "messageId" to "r1", "closesAt" to LATER, "state" to "replaced")
        assertFalse("nothing left to rate", rig.ui.ratingSheetPresented)
        rig.dispose()
    }

    @Test
    fun theHostCanTurnTheBuiltInCardAndThumbsOff() {
        val rig = CRig()
        assertTrue(rig.ui.showsRatingCard && rig.ui.showsAnswerFeedback)
        val off = GiovChatController(
            tenantKey = "t", baseUrl = "https://api.example.test", sessionStore = RecordingStore(), locale = "en",
            showsRatingCard = false, showsAnswerFeedback = false,
            http = rig.server, socketFactory = rig.sockets, dispatcher = rig.dispatcher, clock = rig.now, random = NoJitter,
        )
        assertFalse(off.state.value.showsRatingCard)
        assertFalse(off.state.value.showsAnswerFeedback)
        off.close()
        rig.pump()
        rig.dispose()
    }

    // Thumbs

    @Test
    fun aVoteShowsAtOnceIsSentAndTakenBackWithNone() {
        val votes = mutableListOf<Pair<String, GiovAnswerVote?>>()
        val rig = CRig()
        val controller = GiovChatController(
            tenantKey = "t", baseUrl = "https://api.example.test", sessionStore = RecordingStore(), locale = "en",
            onAnswerFeedback = { id, vote -> votes += id to vote },
            http = rig.server, socketFactory = rig.sockets, dispatcher = rig.dispatcher, clock = rig.now, random = NoJitter,
        )
        rig.start { controller.start() }
        rig.pump()
        assertTrue(rig.settle { controller.send("Hi") })
        rig.pump()
        rig.socket.accept()
        rig.pump()
        rig.deliver(botAnswer())
        rig.server.feedbackResponder = { Triple(200, mapOf("messageId" to "b1", "value" to "up", "reason" to null), emptyMap()) }
        rig.settle { controller.submitAnswerFeedback("b1", GiovAnswerVote.UP) }
        assertEquals(GiovAnswerVote.UP, controller.state.value.messages.first { it.id == "b1" }.feedback)
        rig.settle { controller.submitAnswerFeedback("b1", null) }
        assertNull(controller.state.value.messages.first { it.id == "b1" }.feedback)
        val sent = rig.server.to("/conversations/conv-1/messages/b1/feedback", "POST")
        assertEquals(listOf("up", "none"), sent.map { it.json["value"].asString() })
        assertEquals(listOf("b1" to GiovAnswerVote.UP, "b1" to null), votes)
        controller.close()
        rig.pump()
        rig.dispose()
    }

    @Test
    fun aThumbsDownCarriesTheReasonAndOnlyThen() {
        val rig = CRig()
        ready(rig)
        rig.deliver(botAnswer())
        rig.server.feedbackResponder = { Triple(200, mapOf("messageId" to "b1", "value" to "down", "reason" to "wrong"), emptyMap()) }
        rig.settle { rig.controller.submitAnswerFeedback("b1", GiovAnswerVote.DOWN) }
        rig.settle { rig.controller.submitAnswerFeedback("b1", GiovAnswerVote.DOWN, "  Not my model ") }
        rig.settle { rig.controller.submitAnswerFeedback("b1", GiovAnswerVote.UP, "ignored") }
        rig.settle { rig.controller.submitAnswerFeedback("b1", GiovAnswerVote.DOWN, "x".repeat(500)) }
        val sent = rig.server.to("/conversations/conv-1/messages/b1/feedback", "POST")
        assertEquals(json("value" to "down"), sent[0].json)
        assertEquals(json("value" to "down", "reason" to "Not my model"), sent[1].json)
        assertEquals(json("value" to "up"), sent[2].json)
        assertEquals(300, sent[3].json["reason"].asString()!!.length)
        rig.dispose()
    }

    @Test
    fun aRefusedVoteIsRolledBack() {
        val rig = CRig()
        ready(rig)
        rig.deliver(botAnswer())
        rig.server.feedbackResponder = { Triple(404, mapOf("error" to "message_not_found"), emptyMap()) }
        val error = rig.assertFails<GiovChatException> { rig.controller.submitAnswerFeedback("b1", GiovAnswerVote.DOWN) }
        assertEquals(GiovRatingFailure.NotFound, error.ratingFailure)
        assertNull(rig.ui.messages.first { it.id == "b1" }.feedback)
        assertTrue(rig.ui.feedbackAvailable)
        rig.dispose()
    }

    @Test
    fun aServerWithoutTheFeedbackRouteHidesTheThumbs() {
        val rig = CRig()
        ready(rig)
        rig.deliver(botAnswer())
        val error = rig.assertFails<GiovChatException> { rig.controller.submitAnswerFeedback("b1", GiovAnswerVote.UP) }
        assertEquals(GiovRatingFailure.Unsupported, error.ratingFailure)
        assertFalse(rig.ui.feedbackAvailable)
        assertTrue("the rating route is judged on its own", rig.ui.ratingsAvailable)
        rig.dispose()
    }

    // Conformance fixture

    private fun element(value: Any?): JsonElement = jsonValue(value)

    /**
     * Replays the visitor side of `05-ratings.json` (a copy of docs/protocol/conformance/fixtures): every request the
     * fixture makes with the visitor token goes through the SDK's public API against a server that answers what the
     * fixture expects, and every frame it expects the socket to deliver is delivered and checked.
     */
    @Test
    fun fixture05RatingsReplays() {
        val text = javaClass.classLoader!!.getResourceAsStream("05-ratings.json")!!.bufferedReader().use { it.readText() }
        val steps = (Json.parseToJsonElement(text) as JsonObject)["steps"] as JsonArray
        val requestId = "req-fixture-1"
        val messageId = "msg-fixture-1"
        val vars = mapOf("tenantId" to "t", "conversationId" to "conv-1", "requestId" to requestId, "ratingMessageId" to messageId)

        fun resolve(value: JsonElement?): JsonElement? = when {
            value == null -> null
            value is JsonPrimitive && value.isString -> when (val raw = value.content) {
                "<number>" -> JsonPrimitive(LATER)
                "<nonempty>" -> JsonPrimitive(TEXT)
                else -> JsonPrimitive(vars.entries.fold(raw) { acc, (k, v) -> acc.replace("{{$k}}", v) })
            }
            value is JsonArray -> JsonArray(value.map { resolve(it) ?: JsonNull })
            value is JsonObject -> JsonObject(value.mapValues { resolve(it.value) ?: JsonNull })
            else -> value
        }

        val rig = CRig()
        rig.start()
        rig.begin("My parcel is late")
        var exercised = 0

        for (step in steps) {
            val obj = step as JsonObject
            val http = obj["http"] as? JsonObject
            if (http != null && http["auth"].asString() == "visitor") {
                val path = resolve(http["path"]).asString().orEmpty()
                val body = resolve(http["json"]).asObject()
                val expect = resolve(obj["expect"]).asObject()
                val status = expect["status"].asNumber()?.toInt() ?: 200
                val reply = expect["body"].asObject().toMutableMap()
                if (status == 200 && path.endsWith("/rating")) {
                    // The no-op answer in the fixture lists only the score; the real server sends the whole record.
                    reply.putIfAbsent("requestId", JsonPrimitive(requestId))
                    reply.putIfAbsent("closesAt", JsonPrimitive(LATER))
                    reply["ratedAt"] = JsonPrimitive(EPOCH)
                }
                val answer: Answer = Triple(status, reply, emptyMap())
                val before = rig.server.requests.size
                if (path.endsWith("/rating")) {
                    rig.server.ratingResponder = { answer }
                    val score = body["score"].asNumber()?.toInt() ?: 0
                    val outcome = runCatching {
                        rig.settle { rig.controller.submitRating(score, body["comment"].asString(), body["requestId"].asString()) }
                    }
                    val error = outcome.exceptionOrNull() as? GiovChatException
                    if (error == null) {
                        val result = outcome.getOrThrow()
                        assertEquals("$path succeeded but the fixture expects $status", 200, status)
                        assertEquals(reply["score"].asNumber()?.toInt(), result.score)
                        assertEquals(reply["requestId"].asString() ?: requestId, result.requestId)
                        assertEquals(reply["comment"].asString(), result.comment)
                        val sent = rig.server.requests.last()
                        assertEquals("POST", sent.method)
                        assertEquals(path, sent.path)
                        val sentBody = if (body["requestId"] == null) JsonObject(sent.json - "requestId") else sent.json
                        assertEquals("the SDK sends the fixture's body", body, sentBody)
                    } else {
                        assertTrue(status != 200)
                        assertEquals(reply["error"].asString(), error.code)
                        if (score in 1..5) {
                            assertEquals(status, error.status)
                        } else {
                            assertEquals("an impossible score is refused on the device", before, rig.server.requests.size)
                        }
                    }
                    exercised++
                } else if (path.contains("/messages/") && path.endsWith("/feedback")) {
                    val value = body["value"].asString().orEmpty()
                    // "maybe" cannot be expressed with the SDK's types: that refusal is made impossible at compile time.
                    if (value != "none" && GiovAnswerVote.fromWire(value) == null) continue
                    rig.server.feedbackResponder = { answer }
                    val id = path.split("/")[4]
                    val outcome = runCatching {
                        rig.settle { rig.controller.submitAnswerFeedback(id, GiovAnswerVote.fromWire(value)) }
                    }
                    val error = outcome.exceptionOrNull() as? GiovChatException
                    if (error == null) {
                        assertEquals(200, status)
                    } else {
                        assertTrue(status != 200)
                        assertEquals(reply["error"].asString(), error.code)
                        assertEquals(status, error.status)
                    }
                    val sent = rig.server.requests.last()
                    assertEquals(path, sent.path)
                    assertEquals(body, sent.json)
                    exercised++
                }
                continue
            }
            if (http != null && http["path"].asString().orEmpty().endsWith("/rating-request")) continue // the team's side
            val receive = obj["receive"] as? JsonObject ?: continue
            val match = resolve(receive["match"]).asObject()
            if (match["type"].asString() == "rating") {
                val frame = match.toMutableMap()
                frame["conversationId"] = JsonPrimitive("conv-1")
                frame.putIfAbsent("closesAt", JsonPrimitive(LATER))
                rig.socket.receiveRaw(JsonObject(frame).toString())
                rig.pump()
                assertEquals(match["score"].asNumber()?.toInt(), rig.block(messageId)!!.score)
                exercised++
            } else if (match["role"].asString() == "agent" && match["blocks"] is JsonArray) {
                val message = match.toMutableMap()
                message["content"] = JsonPrimitive(TEXT)
                message["createdAt"] = JsonPrimitive(EPOCH + 5)
                val blocks = (match["blocks"] as JsonArray).toMutableList()
                val first = blocks.firstOrNull() as? JsonObject
                if (first != null) blocks[0] = JsonObject(first + ("closesAt" to JsonPrimitive(SOON)))
                message["blocks"] = JsonArray(blocks)
                rig.socket.receiveRaw(JsonObject(message).toString())
                rig.pump()
                val pending = rig.controller.pendingRating!!
                assertEquals(requestId, pending.requestId)
                assertEquals(messageId, pending.messageId)
                exercised++
            }
        }
        assertTrue("the fixture's visitor steps all ran ($exercised)", exercised >= 8)
        rig.dispose()
    }
}
