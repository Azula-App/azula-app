package dev.azula.theme

import androidx.compose.ui.graphics.Brush

/**
 * Reusable gradient brushes for the azula "neon glass" system. See
 * design-system.md §7.3.
 *
 * [primary] was previously duplicated byte-for-byte as both
 * `dev.azula.ui.Common.PinkButtonBrush` and `dev.azula.a2ui.A2uiTokens.primaryBrush`
 * (design-system.md §11 item 3) — both now delegate here instead of redefining it.
 */
object AzulaBrushes {
    /** Primary button / play-button fill: pink → pinkDark. */
    val primary = Brush.linearGradient(listOf(AzulaColors.pink, AzulaColors.pinkDark))

    /** Glyph/avatar fill: pink → pinkDeep. */
    val glyph = Brush.linearGradient(listOf(AzulaColors.pink, AzulaColors.pinkDeep))

    /** App-root vertical wash: bgGradTop → bg0. */
    val appBackground = Brush.verticalGradient(listOf(AzulaColors.bgGradTop, AzulaColors.bg0))
}
