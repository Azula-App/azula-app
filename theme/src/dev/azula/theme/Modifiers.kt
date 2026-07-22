package dev.azula.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Reusable styling primitives for the azula "neon glass" system, extracted from
 * the hand-rolled `Modifier` chains in `A2uiRenderer.kt`'s `RenderCard`/
 * `RenderButton` and the glow-halo block duplicated in `MobileApp.kt`/`Connect.kt`.
 * See design-system.md §7 (glow/shadow/gradients) and §6 (radius).
 *
 * These are extension functions rather than wrapper composables so a call site
 * can migrate its existing content lambda with a one-line `Modifier` edit.
 */

/**
 * The neon-glass card surface: a shadowed, filled, bordered container, or (when
 * [nested]) a plain clipped-and-bordered nested variant with a stronger border and
 * no shadow/fill. Mirrors `A2uiRenderer.RenderCard`, whose `A2uiTokens.outlineSoft`
 * / `A2uiTokens.outlineStrong` / `A2uiTokens.surfaceAlt` resolve to
 * [AzulaColors.border3] / [AzulaColors.border5] / [AzulaColors.surfaceAlt].
 */
fun Modifier.azulaCard(nested: Boolean = false, shape: Shape = AzulaShapes.rLg): Modifier =
    if (nested) {
        this
            .clip(shape)
            .border(1.dp, AzulaColors.border5, shape)
    } else {
        this
            .shadow(6.dp, shape)
            .clip(shape)
            .background(AzulaColors.surfaceAlt)
            .border(1.dp, AzulaColors.border3, shape)
    }

/**
 * The signature glow (design-system.md §7.1): a symmetric `0 0 [radius]` halo with
 * **no offset** -- light comes from the accent, not from above. The default is
 * §7.1's `glowSm` (`0 0 8px rgba(255,45,155,.40)`), whose alpha is [alpha]; the
 * first cut used `glowLg`'s 18dp and read far too heavy on a real screen.
 *
 * Drawn, not cast. `Modifier.shadow` maps onto Android's elevation model, whose
 * spot shadow is displaced away from a light source above the window and grows
 * with the view's distance down the screen, so it can't express an offset-free
 * halo -- a primary button near the bottom of a tall screen picked up a visibly
 * displaced dark band. Its `spotColor`/`ambientColor` are also honoured only on
 * API 28+, and ignored outright on the iOS/JVM targets, where the "glow" fell
 * back to a black drop shadow.
 *
 * Painted as [steps] nested translucent rounded rects with a per-ring alpha of
 * `1 - (1 - alpha)^(1/steps)`, so they composite to exactly [alpha] at the
 * innermost ring and decay to nothing at [radius]. Only the top-start corner of
 * [shape] is read; every shape on the [AzulaShapes] scale is symmetric.
 *
 * The halo paints *outward* from the node's bounds and so is subject to any
 * ancestor clip (`azulaCard`, `verticalScroll`). A call site flush against a
 * clipping edge needs [radius] of padding for the halo to show in full.
 */
fun Modifier.azulaGlow(
    shape: Shape = AzulaShapes.rMd,
    radius: Dp = 8.dp,
    color: Color = AzulaColors.pink,
    alpha: Float = 0.40f,
    steps: Int = 16,
): Modifier = this.drawBehind {
    val spread = radius.toPx()
    if (spread <= 0f || steps <= 0) return@drawBehind
    val corner = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
    val ringAlpha = 1f - (1f - alpha).pow(1f / steps)
    for (i in steps downTo 1) {
        val grow = spread * i / steps
        drawRoundRect(
            color = color.copy(alpha = ringAlpha),
            topLeft = Offset(-grow, -grow),
            size = Size(size.width + grow * 2f, size.height + grow * 2f),
            cornerRadius = CornerRadius(corner + grow),
        )
    }
}

/** Button surface variant, mirroring `A2uiRenderer.RenderButton`'s string variants. */
enum class AzulaButtonVariant { Primary, Default, Borderless }

/**
 * The neon-glass button fill for [variant]. Mirrors `A2uiRenderer.RenderButton`,
 * whose `A2uiTokens.primary` / `A2uiTokens.surfaceAlt` / `A2uiTokens.outlineStrong`
 * resolve to [AzulaColors.pink] / [AzulaColors.surfaceAlt] / [AzulaColors.border5].
 */
fun Modifier.azulaButtonSurface(variant: AzulaButtonVariant, shape: Shape = AzulaShapes.rMd): Modifier =
    when (variant) {
        AzulaButtonVariant.Primary -> this
            .azulaGlow(shape)
            .clip(shape)
            .background(AzulaBrushes.primary)
        AzulaButtonVariant.Borderless -> this
        AzulaButtonVariant.Default -> this
            .clip(shape)
            .background(AzulaColors.surfaceAlt)
            .border(1.dp, AzulaColors.border5, shape)
    }

/**
 * The pink glow-halo treatment used by "call to action" boxes (e.g. "+ connect a
 * peer", "+ create invite"). Duplicated byte-for-byte at `MobileApp.kt` and
 * `Connect.kt` before this extraction — clip precedes an unparameterized
 * `background(brush)`, matching the original call sites exactly (not
 * `background(brush, shape)`).
 */
fun Modifier.azulaGlowHalo(shape: Shape = AzulaShapes.rLg): Modifier =
    this
        .clip(shape)
        .background(Brush.linearGradient(listOf(AzulaColors.primaryGlowFill, AzulaColors.primaryWash)))
        .border(1.dp, AzulaColors.primaryEdge, shape)

/**
 * The "input surface" recipe (design-system.md §7.5): text fields, code boxes,
 * and share-code chrome across `Chat.kt`, `Settings.kt`, `Connect.kt`,
 * `Common.kt` and `ShareInvite.kt`. Fill is [AzulaColors.inputBg], border is
 * [AzulaColors.border4] — distinct from [azulaCard]'s `surfaceAlt`/`border3`.
 */
fun Modifier.azulaInputSurface(shape: Shape = AzulaShapes.rMd): Modifier =
    this
        .clip(shape)
        .background(AzulaColors.inputBg)
        .border(1.dp, AzulaColors.border4, shape)

/**
 * The "muted panel" recipe (design-system.md §7.5): settings/sidebar grouping
 * panels in `Settings.kt` and `Sidebar.kt`. Fill is [AzulaColors.bg2], border is
 * [AzulaColors.border2].
 */
fun Modifier.azulaMutedPanel(shape: Shape = AzulaShapes.rLg): Modifier =
    this
        .clip(shape)
        .background(AzulaColors.bg2)
        .border(1.dp, AzulaColors.border2, shape)
