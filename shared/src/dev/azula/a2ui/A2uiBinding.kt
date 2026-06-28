package dev.azula.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Helpers for resolving component property values against a surface's data model.
 *
 * A *binding object* is a [JsonObject] whose ONLY key is "path" with a string value,
 * e.g. `{"path":"/user/name"}`. Any other [JsonElement] is treated as a literal.
 */

/** True when [element] is a binding object (a JsonObject with a single "path" key). */
private fun isBinding(element: JsonElement): Boolean =
    element is JsonObject && element.size == 1 && element.containsKey("path")

/**
 * Resolve a component property [value] against [dataModel].
 * - If [value] is a binding object, look up its path in the data model.
 * - Otherwise return [value] as-is (literal).
 * - Returns null when [value] is null or the binding path is missing.
 */
fun resolve(value: JsonElement?, dataModel: JsonElement): JsonElement? {
    if (value == null) return null
    if (isBinding(value)) {
        val path = (value as JsonObject)["path"]!!.jsonPrimitive.content
        return JsonPointer.get(dataModel, path)
    }
    return value
}

/**
 * Like [resolve], but coerces the result to a String via [JsonPrimitive.content].
 * Returns null if the resolved value is not a primitive or is absent.
 */
fun resolveString(value: JsonElement?, dataModel: JsonElement): String? =
    (resolve(value, dataModel) as? JsonPrimitive)?.content

/**
 * Like [resolve], but coerces to Boolean.
 * Returns null if the resolved value is not a primitive boolean or is absent.
 */
fun resolveBoolean(value: JsonElement?, dataModel: JsonElement): Boolean? {
    val prim = resolve(value, dataModel) as? JsonPrimitive ?: return null
    return prim.content.toBooleanStrictOrNull()
}

/**
 * Like [resolve], but coerces to Double.
 * Returns null if the resolved value is not a numeric primitive or is absent.
 */
fun resolveDouble(value: JsonElement?, dataModel: JsonElement): Double? {
    val prim = resolve(value, dataModel) as? JsonPrimitive ?: return null
    return prim.content.toDoubleOrNull()
}

/**
 * Like [resolve], but coerces to a List<String>.
 * - If the resolved value is a [JsonArray], maps each element to its string content
 *   (primitives) and filters out non-primitives.
 * - Otherwise returns an empty list.
 */
fun resolveStringList(value: JsonElement?, dataModel: JsonElement): List<String> {
    val resolved = resolve(value, dataModel) ?: return emptyList()
    return when (resolved) {
        is JsonArray -> resolved.filterIsInstance<JsonPrimitive>().map { it.content }
        else -> emptyList()
    }
}

/** Convenience accessor: returns `component[key]` or null. */
fun prop(component: JsonObject, key: String): JsonElement? = component[key]
