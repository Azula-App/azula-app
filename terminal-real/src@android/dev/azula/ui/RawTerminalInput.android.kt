package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.azula.terminal.TerminalSession

/** Android: the shared invisible soft-keyboard capture field, or the legacy
 *  raw fallback when [smartInput] is disabled in Settings. */
@Composable
actual fun RawTerminalInput(session: TerminalSession, convId: String, modifier: Modifier, reclaimFocusSignal: Int, smartInput: Boolean) =
    if (smartInput) MobileRawTerminalInput(session, convId, modifier, reclaimFocusSignal)
    else LegacyMobileRawTerminalInput(session, convId, modifier, reclaimFocusSignal)
