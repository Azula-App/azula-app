package dev.azula.a2ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Holds the live Compose snapshot state for a single A2UI surface:
 * its component tree and its data model.
 *
 * Both [components] and [dataModel] are Compose snapshot state, so any
 * Composable that reads them will recompose automatically when they change.
 */
class A2uiSurface(val surfaceId: String, val catalogId: String? = null) {

    /** Map from component id → component definition JsonObject. */
    val components: SnapshotStateMap<String, JsonObject> = androidx.compose.runtime.mutableStateMapOf()

    /** The surface's JSON data model; updated by [UpdateDataModel] messages or [setData]. */
    var dataModel: JsonElement by mutableStateOf(JsonObject(emptyMap()))
        private set

    /**
     * Apply an incoming agent message to this surface.
     *
     * - [UpdateComponents]: upserts each component into [components] keyed by its "id" field.
     * - [UpdateDataModel]: runs a JsonPointer.set on [dataModel].
     * - [CreateSurface] / [DeleteSurface]: no-op (lifecycle handled by the caller).
     */
    fun apply(msg: A2uiMessage) {
        when (msg) {
            is UpdateComponents -> {
                for (component in msg.components) {
                    val id = component["id"]?.jsonPrimitive?.content ?: continue
                    components[id] = component
                }
            }
            is UpdateDataModel -> {
                dataModel = JsonPointer.set(dataModel, msg.path, msg.value)
            }
            else -> Unit
        }
    }

    /**
     * Directly write [value] at the given RFC 6901 [path] in [dataModel].
     * Useful for two-way input bindings.
     */
    fun setData(path: String, value: JsonElement) {
        dataModel = JsonPointer.set(dataModel, path, value)
    }

    /** Returns the root component (the one with id "root"), or null if absent. */
    fun root(): JsonObject? = components["root"]

    /** Returns the component with the given [id], or null. */
    fun component(id: String): JsonObject? = components[id]
}
