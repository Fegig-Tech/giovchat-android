package app.giovchat.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

// Attachments, as the protocol describes them (docs/protocol/README.md, "Attachments"): the server's file
// limits and the answering bot's policy decide what a visitor may send, previews from the network are
// validated field by field, and downloads carry a narrow per-conversation access token.

/** The server's upload limit. */
public const val MAX_ATTACHMENT_BYTES: Long = 10L * 1024 * 1024

/** At most this many attachments in one message frame. */
public const val MAX_ATTACHED_FILES: Int = 5

/** Types `upload-visitor` accepts; a policy wildcard cannot enable anything else. */
public val SUPPORTED_ATTACHMENT_TYPES: List<String> = listOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf", "text/plain", "text/csv",
    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "audio/mpeg", "audio/mp4", "audio/ogg", "audio/webm", "audio/wav",
)

/** What a visitor may attach while GiovBot answers (`GET /widget/config` -> `attachments`). */
public class GiovAttachmentPolicy(
    public val enabled: Boolean,
    /** Concrete MIME types (wildcards already expanded against [SUPPORTED_ATTACHMENT_TYPES]). */
    public val allowedTypes: List<String>,
    public val maxSizeMb: Double,
) {
    /** The largest accepted file, in bytes. */
    public val maxBytes: Long get() = (maxSizeMb * 1024 * 1024).toLong()

    public companion object {
        /** Without a published bot policy, visitors may send images (the widget's default). */
        public val DEFAULT: GiovAttachmentPolicy =
            GiovAttachmentPolicy(true, SUPPORTED_ATTACHMENT_TYPES.filter { it.startsWith("image/") }, 10.0)

        internal fun fromJson(json: JsonElement?): GiovAttachmentPolicy? {
            val raw = json as? JsonObject ?: return null
            val patterns = (raw["allowedTypes"] as? JsonArray)?.mapNotNull { it.asString()?.lowercase() } ?: DEFAULT.allowedTypes
            fun matches(type: String) = patterns.any { pattern ->
                pattern == "*" || pattern == type || (pattern.endsWith("/*") && type.startsWith(pattern.dropLast(1)))
            }
            val size = raw["maxSizeMb"].asNumber()
            return GiovAttachmentPolicy(
                enabled = !raw["enabled"].isFalse(),
                allowedTypes = SUPPORTED_ATTACHMENT_TYPES.filter(::matches),
                maxSizeMb = size?.coerceIn(0.1, 10.0) ?: 10.0,
            )
        }
    }
}

/**
 * A file the host's picker returned. The bytes are read lazily through [open]; it is called once per upload
 * attempt (a retried upload opens it again).
 */
public class GiovPickedFile(
    public val name: String,
    /** MIME type, e.g. `image/jpeg`. Files without a known type are refused by the policy check. */
    public val mimeType: String,
    /** Length in bytes. */
    public val size: Long,
    /** Image size, when known; stored so every viewer sees the shape before the image loads. */
    public val width: Int? = null,
    public val height: Int? = null,
    /** Optional placeholder: `data:image/jpeg;base64,...` (or webp), about 32 px, at most 6000 chars. */
    public val thumbnail: String? = null,
    public val open: () -> InputStream,
) {
    public companion object {
        /** A file whose bytes are already in memory. */
        public fun bytes(
            name: String,
            mimeType: String,
            bytes: ByteArray,
            width: Int? = null,
            height: Int? = null,
            thumbnail: String? = null,
        ): GiovPickedFile = GiovPickedFile(name, mimeType, bytes.size.toLong(), width, height, thumbnail) { ByteArrayInputStream(bytes) }
    }
}

/** Why a picked file cannot be sent. */
public sealed interface AttachmentProblem {
    public data object Disabled : AttachmentProblem
    public data class TypeNotAllowed(val name: String) : AttachmentProblem
    public data class TooLarge(val name: String, val maxSizeMb: Double) : AttachmentProblem
    public data class TooMany(val max: Int) : AttachmentProblem
}

/** The picked files checked against the policy; the server stays authoritative. */
public class AttachmentCheck(public val accepted: List<GiovPickedFile>, public val problems: List<AttachmentProblem>)

