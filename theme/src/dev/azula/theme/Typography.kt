package dev.azula.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * The azula type scale. See design-system.md §4.3. Provided in both families
 * since the app leans mono over sans (§4.2): [Mono] backs anything
 * machine-adjacent, [Ui] backs prose.
 *
 * [Mono]'s steps resolve [Fonts.mono] through a `get()` accessor rather than a
 * plain `val`. [Fonts.mono] is `by mutableStateOf`, seeded with a system
 * fallback and reassigned once to the bundled JetBrains Mono by [LoadFonts]'s
 * `SideEffect`. A plain top-level `val` would read [Fonts.mono] exactly once,
 * at `AzulaType.Mono`'s first access, and freeze every style to whatever font
 * was current then — almost always the fallback, since [LoadFonts] runs at the
 * theme root and its `SideEffect` fires after first composition. A `get()`
 * accessor instead re-reads [Fonts.mono] on every access: outside composition
 * it simply returns whatever font is current, and *inside* composition (e.g.
 * `Text(style = AzulaType.Mono.body)`) that read is tracked by the snapshot
 * system exactly like any other `State` read, so the call site recomposes
 * automatically the instant [Fonts.mono] flips to the bundled font. [Ui] uses
 * plain `val`s since [Fonts.ui] is a fixed value, not observable state — no
 * re-resolution is needed.
 *
 * Weights are left unspecified (Material/Compose default to regular/400) per
 * design-system.md §4.4 — these are the base steps, not emphasis variants.
 */
object AzulaType {
    object Mono {
        val micro: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 10.sp, lineHeight = 14.sp)
        val caption: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 11.sp, lineHeight = 15.sp)
        val bodySm: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 12.sp, lineHeight = 17.sp)
        val body: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 13.sp, lineHeight = 18.sp)
        val bodyLg: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 14.sp, lineHeight = 20.sp)
        val title: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 16.sp, lineHeight = 22.sp)
        val heading: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 20.sp, lineHeight = 26.sp)
        val display: TextStyle get() = TextStyle(fontFamily = Fonts.mono, fontSize = 24.sp, lineHeight = 32.sp)
    }

    object Ui {
        val micro = TextStyle(fontFamily = Fonts.ui, fontSize = 10.sp, lineHeight = 14.sp)
        val caption = TextStyle(fontFamily = Fonts.ui, fontSize = 11.sp, lineHeight = 15.sp)
        val bodySm = TextStyle(fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp)
        val body = TextStyle(fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp)
        val bodyLg = TextStyle(fontFamily = Fonts.ui, fontSize = 14.sp, lineHeight = 20.sp)
        val title = TextStyle(fontFamily = Fonts.ui, fontSize = 16.sp, lineHeight = 22.sp)
        val heading = TextStyle(fontFamily = Fonts.ui, fontSize = 20.sp, lineHeight = 26.sp)
        val display = TextStyle(fontFamily = Fonts.ui, fontSize = 24.sp, lineHeight = 32.sp)
    }
}
