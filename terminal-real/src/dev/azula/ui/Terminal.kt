package dev.azula.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.terminal.ATTR_BOLD
import dev.azula.terminal.ATTR_DIM
import dev.azula.terminal.ATTR_HIDDEN
import dev.azula.terminal.ATTR_ITALIC
import dev.azula.terminal.ATTR_REVERSE
import dev.azula.terminal.ATTR_UNDERLINE
import dev.azula.terminal.ATTR_WIDE_TRAIL
import dev.azula.terminal.Row
import dev.azula.terminal.TermFrame
import dev.azula.terminal.TerminalSession
import dev.azula.terminal.codePointToString
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

private val FONT_SIZE = 12.5.sp
private val LINE_HEIGHT = 21.sp
private val PAD = 12.dp

@Composable
fun TerminalView(session: TerminalSession, convId: String) {
    // Online → live keystroke capture + cursor; offline (the shell exited) → the
    // last screen, frozen and read-only.
    LiveTerminalView(session, convId, live = session.terminalOnline(convId))
}

@Composable
private fun LiveTerminalView(session: TerminalSession, convId: String, live: Boolean) {
    val emu = session.terminalScreen(convId)
    val frame = emu.frame   // subscribe: republished on every emulator change

    // Measure a monospace cell so we can report the viewport size in character cells.
    val measurer = rememberTextMeasurer()
    val lineHpx = with(LocalDensity.current) { LINE_HEIGHT.toPx() }
    val cellW = remember(Fonts.mono) {
        measurer.measure("MMMMMMMMMM", TextStyle(fontFamily = Fonts.mono, fontSize = FONT_SIZE)).size.width / 10
    }
    val padPx = with(LocalDensity.current) { PAD.toPx() }

    // A blinking cursor while live; hidden when the app hid it (DECTCEM ?25l).
    val cursorOn = if (live && frame.cursorVisible) rememberCursorBlink() else false

    val sizeMod = if (live) {
        Modifier.onSizeChanged { sz ->
            if (cellW > 0 && lineHpx > 0f) {
                val cols = ((sz.width - 2 * padPx) / cellW).toInt().coerceAtLeast(1)
                val rows = ((sz.height - 2 * padPx) / lineHpx).toInt().coerceAtLeast(1)
                session.terminalResize(convId, cols, rows)
            }
        }
    } else Modifier

    Box(Modifier.fillMaxSize().background(AzulaColors.bg1).then(sizeMod)) {
        // Invisible keystroke-capture surface behind the text; a tap focuses it.
        if (live) RawTerminalInput(session, convId, Modifier.matchParentSize())

        if (frame.altScreen) AltScreen(frame, cursorOn) else PrimaryScreen(frame, cursorOn)
    }
}

/** Full-screen TUI (vi/top/…): a fixed grid, no scrollback, no auto-scroll. */
@Composable
private fun AltScreen(frame: TermFrame, cursorOn: Boolean) {
    Column(Modifier.fillMaxSize().padding(PAD)) {
        val preds = predictionsByRow(frame)
        frame.grid.forEachIndexed { r, row -> GridRow(row, r, frame, cursorOn, preds) }
    }
}

/** Interactive shell: scrollback + the live grid in a lazily-rendered scroller. */
@Composable
private fun PrimaryScreen(frame: TermFrame, cursorOn: Boolean) {
    val listState = rememberLazyListState()
    val total = frame.scrollback.size + frame.grid.size

    // Stick to the bottom when new output arrives *if* we're already near it (so the
    // user can scroll up into scrollback without being yanked back down).
    LaunchedEffect(frame) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (total > 0 && (last < 0 || last >= total - 2)) listState.scrollToItem(total - 1)
    }

    val preds = predictionsByRow(frame)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(PAD)) {
        items(frame.scrollback.size) { i -> GridRow(frame.scrollback[i], -1, frame, cursorOn, preds) }
        items(frame.grid.size) { r -> GridRow(frame.grid[r], r, frame, cursorOn, preds) }
    }
}

