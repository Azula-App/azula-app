package dev.azula.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /**
     * Build a ready-to-display decide surface.
     */
    fun decideSurface(id: String, q: String, opts: List<String>): A2uiSurface {
        val surfaceId = "decide-$id"
        val surface = A2uiSurface(surfaceId)

        val optComponentIds = opts.mapIndexed { i, _ -> "opt$i" }
        val optLabelIds     = opts.mapIndexed { i, _ -> "optLabel$i" }

        val components = mutableListOf(
            component("root",    "Card")   { put("child", "col") },
            component("col",     "Column") {
                put("children", JsonArray(
                    (listOf("title", "question", "optRow", "chosenResult")
                        .map { JsonPrimitive(it) })
                ))
            },
            component("title",   "Text")   {
                put("text", "AZULA · DECIDE")
                put("variant", "caption")
            },
            component("question","Text")   { put("text", q) },
            component("optRow",  "Row")    {
                put("children", JsonArray(optComponentIds.map { JsonPrimitive(it) }))
            },
            component("chosenResult", "Text") {
                put("text", buildJsonObject { put("path", "/decide/chosen") })
                put("variant", "body")
            },
        )

        // One Button per option
        opts.forEachIndexed { i, opt ->
            val labelId = optLabelIds[i]
            val btnId   = optComponentIds[i]
            components += component(labelId, "Text") { put("text", opt) }
            components += component(btnId, "Button") {
                put("child", labelId)
                put("action", buildJsonObject {
                    putJsonObject("event") {
                        put("name", "pick")
                        putJsonObject("context") { put("opt", opt) }
                    }
                })
            }
        }

        surface.apply(UpdateComponents(surfaceId, components))
        surface.setData("/decide/chosen", JsonPrimitive(""))
        return surface
    }

    /**
     * Build a ready-to-display poll surface.
     * [opts] is a list of (label, initialVotes) pairs.
     */
    fun pollSurface(id: String, q: String, opts: List<Pair<String, Int>>): A2uiSurface {
        val surfaceId = "poll-$id"
        val surface = A2uiSurface(surfaceId)

        val optComponentIds = opts.mapIndexed { i, _ -> "pollOpt$i" }
        val optLabelIds     = opts.mapIndexed { i, _ -> "pollOptLabel$i" }

        val components = mutableListOf(
            component("root",        "Card")   { put("child", "col") },
            component("col",         "Column") {
                put("children", JsonArray(
                    (listOf("title", "question", "optCol", "pollResult")
                        .map { JsonPrimitive(it) })
                ))
            },
            component("title",       "Text")   {
                put("text", "AZULA · POLL")
                put("variant", "caption")
            },
            component("question",    "Text")   { put("text", q) },
            component("optCol",      "Column") {
                put("children", JsonArray(optComponentIds.map { JsonPrimitive(it) }))
            },
            component("pollResult",  "Text")   {
                put("text", buildJsonObject { put("path", "/poll/result") })
                put("variant", "body")
            },
        )

        opts.forEachIndexed { i, (label, _) ->
            val labelId = optLabelIds[i]
            val btnId   = optComponentIds[i]
            components += component(labelId, "Text") { put("text", label) }
            components += component(btnId, "Button") {
                put("child", labelId)
                put("action", buildJsonObject {
                    putJsonObject("event") {
                        put("name", "vote")
                        putJsonObject("context") { put("index", i) }
                    }
                })
            }
        }

        surface.apply(UpdateComponents(surfaceId, components))

        // Initial counts
        val countsArray = JsonArray(opts.map { (_, votes) -> JsonPrimitive(votes) })
        surface.setData("/poll/counts", countsArray)
        surface.setData("/poll/result", JsonPrimitive(""))
        return surface
    }

    // ---- Local event interpreter ----------------------------------------

    /**
     * Handle a locally-triggered A2UI action (no live stream).
     */
    fun handleEvent(surface: A2uiSurface, action: A2uiAction) {
        when (action.name) {
            "roll" -> handleRoll(surface)
            "pick" -> handlePick(surface, action)
            "vote" -> handleVote(surface, action)
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

    private fun handlePick(surface: A2uiSurface, action: A2uiAction) {
        val opt = action.context["opt"]?.jsonPrimitive?.content ?: return
        surface.setData("/decide/chosen", JsonPrimitive("✓ locked in: $opt"))
    }

    private fun handleVote(surface: A2uiSurface, action: A2uiAction) {
        val index = action.context["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
        // Read the current counts array
        val current = JsonPointer.get(surface.dataModel, "/poll/counts") as? JsonArray ?: return
        val counts = current.map { (it as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }.toMutableList()
        if (index < 0 || index >= counts.size) return
        counts[index] = counts[index] + 1
        val updated = JsonArray(counts.map { JsonPrimitive(it) })
        surface.setData("/poll/counts", updated)

        // Recompute result string: "label: N · label: N · …"
        // We need to get the option labels from the component tree
        val resultParts = counts.mapIndexed { i, n ->
            val labelId = "pollOptLabel$i"
            val labelComp = surface.component(labelId)
            val label = labelComp?.get("text")?.jsonPrimitive?.content ?: "option $i"
            "$label: $n"
        }
        surface.setData("/poll/result", JsonPrimitive(resultParts.joinToString(" · ")))
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
