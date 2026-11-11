package app.giovchat.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// Visitor messages the server has not acknowledged yet, in the order they were written. Every message keeps
// one client message id for its whole life, so resending after a dropped socket can never store it twice:
// the server answers a repeat with the original's ack (docs/protocol/README.md, "Outbox and idempotency").
// Pure: no sockets or timers, so the rules are testable on their own.

/** `QUEUED`: waiting for a socket. `SENDING`: on a socket, no ack yet. `FAILED`: the visitor must retry or discard. */
internal enum class OutboxState { QUEUED, SENDING, FAILED }

internal class OutboxEntry(
    val clientMessageId: String,
    val content: String,
    /** Uploaded attachment URLs this message carries. */
    val attachments: List<String>,
    /** Unix milliseconds. */
    val createdAt: Long,
    var state: OutboxState = OutboxState.QUEUED,
    /** When it last went out on a socket (Unix ms); 0 while it is waiting for one. */
    var sentAt: Long = 0,
    /** Times the server said it could not save it. */
    var failures: Int = 0,
) {
    fun copy(): OutboxEntry = OutboxEntry(clientMessageId, content, attachments.toList(), createdAt, state, sentAt, failures)
}

/** A sent message with no ack after this long means a dead socket: reconnect and resend. */
internal const val ACK_TIMEOUT_MS: Long = 10_000

/** Save failures retried before the visitor is told the message did not send. */
internal const val MAX_SEND_FAILURES: Int = 3
internal const val MAX_MESSAGE_CHARS: Int = 2000

/** Wait before resending after a save failure: 2 s, 4 s, ... */
internal fun sendRetryDelayMs(failures: Int): Long = 2000L * failures

internal class OutboxFrame(val clientMessageId: String, val frame: String)

internal class OutboxRejection(
    /** True once the message gave up; the visitor decides. */
    val failed: Boolean,
    val retryAfterMs: Long,
)

internal class Outbox(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = LinkedHashMap<String, OutboxEntry>()
    private val listeners = mutableListOf<() -> Unit>()

    /** Calls [listener] after every change; returns the function that stops it. */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    private fun changed() {
        for (listener in listeners.toList()) {
            try {
                listener()
            } catch (_: Exception) {
                // Listeners cannot break the queue.
            }
        }
    }

    /** Copies, oldest first. */
    fun list(): List<OutboxEntry> = entries.values.map { it.copy() }

    operator fun get(clientMessageId: String): OutboxEntry? = entries[clientMessageId]?.copy()

    val size: Int get() = entries.size

    /** Adds a message at the end. Throws [GiovSendFailedException] when it could never be accepted (empty or too long). */
    fun enqueue(
        content: String,
        attachments: List<String> = emptyList(),
        nowMs: Long? = null,
        clientMessageId: String? = null,
    ): OutboxEntry {
        if ((content.isBlank() && attachments.isEmpty()) || content.length > MAX_MESSAGE_CHARS) {
            throw GiovSendFailedException("Messages must contain 1-2000 characters.")
        }
        val id = clientMessageId ?: randomClientId()
        val entry = OutboxEntry(id, content, attachments.toList(), nowMs ?: now())
        entries[id] = entry
        changed()
        return entry.copy()
    }

    /** Everything waiting for a socket, in order, marked as sent now. Failed messages wait for [retry]. */
    fun takeQueued(nowMs: Long? = null): List<OutboxFrame> {
        val out = mutableListOf<OutboxFrame>()
        val time = nowMs ?: now()
        for (entry in entries.values) {
            if (entry.state != OutboxState.QUEUED) continue
            entry.state = OutboxState.SENDING
            entry.sentAt = time
            out += OutboxFrame(entry.clientMessageId, frame(entry))
        }
        if (out.isNotEmpty()) changed()
        return out
    }

    /** The server saved it: the message leaves the queue. Unknown ids (already acknowledged) return null. */
    fun acknowledge(clientMessageId: String): OutboxEntry? {
        val entry = entries.remove(clientMessageId) ?: return null
        changed()
        return entry
    }

    /** The server could not save it: wait and resend, or give up after [MAX_SEND_FAILURES]. */
    fun rejected(clientMessageId: String): OutboxRejection? {
        val entry = entries[clientMessageId]
        if (entry == null || entry.state == OutboxState.FAILED) return null
        entry.failures += 1
        entry.sentAt = 0
        entry.state = if (entry.failures >= MAX_SEND_FAILURES) OutboxState.FAILED else OutboxState.QUEUED
        changed()
        val failed = entry.state == OutboxState.FAILED
        return OutboxRejection(failed, if (failed) 0 else sendRetryDelayMs(entry.failures))
    }

    /** A message the server refused outright (invalid content or attachment): no retry can fix it. */
    fun refuseOldestSending(): OutboxEntry? {
        for (entry in entries.values) {
            if (entry.state != OutboxState.SENDING) continue
            entry.state = OutboxState.FAILED
            entry.sentAt = 0
            changed()
            return entry.copy()
        }
        return null
    }

    /** The socket went away: whatever was on it goes out again on the next one, with the same ids. */
    fun socketClosed() {
        var touched = false
        for (entry in entries.values) {
            if (entry.state == OutboxState.SENDING) {
                entry.state = OutboxState.QUEUED
                entry.sentAt = 0
                touched = true
            }
        }
        if (touched) changed()
    }

    /** True when a message has waited longer than [ACK_TIMEOUT_MS] on an open socket. */
    fun stale(nowMs: Long? = null): Boolean {
        val time = nowMs ?: now()
        return entries.values.any { it.state == OutboxState.SENDING && it.sentAt > 0 && time - it.sentAt > ACK_TIMEOUT_MS }
    }

    /** The visitor asked to try a failed message again: it rejoins the queue in its original place. */
    fun retry(clientMessageId: String): Boolean {
        val entry = entries[clientMessageId]
        if (entry == null || entry.state != OutboxState.FAILED) return false
        entry.state = OutboxState.QUEUED
        entry.failures = 0
        entry.sentAt = 0
        changed()
        return true
    }

    fun remove(clientMessageId: String): Boolean {
        val removed = entries.remove(clientMessageId) != null
        if (removed) changed()
        return removed
    }

    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        changed()
    }

    companion object {
        /** The wire frame; identical every time it is sent. */
        fun frame(entry: OutboxEntry): String = buildJsonObject {
            put("type", "message")
            put("content", entry.content)
            put("clientMessageId", entry.clientMessageId)
            if (entry.attachments.isNotEmpty()) put("attachments", buildJsonArray { entry.attachments.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }
}

/** A random (version 4) UUID, made on the device. */
internal fun randomClientId(): String = UUID.randomUUID().toString()
