package dev.azula.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.terminal.TerminalSession
import dev.azula.terminal.TermSpan
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

@Composable
fun TerminalView(session: TerminalSession, convId: String) {
    // Online → live keystroke capture; offline (the shell exited) → the last
    // screen, frozen and read-only.
    LiveTerminalView(session, convId, live = session.terminalOnline(convId))
}

@Composable
private fun LiveTerminalView(session: TerminalSession, convId: String, live: Boolean) {
    val termScreen = session.terminalScreen(convId)
    val scroll = rememberScrollState()
    // Auto-scroll to bottom whenever rows or current line change.
    LaunchedEffect(termScreen.rows.size, termScreen.current) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    // Measure the mono cell so we can tell the PTY our size in character cells.
    val measurer = rememberTextMeasurer()
    val lineHpx = with(LocalDensity.current) { 21.sp.toPx() }
    val cellW = remember(Fonts.mono) {
        measurer.measure("MMMMMMMMMM", TextStyle(fontFamily = Fonts.mono, fontSize = 12.5.sp)).size.width / 10
    }

    val sizeMod = if (live) {
        Modifier.onSizeChanged { sz ->
            if (cellW > 0 && lineHpx > 0f) {
                // Account for the 18dp padding on each side (approx: subtract ~2 cells).
                session.terminalResize(convId, (sz.width / cellW - 2).coerceAtLeast(1), (sz.height / lineHpx).toInt())
            }
        }
    } else Modifier
    Box(Modifier.fillMaxSize().then(sizeMod)) {
        // Invisible keystroke-capture surface behind the text; a tap focuses it.
        // Omitted when frozen (session ended) — the screen is read-only.
        if (live) RawTerminalInput(session, convId, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(18.dp)) {
            termScreen.rows.forEach { row -> TermSpanRow(row) }
            Row {
                if (live) {
                    // Draw the caret at the real cursor column so backspace / left-arrow
                    // move it, splitting the line around it.
                    if (termScreen.currentBeforeCursor.isNotEmpty()) TermSpanRow(termScreen.currentBeforeCursor)
                    LiveCursor()
                    if (termScreen.currentAfterCursor.isNotEmpty()) TermSpanRow(termScreen.currentAfterCursor)
                } else {
                    TermSpanRow(termScreen.current)
                }
            }
        }
    }
}

/** One committed/current terminal line, rendered with its per-span colors. */
@Composable
private fun TermSpanRow(spans: List<TermSpan>) {
    if (spans.isEmpty()) {
        Text(" ", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
        return
    }
    val text = buildAnnotatedString {
        for (s in spans) {
            withStyle(
                SpanStyle(
                    color = s.color?.let { Color(it.toInt()) } ?: AzulaColors.textDim2,
                    fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
                ),
            ) { append(s.text) }
        }
    }
    Text(text, fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
}

@Composable
private fun LiveCursor() {
    val t = rememberInfiniteTransition(label = "liveCursor")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 1050
            1f at 0
            1f at 520
            0f at 525
            0f at 1050
        }, RepeatMode.Restart),
        label = "liveBlink",
    )
    Text("█", color = AzulaColors.pink.copy(alpha = a), fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
}
