package dev.azula.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import dev.azula.terminal.TerminalSession
import dev.azula.terminal.keyEventToBytes

/** Desktop: a focusable Box capturing the full hardware keyboard. No text field,
 *  so no IME concerns — every key (arrows, Esc, Tab, Ctrl-combos, printables)
 *  goes straight to the PTY. Auto-focuses; a tap re-focuses. */
@Composable
actual fun RawTerminalInput(session: TerminalSession, convId: String, modifier: Modifier, reclaimFocusSignal: Int) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(convId) { runCatching { fr.requestFocus() } }
    Box(
        modifier
            .focusRequester(fr)
            .focusable()
            .pointerInput(Unit) { detectTapGestures { runCatching { fr.requestFocus() } } }
            .onPreviewKeyEvent { ev ->
                val appKeys = session.terminalScreen(convId).applicationCursorKeys
                keyEventToBytes(ev, appCursorKeys = appKeys)?.let { session.terminalRaw(convId, it); true } ?: false
            },
    )
}
