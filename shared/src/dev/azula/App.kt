package dev.azula

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.azula.state.AzulaState
import dev.azula.state.rememberAzulaState
import dev.azula.theme.AzulaTheme
import dev.azula.ui.AppBackgroundBrush
import dev.azula.ui.DesktopApp
import dev.azula.ui.MobileApp
import dev.azula.ui.setup.SetupFlow

/**
 * The single entry point every platform calls. Uses the process-scoped [AzulaState]
 * if a platform pre-installed one (Android's foreground service, or a `-mock` app
 * that injects fakes); otherwise builds the real one in-composition.
 */
@Composable
fun AzulaApp() {
    val state = rememberAzulaState()
    AzulaTheme {
        Box(Modifier.fillMaxSize().background(AppBackgroundBrush)) {
            SetupGate(state) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= 820.dp) DesktopApp(state) else MobileApp(state)
                }
            }
        }
        // Inbound strangers awaiting accept/decline now surface through
        // state.pendingInvites (see InviteService) -- the invites screen/sheet
        // UI for that is wired up separately.
    }
}

/**
 * Gates entry to the main app behind the first-run setup flow (see the onboarding
 * capability). Starts the state here -- moved out of [rememberAzulaState] so the
 * pre-bind gate decision (AzulaState.start's guarded [AzulaState.setupRequired]
 * evaluation) runs before the transport binds and eagerly mints a key. While the
 * decision is pending ([AzulaState.setupRequired] == null) only the app background
 * shows; a fresh install then shows [SetupFlow] until it completes, and every
 * other case shows [mainApp] directly.
 */
@Composable
private fun SetupGate(state: AzulaState, mainApp: @Composable () -> Unit) {
    LaunchedEffect(Unit) { state.start() }
    when (state.setupRequired) {
        null -> Unit // undecided: only the app background, briefly
        true -> SetupFlow(state)
        false -> mainApp()
    }
}
