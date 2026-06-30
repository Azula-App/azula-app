package dev.azula.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.azula.net.createTransport
import kotlin.concurrent.Volatile

/**
 * Lets a platform own [AzulaState] outside the composition. Android sets this to a
 * process-scoped singleton (created by its `Application`) so the iroh node keeps
 * running while the Activity is backgrounded/destroyed — see the foreground
 * service. iOS/desktop leave it null and create per-composition (below).
 */
object AppStateHost {
    @Volatile
    var shared: AzulaState? = null
}

/**
 * Creates the app-wide [AzulaState] once and starts the transport. The scope is
 * tied to the root composition, i.e. the app's lifetime — unless a platform has
 * pre-installed a process-scoped singleton in [AppStateHost] (Android).
 */
@Composable
fun rememberAzulaState(isDemo: Boolean = false): AzulaState {
    AppStateHost.shared?.let { return it }
    val scope = rememberCoroutineScope()
    val state = remember { AzulaState(createTransport(), scope, isDemo) }
    LaunchedEffect(Unit) { state.start() }
    return state
}
