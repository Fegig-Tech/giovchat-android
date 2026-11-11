package app.giovchat.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutboxTest {
    @Test
    fun messagesLeaveInTheOrderTheyWereWrittenEachUnderOneIdForGood() {
        val outbox = Outbox()
        val a = outbox.enqueue("first", nowMs = 1, clientMessageId = "a")
        outbox.enqueue("second", listOf("https://api.test/conversations/c/attachments/x"), nowMs = 2, clientMessageId = "b")
        assertEquals(OutboxState.QUEUED, a.state)
        val frames = outbox.takeQueued(10)
        assertEquals(listOf("a", "b"), frames.map { it.clientMessageId })
        assertEquals(
            json(
                "type" to "message", "content" to "second", "clientMessageId" to "b",
                "attachments" to listOf("https://api.test/conversations/c/attachments/x"),
            ),
            parseJsonObject(frames[1].frame),
        )
        assertFalse("no attachments key without files", parseJsonObject(frames[0].frame).containsKey("attachments"))
        assertTrue("nothing is sent twice on the same socket", outbox.takeQueued(11).isEmpty())
    }

    @Test
    fun aDroppedSocketPutsEverythingBackInLineWithTheSameIdsAnAckRemovesIt() {
        val outbox = Outbox()
        outbox.enqueue("one", nowMs = 1, clientMessageId = "one")
        outbox.enqueue("two", nowMs = 2, clientMessageId = "two")
        val first = outbox.takeQueued(5).map { it.frame }
        outbox.socketClosed()
        assertEquals(listOf(OutboxState.QUEUED to 0L, OutboxState.QUEUED to 0L), outbox.list().map { it.state to it.sentAt })
        assertEquals("identical frames, so the server can deduplicate", first, outbox.takeQueued(6).map { it.frame })
        assertNotNull(outbox.acknowledge("one"))
        assertNull("a repeated ack is ignored", outbox.acknowledge("one"))
        assertEquals(listOf("two"), outbox.list().map { it.clientMessageId })
    }

    @Test
    fun saveFailuresRetryAfter2sAnd4sThenGiveUpAndWaitForTheVisitor() {
        val outbox = Outbox()
        outbox.enqueue("hello", nowMs = 1, clientMessageId = "m")
        outbox.takeQueued(2)
        val delays = mutableListOf<Long>()
        var outcome: OutboxRejection? = null
        repeat(MAX_SEND_FAILURES) {
            outcome = outbox.rejected("m")
            if (!outcome!!.failed) {
                delays += outcome!!.retryAfterMs
                outbox.takeQueued(3)
            }
        }
        assertEquals(listOf(2000L, 4000L), delays)
        assertTrue(outcome!!.failed)
        assertEquals(OutboxState.FAILED, outbox["m"]!!.state)
        assertTrue("a failed message is not sent again on its own", outbox.takeQueued(4).isEmpty())
        outbox.socketClosed()
        assertEquals("reconnecting does not revive it", OutboxState.FAILED, outbox["m"]!!.state)
        assertTrue(outbox.retry("m"))
        assertEquals(listOf("m"), outbox.takeQueued(5).map { it.clientMessageId })
        assertFalse("only failed messages can be retried", outbox.retry("m"))
    }

    @Test
    fun aMessageUnansweredPastTheAckTimeoutMarksTheSocketAsDead() {
        val outbox = Outbox()
        outbox.enqueue("hi", nowMs = 0, clientMessageId = "x")
        assertFalse("queued messages never count", outbox.stale(ACK_TIMEOUT_MS * 5))
        outbox.takeQueued(1000)
        assertFalse(outbox.stale(1000 + ACK_TIMEOUT_MS))
        assertTrue(outbox.stale(1001 + ACK_TIMEOUT_MS))
    }

    @Test
    fun refusalsFailTheOldestMessageOnTheSocketEmptyAndOversizedMessagesAreRejectedUpFront() {
        val outbox = Outbox()
        outbox.enqueue("a", nowMs = 1, clientMessageId = "a")
        outbox.enqueue("b", nowMs = 2, clientMessageId = "b")
        outbox.takeQueued(3)
        assertEquals("a", outbox.refuseOldestSending()!!.clientMessageId)
        assertEquals(listOf(OutboxState.FAILED, OutboxState.SENDING), outbox.list().map { it.state })
        for (bad in listOf("   ", "x".repeat(2001))) {
            try {
                outbox.enqueue(bad)
                fail("accepted")
            } catch (_: GiovSendFailedException) {
            }
        }
        outbox.enqueue("", listOf("https://api.test/a")) // a file alone is a message
    }

    @Test
    fun listenersHearEveryChangeClientIdsAreUniqueUuids() {
        val outbox = Outbox()
        var changes = 0
        val stop = outbox.subscribe { changes += 1 }
        outbox.enqueue("a", nowMs = 1, clientMessageId = "a")
        outbox.takeQueued(2)
        outbox.acknowledge("a")
        stop()
        outbox.enqueue("b")
        assertEquals(3, changes)
        val ids = (0 until 200).map { randomClientId() }.toSet()
        assertEquals(200, ids.size)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(ids.first()))
    }

    @Test
    fun theFrameCarriesAttachmentsAsAnArrayOfUrls() {
        val entry = Outbox().enqueue("see", listOf("u1", "u2"), clientMessageId = "z")
        val frame = parseJsonObject(Outbox.frame(entry))
        assertEquals(JsonArray(listOf(JsonPrimitive("u1"), JsonPrimitive("u2"))), frame["attachments"])
    }
}
