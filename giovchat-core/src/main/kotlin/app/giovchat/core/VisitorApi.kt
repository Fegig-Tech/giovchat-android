package app.giovchat.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The HTTP seam. The client talks to [GiovHttp], so tests (and apps with special needs, such as certificate
// pinning beyond what OkHttp offers) can supply their own; the default wraps OkHttp.

/** A file upload: `multipart/form-data` with the file and, for images, the placeholder fields. */
public class GiovUploadBody(
    public val file: GiovPickedFile,
    public val thumbnail: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
    /** Called with 0..1 as the bytes leave the device. */
    public val onProgress: ((Double) -> Unit)? = null,
)

/** One HTTP request. [jsonBody] and [upload] are mutually exclusive. */
public class GiovHttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val jsonBody: String? = null,
    public val upload: GiovUploadBody? = null,
)

/** The answer: status, body text and the headers with lower-case names. */
public class GiovHttpResponse(
    public val status: Int,
    public val body: String,
    public val headers: Map<String, String> = emptyMap(),
)

/** Sends one request. Throws [IOException] when the server cannot be reached; any HTTP status is a normal response. */
public fun interface GiovHttp {
    @Throws(IOException::class)
    public suspend fun send(request: GiovHttpRequest): GiovHttpResponse
}

/**
 * The OkHttp client the SDK's defaults share (HTTP, sockets and the Compose image loader), so every controller uses
 * one connection pool and one set of threads. Pass your own client to [OkHttpGiovHttp] / [OkHttpGiovSocketFactory]
 * to change timeouts, proxies or certificate pinning.
 */
public object GiovOkHttp {
    public val client: OkHttpClient by lazy { OkHttpClient() }
}

/** [GiovHttp] over OkHttp. */
public class OkHttpGiovHttp(internal val client: OkHttpClient = GiovOkHttp.client) : GiovHttp {
    override suspend fun send(request: GiovHttpRequest): GiovHttpResponse {
        val builder = Request.Builder().url(request.url)
        for ((name, value) in request.headers) builder.header(name, value)
        val body: RequestBody? = when {
            request.upload != null -> multipart(request.upload)
            request.jsonBody != null -> request.jsonBody.toRequestBody(JSON)
            request.method == "POST" || request.method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(request.method, body)
        client.newCall(builder.build()).await().use { response ->
            val headers = response.headers.names().associate { it.lowercase() to (response.header(it) ?: "") }
            return GiovHttpResponse(response.code, response.body?.string().orEmpty(), headers)
        }
    }

    private fun multipart(upload: GiovUploadBody): RequestBody {
        val file = upload.file
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name.ifEmpty { "file" }, StreamBody(file, file.mimeType.toMediaTypeOrNull() ?: OCTET_STREAM))
        if (upload.thumbnail != null && upload.width != null && upload.height != null) {
            form.addFormDataPart("thumbnail", upload.thumbnail)
            form.addFormDataPart("width", upload.width.toString())
            form.addFormDataPart("height", upload.height.toString())
        }
        return ProgressBody(form.build(), upload.onProgress)
    }

    private companion object {
        val JSON: MediaType = "application/json".toMediaTypeOrNull()!!
        val OCTET_STREAM: MediaType = "application/octet-stream".toMediaTypeOrNull()!!
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/** The picked file's bytes, read lazily from [GiovPickedFile.open]. */
private class StreamBody(private val file: GiovPickedFile, private val type: MediaType) : RequestBody() {
    override fun contentType(): MediaType = type
    override fun contentLength(): Long = file.size
    override fun writeTo(sink: BufferedSink) {
        file.open().source().use { sink.writeAll(it) }
    }
}

/** Reports upload progress (0..1) as the body is written. */
private class ProgressBody(private val delegate: RequestBody, private val onProgress: ((Double) -> Unit)?) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        if (onProgress == null || total <= 0) {
            delegate.writeTo(sink)
            return
        }
        var written = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                onProgress.invoke((written.toDouble() / total).coerceIn(0.0, 1.0))
            }
        }.buffer()
        delegate.writeTo(counting)
        counting.flush()
    }
}

/** Validates the API base URL and removes a trailing slash. */
internal fun normalizeBaseUrl(value: String): String {
    val uri = try { URI(value) } catch (_: Exception) { null }
    require(
        uri != null && (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrEmpty() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null,
    ) { "Use an HTTP(S) API URL without credentials, query parameters or fragments." }
    return value.trimEnd('/')
}

internal fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
