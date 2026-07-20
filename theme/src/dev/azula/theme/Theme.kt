package dev.azula.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fills as many of Material3's ~30 color roles as make sense from
 * [AzulaColors], so components azula doesn't style directly (`DropdownMenu`,
 * `AlertDialog`, `TextButton`, …) don't fall through to Material's baseline
 * purple. See design-system.md §11 item 2.
 */
private val AzulaColorScheme = darkColorScheme(
    primary = AzulaColors.pink,
    onPrimary = AzulaColors.text0,
    primaryContainer = AzulaColors.primarySelected,
    onPrimaryContainer = AzulaColors.textBright,
    inversePrimary = AzulaColors.pinkLight,

    secondary = AzulaColors.pinkLight,
    onSecondary = AzulaColors.text0,
    secondaryContainer = AzulaColors.pinkDeep,
    onSecondaryContainer = AzulaColors.textBright,

    tertiary = AzulaColors.accent,
    onTertiary = AzulaColors.bg0,
    tertiaryContainer = AzulaColors.bg2,
    onTertiaryContainer = AzulaColors.text2,

    background = AzulaColors.bg1,
    onBackground = AzulaColors.text1,

    surface = AzulaColors.bg2,
    onSurface = AzulaColors.text1,
    surfaceVariant = AzulaColors.bg2,
    onSurfaceVariant = AzulaColors.textDim2,
    surfaceTint = AzulaColors.pink,

    surfaceBright = AzulaColors.surfaceAlt,
    surfaceDim = AzulaColors.bg0,
    surfaceContainerLowest = AzulaColors.bg0,
    surfaceContainerLow = AzulaColors.bg1,
    surfaceContainer = AzulaColors.bg2,
    surfaceContainerHigh = AzulaColors.surfaceAlt,
    surfaceContainerHighest = AzulaColors.inputBg,

    inverseSurface = AzulaColors.text0,
    inverseOnSurface = AzulaColors.bg1,

    error = AzulaColors.error,

    outline = AzulaColors.border4,
    outlineVariant = AzulaColors.border3,

    scrim = Color.Black,
)

/**
 * Material3 [Typography] built from [AzulaType.Ui] (§4.2: `ui` is the prose
 * family, and Material's default roles — button labels, menu items, dialog
 * text — read as prose, not machine-adjacent values). The app barely reads
 * these roles directly; this exists so any un-styled Material component still
 * lands on the azula scale instead of Material's default Roboto/sans steps.
 * Each of the 15 roles maps to its nearest §4.3 step; weight is left
 * unspecified (regular/400) per §4.4.
 */
private val AzulaTypography = Typography(
    displayLarge = AzulaType.Ui.display,
    displayMedium = AzulaType.Ui.display,
    displaySmall = AzulaType.Ui.heading,
    headlineLarge = AzulaType.Ui.heading,
    headlineMedium = AzulaType.Ui.heading,
    headlineSmall = AzulaType.Ui.title,
    titleLarge = AzulaType.Ui.title,
    titleMedium = AzulaType.Ui.title,
    titleSmall = AzulaType.Ui.bodyLg,
    bodyLarge = AzulaType.Ui.bodyLg,
    bodyMedium = AzulaType.Ui.body,
    bodySmall = AzulaType.Ui.bodySm,
    labelLarge = AzulaType.Ui.body,
    labelMedium = AzulaType.Ui.caption,
    labelSmall = AzulaType.Ui.micro,
)

private val AzulaMaterialShapes = Shapes(
    small = AzulaShapes.rSm,
    medium = AzulaShapes.rMd,
    large = AzulaShapes.rLg,
)

/** Wraps content in the azula dark theme (drives ripples and Material defaults). */
@Composable
fun AzulaTheme(content: @Composable () -> Unit) {
    LoadFonts()
    MaterialTheme(
        colorScheme = AzulaColorScheme,
        typography = AzulaTypography,
        shapes = AzulaMaterialShapes,
        content = content,
    )
}
