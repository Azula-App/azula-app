package dev.azula.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---- Message types ----

sealed interface A2uiMessage {
    val surfaceId: String
}

data class CreateSurface(
    override val surfaceId: String,
    val catalogId: String? = null,
    val sendDataModel: Boolean = false,
) : A2uiMessage

data class UpdateComponents(
    override val surfaceId: String,
    val components: List<JsonObject>,
) : A2uiMessage

data class UpdateDataModel(
    override val surfaceId: String,
    val path: String,
    val value: JsonElement,
) : A2uiMessage

data class DeleteSurface(
    override val surfaceId: String,
) : A2uiMessage

// ---- Action ----

data class A2uiAction(
    val name: String,
    val surfaceId: String,
    val sourceComponentId: String,
    val context: JsonObject = JsonObject(emptyMap()),
)

// ---- Constants + shared Json instance ----

const val A2UI_VERSION = "v0.9.1"

val A2uiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---- Codec ----

object A2uiCodec {

    /**
     * Parse a newline-delimited JSON message from the agent.
     * The message type is identified by the presence of a specific key:
     * "createSurface", "updateComponents", "updateDataModel", or "deleteSurface".
     * Returns null for unrecognized or malformed input.
     */
    fun parse(line: String): A2uiMessage? = runCatching {
        val obj = A2uiJson.parseToJsonElement(line).jsonObject

        obj["createSurface"]?.jsonObject?.let { cs ->
            return@runCatching CreateSurface(
                surfaceId = cs["surfaceId"]!!.jsonPrimitive.content,
                catalogId = cs["catalogId"]?.jsonPrimitive?.content,
                sendDataModel = cs["sendDataModel"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
        }

        obj["updateComponents"]?.jsonObject?.let { uc ->
            return@runCatching UpdateComponents(
                surfaceId = uc["surfaceId"]!!.jsonPrimitive.content,
                components = uc["components"]!!.jsonArray.map { it.jsonObject },
            )
        }

        obj["updateDataModel"]?.jsonObject?.let { udm ->
            return@runCatching UpdateDataModel(
                surfaceId = udm["surfaceId"]!!.jsonPrimitive.content,
                path = udm["path"]!!.jsonPrimitive.content,
                value = udm["value"]!!,
            )
        }

        obj["deleteSurface"]?.jsonObject?.let { ds ->
            return@runCatching DeleteSurface(
                surfaceId = ds["surfaceId"]!!.jsonPrimitive.content,
            )
        }

        null // unrecognized key
    }.getOrNull()

    /**
     * Encode a client→agent action as a JsonObject:
     * {"version":"v0.9.1","action":{"name":…,"surfaceId":…,"sourceComponentId":…,"context":{…}}}
     */
    fun encodeAction(action: A2uiAction): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(A2UI_VERSION))
        put("action", buildJsonObject {
            put("name", JsonPrimitive(action.name))
            put("surfaceId", JsonPrimitive(action.surfaceId))
            put("sourceComponentId", JsonPrimitive(action.sourceComponentId))
            put("context", action.context)
        })
    }
}
