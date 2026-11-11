package app.giovchat.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import kotlin.random.Random

val testSession = GiovSession("conv-1", "b".repeat(64))

/** 2026-01-01T00:00:00Z */
const val EPOCH: Long = 1_767_225_600_000L

/** A random source whose jitter is always zero, so backoff delays are exact. */
object NoJitter : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

/** Converts plain Kotlin values to JSON. */
fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to jsonValue(v) })

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

/** A socket the test drives by hand. */
class FakeSocket(val url: String, val listener: GiovSocketListener, val autoPong: Boolean) : GiovSocket {
    val sent = mutableListOf<String>()
    var open = false
    var closedByClient = false

    override val isOpen: Boolean get() = open

    override fun send(frame: String): Boolean {
        if (!open) return false
        sent += frame
        // A real server answers after the ping has left, never inside send().
        if (autoPong && frame == """{"type":"ping"}""") listener.onMessage("""{"type":"pong"}""")
        return true
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

    fun receiveRaw(text: String) = listener.onMessage(text)

    /** The server (or network) ended the socket. */
    fun drop(code: Int? = null, reason: String? = null) {
        open = false
        listener.onClosed(code, reason)
    }

    /** The upgrade was refused (or the connection broke) with [status]. */
    fun fail(status: Int?, message: String = "failed") {
        open = false
        listener.onFailure(RuntimeException(message), status)
    }

    fun frames(type: String? = null): List<JsonObject> =
        sent.map { Json.parseToJsonElement(it) as JsonObject }.filter { type == null || it["type"].asString() == type }

    fun messageFrames(): List<JsonObject> = frames("message")

    /** Frame types in order, without pings. */
    fun types(): List<String?> = frames().map { it["type"].asString() }.filter { it != "ping" }
}

class Sockets : GiovSocketFactory {
    val all = mutableListOf<FakeSocket>()
    var autoPong = true
    val last: FakeSocket get() = all.last()

    override fun create(url: String, listener: GiovSocketListener): GiovSocket = FakeSocket(url, listener, autoPong).also { all += it }
}

class LoggedRequest(val method: String, val url: String, val headers: Map<String, String>, val json: JsonObject, val upload: GiovUploadBody?) {
    val path: String get() = URI(url).path
    val query: String? get() = URI(url).query
}

/** A small Giov API: config, conversation start/resume, tokens, history, upload and push. */
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
        "botAvatarUrl" to "https://cdn.test/atlas.png",
        "avatarUrl" to "https://cdn.test/team.png",
    )
    var history: List<Map<String, Any?>> = emptyList()
    var status = "open"
    var assignedAgentName: String? = null
    var assignedAgentAvatar: String? = null

    /** Status codes to answer the next calls with (one per call), before the normal behaviour. */
    val historyStatuses = ArrayDeque<Int>()
    val tokenStatuses = ArrayDeque<Int>()
    val uploadStatuses = ArrayDeque<Int>()
    var resumeStatus: Int? = null
    var resumeError: String? = null
    var startStatus: Int? = null
    var startError: String? = null
    var pushStatus = 200
    var uploadDelayMs = 0L
    var tokenLifetimeSeconds = 900
    var accessLifetimeMs = 3_600_000L
    /** How long starting a conversation takes. */
    var startDelayMs = 0L
    /** Ids for the next conversations started (one per start); [testSession]'s id once it is empty. */
    val createdIds = ArrayDeque<String>()

    fun count(key: String): Int = calls[key] ?: 0

    fun to(path: String, method: String? = null): List<LoggedRequest> = requests.filter { it.path == path && (method == null || it.method == method) }

    private fun reply(body: Map<String, Any?>, status: Int = 200, headers: Map<String, String> = emptyMap()) =
        GiovHttpResponse(status, jsonValue(body).toString(), headers)

    private fun bump(key: String) {
        calls[key] = count(key) + 1
    }

    override suspend fun send(request: GiovHttpRequest): GiovHttpResponse {
        val body = request.jsonBody?.let { Json.parseToJsonElement(it) as JsonObject } ?: buildJsonObject { }
        val logged = LoggedRequest(request.method, request.url, request.headers, body, request.upload)
        requests += logged
        val path = logged.path
        if (path == "/widget/config") {
            bump("config")
            return reply(config)
        }
        if (path == "/conversations" && request.method == "POST") {
            if (body["conversationId"] != null) {
                bump("resumed")
                resumeStatus?.let { return reply(mapOf("error" to (resumeError ?: "invalid_session")), it) }
                return reply(mapOf("conversationId" to testSession.conversationId, "sessionToken" to testSession.sessionToken))
            }
            bump("created")
            if (startDelayMs > 0) delay(startDelayMs)
            startStatus?.let {
                return reply(mapOf("error" to (startError ?: "workspace_busy")), it, if (it == 503) mapOf("retry-after" to "30") else emptyMap())
            }
            return reply(
                mapOf(
                    "conversationId" to (createdIds.removeFirstOrNull() ?: testSession.conversationId),
                    "sessionToken" to testSession.sessionToken,
                    "wsUrl" to "wss://api.example.test/conversations/conv-1/ws",
                ),
            )
        }
        if (path == "/auth/widget-token") {
            bump("token")
            tokenStatuses.removeFirstOrNull()?.let { return reply(mapOf("error" to "invalid_session"), it) }
            return reply(
                mapOf(
                    "token" to "visitor-token-${count("token")}",
                    "expiresAt" to now() / 1000 + tokenLifetimeSeconds,
                    "attachmentAccess" to mapOf("token" to "access-token-abcdefgh", "expiresAt" to now() + accessLifetimeMs),
                ),
            )
        }
        if (path.endsWith("/history")) {
            bump("history")
            historyStatuses.removeFirstOrNull()?.let { return reply(mapOf("error" to "unauthorized"), it) }
            return reply(mapOf("status" to status, "messages" to history, "assignedAgentName" to assignedAgentName, "assignedAgentAvatar" to assignedAgentAvatar))
        }
        if (path.endsWith("/upload-visitor")) {
            bump("upload")
            val upload = request.upload
            upload?.onProgress?.invoke(0.5)
            if (uploadDelayMs > 0) delay(uploadDelayMs)
            uploadStatuses.removeFirstOrNull()?.let { return reply(mapOf("error" to "invalid_token"), it) }
            val name = upload?.file?.name ?: "a.png"
            return reply(mapOf("url" to "https://api.example.test/conversations/conv-1/attachments/$name", "filename" to name))
        }
        if (path.endsWith("/push-token")) {
            bump("push-${request.method}")
            return if (pushStatus == 200) reply(mapOf("registered" to true)) else reply(mapOf("error" to "not_found"), pushStatus)
        }
        return reply(mapOf("error" to "not_found"), 404)
    }
}

