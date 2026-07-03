package dev.azula.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Unicode die faces; index 0 = "⚀" (1-pip) … index 5 = "⚅" (6-pip). */
val DICE = listOf("⚀", "⚁", "⚂", "⚃", "⚄", "⚅")

object A2uiExamples {

    // ---- Surface builders -----------------------------------------------

    /**
     * Build a ready-to-display dice surface.
     * surfaceId = "dice-$id".
     */
    fun diceSurface(id: String): A2uiSurface {
        val surfaceId = "dice-$id"
        val surface = A2uiSurface(surfaceId)

        // Component tree
        val components = listOf(
            component("root",      "Card")   { put("child", "col") },
            component("col",       "Column") {
                put("children", JsonArray(listOf(
                    JsonPrimitive("title"),
                    JsonPrimitive("faces"),
                    JsonPrimitive("result"),
                    JsonPrimitive("rollBtn"),
                )))
                put("align", "center")
            },
            component("title",     "Text")   {
                put("text", "AZULA · DICE")
                put("variant", "caption")
            },
            component("faces",     "Row")    {
                put("children", JsonArray(listOf(
                    JsonPrimitive("you"),
                    JsonPrimitive("vs"),
                    JsonPrimitive("them"),
                )))
                put("justify", "center")
                put("align", "center")
            },
            component("you",       "Text")   {
                put("text", buildJsonObject { put("path", "/dice/you") })
                put("variant", "h1")
            },
            component("vs",        "Text")   {
                put("text", "vs")
                put("variant", "body")
            },
            component("them",      "Text")   {
                put("text", buildJsonObject { put("path", "/dice/them") })
                put("variant", "h1")
            },
            component("result",    "Text")   {
                put("text", buildJsonObject { put("path", "/dice/result") })
                put("variant", "body")
            },
            component("rollLabel", "Text")   { put("text", "ROLL") },
            component("rollBtn",   "Button") {
                put("child", "rollLabel")
                put("variant", "primary")
                put("action", buildJsonObject {
                    putJsonObject("event") { put("name", "roll") }
                })
            },
        )
        surface.apply(UpdateComponents(surfaceId, components))

        // Initial data model
        surface.setData("/dice/you",    JsonPrimitive("?"))
        surface.setData("/dice/them",   JsonPrimitive("?"))
        surface.setData("/dice/result", JsonPrimitive(""))
        return surface
    }

    // ---- Local event interpreter ----------------------------------------

    /**
     * Handle a locally-triggered A2UI action (no live stream).
     */
    fun handleEvent(surface: A2uiSurface, action: A2uiAction) {
        when (action.name) {
            "roll" -> handleRoll(surface)
        }
    }

    private fun handleRoll(surface: A2uiSurface) {
        val you  = (1..6).random()
        val them = (1..6).random()
        surface.setData("/dice/you",  JsonPrimitive(DICE[you - 1]))
        surface.setData("/dice/them", JsonPrimitive(DICE[them - 1]))
        val result = when {
            you > them -> "you win 🏆"
            you < them -> "zuko wins"
            else       -> "tie — roll again"
        }
        surface.setData("/dice/result", JsonPrimitive(result))
    }

    // ---- Private helper -------------------------------------------------

    private fun component(
        id: String,
        type: String,
        extras: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("component", type)
        extras()
    }
}
