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
import dev.azula.state.AzulaState
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
fun TerminalKeysBar(state: AzulaState, convId: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(AzulaColors.bg2)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Keycap("esc") { state.terminalRaw(convId, TermSeq.ESC) }
        Keycap("tab") { state.terminalRaw(convId, TermSeq.TAB) }
        Keycap("^C") { state.terminalInterrupt(convId) }
        Keycap("^D") { state.terminalRaw(convId, ctrl('d')) }
        Keycap("^L") { state.terminalRaw(convId, ctrl('l')) }
        Keycap("←") { state.terminalRaw(convId, TermSeq.LEFT) }
        Keycap("↓") { state.terminalRaw(convId, TermSeq.DOWN) }
        Keycap("↑") { state.terminalRaw(convId, TermSeq.UP) }
        Keycap("→") { state.terminalRaw(convId, TermSeq.RIGHT) }
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
