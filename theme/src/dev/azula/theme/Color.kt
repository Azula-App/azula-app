package dev.azula.theme

import androidx.compose.ui.graphics.Color

/** The azula palette, lifted directly from the design mock. */
object AzulaColors {
    // Backgrounds
    val bg0 = Color(0xFF070709)
    val bg1 = Color(0xFF0A0A10)
    val bg2 = Color(0xFF0C0C13)
    val bgSidebar = Color(0xFF08080D)
    val bgGradTop = Color(0xFF120A18)
    val codeBlock = Color(0xFF070709)
    val inputBg = Color(0xFF15151D)
    val msgOther = Color(0xFF15151F)

    // Borders / hairlines
    val border = Color(0xFF1B1B24)
    val border2 = Color(0xFF1D1D26)
    val border3 = Color(0xFF23232E)
    val border4 = Color(0xFF26262F)
    val border5 = Color(0xFF2A2A36)

    // Pink accent family
    val pink = Color(0xFFFF2D9B)
    val pinkLight = Color(0xFFFF6EC7)
    val pinkDark = Color(0xFFC4156E)
    val pinkDeep = Color(0xFF7A1850)

    // A2UI catalog tokens not already covered above
    val surfaceAlt = Color(0xFF0D0D15)
    val muted = Color(0xFF7A7A8A)
    val accent = Color(0xFF3FC8FF)

    // Status
    val online = Color(0xFF52C98A)
    val onlineBright = Color(0xFF00FF9C)
    val warn = Color(0xFFFFD23F)
    val error = Color(0xFFFF6B6B)

    // Mac traffic lights
    val macRed = Color(0xFFFF5F57)
    val macYellow = Color(0xFFFEBC2E)
    val macGreen = Color(0xFF28C840)

    // Text ramp
    val textBright = Color(0xFFF3EEF1)
    val text0 = Color(0xFFF0F0F6)
    val text1 = Color(0xFFE6E6EE)
    val text2 = Color(0xFFD6D6E0)
    val textDim = Color(0xFF9A9AAA)
    val textDim2 = Color(0xFF8A8A9A)
    val textFaint = Color(0xFF6A6A7A)
    val textFaint2 = Color(0xFF5A5A6A)

    // Terminal greens
    val termGreen = Color(0xFF9EFFC4)
    val termPromptUser = Color(0xFF52C98A)

    // Translucent accent — the "selected / active" idiom. See design-system.md §3.4.
    val primaryWash = Color(0x14FF2D9B)        // 8%  — subtle row highlight
    val primarySelected = Color(0x24FF2D9B)    // 14% — selected row/chip fill
    val primaryGlowFill = Color(0x33FF2D9B)    // 20% — glow-halo fill
    val primaryEdge = Color(0x66FF2D9B)        // 40% — default border on any of the above
    val primaryEdgeStrong = Color(0x99FF2D9B)  // 60% — border on the active/selected item
}
