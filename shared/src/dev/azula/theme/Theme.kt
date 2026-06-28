package dev.azula.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AzulaColorScheme = darkColorScheme(
    primary = AzulaColors.pink,
    onPrimary = AzulaColors.text0,
    secondary = AzulaColors.pinkLight,
    background = AzulaColors.bg1,
    onBackground = AzulaColors.text1,
    surface = AzulaColors.bg2,
    onSurface = AzulaColors.text1,
    error = AzulaColors.error,
)

/** Wraps content in the azula dark theme (drives ripples and Material defaults). */
@Composable
fun AzulaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AzulaColorScheme, content = content)
}
