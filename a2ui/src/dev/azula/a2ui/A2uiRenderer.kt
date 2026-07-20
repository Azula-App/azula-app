package dev.azula.a2ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.azula.theme.AzulaButtonVariant
import dev.azula.theme.Fonts
import dev.azula.theme.azulaButtonSurface
import dev.azula.theme.azulaCard
import dev.azula.ui.cacheAudioFile
import dev.azula.ui.decodeImageBitmap
import dev.azula.ui.rememberAudioPlayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Renders an [A2uiSurface] into Compose, styled to azula's neon-glass A2UI design
 * system (see [A2uiTokens] and the `azula - A2UI Catalog` design spec).
 *
 * [onAction] fires on a Button action; [onDataModelChange] fires when a two-way
 * input changes, receiving the RFC-6901 path and new [JsonElement]. Both
 * [A2uiSurface.components] and [A2uiSurface.dataModel] are snapshot state, so the
 * tree recomposes when the agent streams updates.
 */
@Composable
fun A2uiSurfaceView(
    surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit,
    onDataModelChange: (path: String, value: JsonElement) -> Unit,
    modifier: Modifier = Modifier,
    frozen: Boolean = false,
) {
    val root = surface.root() ?: return
    val rootId = root["id"]?.jsonPrimitive?.contentOrNull ?: "root"
    Box(modifier) {
        // When frozen (restored from disk / connection inactive / interaction over)
        // render the final state grayed-out and inert: no callbacks wired, plus an
        // overlay that eats taps so the dead surface's controls can't be pressed or
        // focused.
        Box(if (frozen) Modifier.alpha(0.5f) else Modifier) {
            RenderComponent(
                rootId,
                surface,
                if (frozen) ({}) else onAction,
                if (frozen) ({ _, _ -> }) else onDataModelChange,
            )
        }
        if (frozen) {
            // A no-ripple clickable swallows tap gestures but releases once the
            // gesture becomes a drag, so a vertical scroll falls through to the
            // enclosing message list. (A pointer-consuming overlay would also block
            // scrolling — the bug this replaces.)
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
            )
        }
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
        "Divider"       -> RenderDivider(comp)
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
        "Video"         -> RenderVideo(comp, surface)
        "AudioPlayer"   -> RenderAudio(comp, surface)
        else            -> FallbackChip(type)
    }
}

// ---------------------------------------------------------------------------
// Helpers: child ids, arrangement / alignment
// ---------------------------------------------------------------------------

private fun childIds(comp: JsonObject): List<String> {
    val children = prop(comp, "children") as? JsonArray ?: return emptyList()
    return children.mapNotNull { it.jsonPrimitive.contentOrNull }
}

private fun horizontalArrangementFor(justify: String?): Arrangement.Horizontal = when (justify) {
    "center"       -> Arrangement.Center
    "end"          -> Arrangement.End
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround"  -> Arrangement.SpaceAround
    "spaceEvenly"  -> Arrangement.SpaceEvenly
    else           -> Arrangement.Start
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
    else     -> Alignment.Top
}

private fun columnHorizontalAlignmentFor(align: String?): Alignment.Horizontal = when (align) {
    "center"  -> Alignment.CenterHorizontally
    "end"     -> Alignment.End
    else      -> Alignment.Start
}

// ---------------------------------------------------------------------------
// Text (markdown)
// ---------------------------------------------------------------------------

