package dev.azula.a2ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts
import dev.azula.ui.decodeImageBitmap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Renders an [A2uiSurface] into Compose.
 *
 * [onAction] is called when the user triggers a Button action.
 * [onDataModelChange] is called when a two-way input (TextField, CheckBox, etc.)
 * changes its value, receiving the RFC-6901 path and the new [JsonElement] value.
 *
 * Both [A2uiSurface.components] and [A2uiSurface.dataModel] are Compose snapshot
 * state, so the tree recomposes automatically when the agent sends updates.
 */
@Composable
fun A2uiSurfaceView(
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (path: String, value: JsonElement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val root = surface.root() ?: return
    val rootId = root["id"]?.jsonPrimitive?.contentOrNull ?: "root"
    Box(modifier) {
        RenderComponent(rootId, surface, onAction, onDataModelChange)
    }
}

// ---------------------------------------------------------------------------
// Internal dispatcher
// ---------------------------------------------------------------------------

@Composable
private fun RenderComponent(
    id: String,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (path: String, value: JsonElement) -> Unit,
) {
    val comp = surface.component(id) ?: return
    val type = prop(comp, "component")?.jsonPrimitive?.contentOrNull ?: return

    when (type) {
        "Text"          -> RenderText(comp, surface)
        "Row"           -> RenderRow(comp, surface, onAction, onDataModelChange)
        "Column"        -> RenderColumn(comp, surface, onAction, onDataModelChange)
        "List"          -> RenderList(comp, surface, onAction, onDataModelChange)
        "Card"          -> RenderCard(comp, surface, onAction, onDataModelChange)
        "Divider"       -> RenderDivider()
        "Tabs"          -> RenderTabs(comp, surface, onAction, onDataModelChange)
        "Modal"         -> RenderModal(comp, surface, onAction, onDataModelChange)
        "Button"        -> RenderButton(comp, surface, onAction, onDataModelChange)
        "TextField"     -> RenderTextField(comp, surface, onDataModelChange)
        "CheckBox"      -> RenderCheckBox(comp, surface, onDataModelChange)
        "ChoicePicker"  -> RenderChoicePicker(comp, surface, onDataModelChange)
        "Slider"        -> RenderSlider(comp, surface, onDataModelChange)
        "DateTimeInput" -> RenderDateTimeInput(comp, surface, onDataModelChange)
        "Image"         -> RenderImage(comp, surface)
        "Icon"          -> RenderIcon(comp, surface)
        "Video"         -> RenderVideoPlaceholder(comp, surface)
        "AudioPlayer"   -> RenderAudioPlaceholder(comp, surface)
        else            -> FallbackChip(type)
    }
}

// ---------------------------------------------------------------------------
// Helper: extract ordered child ids from "children" (JsonArray of id strings)
// ---------------------------------------------------------------------------

private fun childIds(comp: JsonObject): List<String> {
    val children = prop(comp, "children") as? JsonArray ?: return emptyList()
    return children.mapNotNull { it.jsonPrimitive.contentOrNull }
}

// ---------------------------------------------------------------------------
// Arrangement / Alignment helpers
// ---------------------------------------------------------------------------

private fun horizontalArrangementFor(justify: String?): Arrangement.Horizontal = when (justify) {
    "center"       -> Arrangement.Center
    "end"          -> Arrangement.End
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround"  -> Arrangement.SpaceAround
    "spaceEvenly"  -> Arrangement.SpaceEvenly
    else           -> Arrangement.Start // "start" | "stretch" | null
}

private fun verticalArrangementFor(justify: String?): Arrangement.Vertical = when (justify) {
    "center"       -> Arrangement.Center
    "end"          -> Arrangement.Bottom
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround"  -> Arrangement.SpaceAround
    "spaceEvenly"  -> Arrangement.SpaceEvenly
    else           -> Arrangement.Top
}

private fun rowVerticalAlignmentFor(align: String?): Alignment.Vertical = when (align) {
    "center" -> Alignment.CenterVertically
    "end"    -> Alignment.Bottom
    else     -> Alignment.Top // "start" | "stretch" | null
}

private fun columnHorizontalAlignmentFor(align: String?): Alignment.Horizontal = when (align) {
    "center"  -> Alignment.CenterHorizontally
    "end"     -> Alignment.End
    else      -> Alignment.Start
}

// ---------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------

@Composable
private fun RenderText(comp: JsonObject, surface: A2uiSurface) {
    val text    = resolveString(prop(comp, "text"), surface.dataModel) ?: return
    val variant = prop(comp, "variant")?.jsonPrimitive?.contentOrNull ?: "body"
    val (fontSize, fontWeight, color) = when (variant) {
        "h1"      -> Triple(20.sp, FontWeight.Bold,       AzulaColors.text1)
        "h2"      -> Triple(18.sp, FontWeight.Bold,       AzulaColors.text1)
        "h3"      -> Triple(16.sp, FontWeight.SemiBold,   AzulaColors.text1)
        "h4"      -> Triple(15.sp, FontWeight.SemiBold,   AzulaColors.text1)
        "h5"      -> Triple(14.sp, FontWeight.Medium,     AzulaColors.text1)
        "h6"      -> Triple(13.sp, FontWeight.Medium,     AzulaColors.text1)
        "caption" -> Triple(11.sp, FontWeight.Normal,     AzulaColors.textDim)
        else      -> Triple(13.5.sp, FontWeight.Normal,   AzulaColors.text1) // "body"
    }
    Text(
        text       = text,
        color      = color,
        fontFamily = Fonts.ui,
        fontSize   = fontSize,
        fontWeight = fontWeight,
    )
}

// ---------------------------------------------------------------------------
// Row
// ---------------------------------------------------------------------------

@Composable
private fun RenderRow(
    comp: JsonObject,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val justify = prop(comp, "justify")?.jsonPrimitive?.contentOrNull
    val align   = prop(comp, "align")?.jsonPrimitive?.contentOrNull
    Row(
        horizontalArrangement = horizontalArrangementFor(justify),
        verticalAlignment     = rowVerticalAlignmentFor(align),
    ) {
        childIds(comp).forEach { childId ->
            RenderComponent(childId, surface, onAction, onDataModelChange)
        }
    }
}

// ---------------------------------------------------------------------------
// Column
// ---------------------------------------------------------------------------

@Composable
private fun RenderColumn(
    comp: JsonObject,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val justify = prop(comp, "justify")?.jsonPrimitive?.contentOrNull
    val align   = prop(comp, "align")?.jsonPrimitive?.contentOrNull
    Column(
        verticalArrangement   = verticalArrangementFor(justify),
        horizontalAlignment   = columnHorizontalAlignmentFor(align),
    ) {
        childIds(comp).forEach { childId ->
            RenderComponent(childId, surface, onAction, onDataModelChange)
        }
    }
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

@Composable
private fun RenderList(
    comp: JsonObject,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val direction = prop(comp, "direction")?.jsonPrimitive?.contentOrNull ?: "vertical"
    val align     = prop(comp, "align")?.jsonPrimitive?.contentOrNull
    val ids = childIds(comp)
    if (direction == "horizontal") {
        Row(verticalAlignment = rowVerticalAlignmentFor(align)) {
            ids.forEach { RenderComponent(it, surface, onAction, onDataModelChange) }
        }
    } else {
        Column(horizontalAlignment = columnHorizontalAlignmentFor(align)) {
            ids.forEach { RenderComponent(it, surface, onAction, onDataModelChange) }
        }
    }
}

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

@Composable
private fun RenderCard(
    comp: JsonObject,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val childId = prop(comp, "child")?.jsonPrimitive?.contentOrNull
    Column(
        Modifier
            .wrapContentWidth()
            .background(Color(0xFF120C1A))
            .border(2.dp, AzulaColors.pink)
            .padding(14.dp),
    ) {
        if (childId != null) {
            RenderComponent(childId, surface, onAction, onDataModelChange)
        }
    }
}

// ---------------------------------------------------------------------------
// Divider
// ---------------------------------------------------------------------------

@Composable
private fun RenderDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AzulaColors.border),
    )
}

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------

