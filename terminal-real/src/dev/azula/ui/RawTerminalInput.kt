package dev.azula.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import dev.azula.terminal.TerminalSession
import dev.azula.terminal.TermSeq
import dev.azula.terminal.keyEventToBytes

/**
 * The invisible keystroke-capture surface for a LIVE terminal: fills the terminal
 * area, auto-focuses, and sends raw bytes to the PTY. Platform-split because
 * desktop uses a hardware keyboard (a focusable Box + onPreviewKeyEvent) while
 * mobile uses the soft keyboard (a hidden text field). Only shown when the
 * terminal is online (the offline demo keeps the classic input bar).
 *
 * [reclaimFocusSignal] is bumped by [Terminal.kt]'s outer Box on every tap
 * anywhere on the terminal surface -- see its doc for why that tap has to be
 * detected there rather than on this composable's own modifier chain -- and is
 * ignored by the jvm actual, which already re-focuses itself on tap.
 */
@Composable
expect fun RawTerminalInput(session: TerminalSession, convId: String, modifier: Modifier = Modifier, reclaimFocusSignal: Int = 0)

/**
 * Shared mobile implementation (Android + iOS): an invisible [BasicTextField]
 * that captures the soft keyboard. Printable characters and Backspace are read
 * via [onValueChange] using a one-char baseline **sentinel** — the field is kept
 * at the sentinel with the caret after it so that (a) typed characters appear as
 * a longer value and (b) Backspace on the otherwise-empty field is detectable as
 * a shorter value (soft keyboards emit nothing when deleting an empty field).
 * `KeyboardType.Password` disables IME composition/autocorrect so each keystroke
 * arrives as an isolated commit. Enter is the keyboard's Go action → CR. A
 * hardware keyboard (tablet) still delivers non-printable keys via
 * [onPreviewKeyEvent] (printables fall through to the field, avoiding double-send).
 */
@Composable
internal fun MobileRawTerminalInput(session: TerminalSession, convId: String, modifier: Modifier, reclaimFocusSignal: Int = 0) {
    val baseline = " " // NBSP; the field is invisible so it never shows.
    val fr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var value by remember { mutableStateOf(TextFieldValue(baseline, TextRange(baseline.length))) }

    LaunchedEffect(convId) {
        runCatching { fr.requestFocus() }
        keyboard?.show()
    }

    // Re-summon the keyboard when Terminal.kt's outer Box detects a tap on the
    // terminal surface -- the field only auto-focuses on conversation switch
    // (the effect above), so a user who dismissed the soft keyboard (e.g.
    // Android's back gesture, which drops only the IME, not Compose focus) and
    // then tapped the terminal would otherwise be stuck with focus but no
    // visible keyboard. Skip signal 0 (the initial value, already covered above).
    //
    // This can't be a tap handler on this field's own modifier chain: confirmed
    // on-device (Pixel 6 Pro, `dumpsys input_method | grep mInputShown`) that a
    // pointerInput attached here -- Main pass via detectTapGestures, and even an
    // Initial-pass raw awaitFirstDown -- never sees ANY touch, not even its own
    // coroutine start (instrumented with logging). BasicTextField's internal
    // implementation apparently doesn't route real touch hit-testing through
    // the modifier chain passed into it the way it does for e.g. focusRequester;
    // an identical pointerInput on Terminal.kt's outer Box fired immediately.
    LaunchedEffect(reclaimFocusSignal) {
        if (reclaimFocusSignal > 0) {
            focusManager.clearFocus()
            runCatching { fr.requestFocus() }
            keyboard?.show()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { v ->
            val text = v.text
            val delta = if (text.length > baseline.length) text.substring(baseline.length).replace("\n", "\r") else ""
            when {
                // A multi-character delta is a paste — route it through the paste path
                // (bracketed-paste wrapped when the remote app enabled it). Single
                // characters are ordinary keystrokes (and get predicted).
                delta.length > 1 -> session.terminalPaste(convId, delta)
                delta.length == 1 -> session.terminalRaw(convId, delta)
                text.length < baseline.length -> session.terminalRaw(convId, TermSeq.BACKSPACE)
            }
            // Always snap back to the sentinel so the next keystroke is a clean delta.
            value = TextFieldValue(baseline, TextRange(baseline.length))
        },
        modifier = modifier
            .focusRequester(fr)
            .onPreviewKeyEvent { ev ->
                val appKeys = session.terminalScreen(convId).applicationCursorKeys
                keyEventToBytes(ev, includePrintable = false, appCursorKeys = appKeys)
                    ?.let { session.terminalRaw(convId, it); true } ?: false
            },
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { session.terminalRaw(convId, TermSeq.ENTER) }),
    )
}
