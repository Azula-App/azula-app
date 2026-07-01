package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.azula.state.AzulaState

/** Android: the shared invisible soft-keyboard capture field. */
@Composable
actual fun RawTerminalInput(state: AzulaState, convId: String, modifier: Modifier) =
    MobileRawTerminalInput(state, convId, modifier)
