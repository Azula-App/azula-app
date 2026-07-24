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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import dev.azula.terminal.TerminalSession
import dev.azula.terminal.keyEventToBytes

/** Desktop: a focusable Box capturing the full hardware keyboard. No text field,
 *  so no IME concerns — every key (arrows, Esc, Tab, Ctrl-combos, printables)
 *  goes straight to the PTY. Auto-focuses; a tap re-focuses. [smartInput] is
 *  unused here (desktop has no soft-keyboard fallback to switch between).
 *
 *  Paste is intercepted here rather than encoded as bytes: it has to go through
 *  `TerminalSession.terminalPaste` so the remote app's bracketed-paste mode is
 *  honored. This is desktop's *only* paste route now that the accessory key row
 *  — whose Paste key used to be it — is soft-keyboard-only (see
 *  `openspec/specs/terminal/spec.md`, "Accessory Key Row Is Soft-Keyboard
 *  Only"). Cmd-V on macOS, Ctrl-Shift-V elsewhere; deliberately **not** plain
 *  Ctrl-V, which a terminal owes the remote program as SYN (readline's
 *  quoted-insert). */
@Composable
actual fun RawTerminalInput(session: TerminalSession, convId: String, modifier: Modifier, reclaimFocusSignal: Int, smartInput: Boolean) {
    val fr = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(convId) { runCatching { fr.requestFocus() } }
    Box(
        modifier
            .focusRequester(fr)
            .focusable()
            .pointerInput(Unit) { detectTapGestures { runCatching { fr.requestFocus() } } }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.V &&
                    (ev.isMetaPressed || (ev.isCtrlPressed && ev.isShiftPressed))
                ) {
                    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }
                        ?.let { session.terminalPaste(convId, it) }
                    return@onPreviewKeyEvent true
                }
                val appKeys = session.terminalScreen(convId).applicationCursorKeys
                keyEventToBytes(ev, appCursorKeys = appKeys)?.let { session.terminalRaw(convId, it); true } ?: false
            },
    )
}
