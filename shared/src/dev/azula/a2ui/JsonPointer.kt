package dev.azula.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Minimal RFC 6901 JSON Pointer implementation over kotlinx.serialization's
 * immutable JsonElement tree.
 *
 * Token unescaping: "~1" → "/", "~0" → "~" (applied in that order per spec).
 */
object JsonPointer {

    private fun unescape(token: String): String =
        token.replace("~1", "/").replace("~0", "~")

    private fun tokens(pointer: String): List<String> {
        if (pointer == "") return emptyList()
        // A valid pointer starts with '/'; split on '/' and drop the leading empty element.
        return pointer.split("/").drop(1).map { unescape(it) }
    }

    /**
     * Resolve [pointer] against [root].
     * "" returns [root] itself.
     * Returns null if any step is missing or the token is out of range.
     */
    fun get(root: JsonElement, pointer: String): JsonElement? {
        if (pointer == "") return root
        var current: JsonElement = root
        for (token in tokens(pointer)) {
            current = when (current) {
                is JsonObject -> current[token] ?: return null
                is JsonArray -> {
                    val idx = token.toIntOrNull() ?: return null
                    current.getOrNull(idx) ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    /**
     * Return a new JSON tree identical to [root] but with [value] placed at
     * [pointer]. Intermediate [JsonObject] nodes are created as needed.
     * A numeric token into an existing [JsonArray] replaces that index.
     * The pointer "" replaces the root entirely.
     */
    fun set(root: JsonElement, pointer: String, value: JsonElement): JsonElement {
        if (pointer == "") return value
        return setAt(root, tokens(pointer), value)
    }

    private fun setAt(node: JsonElement, tokens: List<String>, value: JsonElement): JsonElement {
        if (tokens.isEmpty()) return value

        val head = tokens.first()
        val tail = tokens.drop(1)

        return when {
            node is JsonArray && head.toIntOrNull() != null -> {
                val idx = head.toInt()
                val updated = node.toMutableList()
                if (idx < updated.size) {
                    updated[idx] = setAt(updated[idx], tail, value)
                }
                // Out-of-range index into an existing array: no-op (return unchanged)
                JsonArray(updated)
            }
            node is JsonObject -> {
                val child = node[head] ?: JsonObject(emptyMap())
                val updated = node.toMutableMap()
                updated[head] = setAt(child, tail, value)
                JsonObject(updated)
            }
            else -> {
                // Scalar node but we still need to descend: create a JsonObject layer.
                val child = JsonObject(emptyMap())
                val updated = mutableMapOf(head to setAt(child, tail, value))
                JsonObject(updated)
            }
        }
    }
}
