package dev.azula

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.azula.state.rememberAzulaState
import dev.azula.theme.AzulaTheme
import dev.azula.ui.AppBackgroundBrush
import dev.azula.ui.DesktopApp
import dev.azula.ui.MobileApp

/** The single entry point every platform calls. [isDemo] enables seeded demo
 *  content for the demo/UI-test build; the shipping build leaves it false. */
@Composable
fun AzulaApp(isDemo: Boolean = false) {
    val state = rememberAzulaState(isDemo)
    AzulaTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(AppBackgroundBrush)) {
            if (maxWidth >= 820.dp) DesktopApp(state) else MobileApp(state)
        }
    }
}
