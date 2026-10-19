package com.indiotube.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

// ---- JSON navigation helpers ------------------------------------------------
// InnerTube payloads reshuffle their renderer nesting regularly. Parsing is
// deliberately layout-tolerant: known paths first, then a bounded tree scan,
// so a renderer change degrades gracefully instead of breaking a screen.

fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun JsonElement?.obj(key: String): JsonObject? = field(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? = field(key) as? JsonArray

fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) {
        cur = when (k) {
            is Int -> (cur as? JsonArray)?.getOrNull(k)
            is String -> (cur as? JsonObject)?.get(k)
            else -> null
        } ?: return null
    }
    return cur
}

fun JsonElement?.pathStr(vararg keys: Any): String? {
    val v = path(*keys) as? JsonPrimitive ?: return null
    return if (v is JsonNull) null else v.contentOrNull
}

// Bounded breadth-first scan for every value stored under `key`.
fun JsonElement?.findAll(key: String, maxDepth: Int = 14, limit: Int = 60): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    if (this == null) return out
    var current = listOf(this)
    var depth = 0
    while (current.isNotEmpty() && depth <= maxDepth && out.size < limit) {
        val next = ArrayList<JsonElement>()
        for (el in current) {
            when (el) {
                is JsonObject -> {
                    el[key]?.let { if (out.size < limit) out.add(it) }
                    for (v in el.values) next.add(v)
                }
                is JsonArray -> for (v in el) next.add(v)
                else -> {}
            }
        }
        current = next
        depth++
    }
    return out
}

// Join the text of a runs[] array ("title": {"runs": [{"text": "a"}, ...]}).
fun JsonElement?.runsText(): String? {
    val runs = arr("runs") ?: return null
    val joined = runs.mapNotNull { it.pathStr("text") }.joinToString("").trim()
    return joined.ifEmpty { null }
}

// simpleText or runs, whichever the renderer uses today.
fun JsonElement?.textEither(): String? = str("simpleText") ?: runsText()
