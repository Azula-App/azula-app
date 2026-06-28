package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
