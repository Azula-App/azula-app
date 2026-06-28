package dev.azula.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * The design uses four Google fonts. They are OFL-licensed and not bundled here
 * (no network at build time), so each maps to the nearest system family. Drop
 * the real .ttf into `shared/composeResources/font/` and swap these for
 * `FontFamily(Font(Res.font.…))` to upgrade the look with no other changes.
 */
object Fonts {
    /** Space Grotesk — primary UI font. */
    val ui: FontFamily = FontFamily.SansSerif

    /** JetBrains Mono — code, labels, the "your code" string. */
    val mono: FontFamily = FontFamily.Monospace

    /** VT323 — retro terminal numerals in widgets. */
    val vt323: FontFamily = FontFamily.Monospace

    /** Press Start 2P — pixel headers on widgets. */
    val pixel: FontFamily = FontFamily.Monospace
}
