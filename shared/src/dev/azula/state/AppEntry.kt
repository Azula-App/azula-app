package dev.azula.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.azula.net.createTransport

/**
 * Creates the app-wide [AzulaState] once and starts the transport. The scope is
 * tied to the root composition, i.e. the app's lifetime.
 */
@Composable
fun rememberAzulaState(): AzulaState {
    val scope = rememberCoroutineScope()
    val state = remember { AzulaState(createTransport(), scope) }
    LaunchedEffect(Unit) { state.start() }
    return state
}
