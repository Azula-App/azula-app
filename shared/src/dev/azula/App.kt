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
import dev.azula.ui.ShareSheet

/**
 * The single entry point every platform calls. Uses the process-scoped [AzulaState]
 * if a platform pre-installed one (Android's foreground service, or a `-mock` app
 * that injects fakes); otherwise builds the real one in-composition.
 */
@Composable
fun AzulaApp() {
    val state = rememberAzulaState()
    AzulaTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(AppBackgroundBrush)) {
            if (maxWidth >= 820.dp) DesktopApp(state) else MobileApp(state)
        }
        // Inbound connection: prompt (each time) to share a profile back. Shown
        // over either layout; one at a time from the queue.
        state.pendingShares.firstOrNull()?.let { req ->
            ShareSheet(
                state = state,
                title = "${req.peerCode} connected",
                subtitle = "Share a profile back? Choose which persona and which details to show.",
                confirmLabel = "Share",
                dismissLabel = "Decline",
                onResult = { frame -> state.respondShare(req.convId, frame) },
                onCancel = { /* keep pending; surfaces again next recomposition */ },
            )
        }
    }
}
