package app.giovchat.compose

import app.giovchat.core.GiovHttp
import app.giovchat.core.GiovHttpRequest
import app.giovchat.core.GiovHttpResponse
import app.giovchat.core.GiovSession
import app.giovchat.core.GiovSocket
import app.giovchat.core.GiovSocketFactory
import app.giovchat.core.GiovSocketListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import kotlin.random.Random

// A minimal copy of the core module's test doubles: a Giov API in memory and sockets the test drives by hand.

val testSession = GiovSession("conv-1", "b".repeat(64))

/** 2026-01-01T00:00:00Z */
const val EPOCH: Long = 1_767_225_600_000L

object NoJitter : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to jsonValue(v) })
    is List<*> -> JsonArray(value.map(::jsonValue))
    else -> error("unsupported $value")
}

fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to jsonValue(v) })

fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

class FakeSocket(val url: String, val listener: GiovSocketListener) : GiovSocket {
    val sent = mutableListOf<String>()
    var open = false
    var closedByClient = false

    override val isOpen: Boolean get() = open

    override fun send(frame: String): Boolean {
        if (!open) return false
        sent += frame
        if (frame == """{"type":"ping"}""") listener.onMessage("""{"type":"pong"}""")
        return true
    }

    fun receiveRaw(text: String) = listener.onMessage(text)

    /** The network ended the socket. */
    fun drop() {
        open = false
        listener.onClosed(null, null)
    }

    override fun close(code: Int?, reason: String?) {
        closedByClient = true
        open = false
    }

    fun accept() {
        open = true
        listener.onOpen()
    }

    fun receive(vararg pairs: Pair<String, Any?>) = listener.onMessage(json(*pairs).toString())

    fun messageFrames(): List<JsonObject> =
        sent.map { Json.parseToJsonElement(it) as JsonObject }.filter { it["type"].text() == "message" }
}

class Sockets : GiovSocketFactory {
    val all = mutableListOf<FakeSocket>()
    val last: FakeSocket get() = all.last()

    override fun create(url: String, listener: GiovSocketListener): GiovSocket = FakeSocket(url, listener).also { all += it }
}

class LoggedRequest(val method: String, val url: String, val body: String) {
    val path: String get() = URI(url).path
    val json: JsonObject get() = if (body.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(body) as JsonObject
}

class FakeServer(private val now: () -> Long) : GiovHttp {
    val requests = mutableListOf<LoggedRequest>()
    val calls = mutableMapOf<String, Int>()

    var config: Map<String, Any?> = mapOf(
        "title" to "Acme",
        "theme" to "light",
        "primaryColor" to "#123456",
        "greetingText" to "Hi",
        "botEnabled" to true,
        "botName" to "Atlas",
    )
    var history: List<Map<String, Any?>> = emptyList()
    var configStatus = 200
    /** How long an upload takes. */
    var uploadDelayMs = 0L
    /** Whether the rating and feedback routes exist (an older server answers 404). */
    var ratingsEnabled = true

    /** `open` or `closed` in the history answer. */
    var status = "open"

    fun count(key: String): Int = calls[key] ?: 0

    private fun reply(body: Map<String, Any?>, status: Int = 200) = GiovHttpResponse(status, jsonValue(body).toString(), emptyMap())

    private fun bump(key: String) {
        calls[key] = count(key) + 1
    }

    override suspend fun send(request: GiovHttpRequest): GiovHttpResponse {
        val logged = LoggedRequest(request.method, request.url, request.jsonBody.orEmpty())
        requests += logged
        val path = logged.path
        if (path == "/widget/config") {
            bump("config")
            return if (configStatus == 200) reply(config) else reply(mapOf("error" to "boom"), configStatus)
        }
        if (path == "/conversations" && request.method == "POST") {
            if (logged.json["conversationId"] != null) {
                bump("resumed")
                return reply(mapOf("conversationId" to testSession.conversationId, "sessionToken" to testSession.sessionToken))
            }
            bump("created")
            return reply(
                mapOf(
                    "conversationId" to testSession.conversationId,
                    "sessionToken" to testSession.sessionToken,
                    "wsUrl" to "wss://api.example.test/conversations/conv-1/ws",
                ),
            )
        }
        if (path == "/auth/widget-token") {
            bump("token")
            return reply(
                mapOf(
                    "token" to "visitor-token-${count("token")}",
                    "expiresAt" to now() / 1000 + 900,
                    "attachmentAccess" to mapOf("token" to "access-token-abcdefgh", "expiresAt" to now() + 3_600_000),
                ),
            )
        }
        if (path.endsWith("/history")) {
            bump("history")
            return reply(mapOf("status" to status, "messages" to history))
        }
        if (path.endsWith("/upload-visitor")) {
            bump("upload")
            if (uploadDelayMs > 0) kotlinx.coroutines.delay(uploadDelayMs)
            return reply(mapOf("url" to "https://api.example.test/conversations/conv-1/attachments/a.png", "filename" to "a.png"))
        }
        if (path.endsWith("/rating") && request.method == "POST") {
            bump("rating")
            if (!ratingsEnabled) return reply(mapOf("error" to "not_found"), 404)
            val score = logged.json["score"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() }
            return reply(mapOf("requestId" to (logged.json["requestId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: "req-1"), "score" to score, "comment" to null, "closesAt" to System.currentTimeMillis() + 86_400_000, "ratedAt" to now()))
        }
        if (path.endsWith("/feedback") && request.method == "POST") {
            bump("feedback")
            if (!ratingsEnabled) return reply(mapOf("error" to "not_found"), 404)
            return reply(mapOf("messageId" to "x", "value" to null, "reason" to null))
        }
        if (path.endsWith("/push-token")) return reply(mapOf("registered" to true))
        return reply(mapOf("error" to "not_found"), 404)
    }

    /** Pre-fills what POST /conversations can be asked for, to check a request carried a field. */
    fun lastCreate(): LoggedRequest = requests.last { it.path == "/conversations" && it.method == "POST" }
}
