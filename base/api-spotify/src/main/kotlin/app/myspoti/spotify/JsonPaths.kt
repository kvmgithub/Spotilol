package app.myspoti.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Lenient reads over Spotify's JSON: a missing or null field gives null, never an exception.

/** Walks a dotted path such as `"data.searchV2.tracksV2"`. */
internal fun JsonElement?.at(path: String): JsonElement? =
    path.split('.').fold(this) { node, key -> (node as? JsonObject)?.get(key)?.takeUnless { it is JsonNull } }

internal fun JsonElement?.obj(path: String): JsonObject? = at(path) as? JsonObject
internal fun JsonElement?.list(path: String): List<JsonElement> = (at(path) as? JsonArray).orEmpty()
internal fun JsonElement?.str(path: String): String? = (at(path) as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.long(path: String): Long? =
    (at(path) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toLongOrNull() }
internal fun JsonElement?.int(path: String): Int? = long(path)?.toInt()
internal fun JsonElement?.double(path: String): Double? = (at(path) as? JsonPrimitive)?.doubleOrNull
internal fun JsonElement?.bool(path: String): Boolean? = (at(path) as? JsonPrimitive)?.booleanOrNull

/** `__typename` of an entity, to tell `Track` from `NotFound`. */
internal fun JsonElement?.typename(): String? = str("__typename")
