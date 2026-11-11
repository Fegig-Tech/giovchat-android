package app.giovchat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// The protocol is parsed defensively (docs/protocol/README.md, "Versioning"): unknown fields are ignored and
// malformed values fall back instead of throwing.

internal val EMPTY_OBJECT: JsonObject = JsonObject(emptyMap())

/** The object [text] holds, or an empty one when it is not valid JSON or not an object. */
internal fun parseJsonObject(text: String): JsonObject =
    parseJson(text) as? JsonObject ?: EMPTY_OBJECT

internal fun parseJson(text: String): JsonElement? =
    try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

internal fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: EMPTY_OBJECT

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A trimmed, non-empty string, or null. */
internal fun JsonElement?.asNonEmpty(): String? = asString()?.trim()?.takeIf { it.isNotEmpty() }

/** A finite JSON number, or null. */
internal fun JsonElement?.asNumber(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

internal fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

internal fun JsonElement?.isFalse(): Boolean = (this as? JsonPrimitive)?.let { !it.isString && it.content == "false" } == true

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

private val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

/** An http(s) URL, or null. Images and links from the network are used only when they pass. */
internal fun safeHttpUrl(value: JsonElement?): String? = value.asNonEmpty()?.takeIf { HTTP_URL.containsMatchIn(it) }

internal fun safeHttpUrl(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && HTTP_URL.containsMatchIn(it) }
