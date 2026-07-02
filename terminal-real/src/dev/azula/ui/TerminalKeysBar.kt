package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.terminal.TerminalSession
import dev.azula.terminal.TermSeq
import dev.azula.terminal.ctrl
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

/**
 * The compact accessory key row for a live terminal — the keys a soft keyboard
 * lacks: Esc, Tab, common Ctrl sequences, and the arrows. One slim, horizontally
 * scrollable row so it never crowds the screen. Keys use [detectTapGestures] (not
 * `clickable`) so tapping them never steals focus / dismisses the keyboard.
 */
@Composable
fun TerminalKeysBar(session: TerminalSession, convId: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(AzulaColors.bg2)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Arrows honor the app-cursor-keys mode (vi/less flip to `ESC O x`); read at
        // tap time so it always reflects the emulator's current mode.
        fun appKeys() = session.terminalScreen(convId).applicationCursorKeys
        Keycap("esc") { session.terminalRaw(convId, TermSeq.ESC) }
        Keycap("tab") { session.terminalRaw(convId, TermSeq.TAB) }
        Keycap("^C") { session.terminalInterrupt(convId) }
        Keycap("^D") { session.terminalRaw(convId, ctrl('d')) }
        Keycap("^L") { session.terminalRaw(convId, ctrl('l')) }
        Keycap("←") { session.terminalRaw(convId, if (appKeys()) TermSeq.APP_LEFT else TermSeq.LEFT) }
        Keycap("↓") { session.terminalRaw(convId, if (appKeys()) TermSeq.APP_DOWN else TermSeq.DOWN) }
        Keycap("↑") { session.terminalRaw(convId, if (appKeys()) TermSeq.APP_UP else TermSeq.UP) }
        Keycap("→") { session.terminalRaw(convId, if (appKeys()) TermSeq.APP_RIGHT else TermSeq.RIGHT) }
    }
}

@Composable
private fun Keycap(label: String, onTap: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .background(AzulaColors.inputBg)
            .border(1.dp, AzulaColors.border4, RoundedCornerShape(7.dp))
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .padding(horizontal = 13.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = AzulaColors.text2, fontFamily = Fonts.mono, fontSize = 13.sp)
    }
}