/** Checks [files] against [policy] and the server limits; [existingCount] files are already attached. */
public fun validateAttachments(files: List<GiovPickedFile>, policy: GiovAttachmentPolicy, existingCount: Int = 0): AttachmentCheck {
    if (!policy.enabled) return AttachmentCheck(emptyList(), if (files.isEmpty()) emptyList() else listOf(AttachmentProblem.Disabled))
    val accepted = mutableListOf<GiovPickedFile>()
    val problems = mutableListOf<AttachmentProblem>()
    val capacity = (MAX_ATTACHED_FILES - existingCount).coerceIn(0, MAX_ATTACHED_FILES)
    for (file in files) {
        if (file.mimeType.lowercase() !in policy.allowedTypes) {
            problems += AttachmentProblem.TypeNotAllowed(file.name)
        } else if (file.size > policy.maxBytes || file.size > MAX_ATTACHMENT_BYTES) {
            problems += AttachmentProblem.TooLarge(file.name, policy.maxSizeMb)
        } else if (accepted.size >= capacity) {
            if (problems.none { it is AttachmentProblem.TooMany }) problems += AttachmentProblem.TooMany(MAX_ATTACHED_FILES)
        } else {
            accepted += file
        }
    }
    return AttachmentCheck(accepted, problems)
}

private val THUMB_PATTERN = Regex("^data:image/(jpeg|webp);base64,[A-Za-z0-9+/=]+$")
private const val MAX_THUMB_LENGTH = 6000

/** A blurred placeholder for an image while it loads. Every field is validated; unsafe ones are dropped. */
public class GiovAttachmentPreview(
    /** A `data:image/jpeg|webp;base64,...` URI of at most 6000 characters, or null. */
    public val thumb: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
) {
    /** The Base64 payload of [thumb] (decode it with `android.util.Base64`), or null. */
    public val thumbBase64: String? get() = thumb?.substringAfter(',', "")?.takeIf { it.isNotEmpty() }

    /** `width / height`, when both are known. */
    public val aspectRatio: Double? get() = if (width != null && height != null) width.toDouble() / height else null

    override fun equals(other: Any?): Boolean =
        other is GiovAttachmentPreview && other.thumb == thumb && other.width == width && other.height == height

    override fun hashCode(): Int = listOf(thumb, width, height).hashCode()

    override fun toString(): String = "GiovAttachmentPreview(${width}x$height, thumb=${thumb != null})"

    public companion object {
        /** Keeps only the parts of an untrusted preview that are safe to render. */
        internal fun sanitize(json: JsonElement?): GiovAttachmentPreview? {
            val map = json as? JsonObject ?: return null
            val thumb = map["thumb"].asString()?.takeIf { it.length <= MAX_THUMB_LENGTH && THUMB_PATTERN.matches(it) }
            fun side(value: JsonElement?) = value.asNumber()?.takeIf { it > 0 && it <= 20000 }?.toInt()
            val width = side(map["width"])
            val height = side(map["height"])
            // An aspect ratio needs both sides, so a lone dimension is dropped.
            val sized = width != null && height != null
            if (thumb == null && !sized) return null
            return GiovAttachmentPreview(thumb, if (sized) width else null, if (sized) height else null)
        }
    }
}

