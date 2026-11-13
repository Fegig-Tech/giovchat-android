package app.giovchat.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.giovchat.core.GiovAnswerVote
import app.giovchat.core.GiovRatingBlock
import app.giovchat.core.GiovRatingFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class RatingModelsTest {
    private val block = GiovRatingBlock("r", 1000)

    @Test
    fun pendingToAnsweredToChangedToClosed() {
        var model = RatingCardModel()
        assertNull("nothing picked yet", model.beginSend())
        model = model.select(4).withComment("  Quick answer ")
        val (sending, choice) = model.beginSend()!!
        assertEquals(RatingChoice(4, "Quick answer"), choice)
        assertTrue(sending.sending)
        assertNull("one answer at a time", sending.beginSend())
        assertEquals("the faces are off while sending", 4, sending.select(1).selected)
        val answered = block.copy(score = 4, comment = "Quick answer")
        model = sending.sent()
        assertEquals(app.giovchat.core.GiovRatingCardState.Answered(4, "Quick answer", true), model.card(answered, 0))
        // Change, then Cancel.
        model = model.beginEditing(answered)
        assertEquals(app.giovchat.core.GiovRatingCardState.Form(4, "Quick answer", true), model.card(answered, 0))
        model = model.cancelEditing()
        assertEquals(app.giovchat.core.GiovRatingCardState.Answered(4, "Quick answer", true), model.card(answered, 0))
        assertEquals(app.giovchat.core.GiovRatingCardState.Answered(4, "Quick answer", false), model.card(answered, 1000))
    }

    @Test
    fun aBlankCommentIsLeftOut() {
        assertNull(RatingCardModel().select(3).withComment("   ").beginSend()!!.second.comment)
        assertEquals(1000, RatingCardModel().withComment("x".repeat(1500)).comment.length)
    }

    @Test
    fun aFailedSendComesBackAsTheFormWithTheChoiceKept() {
        val (sending, _) = RatingCardModel().select(2).withComment("slow").beginSend()!!
        val failed = sending.failed(GiovRatingFailure.Retry)
        assertFalse(failed.sending)
        assertTrue(failed.editing)
        assertEquals(GiovRatingFailure.Retry, failed.failure)
        assertEquals(app.giovchat.core.GiovRatingCardState.Form(2, "slow", false), failed.card(block, 0))
        val again = failed.beginSend()!!.first
        assertNull("a new try clears the message", again.failure)
        assertEquals(GiovRatingFailure.RateLimited(12), again.failed(GiovRatingFailure.RateLimited(12)).failure)
    }

    @Test
    fun closedAndReplacedRefusalsLockTheCard() {
        val picked = RatingCardModel().select(2).beginSend()!!.first
        assertEquals(app.giovchat.core.GiovRatingCardState.Closed, picked.failed(GiovRatingFailure.Closed).card(block, 0))
        assertEquals(app.giovchat.core.GiovRatingCardState.Hidden, picked.failed(GiovRatingFailure.Replaced).card(block, 0))
        assertEquals(
            app.giovchat.core.GiovRatingCardState.Answered(3, null, false),
            picked.failed(GiovRatingFailure.TooManyChanges).card(block.copy(score = 3), 0),
        )
        assertEquals(app.giovchat.core.GiovRatingCardState.Closed, picked.failed(GiovRatingFailure.Unsupported).card(block, 0))
    }

    @Test
    fun tappingTheActiveThumbTakesTheVoteBack() {
        var model = FeedbackModel()
        var (next, vote) = model.tap(GiovAnswerVote.UP, null)
        assertEquals(GiovAnswerVote.UP, vote)
        assertFalse(next.asking)
        assertNull(next.tap(GiovAnswerVote.UP, GiovAnswerVote.UP).second)
        val down = next.tap(GiovAnswerVote.DOWN, GiovAnswerVote.UP)
        assertEquals(GiovAnswerVote.DOWN, down.second)
        assertTrue("a thumbs-down offers a reason", down.first.asking)
        model = down.first.withReason("  too slow ")
        val (done, reason) = model.submitReason()
        assertEquals("too slow", reason)
        assertFalse(done.asking)
        assertNull("blank reason: nothing to send", FeedbackModel(asking = true, reason = "  ").submitReason().second)
        assertEquals(300, FeedbackModel().withReason("r".repeat(400)).reason.length)
        val failed = FeedbackModel(asking = true).fail()
        assertTrue(failed.failed && !failed.asking)
        assertFalse("a new tap clears the failure", failed.tap(GiovAnswerVote.UP, null).first.failed)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class RatingsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun card(closesAt: Long) = mapOf(
        "id" to "r1", "role" to "agent", "content" to "How did we do? Please rate this conversation.", "createdAt" to EPOCH + 10, "senderName" to "Ada",
        "blocks" to listOf(mapOf("type" to "rating", "requestId" to "req-1", "closesAt" to closesAt)),
    )

    @Test
    fun theCardIsAnsweredWithAFaceAndACommentThenShowsThanksAndCanBeChanged() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        rig.socket.receiveRaw(jsonValue(card(System.currentTimeMillis() + 3_600_000)).toString())
        rig.settle()
        rule.onNodeWithTag("giov-rating-card").assertIsDisplayed()
        rule.onNodeWithTag("giov-rating-send").performClick()
        assertEquals("Send waits for a face", 0, rig.server.count("rating"))
        rule.onNodeWithContentDescription("Good").performClick()
        rule.onNodeWithTag("giov-rating-comment").performTextInput("Quick answer")
        rule.onNodeWithTag("giov-rating-send").performClick()
        rig.settle()
        assertEquals(1, rig.server.count("rating"))
        rule.onNodeWithText("Thanks · Good").assertIsDisplayed()
        rule.onNodeWithTag("giov-rating-change").performClick()
        rig.settle()
        rule.onNodeWithTag("giov-rating-cancel").assertIsDisplayed()
        rule.onNodeWithTag("giov-rating-cancel").performClick()
        rig.settle()
        rule.onNodeWithText("Thanks · Good").assertIsDisplayed()
        rig.finish()
    }

    @Test
    fun aReplacedCardDrawsNothingAndAnOldServerHidesTheCard() {
        val rig = UiRig(rule)
        rig.server.ratingsEnabled = false
        rig.show()
        rig.begin()
        rig.socket.receiveRaw(jsonValue(card(System.currentTimeMillis() + 3_600_000)).toString())
        rig.settle()
        rule.onNodeWithContentDescription("Great").performClick()
        rule.onNodeWithTag("giov-rating-send").performClick()
        rig.settle()
        assertFalse(rig.ui.ratingsAvailable)
        rule.onAllNodesWithTag("giov-rating-card").assertCountEquals(0)
        rig.finish()
    }

    @Test
    fun thumbsShowUnderBotAnswersAndAThumbsDownAsksForAReason() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        rig.receive("id" to "b1", "role" to "agent", "content" to "Try restarting", "createdAt" to rig.now() + 5, "isBot" to true)
        rule.onNodeWithTag("giov-thumb-up").assertIsDisplayed()
        rule.onNodeWithTag("giov-thumb-down").performClick()
        rig.settle()
        assertEquals(GiovAnswerVote.DOWN, rig.ui.messages.first { it.id == "b1" }.feedback)
        rule.onNodeWithTag("giov-thumb-reason").performTextInput("Wrong plan")
        rule.onNodeWithTag("giov-thumb-send").performClick()
        rig.settle()
        assertEquals(2, rig.server.count("feedback"))
        rig.finish()
    }

    @Test
    fun aClosedConversationShowsItsCardAndTheStartButtonInsteadOfTheComposer() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        rig.socket.receiveRaw(jsonValue(card(System.currentTimeMillis() + 3_600_000)).toString())
        rig.settle()
        rig.server.status = "closed"
        rig.controller.onForeground()
        rig.settle()
        assertTrue(rig.ui.conversationClosed)
        rule.onNodeWithTag("giov-closed-bar").assertIsDisplayed()
        rule.onNodeWithText("Start a new conversation").assertIsDisplayed()
        rule.onAllNodesWithTag("giov-input").assertCountEquals(0)
        // The card stays interactive.
        rule.onNodeWithContentDescription("Okay").performClick()
        rule.onNodeWithTag("giov-rating-send").performClick()
        rig.settle()
        assertEquals(1, rig.server.count("rating"))
        // A new conversation clears the closed one and brings the composer back.
        rule.onNodeWithTag("giov-start-new").performClick()
        rig.settle()
        assertFalse(rig.ui.conversationClosed)
        rule.onNodeWithTag("giov-input").assertIsDisplayed()
        rule.onAllNodesWithTag("giov-rating-card").assertCountEquals(0)
        rig.finish()
    }

    @Test
    fun theBotsRatingCardMessageHasNoThumbs() {
        val rig = UiRig(rule)
        rig.show()
        rig.begin()
        val botCard = card(System.currentTimeMillis() + 3_600_000) + ("isBot" to true)
        rig.socket.receiveRaw(jsonValue(botCard).toString())
        rig.settle()
        rule.onAllNodesWithTag("giov-thumb-up").assertCountEquals(0)
        assertNotNull(rig.controller.pendingRating)
        rig.finish()
    }
}
