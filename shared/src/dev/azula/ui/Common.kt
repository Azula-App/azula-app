package dev.azula.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.model.ConversationKind
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

/** Visual treatment for a conversation/avatar glyph. */
enum class GlyphStyle { PINK, TERMINAL, PEER }

fun glyphStyleFor(kind: ConversationKind): GlyphStyle = when (kind) {
    ConversationKind.LLM -> GlyphStyle.PINK
    ConversationKind.TERMINAL -> GlyphStyle.TERMINAL
    ConversationKind.PEER -> GlyphStyle.PEER
}

fun dotColorFor(kind: ConversationKind): Color = when (kind) {
    ConversationKind.LLM -> AzulaColors.pink
    else -> AzulaColors.online
}

val PinkButtonBrush = Brush.linearGradient(listOf(AzulaColors.pink, AzulaColors.pinkDark))
val PinkGlyphBrush = Brush.linearGradient(listOf(AzulaColors.pink, AzulaColors.pinkDeep))
val AppBackgroundBrush = Brush.verticalGradient(listOf(AzulaColors.bgGradTop, AzulaColors.bg0))

@Composable
fun GlyphBox(style: GlyphStyle, glyph: String, size: Dp = 38.dp) {
    val base = Modifier.size(size).clip(RoundedCornerShape(12.dp))
    val (mod, color) = when (style) {
        GlyphStyle.PINK -> base.background(PinkGlyphBrush) to Color.White
        GlyphStyle.TERMINAL -> base.background(Color(0xFF0E1A14)).border(1.dp, Color(0xFF1E3A2A), RoundedCornerShape(12.dp)) to AzulaColors.online
        GlyphStyle.PEER -> base.background(Color(0xFF1C1C26)).border(1.dp, AzulaColors.border5, RoundedCornerShape(12.dp)) to AzulaColors.text2
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
            modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)),
        )
    } else {
        GlyphBox(glyphStyleFor(kind), glyph, size)
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * A ⋮ overflow menu for managing a conversation: Enable/Disable + Delete (with a
 * confirmation dialog). Shared by the sidebar/mobile rows and the chat header.
 */
@Composable
fun ConvOverflowMenu(
    enabled: Boolean,
    onToggleEnabled: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    glyphSize: Int = 16,
) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("⋮", color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = glyphSize.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onToggleEnabled != null) {
                DropdownMenuItem(
                    text = { Text(if (enabled) "Disable" else "Enable") },
                    onClick = { open = false; onToggleEnabled() },
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { open = false; confirmDelete = true },
                )
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?") },
            text = { Text("This removes its history and saved connection. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