/** A file attached to a message. */
public class GiovAttachment(
    /** The download URL without credentials; load it through [GiovChatController.attachmentUrl]. */
    public val url: String,
    /** Empty when the server did not say; the UI then names it from the URL ([displayName]). */
    public val filename: String = "",
    public val preview: GiovAttachmentPreview? = null,
    /** Known only for files sent from this device. */
    public val contentType: String? = null,
) {
    /** Images render inline; everything else is a file row. */
    public val isImage: Boolean
        get() = when {
            contentType != null -> contentType.startsWith("image/")
            preview != null -> true
            else -> IMAGE_EXTENSION.containsMatchIn(lastSegment(url))
        }

    /** A readable name: the known filename, else the last path segment of the URL. */
    public val displayName: String get() = filename.ifEmpty { lastSegment(url) }

    override fun equals(other: Any?): Boolean = other is GiovAttachment && other.url == url && other.filename == filename &&
        other.preview == preview && other.contentType == contentType

    override fun hashCode(): Int = listOf(url, filename, preview, contentType).hashCode()

    override fun toString(): String = "GiovAttachment($url)"

    private companion object {
        val IMAGE_EXTENSION = Regex("\\.(jpe?g|png|gif|webp)$", RegexOption.IGNORE_CASE)

        fun lastSegment(url: String): String {
            val path = try { URI(url).rawPath ?: url } catch (_: Exception) { url }
            val last = path.split('/').lastOrNull { it.isNotEmpty() }.orEmpty()
            val decoded = try { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { last }
            // Keys look like `attachments/<tenant>/<conversation>/<file>`, URL-encoded into one segment.
            return decoded.substringAfterLast('/')
        }
    }
}

/** `attachmentId` is a JSON-encoded array of URLs; `previews` is a real array aligned by index. */
internal fun parseAttachments(attachmentId: JsonElement?, previews: JsonElement? = null): List<GiovAttachment> {
    val encoded = attachmentId.asString()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val urls = parseJson(encoded) as? JsonArray ?: return emptyList()
    val list = previews as? JsonArray
    val out = mutableListOf<GiovAttachment>()
    urls.forEachIndexed { index, element ->
        val url = element.asString()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
        out += GiovAttachment(url = url, preview = GiovAttachmentPreview.sanitize(list?.getOrNull(index)))
    }
    return out
}

/** A narrow token for downloading one conversation's files (`?at=`), stable for hours. */
public class AttachmentAccess(
    public val token: String,
    /** Unix milliseconds (the wire format). */
    public val expiresAt: Long,
) {
    /** True while more than five minutes remain: stop using a token a little before it expires. */
    public fun usableAt(nowMs: Long): Boolean = expiresAt - nowMs > MARGIN_MS

    override fun equals(other: Any?): Boolean = other is AttachmentAccess && other.token == token && other.expiresAt == expiresAt

    override fun hashCode(): Int = token.hashCode() * 31 + expiresAt.hashCode()

    override fun toString(): String = "AttachmentAccess(expiresAt=$expiresAt)"

    public companion object {
        private val PATTERN = Regex("^[A-Za-z0-9._-]{16,256}$")
        private const val MARGIN_MS: Long = 5 * 60_000L

        internal fun fromJson(json: JsonElement?): AttachmentAccess? {
            val map = json as? JsonObject ?: return null
            val token = map["token"].asString()?.takeIf { PATTERN.matches(it) } ?: return null
            val expiresAt = map["expiresAt"].asNumber() ?: return null
            return AttachmentAccess(token, expiresAt.toLong())
        }

        /** Keeps the current token unless [incoming] expires no earlier. */
        internal fun newer(current: AttachmentAccess?, incoming: AttachmentAccess?): AttachmentAccess? =
            if (incoming != null && !(current != null && current.expiresAt > incoming.expiresAt)) incoming else current
    }
}

/** Adds exactly one credential: `at` when an access token is usable, else the visitor token. */
public fun attachmentDisplayUrl(url: String, accessToken: String? = null, visitorToken: String? = null): String {
    val credential = when {
        accessToken != null -> "at=$accessToken"
        visitorToken != null -> "token=${URLEncoder.encode(visitorToken, "UTF-8")}"
        else -> return url
    }
    return "$url${if ('?' in url) '&' else '?'}$credential"
}

/** The placeholder fields an upload may carry: all three or none, as the server requires. */
internal class UploadPreview(val thumb: String, val width: Int, val height: Int)

internal fun uploadPreview(file: GiovPickedFile): UploadPreview? {
    if (!file.mimeType.startsWith("image/")) return null
    val thumb = file.thumbnail?.takeIf { it.length <= MAX_THUMB_LENGTH && THUMB_PATTERN.matches(it) } ?: return null
    val width = file.width?.takeIf { it in 1..20000 } ?: return null
    val height = file.height?.takeIf { it in 1..20000 } ?: return null
    return UploadPreview(thumb, width, height)
}
