package dev.azula.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.a2ui.A2uiTokens
import dev.azula.model.ConversationKind
import dev.azula.theme.AzulaBrushes
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaInputSurface

/** Visual treatment for a conversation/avatar glyph. */
enum class GlyphStyle { PINK, TERMINAL, PEER }

fun glyphStyleFor(kind: ConversationKind): GlyphStyle = when (kind) {
    ConversationKind.LLM -> GlyphStyle.PINK
    ConversationKind.TERMINAL -> GlyphStyle.TERMINAL
    ConversationKind.PEER -> GlyphStyle.PEER
}

/** The conversation-list/header status dot color: green only when actually
 *  connected; LLM keeps its brand resting color when idle; PEER/TERMINAL fall
 *  back to a neutral gray instead of the online green (a disconnected
 *  peer/terminal must not look connected). */
fun statusDotColor(online: Boolean, enabled: Boolean, kind: ConversationKind): Color = when {
    online && enabled -> A2uiTokens.success
    kind == ConversationKind.LLM -> A2uiTokens.primary
    else -> A2uiTokens.muted
}

val PinkButtonBrush = AzulaBrushes.primary
val PinkGlyphBrush = AzulaBrushes.glyph
val AppBackgroundBrush = AzulaBrushes.appBackground

@Composable
fun GlyphBox(style: GlyphStyle, glyph: String, size: Dp = 38.dp) {
    val base = Modifier.size(size).clip(AzulaShapes.rLg)
    val (mod, color) = when (style) {
        GlyphStyle.PINK -> base.background(PinkGlyphBrush) to Color.White
        GlyphStyle.TERMINAL -> base.background(Color(0xFF0E1A14)).border(1.dp, Color(0xFF1E3A2A), AzulaShapes.rLg) to AzulaColors.online
        GlyphStyle.PEER -> base.background(Color(0xFF1C1C26)).border(1.dp, AzulaColors.border5, AzulaShapes.rLg) to AzulaColors.text2
    }
    Box(mod, contentAlignment = Alignment.Center) {
        Text(glyph, color = color, fontFamily = Fonts.mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/**
 * A conversation avatar: shows the peer's profile picture [bitmap] if present,
 * otherwise falls back to the [GlyphBox] for [kind]/[glyph].
 */
@Composable
fun AvatarBox(bitmap: ImageBitmap?, glyph: String, kind: ConversationKind, size: Dp = 38.dp) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(AzulaShapes.rLg),
        )
    } else {
        GlyphBox(glyphStyleFor(kind), glyph, size)
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Whether the on-screen (soft) keyboard is currently showing, tracked via the
 *  IME window inset. Used to tie terminal-only chrome (e.g. [TerminalKeysBar])
 *  to the keyboard's actual visibility instead of just "is this a live terminal". */
@Composable
fun rememberImeVisible(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.ime.getBottom(density) > 0
}

/**
 * A ⋮ overflow menu for managing a conversation: Rename + Enable/Disable + Delete
 * (each with its own dialog). Shared by the sidebar/mobile rows and the chat header.
 *
 * The expanded state is optionally controllable: pass [onExpandedChange] to drive
 * it from outside (e.g. a long-press on the whole row). Left unset, the menu owns
 * its own state and opens only from the ⋮ button.
 *
 * [onRename] (with [currentName] pre-filling the field) enables a "Rename" item
 * that opens a small dialog: Save calls `onRename(newName)`; "Reset to default"
 * calls `onRename(null)` to clear the local rename back to the shared/mnemonic name.
 *
 * The menu is also reused for non-conversation rows (e.g. personas in settings);
 * those pass [deleteTitle]/[deleteBody] to replace the conversation-specific
 * confirm copy.
 */
@Composable
fun ConvOverflowMenu(
    enabled: Boolean,
    onToggleEnabled: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    deleteTitle: String = "Delete conversation?",
    deleteBody: String = "This removes its history and saved connection. This can't be undone.",
    onCopyScreen: (() -> Unit)? = null,
    onCopyAll: (() -> Unit)? = null,
    currentName: String? = null,
    onRename: ((String?) -> Unit)? = null,
    glyphSize: Int = 16,
    expanded: Boolean = false,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    var internalOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val open = if (onExpandedChange != null) expanded else internalOpen
    val setOpen: (Boolean) -> Unit = onExpandedChange ?: { internalOpen = it }
    Box {
        Box(
            Modifier.clip(AzulaShapes.rXs).clickable { setOpen(true) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("⋮", color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = glyphSize.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { setOpen(false); renaming = true },
                )
            }
            if (onCopyScreen != null) {
                DropdownMenuItem(
                    text = { Text("Copy screen") },
                    onClick = { setOpen(false); onCopyScreen() },
                )
            }
            if (onCopyAll != null) {
                DropdownMenuItem(
                    text = { Text("Copy all") },
                    onClick = { setOpen(false); onCopyAll() },
                )
            }
            if (onToggleEnabled != null) {
                DropdownMenuItem(
                    text = { Text(if (enabled) "Disable" else "Enable") },
                    onClick = { setOpen(false); onToggleEnabled() },
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { setOpen(false); confirmDelete = true },
                )
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(deleteTitle) },
            text = { Text(deleteBody) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (renaming && onRename != null) {
        RenameConversationDialog(
            currentName = currentName.orEmpty(),
            onSave = { renaming = false; onRename(it) },
            onReset = { renaming = false; onRename(null) },
            onCancel = { renaming = false },
        )
    }
}

/**
 * A small neon-glass dialog to set a conversation's local rename. The field is
 * pre-filled with the current display name; Save applies it (blank clears back to
 * the default), and "Reset to default" clears any existing rename explicitly.
 */
@Composable
private fun RenameConversationDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Set a name just for you. Leave blank (or reset) to use the shared name.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp,
                )
                Box(
                    Modifier.fillMaxWidth().azulaInputSurface(AzulaShapes.rSm)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 13.sp),
                        cursorBrush = SolidColor(AzulaColors.pink),
                        modifier = Modifier.fillMaxWidth().testTag("rename-input"),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text("conversation name", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                }
                if (currentName.isNotBlank()) {
                    Text(
                        "Reset to default",
                        color = AzulaColors.pink, fontFamily = Fonts.mono, fontSize = 12.sp,
                        modifier = Modifier.clickable { onReset() }.testTag("rename-reset"),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
