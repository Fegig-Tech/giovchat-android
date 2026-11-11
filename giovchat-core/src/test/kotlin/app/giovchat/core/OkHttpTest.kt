package app.giovchat.core

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The OkHttp implementations of the HTTP and socket seams, against a real local server. */
class OkHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getsAndPostsJsonAndReturnsErrorStatusesAsResponses() = runBlocking {
        val http = OkHttpGiovHttp()
        server.enqueue(MockResponse().setBody("""{"title":"Acme"}"""))
        val config = http.send(GiovHttpRequest("GET", server.url("/widget/config?tenantId=t").toString(), mapOf("X-Giov-Client" to "android/0.1.0")))
        assertEquals(200, config.status)
        assertEquals("Acme", parseJsonObject(config.body)["title"].asString())
        val first = server.takeRequest()
        assertEquals("android/0.1.0", first.getHeader("X-Giov-Client"))
        assertEquals("/widget/config?tenantId=t", first.path)

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("""{"error":"rate_limited"}"""))
        val limited = http.send(
            GiovHttpRequest("POST", server.url("/conversations").toString(), mapOf("Authorization" to "Bearer s"), jsonBody = """{"tenantId":"t"}"""),
        )
        assertEquals(429, limited.status)
        assertEquals("7", limited.headers["retry-after"])
        val second = server.takeRequest()
        assertEquals("Bearer s", second.getHeader("Authorization"))
        assertTrue(second.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"tenantId":"t"}""", second.body.readUtf8())

        server.enqueue(MockResponse().setBody("{}"))
        http.send(GiovHttpRequest("DELETE", server.url("/conversations/c/push-token").toString(), jsonBody = """{"token":"x"}"""))
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun anUnreachableServerIsAnIoException() = runBlocking {
        val url = server.url("/x").toString()
        server.shutdown()
        try {
            OkHttpGiovHttp().send(GiovHttpRequest("GET", url))
            throw AssertionError("expected a failure")
        } catch (_: IOException) {
        }
    }

    @Test
    fun uploadsMultipartWithTheFileThePlaceholderAndProgress() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"url":"https://x.test/a.png"}"""))
        val progress = mutableListOf<Double>()
        val file = GiovPickedFile.bytes("a.png", "image/png", ByteArray(50_000) { 7 })
        val response = OkHttpGiovHttp().send(
            GiovHttpRequest(
                "POST", server.url("/conversations/c/upload-visitor").toString(), mapOf("Authorization" to "Bearer v"),
                upload = GiovUploadBody(file, "data:image/jpeg;base64,AAAA", 40, 30) { progress += it },
            ),
        )
        assertEquals(200, response.status)
        val request = server.takeRequest()
        assertEquals("Bearer v", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"a.png\""))
        assertTrue(body.contains("Content-Type: image/png"))
        assertTrue(body.contains("name=\"thumbnail\"") && body.contains("name=\"width\"") && body.contains("name=\"height\""))
        assertTrue("progress was reported", progress.isNotEmpty())
        assertEquals(1.0, progress.last(), 0.0)
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    private class Recorder : GiovSocketListener {
        val events = LinkedBlockingQueue<String>()
        val statuses = LinkedBlockingQueue<Int?>()
        override fun onOpen() {
            events += "open"
        }

        override fun onMessage(text: String) {
            events += "message:$text"
        }

        override fun onClosed(code: Int?, reason: String?) {
            events += "closed:$code:$reason"
        }

        override fun onFailure(error: Throwable, httpStatus: Int?) {
            events += "failure"
            statuses += httpStatus
        }

        fun next(): String = events.poll(5, TimeUnit.SECONDS) ?: "timeout"
    }

    @Test
    fun theSocketOpensExchangesTextAndReportsTheCloseCodeAndReason() {
        val received = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    webSocket.send("""{"type":"pong"}""")
                    webSocket.close(1000, "Conversation deleted")
                }
            }),
        )
        val recorder = Recorder()
        val socket = OkHttpGiovSocketFactory().create(server.url("/conversations/c/ws?role=user&token=t").toString().replace("http", "ws"), recorder)
        assertEquals("open", recorder.next())
        assertTrue(socket.isOpen)
        assertTrue(socket.send("""{"type":"ping"}"""))
        assertEquals("""{"type":"ping"}""", received.poll(5, TimeUnit.SECONDS))
        assertEquals("""message:{"type":"pong"}""", recorder.next())
        assertEquals("closed:1000:Conversation deleted", recorder.next())
        assertTrue(!socket.isOpen)
        assertTrue("sending on a closed socket is refused", !socket.send("x"))
    }

    @Test
    fun aRefusedUpgradeReportsOnlyTheHttpStatus() {
        for (status in listOf(410, 401)) {
            server.enqueue(MockResponse().setResponseCode(status))
            val recorder = Recorder()
            // The conversation id contains "410"; only the status may decide anything.
            OkHttpGiovSocketFactory().create(server.url("/conversations/9c410f2e-4100-4abc-8410-000000000410/ws").toString().replace("http", "ws"), recorder)
            assertEquals("failure", recorder.next())
            assertEquals(status, recorder.statuses.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun anUnreachableSocketFailsWithoutAStatus() {
        val url = server.url("/c/ws").toString().replace("http", "ws")
        server.shutdown()
        val recorder = Recorder()
        OkHttpGiovSocketFactory().create(url, recorder)
        assertEquals("failure", recorder.next())
        assertNull(recorder.statuses.poll(5, TimeUnit.SECONDS))
    }
}
