package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.azula.terminal.TerminalSession

/** Android: the shared invisible soft-keyboard capture field. */
@Composable
actual fun RawTerminalInput(session: TerminalSession, convId: String, modifier: Modifier, reclaimFocusSignal: Int) =
    MobileRawTerminalInput(session, convId, modifier, reclaimFocusSignal)