@Composable
private fun RenderText(comp: JsonObject, surface: A2uiSurface) {
    val text    = resolveString(prop(comp, "text"), surface.dataModel) ?: return
    val variant = prop(comp, "variant")?.jsonPrimitive?.contentOrNull ?: "body"
    val (fontSize, fontWeight, color) = when (variant) {
        "h1"      -> Triple(20.sp,   FontWeight.Bold,     A2uiTokens.contentBright)
        "h2"      -> Triple(18.sp,   FontWeight.Bold,     A2uiTokens.contentBright)
        "h3"      -> Triple(16.sp,   FontWeight.SemiBold, A2uiTokens.contentBright)
        "h4"      -> Triple(15.sp,   FontWeight.SemiBold, A2uiTokens.content)
        "h5"      -> Triple(14.sp,   FontWeight.Medium,   A2uiTokens.content)
        "h6"      -> Triple(13.sp,   FontWeight.Medium,   A2uiTokens.content)
        "caption" -> Triple(11.sp,   FontWeight.Normal,   A2uiTokens.muted)
        else      -> Triple(13.5.sp, FontWeight.Normal,   A2uiTokens.content) // "body"
    }
    if (variant == "caption") {
        Text(text, color = color, fontFamily = Fonts.ui, fontSize = fontSize, fontStyle = FontStyle.Italic)
    } else if (hasMarkdown(text)) {
        MarkdownText(text, fontSize, fontWeight, color)
    } else {
        Text(text, color = color, fontFamily = Fonts.ui, fontSize = fontSize, fontWeight = fontWeight)
    }
}

// ---------------------------------------------------------------------------
// Row / Column / List — invisible structural containers
// ---------------------------------------------------------------------------

@Composable
private fun RenderRow(
    comp: JsonObject, surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit, onDataModelChange: (String, JsonElement) -> Unit,
) {
    Row(
        horizontalArrangement = horizontalArrangementFor(prop(comp, "justify")?.jsonPrimitive?.contentOrNull),
        verticalAlignment     = rowVerticalAlignmentFor(prop(comp, "align")?.jsonPrimitive?.contentOrNull),
    ) {
        childIds(comp).forEach { RenderComponent(it, surface, onAction, onDataModelChange) }
    }
}

@Composable
private fun RenderColumn(
    comp: JsonObject, surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit, onDataModelChange: (String, JsonElement) -> Unit,
) {
    Column(
        verticalArrangement = verticalArrangementFor(prop(comp, "justify")?.jsonPrimitive?.contentOrNull),
        horizontalAlignment = columnHorizontalAlignmentFor(prop(comp, "align")?.jsonPrimitive?.contentOrNull),
    ) {
        childIds(comp).forEach { RenderComponent(it, surface, onAction, onDataModelChange) }
    }
}

@Composable
private fun RenderList(
    comp: JsonObject, surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit, onDataModelChange: (String, JsonElement) -> Unit,
) {
    val direction = prop(comp, "direction")?.jsonPrimitive?.contentOrNull ?: "vertical"
    val align     = prop(comp, "align")?.jsonPrimitive?.contentOrNull
    val ids = childIds(comp)
    if (direction == "horizontal") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = rowVerticalAlignmentFor(align),
        ) { ids.forEach { RenderComponent(it, surface, onAction, onDataModelChange) } }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = columnHorizontalAlignmentFor(align),
        ) { ids.forEach { RenderComponent(it, surface, onAction, onDataModelChange) } }
    }
}

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

@Composable
private fun RenderCard(
    comp: JsonObject, surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit, onDataModelChange: (String, JsonElement) -> Unit,
) {
    val childId = prop(comp, "child")?.jsonPrimitive?.contentOrNull
    val nested  = prop(comp, "variant")?.jsonPrimitive?.contentOrNull == "nested"
    val base = Modifier.azulaCard(nested = nested)
    Column(base.wrapContentWidth().padding(16.dp)) {
        if (childId != null) RenderComponent(childId, surface, onAction, onDataModelChange)
    }
}

// ---------------------------------------------------------------------------
// Divider
// ---------------------------------------------------------------------------

@Composable
private fun RenderDivider(comp: JsonObject) {
    val vertical = prop(comp, "axis")?.jsonPrimitive?.contentOrNull == "vertical"
    if (vertical) {
        Box(Modifier.width(1.dp).height(24.dp).background(A2uiTokens.outline))
    } else {
        Box(Modifier.fillMaxWidth().height(1.dp).background(A2uiTokens.outline))
    }
}

// ---------------------------------------------------------------------------
// Tabs — underline style
// ---------------------------------------------------------------------------

