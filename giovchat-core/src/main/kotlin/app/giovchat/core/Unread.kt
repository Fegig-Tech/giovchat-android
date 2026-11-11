package app.giovchat.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unread replies per chat controller, so any screen can show a badge without passing the controller around.
 * Each [GiovChatController] publishes its own count under its workspace key.
 *
 * Counts only while a controller with `keepConnected = true` stays connected; opening the chat clears it.
 */
public object GiovUnread {
    private class Entry(val tenantKey: String, val count: Int)

    private val lock = Any()
    private val counts = HashMap<Any, Entry>()
    private val flows = HashMap<String?, MutableStateFlow<Int>>()

    /** Called by the controller; apps do not need it. */
    internal fun publish(owner: Any, tenantKey: String, count: Int) {
        synchronized(lock) {
            val current = counts[owner]
            if (current != null && current.tenantKey == tenantKey && current.count == count) return
            counts[owner] = Entry(tenantKey, count)
            republish()
        }
    }

    internal fun forget(owner: Any) {
        synchronized(lock) {
            if (counts.remove(owner) != null) republish()
        }
    }

    /** Agent replies not seen because the chat was closed, summed over all chats (or one workspace). */
    public fun read(tenantKey: String? = null): Int = synchronized(lock) {
        counts.values.filter { tenantKey == null || it.tenantKey == tenantKey }.sumOf { it.count }
    }

    /** A flow of the count for a badge: `GiovUnread.watch().collectAsState()`. */
    public fun watch(tenantKey: String? = null): StateFlow<Int> = synchronized(lock) {
        flows.getOrPut(tenantKey) { MutableStateFlow(read(tenantKey)) }
    }

    private fun republish() {
        for ((key, flow) in flows) flow.value = counts.values.filter { key == null || it.tenantKey == key }.sumOf { it.count }
    }
}
