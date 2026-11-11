package app.giovchat.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// The WebSocket seam. The client talks to [GiovSocket], so tests (and apps with special needs) can supply
// their own; the default wraps OkHttp.

/**
 * Callbacks of one socket. They may be called on any thread (the client moves them onto its own dispatcher),
 * but one at a time and in order.
 */
public interface GiovSocketListener {
    /** The upgrade succeeded. */
    public fun onOpen()

    /** A text frame. */
    public fun onMessage(text: String)

    /**
     * The socket is finished. Close code `1000` with reason `Conversation deleted` means the conversation is gone
     * (docs/protocol/README.md, "Connecting"). [code] is null when the connection dropped without a close frame.
     */
    public fun onClosed(code: Int?, reason: String?)

    /**
     * The connection failed or broke; this is terminal (no [onClosed] follows). [httpStatus] is the status of a
     * refused upgrade: only `410` means the conversation was deleted.
     */
    public fun onFailure(error: Throwable, httpStatus: Int?)
}

/** One conversation socket. */
public interface GiovSocket {
    /** True between the upgrade and the first close or failure. */
    public val isOpen: Boolean

    /** Queues [frame]; false when the socket is closing or closed. */
    public fun send(frame: String): Boolean

    /** Closes the socket; no callback follows for a close the client asked for. */
    public fun close(code: Int? = null, reason: String? = null)
}

/** Creates a socket for `ws(s)://.../conversations/{id}/ws?role=user&token=...` and starts connecting at once. */
public fun interface GiovSocketFactory {
    public fun create(url: String, listener: GiovSocketListener): GiovSocket
}

/** [GiovSocket] over OkHttp. The client's own ping/pong runs on top; OkHttp's pings stay off. */
public class OkHttpGiovSocketFactory(
    client: OkHttpClient = GiovOkHttp.client,
) : GiovSocketFactory {
    // newBuilder() keeps the connection pool and dispatcher of [client], so nothing new is started per controller.
    internal val client: OkHttpClient = client.newBuilder().pingInterval(0, java.util.concurrent.TimeUnit.SECONDS).build()

    override fun create(url: String, listener: GiovSocketListener): GiovSocket {
        val adapter = OkHttpSocket()
        adapter.socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                adapter.open = true
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                adapter.open = false
                webSocket.close(code, null)
                listener.onClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                adapter.open = false
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                adapter.open = false
                // Only the HTTP status of a refused upgrade is looked at, never the error text (it contains the URL).
                val status = response?.code
                response?.close()
                listener.onFailure(t, status)
            }
        })
        return adapter
    }

    private class OkHttpSocket : GiovSocket {
        @Volatile var open: Boolean = false
        lateinit var socket: WebSocket

        override val isOpen: Boolean get() = open

        override fun send(frame: String): Boolean = open && socket.send(frame)

        override fun close(code: Int?, reason: String?) {
            open = false
            socket.close(code ?: 1000, reason)
        }
    }
}