/** One terminal row (grid row [gridIndex]; -1 for a scrollback row). */
@Composable
private fun GridRow(row: Row, gridIndex: Int, frame: TermFrame, cursorOn: Boolean, preds: Map<Int, Map<Int, Int>>) {
    val cursorHere = cursorOn && gridIndex == frame.cursorRow
    val rowPreds = if (gridIndex >= 0) preds[gridIndex] else null
    val text = remember(row.gen, cursorHere, frame.cursorCol, rowPreds) {
        buildRow(row, if (cursorHere) frame.cursorCol else -1, rowPreds)
    }
    Text(
        text,
        fontFamily = Fonts.mono,
        fontSize = FONT_SIZE,
        lineHeight = LINE_HEIGHT,
        softWrap = false,
        maxLines = 1,
    )
}

/** Group predictions by grid row → (col → code point). */
private fun predictionsByRow(frame: TermFrame): Map<Int, Map<Int, Int>> {
    if (frame.predictions.isEmpty()) return emptyMap()
    return frame.predictions.groupBy { it.row }.mapValues { (_, cells) -> cells.associate { it.col to it.cp } }
}

private val DEFAULT_FG = AzulaColors.text2
private val TERM_BG = AzulaColors.bg1
private val CURSOR = AzulaColors.pink
private val PRED_FG = AzulaColors.textDim

/**
 * Build a row's [AnnotatedString], coalescing runs of equal style. Applies fg/bg,
 * bold/dim/italic/underline, reverse (swap fg/bg) and hidden; overlays the block
 * cursor at [cursorCol] and any predicted glyphs from [rowPreds].
 */
private fun buildRow(row: Row, cursorCol: Int, rowPreds: Map<Int, Int>?): AnnotatedString = buildAnnotatedString {
    var runStyle: SpanStyle? = null
    val sb = StringBuilder()
    fun flush() { if (sb.isNotEmpty()) { withStyle(runStyle ?: SpanStyle(color = DEFAULT_FG)) { append(sb.toString()) }; sb.setLength(0) } }

    for (c in 0 until row.cols) {
        if (row.flags[c] and ATTR_WIDE_TRAIL != 0) continue
        val pred = rowPreds?.get(c)
        val isCursor = c == cursorCol
        val cp = pred ?: row.cp[c]
        val glyph = if (cp <= 0) " " else codePointToString(cp)

        val style = when {
            isCursor -> SpanStyle(color = TERM_BG, background = CURSOR)
            pred != null -> SpanStyle(color = PRED_FG, textDecoration = TextDecoration.Underline)
            else -> cellStyle(row, c)
        }
        if (style != runStyle) { flush(); runStyle = style }
        sb.append(glyph)
    }
    flush()
}

private fun cellStyle(row: Row, c: Int): SpanStyle {
    val fl = row.flags[c]
    var fg = if (row.fg[c] == 0L) DEFAULT_FG else Color(row.fg[c].toInt())
    var bg = if (row.bg[c] == 0L) null else Color(row.bg[c].toInt())
    if (fl and ATTR_REVERSE != 0) { val f = fg; fg = bg ?: TERM_BG; bg = f }
    if (fl and ATTR_DIM != 0) fg = fg.copy(alpha = 0.6f)
    if (fl and ATTR_HIDDEN != 0) fg = bg ?: TERM_BG
    return SpanStyle(
        color = fg,
        background = bg ?: Color.Unspecified,
        fontWeight = if (fl and ATTR_BOLD != 0) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (fl and ATTR_ITALIC != 0) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (fl and ATTR_UNDERLINE != 0) TextDecoration.Underline else null,
    )
}

@Composable
private fun rememberCursorBlink(): Boolean {
    val t = rememberInfiniteTransition(label = "cursor")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 1050
            1f at 0
            1f at 520
            0f at 525
            0f at 1050
        }, RepeatMode.Restart),
        label = "blink",
    )
    return a > 0.5f
}
