package dev.azula.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.terminal.ATTR_BOLD
import dev.azula.terminal.ATTR_DIM
import dev.azula.terminal.ATTR_HIDDEN
import dev.azula.terminal.ATTR_ITALIC
import dev.azula.terminal.ATTR_REVERSE
import dev.azula.terminal.ATTR_UNDERLINE
import dev.azula.terminal.ATTR_WIDE_LEAD
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
fun TerminalView(session: TerminalSession, convId: String, smartInput: Boolean = true) {
    // Online → live keystroke capture + cursor; offline (the shell exited) → the
    // last screen, frozen and read-only.
    LiveTerminalView(session, convId, live = session.terminalOnline(convId), smartInput = smartInput)
}

@Composable
private fun LiveTerminalView(session: TerminalSession, convId: String, live: Boolean, smartInput: Boolean = true) {
    val emu = session.terminalScreen(convId)
    val frame = emu.frame   // subscribe: republished on every emulator change

    // Measure a monospace cell so we can report the viewport size in character cells.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineHpx = with(density) { LINE_HEIGHT.toPx() }
    // Rounded to a whole pixel so IME-driven resizes (which nudge the available
    // density/height) don't sub-pixel remeasure every row and jitter them.
    val rowHeight = with(density) { kotlin.math.round(lineHpx).toDp() }
    val cellW = remember(Fonts.mono) {
        measurer.measure("MMMMMMMMMM", TextStyle(fontFamily = Fonts.mono, fontSize = FONT_SIZE)).size.width / 10
    }
    val padPx = with(density) { PAD.toPx() }

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

    // Bumped on every tap on the terminal surface, to tell RawTerminalInput to
    // reclaim focus + re-summon the keyboard (mobile only; the jvm actual
    // re-focuses itself and ignores this). Detected here, on this outer Box,
    // rather than on RawTerminalInput's own modifier chain: confirmed on-device
    // that a tap pointerInput attached to the (BasicTextField-backed) mobile
    // field's own modifier chain never receives real touches at all -- not even
    // its coroutine start, instrumented with logging -- while an identical one
    // on this Box fires immediately. BasicTextField's internal implementation
    // apparently doesn't route real hit-testing through the modifier chain
    // passed into it the way it does e.g. focusRequester.
    var reclaimFocusSignal by remember(convId) { mutableStateOf(0) }
    val tapMod = if (live) {
        Modifier.pointerInput(convId) { detectTapGestures { reclaimFocusSignal++ } }
    } else Modifier

    Box(Modifier.fillMaxSize().background(AzulaColors.bg1).then(sizeMod).then(tapMod)) {
        // Invisible keystroke-capture surface behind the text; a tap focuses it.
        if (live) RawTerminalInput(session, convId, Modifier.matchParentSize(), reclaimFocusSignal, smartInput)

        if (frame.altScreen) AltScreen(frame, cursorOn, rowHeight, cellW, measurer) else PrimaryScreen(frame, cursorOn, rowHeight, cellW, measurer)
    }
}

/** Full-screen TUI (vi/top/…): a fixed grid, no scrollback, no auto-scroll. */
@Composable
private fun AltScreen(frame: TermFrame, cursorOn: Boolean, rowHeight: Dp, cellW: Int, measurer: TextMeasurer) {
    Column(Modifier.fillMaxSize().padding(PAD)) {
        val preds = predictionsByRow(frame)
        frame.grid.forEachIndexed { r, row -> GridRow(row, r, frame, cursorOn, preds, rowHeight, cellW, measurer) }
    }
}

/** Interactive shell: scrollback + the live grid in a lazily-rendered scroller. */
@Composable
private fun PrimaryScreen(frame: TermFrame, cursorOn: Boolean, rowHeight: Dp, cellW: Int, measurer: TextMeasurer) {
    val listState = rememberLazyListState()
    val total = frame.scrollback.size + frame.grid.size

    // Stick to the bottom when new output arrives *if* we're already pinned to it --
    // never while the user is actively scrolling/dragging (that fight is the main
    // source of jitter, including on every IME-driven resize).
    LaunchedEffect(frame) {
        if (!listState.isScrollInProgress && atBottom(listState, total)) listState.scrollToItem(total - 1)
    }

    val preds = predictionsByRow(frame)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(PAD)) {
        items(frame.scrollback.size) { i -> GridRow(frame.scrollback[i], -1, frame, cursorOn, preds, rowHeight, cellW, measurer) }
        items(frame.grid.size) { r -> GridRow(frame.grid[r], r, frame, cursorOn, preds, rowHeight, cellW, measurer) }
    }
}

