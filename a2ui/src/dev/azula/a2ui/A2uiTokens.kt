package dev.azula.a2ui

import androidx.compose.ui.graphics.Color
import dev.azula.theme.AzulaBrushes
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes

/**
 * The A2UI "neon-glass" design-system tokens — the single source of truth the
 * [A2uiSurfaceView] renderer draws from, so component styling is token-driven
 * rather than hardcoded per call site. Mirrors the `azula - A2UI Catalog` design
 * spec; brand colors are shared with [AzulaColors], a few catalog-specific values
 * live there too ([AzulaColors.surfaceAlt], [AzulaColors.muted], [AzulaColors.accent]).
 */
object A2uiTokens {
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
    val mutedFaint = AzulaColors.textFaint    // #6A6A7A — fails AA (3.8:1 on bg0); >12sp uses only, see design-system.md §11 item 8.
    /** Accessible stand-in for [mutedFaint] at ≤12sp, where 3.8:1 fails WCAG AA.
     *  #8A8A9A measures 5.9:1 on bg0. Do not repoint [mutedFaint] itself — that
     *  would also shift the >12sp call sites that are fine as-is. */
    val mutedSmall = AzulaColors.textDim2     // #8A8A9A

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

    // ── Shape scale ── delegates to AzulaShapes so there is one definition.
    // NOTE: this intentionally changes rMd 9→10dp and rLg 12→13dp — see
    // design-system.md §6.
    val rSm = AzulaShapes.rSm
    val rMd = AzulaShapes.rMd
    val rLg = AzulaShapes.rLg
    val rXl = AzulaShapes.rXl
    val rPill = AzulaShapes.rPill

    /** Chat-bubble "tail" corners — asymmetric so ME/THEM bubbles read as
     *  speech shapes rather than plain rounded rects. Not part of the
     *  agent-facing A2UI catalog (no renderer/tools.rs touch needed), just the
     *  shared chat-chrome shape used by [dev.azula.ui.MessageBubble] and
     *  [dev.azula.ui.ThinkingIndicator]. */
    val rBubbleMe = AzulaShapes.rBubbleMe
    val rBubbleThem = AzulaShapes.rBubbleThem

    // ── Gradients ──
    /** Primary Button / play-button fill: pink → deep pink (135° in the spec). */
    val primaryBrush = AzulaBrushes.primary
}