/** A [GiovSessionStore] that records what happens. */
open class RecordingStore : GiovSessionStore {
    private val inner = InMemoryGiovSessionStore()
    val log = mutableListOf<String>()

    override suspend fun read(scope: String): GiovSession? = inner.read(scope)

    override suspend fun write(scope: String, session: GiovSession) {
        log += "write"
        inner.write(scope, session)
    }

    override suspend fun delete(scope: String) {
        log += "delete"
        inner.delete(scope)
    }

    suspend fun seed(scope: String, session: GiovSession) = inner.write(scope, session)
}

/** What the client reported, in order. */
internal class RecordingListener : ClientListener {
    val messages = mutableListOf<GiovMessage>()
    val acks = mutableListOf<Pair<String, GiovMessage>>()
    val streams = mutableListOf<String>()
    val typing = mutableListOf<String>()
    val connection = mutableListOf<Boolean>()
    val errors = mutableListOf<GiovChatException>()
    val access = mutableListOf<AttachmentAccess>()

    override fun onMessage(message: GiovMessage) {
        messages += message
    }

    override fun onAck(clientMessageId: String, message: GiovMessage) {
        acks += clientMessageId to message
    }

    override fun onStream(event: StreamEvent) {
        streams += when (event) {
            is StreamEvent.Update -> "${event.streamId}:${event.content}"
            is StreamEvent.End -> "${event.streamId}:end"
        }
    }

    override fun onTyping(active: Boolean, isBot: Boolean?) {
        typing += "$active/$isBot"
    }

    override fun onConnection(connected: Boolean) {
        connection += connected
    }

    override fun onError(error: GiovChatException) {
        errors += error
    }

    override fun onAttachmentAccess(access: AttachmentAccess) {
        this.access += access
    }
}

/** Virtual time for a test: a scheduler, a single-threaded test dispatcher and helpers to move time and run suspend code. */
@OptIn(ExperimentalCoroutinesApi::class)
open class TimeRig {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val now: () -> Long = { EPOCH + scheduler.currentTime }
    val elapsedMs: Long get() = scheduler.currentTime

    /** Runs everything that is ready now, a few rounds so chained work settles. */
    fun pump(rounds: Int = 12) {
        repeat(rounds) { scheduler.runCurrent() }
    }

    /** Moves virtual time forward, running whatever comes due (including tasks due exactly at the end). */
    fun elapse(ms: Long) {
        scheduler.advanceTimeBy(ms)
        pump()
    }

    /** Runs [block] to completion on the test dispatcher and returns its value (or throws what it threw). */
    fun <T> settle(block: suspend () -> T): T {
        val deferred = scope.async { block() }
        return await(deferred)
    }

    fun <T> await(deferred: Deferred<T>): T {
        pump(40)
        check(deferred.isCompleted) { "The call is still pending." }
        @Suppress("OPT_IN_USAGE")
        return deferred.getCompleted()
    }

    fun <T> start(block: suspend () -> T): Deferred<T> = scope.async { block() }

    fun failure(deferred: Deferred<*>): Throwable? {
        pump(40)
        @Suppress("OPT_IN_USAGE")
        return if (deferred.isCompleted) deferred.getCompletionExceptionOrNull() else null
    }

    inline fun <reified E : Throwable> assertFails(noinline block: suspend () -> Unit): E {
        val deferred = scope.async { block() }
        pump(40)
        check(deferred.isCompleted) { "The call is still pending." }
        @Suppress("OPT_IN_USAGE")
        val error = deferred.getCompletionExceptionOrNull() ?: error("Expected ${E::class.simpleName}, but the call succeeded")
        if (error !is E) throw AssertionError("Expected ${E::class.simpleName}, got $error", error)
        return error
    }
}