/** Whether [listState] is scrolled to (or past) the last item, i.e. pinned to the
 *  tail -- also true before the first layout pass (no visible items yet), so the
 *  initial open-to-bottom behavior is preserved. */
private fun atBottom(listState: LazyListState, total: Int): Boolean {
    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return total > 0 && last >= total - 1
}

/**
 * One terminal row (grid row [gridIndex]; -1 for a scrollback row), rendered on a
 * hard monospace grid: every cell is drawn at an exact `x = col * cellW` on a
 * [Canvas], instead of flowing one `Text` per row and trusting font advance to
 * keep columns aligned. This makes alignment immune to font-advance drift -- a
 * mis-widthed glyph (an emoji/symbol the fallback font renders at a slightly
 * different width) can only smear itself and its own coalesced run, never shift
 * anything in a later run or row, because every run's start x is recomputed from
 * its grid column, not accumulated from the glyphs before it.
 *
 * [rowHeight] is a whole-pixel-rounded height so IME-driven density/size changes
 * don't sub-pixel remeasure rows and nudge their positions. [cellW] is the
 * monospace cell width in px (see [LiveTerminalView]).
 */
@Composable
private fun GridRow(
    row: Row,
    gridIndex: Int,
    frame: TermFrame,
    cursorOn: Boolean,
    preds: Map<Int, Map<Int, Int>>,
    rowHeight: Dp,
    cellW: Int,
    measurer: TextMeasurer,
) {
    val cursorHere = cursorOn && gridIndex == frame.cursorRow
    val rowPreds = if (gridIndex >= 0) preds[gridIndex] else null
    // cellW is a remember() key (not just a draw-time offset) because the glyphs are
    // pre-measured with Fonts.mono here: it's read inside this plain (non-@Composable)
    // lambda, so a later font swap (system-fallback -> bundled JetBrains Mono, see
    // LoadFonts) wouldn't otherwise invalidate already-built segments. cellW itself is
    // remember(Fonts.mono)-keyed in LiveTerminalView, so it changes exactly when the
    // font does, piggy-backing that invalidation here.
    val segments = remember(row.gen, cursorHere, frame.cursorCol, rowPreds, cellW) {
        buildRowSegments(row, if (cursorHere) frame.cursorCol else -1, rowPreds, measurer)
    }
    Canvas(Modifier.fillMaxWidth().height(rowHeight)) {
        val rowHeightPx = size.height
        for (seg in segments) {
            val x = seg.startCol * cellW.toFloat()
            if (seg.bg != null) {
                drawRect(seg.bg, topLeft = Offset(x, 0f), size = Size(seg.cellSpan * cellW.toFloat(), rowHeightPx))
            }
            val y = (rowHeightPx - seg.layout.size.height) / 2f
            drawText(seg.layout, topLeft = Offset(x, y))
        }
    }
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

/** A resolved, already-measured run of one or more cells sharing a style, ready
 *  to paint at [startCol] * cellW. [cellSpan] is how many grid columns the
 *  background rect (and the next segment's start) should account for. */
private class RowSegment(val startCol: Int, val layout: TextLayoutResult, val bg: Color?, val cellSpan: Int)

/** Resolved per-cell paint attributes (fg/bg after reverse/dim/hidden, plus the
 *  text attributes that affect glyph shape). Two cells with equal [CellAttrs]
 *  can share one coalesced run. */
private data class CellAttrs(
    val fg: Color,
    val bg: Color?,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
)

private fun cellAttrs(row: Row, c: Int): CellAttrs {
    val fl = row.flags[c]
    var fg = if (row.fg[c] == 0L) DEFAULT_FG else Color(row.fg[c].toInt())
    var bg = if (row.bg[c] == 0L) null else Color(row.bg[c].toInt())
    if (fl and ATTR_REVERSE != 0) { val f = fg; fg = bg ?: TERM_BG; bg = f }
    if (fl and ATTR_DIM != 0) fg = fg.copy(alpha = 0.6f)
    if (fl and ATTR_HIDDEN != 0) fg = bg ?: TERM_BG
    return CellAttrs(fg, bg, fl and ATTR_BOLD != 0, fl and ATTR_ITALIC != 0, fl and ATTR_UNDERLINE != 0)
}

private fun textStyleFor(a: CellAttrs): TextStyle = TextStyle(
    color = a.fg,
    fontFamily = Fonts.mono,
    fontSize = FONT_SIZE,
    fontWeight = if (a.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (a.italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = if (a.underline) TextDecoration.Underline else null,
)

private val PRED_STYLE = TextStyle(color = PRED_FG, fontFamily = Fonts.mono, fontSize = FONT_SIZE, textDecoration = TextDecoration.Underline)
private val CURSOR_STYLE = TextStyle(color = TERM_BG, fontFamily = Fonts.mono, fontSize = FONT_SIZE)

/** Conservatively "safe to coalesce" into one multi-char `drawText` run: plain
 *  ASCII/Latin text and DEC box-drawing, which JetBrains Mono renders natively at
 *  exactly `cellW` (no fallback-font substitution). Everything else (symbols,
 *  dingbats, CJK, emoji) is drawn per-cell so a mis-widthed fallback glyph can't
 *  smear its neighbors within the same run. */
private fun isMonoSafe(cp: Int): Boolean = cp in 0x20..0x24F || cp in 0x2500..0x257F

/**
 * Build a row's paint plan: coalesce consecutive same-style, mono-safe,
 * single-width cells into one measured run per [isMonoSafe]; isolate wide,
 * non-mono-safe, predicted, and cursor cells as their own single-cell segments.
 * The cursor is drawn as a filled rect (from the caller's rect pass) with the
 * glyph on top in [TERM_BG], replacing the old span-based cursor overlay.
 */
private fun buildRowSegments(row: Row, cursorCol: Int, rowPreds: Map<Int, Int>?, measurer: TextMeasurer): List<RowSegment> {
    val segments = ArrayList<RowSegment>()
    var runStart = -1
    var runAttrs: CellAttrs? = null
    val runText = StringBuilder()

    fun flushRun() {
        if (runStart >= 0 && runText.isNotEmpty()) {
            val allBlank = runAttrs?.bg == null && runText.all { it == ' ' }
            if (!allBlank) {
                val layout = measurer.measure(AnnotatedString(runText.toString()), textStyleFor(runAttrs!!), softWrap = false, maxLines = 1)
                segments.add(RowSegment(runStart, layout, runAttrs!!.bg, runText.length))
            }
        }
        runStart = -1; runAttrs = null; runText.setLength(0)
    }

    var c = 0
    while (c < row.cols) {
        val isCursor = c == cursorCol
        if (!isCursor && row.flags[c] and ATTR_WIDE_TRAIL != 0) { c++; continue }

        val isWideLead = row.flags[c] and ATTR_WIDE_LEAD != 0
        val cellSpan = if (isWideLead) 2 else 1
        val pred = rowPreds?.get(c)
        val cp = pred ?: row.cp[c]
        val glyph = if (cp <= 0) " " else codePointToString(cp)

        if (isCursor) {
            flushRun()
            val layout = measurer.measure(AnnotatedString(glyph), CURSOR_STYLE, softWrap = false, maxLines = 1)
            segments.add(RowSegment(c, layout, CURSOR, cellSpan))
            c += cellSpan
            continue
        }

        if (pred != null) {
            flushRun()
            val layout = measurer.measure(AnnotatedString(glyph), PRED_STYLE, softWrap = false, maxLines = 1)
            segments.add(RowSegment(c, layout, null, cellSpan))
            c += cellSpan
            continue
        }

        val attrs = cellAttrs(row, c)
        if (isMonoSafe(cp) && !isWideLead) {
            if (runStart >= 0 && attrs != runAttrs) flushRun()
            if (runStart < 0) { runStart = c; runAttrs = attrs }
            runText.append(glyph)
        } else {
            flushRun()
            val layout = measurer.measure(AnnotatedString(glyph), textStyleFor(attrs), softWrap = false, maxLines = 1)
            segments.add(RowSegment(c, layout, attrs.bg, cellSpan))
        }
        c += cellSpan
    }
    flushRun()
    return segments
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
