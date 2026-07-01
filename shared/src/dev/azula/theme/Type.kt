package dev.azula.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import shared.generated.resources.Res
import shared.generated.resources.jetbrainsmono_bold
import shared.generated.resources.jetbrainsmono_regular

/**
 * The design uses four Google fonts. JetBrains Mono is now bundled
 * (`shared/composeResources/font/`) and backs [mono]; the rest still map to the
 * nearest system family (drop their .ttf in and wire them the same way).
 *
 * [mono] is observable snapshot state seeded with the system monospace and
 * upgraded to the bundled JetBrains Mono by [LoadFonts] at the theme root. This
 * lets even non-@Composable styling (e.g. the markdown `code` span) read it,
 * since the resource font API itself is @Composable.
 */
object Fonts {
    /** Space Grotesk — primary UI font. */
    val ui: FontFamily = FontFamily.SansSerif

    /** JetBrains Mono — code, labels, the brand mark, the "your code" string. */
    var mono: FontFamily by mutableStateOf(FontFamily.Monospace)
        internal set

    /** VT323 — retro terminal numerals in widgets. */
    val vt323: FontFamily = FontFamily.Monospace

    /** Press Start 2P — pixel headers on widgets. */
    val pixel: FontFamily = FontFamily.Monospace
}

/** Resolve the bundled JetBrains Mono and publish it into [Fonts.mono]. Called
 *  once at the theme root (see [AzulaTheme]). */
@Composable
internal fun LoadFonts() {
    val regular = Font(Res.font.jetbrainsmono_regular, FontWeight.Normal)
    val bold = Font(Res.font.jetbrainsmono_bold, FontWeight.Bold)
    val family = remember(regular, bold) { FontFamily(regular, bold) }
    SideEffect { Fonts.mono = family }
}