@Composable
private fun RenderTabs(
    comp: JsonObject,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    // Each tab entry: {"title":"…","child":"<id>"}
    val tabsArray = (prop(comp, "tabs") as? JsonArray) ?: return
    val tabs = tabsArray.mapNotNull { it as? JsonObject }
    if (tabs.isEmpty()) return

    var selectedIndex by remember { mutableStateOf(0) }

    Column {
        // Title row
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, tab ->
                val title = tab["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .padding(end = 4.dp, bottom = 0.dp)
                        .border(
                            width = 1.dp,
                            color = if (selected) AzulaColors.pink else AzulaColors.border3,
                        )
                        .background(if (selected) Color(0x22FF2D9B) else Color.Transparent)
                        .clickable { selectedIndex = index }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text       = title,
                        color      = if (selected) AzulaColors.pinkLight else AzulaColors.textDim,
                        fontFamily = Fonts.ui,
                        fontSize   = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        // Divider below titles
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
        // Active tab content
        val activeTab = tabs.getOrNull(selectedIndex)
        val childId   = activeTab?.get("child")?.jsonPrimitive?.contentOrNull
        if (childId != null) {
            Box(Modifier.padding(top = 8.dp)) {
                RenderComponent(childId, surface, onAction, onDataModelChange)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Modal
// ---------------------------------------------------------------------------

@Composable
private fun RenderModal(
    comp: JsonObject,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val triggerId = prop(comp, "trigger")?.jsonPrimitive?.contentOrNull
    val contentId = prop(comp, "content")?.jsonPrimitive?.contentOrNull
    var show by remember { mutableStateOf(false) }

    if (triggerId != null) {
        Box(Modifier.clickable { show = true }) {
            RenderComponent(triggerId, surface, onAction, onDataModelChange)
        }
    }

    if (show && contentId != null) {
        Dialog(onDismissRequest = { show = false }) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .background(Color(0xFF120C1A))
                    .border(2.dp, AzulaColors.pink)
                    .padding(20.dp),
            ) {
                RenderComponent(contentId, surface, onAction, onDataModelChange)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .wrapContentWidth()
                        .border(1.dp, AzulaColors.border3)
                        .clickable { show = false }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("Dismiss", color = AzulaColors.textDim, fontFamily = Fonts.ui, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Button
// ---------------------------------------------------------------------------

@Composable
private fun RenderButton(
    comp: JsonObject,
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val childId = prop(comp, "child")?.jsonPrimitive?.contentOrNull
    val variant = prop(comp, "variant")?.jsonPrimitive?.contentOrNull ?: "default"
    val actionDef = prop(comp, "action") as? JsonObject

    val compId = comp["id"]?.jsonPrimitive?.contentOrNull ?: ""

    val (bgMod, borderMod) = when (variant) {
        "primary"    -> Pair(
            Modifier.background(AzulaColors.pink),
            Modifier as Modifier,
        )
        "borderless" -> Pair(Modifier as Modifier, Modifier as Modifier)
        else         -> Pair(Modifier as Modifier, Modifier.border(1.dp, AzulaColors.pink))
    }

    Box(
        Modifier
            .then(bgMod)
            .then(borderMod)
            .clickable {
                if (actionDef != null) {
                    val event = (actionDef["event"] as? JsonObject) ?: actionDef
                    val name = event["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val rawCtx = (event["context"] as? JsonObject) ?: JsonObject(emptyMap())
                    // Resolve each context entry against the data model
                    val resolvedCtx = JsonObject(
                        rawCtx.entries.associate { (k, v) ->
                            k to (resolve(v, surface.dataModel) ?: v)
                        }
                    )
                    onAction(A2uiAction(name, surface.surfaceId, compId, resolvedCtx))
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (childId != null) {
            RenderComponent(childId, surface, onAction, onDataModelChange)
        }
    }
}

// ---------------------------------------------------------------------------
// TextField
// ---------------------------------------------------------------------------

@Composable
private fun RenderTextField(
    comp: JsonObject,
    surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label   = prop(comp, "label")?.jsonPrimitive?.contentOrNull
    val value   = prop(comp, "value")
    val variant = prop(comp, "variant")?.jsonPrimitive?.contentOrNull ?: "shortText"
    val path    = bindingPath(value)
    val text    = resolveString(value, surface.dataModel) ?: ""

    val (keyboardType, singleLine, visualTransformation) = when (variant) {
        "number"   -> Triple(KeyboardType.Number,   true,  VisualTransformation.None)
        "obscured" -> Triple(KeyboardType.Password, true,  PasswordVisualTransformation())
        "longText" -> Triple(KeyboardType.Text,     false, VisualTransformation.None)
        else       -> Triple(KeyboardType.Text,     true,  VisualTransformation.None) // "shortText"
    }

    Column {
        if (label != null) {
            Text(
                text       = label,
                color      = AzulaColors.textDim,
                fontFamily = Fonts.ui,
                fontSize   = 11.sp,
                modifier   = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(AzulaColors.inputBg)
                .border(1.dp, AzulaColors.border4)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value               = text,
                onValueChange       = { newText ->
                    if (path != null) onDataModelChange(path, JsonPrimitive(newText))
                },
                singleLine          = singleLine,
                visualTransformation = visualTransformation,
                textStyle           = TextStyle(
                    color      = AzulaColors.text1,
                    fontFamily = Fonts.ui,
                    fontSize   = 13.5.sp,
                ),
                cursorBrush         = SolidColor(AzulaColors.pink),
                keyboardOptions     = KeyboardOptions(keyboardType = keyboardType),
                modifier            = Modifier.fillMaxWidth(),
                decorationBox       = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text       = label ?: "",
                            color      = AzulaColors.textFaint,
                            fontFamily = Fonts.ui,
                            fontSize   = 13.5.sp,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// CheckBox
// ---------------------------------------------------------------------------

@Composable
private fun RenderCheckBox(
    comp: JsonObject,
    surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label   = resolveString(prop(comp, "label"), surface.dataModel) ?: ""
    val value   = prop(comp, "value")
    val path    = bindingPath(value)
    val checked = resolveBoolean(value, surface.dataModel) ?: false

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.clickable {
            if (path != null) onDataModelChange(path, JsonPrimitive(!checked))
        },
    ) {
        Checkbox(
            checked         = checked,
            onCheckedChange = { if (path != null) onDataModelChange(path, JsonPrimitive(it)) },
            colors          = CheckboxDefaults.colors(
                checkedColor        = AzulaColors.pink,
                uncheckedColor      = AzulaColors.border5,
                checkmarkColor      = Color.White,
            ),
        )
        Text(
            text       = label,
            color      = AzulaColors.text1,
            fontFamily = Fonts.ui,
            fontSize   = 13.5.sp,
            modifier   = Modifier.padding(start = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// ChoicePicker
// ---------------------------------------------------------------------------

@Composable
private fun RenderChoicePicker(
    comp: JsonObject,
    surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label        = prop(comp, "label")?.jsonPrimitive?.contentOrNull
    val value        = prop(comp, "value")
    val path         = bindingPath(value)
    val variant      = prop(comp, "variant")?.jsonPrimitive?.contentOrNull ?: "mutuallyExclusive"
    val displayStyle = prop(comp, "displayStyle")?.jsonPrimitive?.contentOrNull ?: "chips"
    val selected     = resolveStringList(value, surface.dataModel)

    val optionsArray = (prop(comp, "options") as? JsonArray) ?: JsonArray(emptyList())
    val options = optionsArray.mapNotNull { it as? JsonObject }

    Column {
        if (label != null) {
            Text(
                text       = label,
                color      = AzulaColors.textDim,
                fontFamily = Fonts.ui,
                fontSize   = 11.sp,
                modifier   = Modifier.padding(bottom = 6.dp),
            )
        }
        if (displayStyle == "chips") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { opt ->
                    val optLabel = opt["label"]?.jsonPrimitive?.contentOrNull ?: ""
                    val optValue = opt["value"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isSelected = optValue in selected
                    Box(
                        Modifier
                            .border(1.dp, if (isSelected) AzulaColors.pink else AzulaColors.border3)
                            .background(if (isSelected) Color(0x22FF2D9B) else Color.Transparent)
                            .clickable {
                                if (path != null) {
                                    val newList = computeNewSelection(variant, selected, optValue)
                                    onDataModelChange(path, JsonArray(newList.map { JsonPrimitive(it) }))
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text       = optLabel,
                            color      = if (isSelected) AzulaColors.pinkLight else AzulaColors.text1,
                            fontFamily = Fonts.ui,
                            fontSize   = 13.sp,
                        )
                    }
                }
            }
        } else {
            // "checkbox" display style
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { opt ->
                    val optLabel = opt["label"]?.jsonPrimitive?.contentOrNull ?: ""
                    val optValue = opt["value"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isSelected = optValue in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            if (path != null) {
                                val newList = computeNewSelection(variant, selected, optValue)
                                onDataModelChange(path, JsonArray(newList.map { JsonPrimitive(it) }))
                            }
                        },
                    ) {
                        Checkbox(
                            checked         = isSelected,
                            onCheckedChange = {
                                if (path != null) {
                                    val newList = computeNewSelection(variant, selected, optValue)
                                    onDataModelChange(path, JsonArray(newList.map { JsonPrimitive(it) }))
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor   = AzulaColors.pink,
                                uncheckedColor = AzulaColors.border5,
                                checkmarkColor = Color.White,
                            ),
                        )
                        Text(
                            text       = optLabel,
                            color      = AzulaColors.text1,
                            fontFamily = Fonts.ui,
                            fontSize   = 13.5.sp,
                            modifier   = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Compute the next selection list given [variant] and the newly-tapped [optValue]. */
private fun computeNewSelection(variant: String, current: List<String>, optValue: String): List<String> =
    if (variant == "multipleSelection") {
        // Toggle
        if (optValue in current) current - optValue else current + optValue
    } else {
        // mutuallyExclusive — replace
        listOf(optValue)
    }

// ---------------------------------------------------------------------------
// Slider
// ---------------------------------------------------------------------------

@Composable
private fun RenderSlider(
    comp: JsonObject,
    surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label = prop(comp, "label")?.jsonPrimitive?.contentOrNull
    val value = prop(comp, "value")
    val path  = bindingPath(value)
    val min   = resolveDouble(prop(comp, "min"), surface.dataModel)?.toFloat() ?: 0f
    val max   = resolveDouble(prop(comp, "max"), surface.dataModel)?.toFloat() ?: 100f
    val cur   = resolveDouble(value, surface.dataModel)?.toFloat() ?: min

    Column {
        if (label != null) {
            Text(
                text       = label,
                color      = AzulaColors.textDim,
                fontFamily = Fonts.ui,
                fontSize   = 11.sp,
                modifier   = Modifier.padding(bottom = 2.dp),
            )
        }
        Slider(
            value         = cur.coerceIn(min, max),
            onValueChange = { newVal ->
                if (path != null) onDataModelChange(path, JsonPrimitive(newVal.toDouble()))
            },
            valueRange    = min..max,
            colors        = SliderDefaults.colors(
                thumbColor       = AzulaColors.pink,
                activeTrackColor = AzulaColors.pink,
            ),
            modifier      = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// DateTimeInput
// ---------------------------------------------------------------------------

@Composable
private fun RenderDateTimeInput(
    comp: JsonObject,
    surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label      = prop(comp, "label")?.jsonPrimitive?.contentOrNull
    val value      = prop(comp, "value")
    val path       = bindingPath(value)
    val isoText    = resolveString(value, surface.dataModel) ?: ""
    // TODO native picker — for now render a plain text field for the ISO string

    Column {
        if (label != null) {
            Text(
                text       = label,
                color      = AzulaColors.textDim,
                fontFamily = Fonts.ui,
                fontSize   = 11.sp,
                modifier   = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(AzulaColors.inputBg)
                .border(1.dp, AzulaColors.border4)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value           = isoText,
                onValueChange   = { newText ->
                    if (path != null) onDataModelChange(path, JsonPrimitive(newText))
                },
                singleLine      = true,
                textStyle       = TextStyle(
                    color      = AzulaColors.text1,
                    fontFamily = Fonts.mono,
                    fontSize   = 13.5.sp,
                ),
                cursorBrush     = SolidColor(AzulaColors.pink),
                modifier        = Modifier.fillMaxWidth(),
                decorationBox   = { inner ->
                    if (isoText.isEmpty()) {
                        Text(
                            text       = label ?: "YYYY-MM-DDTHH:MM:SS",
                            color      = AzulaColors.textFaint,
                            fontFamily = Fonts.mono,
                            fontSize   = 13.5.sp,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Image
// ---------------------------------------------------------------------------

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun RenderImage(comp: JsonObject, surface: A2uiSurface) {
    val url = resolveString(prop(comp, "url"), surface.dataModel) ?: return
    val fit = when (prop(comp, "fit")?.jsonPrimitive?.contentOrNull) {
        "cover"   -> ContentScale.Crop
        "stretch" -> ContentScale.FillBounds
        "contain" -> ContentScale.Fit
        else      -> ContentScale.Fit
    }

    // data: URI — decode base64 and render with the expect/actual decodeImageBitmap
    val dataUriPrefix = "data:image/"
    if (url.startsWith(dataUriPrefix)) {
        val commaIdx = url.indexOf(',')
        if (commaIdx != -1) {
            val b64 = url.substring(commaIdx + 1)
            val bytes = runCatching { Base64.decode(b64) }.getOrNull()
            val bitmap = bytes?.let { decodeImageBitmap(it) }
            if (bitmap != null) {
                Image(
                    bitmap             = bitmap,
                    contentDescription = null,
                    contentScale       = fit,
                    modifier           = Modifier
                        .heightIn(max = 220.dp)
                        .widthIn(max = 480.dp),
                )
                return
            }
        }
    }
    // http / unknown URL — placeholder
    ImagePlaceholder(url)
}

@Composable
private fun ImagePlaceholder(url: String) {
    Row(
        Modifier
            .background(AzulaColors.inputBg)
            .border(1.dp, AzulaColors.border4)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🖼", fontSize = 18.sp)
        Text(
            text       = " image · $url",
            color      = AzulaColors.textFaint,
            fontFamily = Fonts.mono,
            fontSize   = 11.sp,
            modifier   = Modifier.padding(start = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Icon
// ---------------------------------------------------------------------------

private val ICON_GLYPHS = mapOf(
    "check"         to "✓",
    "close"         to "✕",
    "add"           to "+",
    "delete"        to "🗑",
    "edit"          to "✎",
    "favorite"      to "♥",
    "home"          to "⌂",
    "search"        to "⌕",
    "settings"      to "⚙",
    "star"          to "★",
    "warning"       to "⚠",
    "arrowBack"     to "←",
    "accountCircle" to "○",
)

@Composable
private fun RenderIcon(comp: JsonObject, surface: A2uiSurface) {
    val name  = resolveString(prop(comp, "name"), surface.dataModel) ?: ""
    val glyph = ICON_GLYPHS[name] ?: "•"
    Text(
        text       = glyph,
        color      = AzulaColors.text1,
        fontFamily = Fonts.ui,
        fontSize   = 16.sp,
    )
}

// ---------------------------------------------------------------------------
// Video / AudioPlayer — placeholders
// ---------------------------------------------------------------------------

@Composable
private fun RenderVideoPlaceholder(comp: JsonObject, surface: A2uiSurface) {
    val url = resolveString(prop(comp, "url"), surface.dataModel) ?: ""
    MediaPlaceholder("▶ video", url)
}

@Composable
private fun RenderAudioPlaceholder(comp: JsonObject, surface: A2uiSurface) {
    val url = resolveString(prop(comp, "url"), surface.dataModel) ?: ""
    MediaPlaceholder("♪ audio", url)
}

@Composable
private fun MediaPlaceholder(kind: String, url: String) {
    Row(
        Modifier
            .background(AzulaColors.inputBg)
            .border(1.dp, AzulaColors.border4)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = "$kind · $url",
            color      = AzulaColors.textFaint,
            fontFamily = Fonts.mono,
            fontSize   = 11.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Fallback chip for unknown component types
// ---------------------------------------------------------------------------

@Composable
private fun FallbackChip(type: String) {
    Text(
        text       = "⚠ $type",
        color      = AzulaColors.textFaint2,
        fontFamily = Fonts.mono,
        fontSize   = 11.sp,
        modifier   = Modifier
            .background(AzulaColors.inputBg)
            .border(1.dp, AzulaColors.border4)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