@Composable
private fun RenderTabs(
    comp: JsonObject, surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit, onDataModelChange: (String, JsonElement) -> Unit,
) {
    val tabsArray = (prop(comp, "tabs") as? JsonArray) ?: return
    val tabs = tabsArray.mapNotNull { it as? JsonObject }
    if (tabs.isEmpty()) return
    var selectedIndex by remember { mutableStateOf(0) }

    Column {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, tab ->
                val title = tab["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val selected = index == selectedIndex
                Column(
                    Modifier
                        .clickable { selectedIndex = index }
                        .padding(end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text       = title,
                        color      = if (selected) A2uiTokens.primaryLight else A2uiTokens.muted,
                        fontFamily = Fonts.ui,
                        fontSize   = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier   = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (selected) A2uiTokens.primary else Color.Transparent),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(A2uiTokens.outline))
        val activeTab = tabs.getOrNull(selectedIndex)
        val childId   = activeTab?.get("child")?.jsonPrimitive?.contentOrNull
        if (childId != null) {
            Box(Modifier.padding(top = 14.dp)) {
                RenderComponent(childId, surface, onAction, onDataModelChange)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Modal — glass sheet with a ✕ close
// ---------------------------------------------------------------------------

@Composable
private fun RenderModal(
    comp: JsonObject, surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit, onDataModelChange: (String, JsonElement) -> Unit,
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
                    .widthIn(max = 420.dp)
                    .shadow(24.dp, A2uiTokens.rXl)
                    .clip(A2uiTokens.rXl)
                    .background(A2uiTokens.surfaceAlt)
                    .border(1.dp, A2uiTokens.outlineStrong, A2uiTokens.rXl)
                    .padding(20.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .clip(A2uiTokens.rSm)
                            .clickable { show = false }
                            .padding(4.dp),
                    ) { Text("✕", color = A2uiTokens.muted, fontFamily = Fonts.mono, fontSize = 16.sp) }
                }
                RenderComponent(contentId, surface, onAction, onDataModelChange)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Button — primary (gradient+glow) / default / borderless
// ---------------------------------------------------------------------------

@Composable
private fun RenderButton(
    comp: JsonObject, surface: A2uiSurface,
    onAction: (A2uiAction) -> Unit, onDataModelChange: (String, JsonElement) -> Unit,
) {
    val childId   = prop(comp, "child")?.jsonPrimitive?.contentOrNull
    val variant   = prop(comp, "variant")?.jsonPrimitive?.contentOrNull ?: "default"
    val actionDef = prop(comp, "action") as? JsonObject
    val compId    = comp["id"]?.jsonPrimitive?.contentOrNull ?: ""

    val fill: Modifier = Modifier.azulaButtonSurface(
        when (variant) {
            "primary"    -> AzulaButtonVariant.Primary
            "borderless" -> AzulaButtonVariant.Borderless
            else         -> AzulaButtonVariant.Default
        },
    )
    val hPad = if (variant == "borderless") 6.dp else 16.dp

    Box(
        fill
            .clickable {
                if (actionDef != null) {
                    val event = (actionDef["event"] as? JsonObject) ?: actionDef
                    val name = event["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val rawCtx = (event["context"] as? JsonObject) ?: JsonObject(emptyMap())
                    val resolvedCtx = JsonObject(
                        rawCtx.entries.associate { (k, v) -> k to (resolve(v, surface.dataModel) ?: v) }
                    )
                    onAction(A2uiAction(name, surface.surfaceId, compId, resolvedCtx))
                }
            }
            .padding(horizontal = hPad, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (childId != null) RenderComponent(childId, surface, onAction, onDataModelChange)
    }
}

// ---------------------------------------------------------------------------
// TextField
// ---------------------------------------------------------------------------

@Composable
private fun RenderTextField(
    comp: JsonObject, surface: A2uiSurface,
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
        else       -> Triple(KeyboardType.Text,     true,  VisualTransformation.None)
    }

    Column {
        if (label != null) FieldLabel(label)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(A2uiTokens.rMd)
                .background(A2uiTokens.inputBg)
                .border(1.dp, A2uiTokens.outline, A2uiTokens.rMd)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value                = text,
                onValueChange        = { if (path != null) onDataModelChange(path, JsonPrimitive(it)) },
                singleLine           = singleLine,
                visualTransformation = visualTransformation,
                textStyle            = TextStyle(color = A2uiTokens.content, fontFamily = Fonts.mono, fontSize = 13.sp),
                cursorBrush          = SolidColor(A2uiTokens.primary),
                keyboardOptions      = KeyboardOptions(keyboardType = keyboardType),
                modifier             = Modifier.fillMaxWidth(),
                decorationBox        = { inner ->
                    if (text.isEmpty()) Text(label ?: "", color = A2uiTokens.mutedFaint, fontFamily = Fonts.mono, fontSize = 13.sp)
                    inner()
                },
            )
        }
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(
        text       = label,
        color      = A2uiTokens.muted,
        fontFamily = Fonts.mono,
        fontSize   = 11.sp,
        modifier   = Modifier.padding(bottom = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// CheckBox — custom rounded tick
// ---------------------------------------------------------------------------

@Composable
private fun RenderCheckBox(
    comp: JsonObject, surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label   = resolveString(prop(comp, "label"), surface.dataModel) ?: ""
    val value   = prop(comp, "value")
    val path    = bindingPath(value)
    val checked = resolveBoolean(value, surface.dataModel) ?: false

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { if (path != null) onDataModelChange(path, JsonPrimitive(!checked)) },
    ) {
        TickBox(checked)
        Text(label, color = A2uiTokens.content, fontFamily = Fonts.ui, fontSize = 13.5.sp, modifier = Modifier.padding(start = 11.dp))
    }
}

/** The pink rounded-square checkbox affordance shared by CheckBox and multi-select ChoicePicker. */
@Composable
private fun TickBox(checked: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .clip(A2uiTokens.rSm)
            .background(if (checked) A2uiTokens.primary else Color.Transparent)
            .border(2.dp, if (checked) A2uiTokens.primary else A2uiTokens.outlineStrong, A2uiTokens.rSm),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = A2uiTokens.onAccent, fontFamily = Fonts.mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The pink radio dot for mutually-exclusive ChoicePicker. */
@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) A2uiTokens.primary else A2uiTokens.outlineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(A2uiTokens.primary))
    }
}

// ---------------------------------------------------------------------------
// ChoicePicker — chips (pill) / checkbox (radio | tick)
// ---------------------------------------------------------------------------

@Composable
private fun RenderChoicePicker(
    comp: JsonObject, surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label        = prop(comp, "label")?.jsonPrimitive?.contentOrNull
    val value        = prop(comp, "value")
    val path         = bindingPath(value)
    val variant      = prop(comp, "variant")?.jsonPrimitive?.contentOrNull ?: "mutuallyExclusive"
    val displayStyle = prop(comp, "displayStyle")?.jsonPrimitive?.contentOrNull ?: "chips"
    val selected     = resolveStringList(value, surface.dataModel)

    val options = ((prop(comp, "options") as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }

    fun pick(optValue: String) {
        if (path != null) {
            val newList = computeNewSelection(variant, selected, optValue)
            onDataModelChange(path, JsonArray(newList.map { JsonPrimitive(it) }))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (label != null) FieldLabel(label)
        if (displayStyle == "chips") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { opt ->
                    val optLabel = opt["label"]?.jsonPrimitive?.contentOrNull ?: ""
                    val optValue = opt["value"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isSel = optValue in selected
                    Box(
                        Modifier
                            .clip(A2uiTokens.rPill)
                            .background(if (isSel) A2uiTokens.selectionFill else A2uiTokens.inputBg)
                            .border(1.dp, if (isSel) A2uiTokens.primary else A2uiTokens.outlineStrong, A2uiTokens.rPill)
                            .clickable { pick(optValue) }
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                    ) {
                        Text(optLabel, color = if (isSel) A2uiTokens.primaryLight else A2uiTokens.content, fontFamily = Fonts.mono, fontSize = 12.sp)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                options.forEach { opt ->
                    val optLabel = opt["label"]?.jsonPrimitive?.contentOrNull ?: ""
                    val optValue = opt["value"]?.jsonPrimitive?.contentOrNull ?: ""
                    val optDesc  = opt["description"]?.jsonPrimitive?.contentOrNull
                    val isSel = optValue in selected
                    val multi = variant == "multipleSelection"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { pick(optValue) },
                    ) {
                        if (multi) TickBox(isSel) else RadioDot(isSel)
                        Column(Modifier.padding(start = 11.dp)) {
                            Text(optLabel, color = if (isSel) A2uiTokens.contentBright else A2uiTokens.content, fontFamily = Fonts.ui, fontSize = 13.5.sp)
                            if (optDesc != null) Text(optDesc, color = A2uiTokens.mutedSmall, fontFamily = Fonts.mono, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun computeNewSelection(variant: String, current: List<String>, optValue: String): List<String> =
    if (variant == "multipleSelection") {
        if (optValue in current) current - optValue else current + optValue
    } else listOf(optValue)

// ---------------------------------------------------------------------------
// Slider
// ---------------------------------------------------------------------------

@Composable
private fun RenderSlider(
    comp: JsonObject, surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label = prop(comp, "label")?.jsonPrimitive?.contentOrNull
    val value = prop(comp, "value")
    val path  = bindingPath(value)
    val min   = resolveDouble(prop(comp, "min"), surface.dataModel)?.toFloat() ?: 0f
    val max   = resolveDouble(prop(comp, "max"), surface.dataModel)?.toFloat() ?: 100f
    val cur   = resolveDouble(value, surface.dataModel)?.toFloat() ?: min

    Column {
        if (label != null) FieldLabel(label)
        Slider(
            value         = cur.coerceIn(min, max),
            onValueChange = { if (path != null) onDataModelChange(path, JsonPrimitive(it.toDouble())) },
            valueRange    = min..max,
            colors        = SliderDefaults.colors(
                thumbColor         = A2uiTokens.primary,
                activeTrackColor   = A2uiTokens.primary,
                inactiveTrackColor = A2uiTokens.outlineStrong,
            ),
            modifier      = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// DateTimeInput (ISO text field; native picker is a follow-up)
// ---------------------------------------------------------------------------

@Composable
private fun RenderDateTimeInput(
    comp: JsonObject, surface: A2uiSurface,
    onDataModelChange: (String, JsonElement) -> Unit,
) {
    val label   = prop(comp, "label")?.jsonPrimitive?.contentOrNull
    val value   = prop(comp, "value")
    val path    = bindingPath(value)
    val isoText = resolveString(value, surface.dataModel) ?: ""

    Column {
        if (label != null) FieldLabel(label)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(A2uiTokens.rMd)
                .background(A2uiTokens.inputBg)
                .border(1.dp, A2uiTokens.outline, A2uiTokens.rMd)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value         = isoText,
                onValueChange = { if (path != null) onDataModelChange(path, JsonPrimitive(it)) },
                singleLine    = true,
                textStyle     = TextStyle(color = A2uiTokens.content, fontFamily = Fonts.mono, fontSize = 13.sp),
                cursorBrush   = SolidColor(A2uiTokens.primary),
                modifier      = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (isoText.isEmpty()) Text(label ?: "YYYY-MM-DDTHH:MM:SS", color = A2uiTokens.mutedFaint, fontFamily = Fonts.mono, fontSize = 13.sp)
                    inner()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Image — sized presets
// ---------------------------------------------------------------------------

private fun imageModifier(variant: String?): Modifier = when (variant) {
    "icon"         -> Modifier.size(24.dp).clip(A2uiTokens.rSm)
    "avatar"       -> Modifier.size(40.dp).clip(CircleShape)
    "smallFeature" -> Modifier.size(100.dp).clip(A2uiTokens.rLg)
    "largeFeature" -> Modifier.fillMaxWidth().height(200.dp).clip(A2uiTokens.rLg)
    "header"       -> Modifier.fillMaxWidth().height(150.dp).clip(A2uiTokens.rLg)
    else           -> Modifier.width(180.dp).height(130.dp).clip(A2uiTokens.rLg) // mediumFeature
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun RenderImage(comp: JsonObject, surface: A2uiSurface) {
    val url = resolveString(prop(comp, "url"), surface.dataModel) ?: return
    val variant = prop(comp, "variant")?.jsonPrimitive?.contentOrNull
    val fit = when (prop(comp, "fit")?.jsonPrimitive?.contentOrNull) {
        "cover"   -> ContentScale.Crop
        "stretch" -> ContentScale.FillBounds
        else      -> ContentScale.Fit
    }
    val mod = imageModifier(variant)

    if (url.startsWith("data:image/")) {
        val commaIdx = url.indexOf(',')
        if (commaIdx != -1) {
            val bytes = runCatching { Base64.decode(url.substring(commaIdx + 1)) }.getOrNull()
            val bitmap = bytes?.let { decodeImageBitmap(it) }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, contentScale = fit, modifier = mod)
                return
            }
        }
    }
    // http / unknown / undecodable — themed gradient placeholder at the preset size
    Box(
        mod.background(Brush.linearGradient(listOf(A2uiTokens.pinkFaint, A2uiTokens.blueFaint))),
        contentAlignment = Alignment.Center,
    ) {
        Text("IMG", color = A2uiTokens.mutedSmall, fontFamily = Fonts.mono, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------------------
// Icon — vector line icons (inherits content color), glyph fallback
// ---------------------------------------------------------------------------

private val ICON_GLYPHS = mapOf(
    "check" to "✓", "close" to "✕", "add" to "+", "delete" to "🗑", "edit" to "✎",
    "favorite" to "♥", "home" to "⌂", "search" to "⌕", "settings" to "⚙", "star" to "★",
    "warning" to "⚠", "arrowBack" to "←", "accountCircle" to "○",
)

private val VECTOR_ICONS = setOf("bolt", "terminal", "lock", "link", "chat", "controls")

@Composable
private fun RenderIcon(comp: JsonObject, surface: A2uiSurface) {
    val name = resolveString(prop(comp, "name"), surface.dataModel) ?: ""
    val tint = A2uiTokens.content
    if (name in VECTOR_ICONS) {
        Canvas(Modifier.size(24.dp)) {
            val s = size.minDimension / 24f            // scale a 24-unit viewbox
            fun p(x: Float, y: Float) = Offset(x * s, y * s)
            val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round)
            when (name) {
                "bolt" -> {
                    val path = Path().apply {
                        moveTo(13 * s, 2 * s); lineTo(4 * s, 14 * s); lineTo(10 * s, 14 * s)
                        lineTo(9 * s, 22 * s); lineTo(18 * s, 10 * s); lineTo(12 * s, 10 * s); close()
                    }
                    drawPath(path, tint)
                }
                "terminal" -> {
                    drawPath(Path().apply { moveTo(4 * s, 6 * s); lineTo(9 * s, 12 * s); lineTo(4 * s, 18 * s) }, tint, style = stroke)
                    drawLine(tint, p(12f, 18f), p(20f, 18f), 2f * s, StrokeCap.Round)
                }
                "lock" -> {
                    drawPath(Path().apply { moveTo(8 * s, 10 * s); lineTo(8 * s, 7 * s); cubicTo(8 * s, 4 * s, 16 * s, 4 * s, 16 * s, 7 * s); lineTo(16 * s, 10 * s) }, tint, style = stroke)
                    drawRoundRect(
                        color = tint, topLeft = p(4f, 10f),
                        size = androidx.compose.ui.geometry.Size(16f * s, 11f * s),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * s, 2f * s), style = stroke,
                    )
                }
                "link" -> {
                    drawCircleStroke(tint, p(7f, 8f), 3f * s, stroke)
                    drawCircleStroke(tint, p(17f, 16f), 3f * s, stroke)
                    drawLine(tint, p(9f, 10f), p(15f, 14f), 2f * s, StrokeCap.Round)
                }
                "chat" -> {
                    drawPath(Path().apply {
                        moveTo(4 * s, 5 * s); lineTo(20 * s, 5 * s); lineTo(20 * s, 16 * s)
                        lineTo(10 * s, 16 * s); lineTo(5 * s, 20 * s); lineTo(5 * s, 16 * s); lineTo(4 * s, 16 * s); close()
                    }, tint, style = stroke)
                }
                "controls" -> {
                    drawLine(tint, p(4f, 8f), p(20f, 8f), 2f * s, StrokeCap.Round)
                    drawLine(tint, p(4f, 16f), p(20f, 16f), 2f * s, StrokeCap.Round)
                    drawCircleStroke(tint, p(15f, 8f), 2.4f * s, stroke)
                    drawCircleStroke(tint, p(9f, 16f), 2.4f * s, stroke)
                }
            }
        }
    } else {
        Text(ICON_GLYPHS[name] ?: "•", color = tint, fontFamily = Fonts.ui, fontSize = 18.sp)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCircleStroke(
    color: Color, center: Offset, radius: Float, stroke: Stroke,
) = drawCircle(color, radius, center, style = stroke)

// ---------------------------------------------------------------------------
// Video / AudioPlayer — Video is a styled mock (visual only, no playback);
// AudioPlayer plays real audio when `url` is a `data:audio/...;base64,` URI,
// falling back to the same static mock look for a remote/absent url.
// ---------------------------------------------------------------------------

@Composable
private fun RenderVideo(comp: JsonObject, surface: A2uiSurface) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(A2uiTokens.rLg)
            .background(Brush.linearGradient(listOf(A2uiTokens.videoTop, A2uiTokens.videoBottom)))
            .border(1.dp, A2uiTokens.outlineSoft, A2uiTokens.rLg),
        contentAlignment = Alignment.Center,
    ) {
        // Centered glow play button
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(A2uiTokens.selectionFill).border(1.5.dp, A2uiTokens.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { PlayTriangle(16.dp, A2uiTokens.primary) }
        // Bottom scrubber overlay
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("1:04", color = A2uiTokens.content, fontFamily = Fonts.mono, fontSize = 11.sp)
            Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(4.dp).clip(A2uiTokens.rPill).background(A2uiTokens.trackIdle)) {
                Box(Modifier.fillMaxWidth(0.38f).height(4.dp).clip(A2uiTokens.rPill).background(A2uiTokens.primary))
            }
            Text("2:45", color = A2uiTokens.muted, fontFamily = Fonts.mono, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun RenderAudio(comp: JsonObject, surface: A2uiSurface) {
    val url = resolveString(prop(comp, "url"), surface.dataModel)
    val compId = comp["id"]?.jsonPrimitive?.contentOrNull ?: ""

    val path = remember(surface.surfaceId, compId, url) {
        if (url != null && url.startsWith("data:audio/")) {
            val commaIdx = url.indexOf(',')
            if (commaIdx != -1) {
                val bytes = runCatching { Base64.decode(url.substring(commaIdx + 1)) }.getOrNull()
                bytes?.let { cacheAudioFile(it, key = "${surface.surfaceId}:$compId") }
            } else null
        } else null
    }

    if (path != null) {
        val player = rememberAudioPlayer(path)
        A2uiAudioBar(
            isPlaying = player.isPlaying,
            positionMs = player.positionMs,
            durationMs = player.durationMs,
            onToggle = player::toggle,
            onSeek = player::seekTo,
            seed = compId.hashCode(),
        )
    } else {
        // No url, a remote (non-data-URI) url, or an undecodable payload — same
        // static mock look as before this component could play anything.
        A2uiAudioBar(
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            onToggle = {},
            onSeek = null,
        )
    }
}

@Composable
internal fun PlayTriangle(dim: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(Modifier.size(dim)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, 0f)
            lineTo(w * 0.18f, h)
            lineTo(w, h / 2f)
            close()
        }
        drawPath(path, color)
    }
}

// ---------------------------------------------------------------------------
// Fallback chip for unknown component types
// ---------------------------------------------------------------------------

@Composable
private fun FallbackChip(type: String) {
    Text(
        text       = "⚠ $type",
        color      = A2uiTokens.mutedSmall,
        fontFamily = Fonts.mono,
        fontSize   = 11.sp,
        modifier   = Modifier
            .clip(A2uiTokens.rSm)
            .background(A2uiTokens.inputBg)
            .border(1.dp, A2uiTokens.outline, A2uiTokens.rSm)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
