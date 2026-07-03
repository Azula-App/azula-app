package dev.azula.a2ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.azula.theme.AzulaColors

/**
 * The A2UI "neon-glass" design-system tokens — the single source of truth the
 * [A2uiSurfaceView] renderer draws from, so component styling is token-driven
 * rather than hardcoded per call site. Mirrors the `azula - A2UI Catalog` design
 * spec; brand colors are shared with [AzulaColors], a few catalog-specific values
 * live there too ([AzulaColors.surfaceAlt], [AzulaColors.muted], [AzulaColors.accent]).
 */
internal object A2uiTokens {
    // ── Color ──
    val primary = AzulaColors.pink            // #FF2D9B
    val primaryLight = AzulaColors.pinkLight  // #FF6EC7
    val primaryDark = AzulaColors.pinkDark    // #C4156E
    val onPrimary = Color.White               // text on a primary/gradient fill
    val onAccent = AzulaColors.bg0            // dark ink on a pink chip/tick (#070709)

    val bg = AzulaColors.bg0                   // #070709
    val surface = AzulaColors.bg1             // #0A0A10
    val surfaceAlt = AzulaColors.surfaceAlt   // #0D0D15
    val inputBg = AzulaColors.inputBg         // #15151D

    val outline = AzulaColors.border4         // #26262F
    val outlineSoft = AzulaColors.border3     // #23232E
    val outlineStrong = AzulaColors.border5   // #2A2A36

    val content = AzulaColors.text2           // #D6D6E0
    val contentBright = AzulaColors.textBright // #F3EEF1
    val muted = AzulaColors.muted             // #7A7A8A
    val mutedFaint = AzulaColors.textFaint    // #6A6A7A

    val success = AzulaColors.online          // #52C98A
    val warning = AzulaColors.warn            // #FFD23F
    val accent = AzulaColors.accent           // #3FC8FF

    /** Selection fill for chips / tabs / picker highlights (was a raw 0x22FF2D9B). */
    val selectionFill = AzulaColors.pink.copy(alpha = 0.14f)
    /** Idle waveform / inactive-track grey used by the media players. */
    val trackIdle = Color(0xFF2F2F3C)

    // Decorative gradient stops from the catalog (image placeholder + video frame).
    val pinkFaint = Color(0xFF241028)
    val blueFaint = Color(0xFF0D1A24)
    val videoTop = Color(0xFF160B22)
    val videoBottom = Color(0xFF0A0F1A)

    // ── Shape scale ──
    val rSm = RoundedCornerShape(7.dp)
    val rMd = RoundedCornerShape(9.dp)
    val rLg = RoundedCornerShape(12.dp)
    val rXl = RoundedCornerShape(16.dp)
    val rPill = RoundedCornerShape(percent = 50)

    // ── Gradients ──
    /** Primary Button / play-button fill: pink → deep pink (135° in the spec). */
    val primaryBrush = Brush.linearGradient(listOf(AzulaColors.pink, AzulaColors.pinkDark))
}
