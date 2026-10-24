package dev.apkcli.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Terse accessors for the JSON that crosses the native boundary and the LLM
 * wire protocols.
 *
 * Both of those are places where the shape is defined by someone else and a
 * missing field is normal, so everything here is forgiving by design: ask for
 * what you want, name a default, move on. Strict `@Serializable` classes are
 * used where *we* own the schema (settings, agent definitions) — not here.
 */

/**
 * Build an object, dropping null values.
 *
 * Omitting a key is almost always what the other side wants: both the native
 * dispatchers and the chat APIs treat "absent" as "use the default", while an
 * explicit `null` is frequently a validation error. Pass [JsonNull] when you
 * really do mean null.
 */
fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        if (value != null) put(key, value.toJsonElement())
    }
}

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(name)
    is Map<*, *> -> JsonObject(
        entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v.toJsonElement() } }.toMap(),
    )
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

fun JsonObject.str(key: String): String? = this[key].prim()?.contentOrNull

fun JsonObject.str(key: String, default: String): String = str(key) ?: default

fun JsonObject.int(key: String, default: Int = 0): Int =
    this[key].prim()?.longOrNull?.toInt() ?: default

fun JsonObject.intOrNull(key: String): Int? = this[key].prim()?.longOrNull?.toInt()

fun JsonObject.long(key: String, default: Long = 0L): Long =
    this[key].prim()?.longOrNull ?: default

fun JsonObject.longOrNull(key: String): Long? = this[key].prim()?.longOrNull

fun JsonObject.double(key: String, default: Double = 0.0): Double =
    this[key].prim()?.doubleOrNull ?: default

fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    this[key].prim()?.booleanOrNull ?: default

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objects(key: String): List<JsonObject> =
    arr(key)?.mapNotNull { it as? JsonObject } ?: emptyList()

fun JsonObject.strings(key: String): List<String> =
    arr(key)?.mapNotNull { it.prim()?.contentOrNull } ?: emptyList()

/** Flatten to a single line for log lines and previews. */
fun JsonElement.preview(limit: Int = 160): String {
    val text = toString().replace(Regex("\\s+"), " ")
    return if (text.length <= limit) text else text.take(limit) + "…"
}
