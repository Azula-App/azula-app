package dev.azula.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import dev.azula.terminal.TermSelection
import dev.azula.terminal.TermSeq
import dev.azula.terminal.TerminalSession
import dev.azula.terminal.codePointToString
import dev.azula.terminal.rowAt
import dev.azula.terminal.textInSelection
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val cellW = remember(Fonts.mono) {
        measurer.measure("MMMMMMMMMM", TextStyle(fontFamily = Fonts.mono, fontSize = FONT_SIZE)).size.width / 10
    }
    val padPx = with(density) { PAD.toPx() }

    // A blinking cursor while live; hidden when the app hid it (DECTCEM ?25l).
    val cursorOn = if (live && frame.cursorVisible) rememberCursorBlink() else false

    // The Box's own pixel height, independent of the emulator's row count: an IME
    // show/hide always changes this, but `TerminalEmulator.resize` is a no-op (no
    // new `frame`) when the resulting row count happens not to change. PrimaryScreen
    // keys its auto-scroll on this too so a keyboard cycle always re-evaluates the
    // pin, not just when the grid itself changes shape.
    var viewportHeightPx by remember(convId) { mutableStateOf(0) }

    val sizeMod = if (live) {
        Modifier.onSizeChanged { sz ->
            viewportHeightPx = sz.height
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

    // The LazyColumn's own scroll state, hoisted up here (instead of created
    // inside PrimaryScreen) so the selection gesture's hit-testing -- which
    // needs firstVisibleItemIndex/ScrollOffset to translate a tap's y into an
    // absolute row -- can share it. Harmless to create even on the alt screen,
    // where it just isn't attached to any LazyColumn.
    val listState = rememberLazyListState()

    // ---- selection (Feature 3: region selection + copy) ----
    var selection by remember(convId) { mutableStateOf<TermSelection?>(null) }
    var justCopied by remember(convId) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Invalidate the selection when the coordinate space it anchors to shifts
    // from underneath it: an alt-screen toggle repurposes the same abs range for
    // unrelated content; `totalScrolled` going backwards only happens on a full
    // clear() (otherwise monotonic), which also resets the retained window; and
    // an endpoint scrolled off past the scrollback cap is no longer resolvable.
    // Conv switch is handled for free by the remember(convId) above.
    var lastAltForSel by remember(convId) { mutableStateOf(frame.altScreen) }
    var lastTotalScrolledForSel by remember(convId) { mutableStateOf(frame.totalScrolled) }
    LaunchedEffect(frame) {
        val sel = selection
        if (sel != null) {
            val altToggled = frame.altScreen != lastAltForSel
            val cleared = frame.totalScrolled < lastTotalScrolledForSel
            val evicted = frame.rowAt(sel.startAbs) == null || frame.rowAt(sel.endAbs) == null
            if (altToggled || cleared || evicted) selection = null
        }
        lastAltForSel = frame.altScreen
        lastTotalScrolledForSel = frame.totalScrolled
    }

    // Tap-to-focus and long-press-to-select share one pointerInput (keyed only on
    // convId, like the tap-to-focus gesture already was): a plain tap either
    // clears an active selection (and, in that case, skips the focus-reclaim
    // bump -- the tap was "for" dismissing the selection, not refocusing) or
    // bumps reclaimFocusSignal as before; a long-press hit-tests the press
    // position, selects the word under it, and gives haptic feedback. `emu.frame`
    // (not the outer `frame` val) is read here deliberately: this gesture
    // detector coroutine outlives any single recomposition (it's only torn down
    // when convId changes), so closing over the plain `frame` local would pin it
    // to a stale snapshot from whenever the coroutine last (re)started -- reading
    // the live State property instead always sees the current frame.
    val tapMod = if (live) {
        Modifier.pointerInput(convId) {
            detectTapGestures(
                onTap = {
                    if (selection != null) selection = null else reclaimFocusSignal++
                },
                onLongPress = { pos ->
                    val hit = hitTest(pos, emu.frame, listState, rowHeightPx, cellW, padPx)
                    val row = emu.frame.rowAt(hit.abs)
                    if (row != null) {
                        val (s, e) = wordBoundsAt(row, hit.col)
                        selection = TermSelection(hit.abs, s, hit.abs, e)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
            )
        }
    } else Modifier

    // Extends the live end of the selection while dragging after the long-press
    // that started it (a separate pointerInput so it doesn't compete with -- or
    // get eaten by -- the LazyColumn's own drag-to-scroll).
    val dragSelectMod = if (live) {
        Modifier.pointerInput(convId) {
            detectDragGesturesAfterLongPress(
                onDrag = { change, _ ->
                    change.consume()
                    if (selection != null) {
                        val hit = hitTest(change.position, emu.frame, listState, rowHeightPx, cellW, padPx)
                        selection = selection?.copy(endAbs = hit.abs, endCol = hit.col)
                    }
                },
            )
        }
    } else Modifier

    Box(Modifier.fillMaxSize().background(AzulaColors.bg1).then(sizeMod).then(tapMod).then(dragSelectMod)) {
        // Invisible keystroke-capture surface behind the text; a tap focuses it.
        if (live) RawTerminalInput(session, convId, Modifier.matchParentSize(), reclaimFocusSignal, smartInput)

        if (frame.altScreen) {
            AltScreen(session, convId, frame, cursorOn, rowHeight, cellW, measurer, selection)
        } else {
            PrimaryScreen(frame, cursorOn, rowHeight, cellW, measurer, listState, selection, convId, viewportHeightPx)
        }

        if (selection != null) {
            SelectionActionChip(
                copied = justCopied,
                onCopy = {
                    val text = emu.frame.textInSelection(selection!!)
                    clipboard.setText(AnnotatedString(text))
                    justCopied = true
                    scope.launch {
                        delay(1400)
                        justCopied = false
                        selection = null
                    }
                },
                onCancel = { selection = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

/** Rate cap on scrollback-navigation arrows sent per gesture (drag or wheel
 *  event) — a runaway fling shouldn't queue an unbounded burst of keystrokes.
 *  Referenced from the jvm actual of [altScreenWheelScroll] too, hence internal. */
internal const val ALT_SCROLL_RATE_CAP = 60

/** Desktop mouse-wheel: lines sent to the PTY per notch (see [altScreenWheelScroll]). */
internal const val WHEEL_LINES_PER_NOTCH = 3

/** Full-screen TUI (vi/top/…): a fixed grid, no scrollback, no auto-scroll.
 *  Swipe (mobile) / wheel (desktop) scrolling is translated into arrow-key
 *  presses sent to the PTY -- there's no local scrollback to page through on
 *  the alt screen, so the app itself (less/vi/claude/…) has to move. */
@Composable
private fun AltScreen(
    session: TerminalSession,
    convId: String,
    frame: TermFrame,
    cursorOn: Boolean,
    rowHeight: Dp,
    cellW: Int,
    measurer: TextMeasurer,
    selection: TermSelection?,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    // Accumulated sub-row drag distance carried between onVerticalDrag calls;
    // sentThisGesture caps how many arrows one continuous drag can fire.
    var dragAccumPx by remember(convId) { mutableStateOf(0f) }
    var sentThisGesture by remember(convId) { mutableStateOf(0) }
    // Read inside the long-lived gesture coroutines below via rememberUpdatedState:
    // pointerInput(convId) only restarts on a convId change, so closing directly
    // over the `selection` parameter would pin it to a stale value from whenever
    // that coroutine last (re)started.
    val currentSelection = rememberUpdatedState(selection)

    // A selection is active -- this drag is extending it (LiveTerminalView's
    // dragSelectMod on the ancestor Box), not paging the alt screen, so both
    // recognizers below bail without sending scroll-arrows. This is the
    // deterministic fix for a gesture race that would otherwise exist: a
    // descendant's pointerInput (this Column's, below) sees Main-pass pointer
    // events before its ancestors, so an unguarded drag here could win the race
    // against the selection-extend recognizer on the outer Box and get read as
    // scroll-arrows instead. Gating on "no active selection" sidesteps needing
    // to reason about which recognizer wins that race at all -- arrows are never
    // wanted mid-selection regardless.
    Column(
        Modifier.fillMaxSize().padding(PAD)
            .pointerInput(convId) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumPx = 0f; sentThisGesture = 0 },
                    onDragEnd = { dragAccumPx = 0f },
                    onDragCancel = { dragAccumPx = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        if (currentSelection.value == null) {
                            change.consume()
                            dragAccumPx += dragAmount
                            // Finger moving UP (dragAmount < 0) reveals content ABOVE, like
                            // pressing Up in `less` -- flip this mapping if device testing
                            // disagrees, it's the one constant that encodes the choice.
                            while (dragAccumPx <= -rowHeightPx && sentThisGesture < ALT_SCROLL_RATE_CAP) {
                                sendScrollArrow(session, convId, up = true)
                                dragAccumPx += rowHeightPx; sentThisGesture++
                            }
                            while (dragAccumPx >= rowHeightPx && sentThisGesture < ALT_SCROLL_RATE_CAP) {
                                sendScrollArrow(session, convId, up = false)
                                dragAccumPx -= rowHeightPx; sentThisGesture++
                            }
                        }
                        // else: leave the change unconsumed so the outer Box's
                        // selection-extend recognizer can use it instead.
                    },
                )
            }
            .altScreenWheelScroll { lines ->
                if (currentSelection.value == null) {
                    repeat(kotlin.math.abs(lines)) { sendScrollArrow(session, convId, up = lines > 0) }
                }
            },
    ) {
        val preds = predictionsByRow(frame)
        frame.grid.forEachIndexed { r, row ->
            val abs = frame.totalScrolled + r
            GridRow(row, r, frame, cursorOn, preds, rowHeight, cellW, measurer, selectionSpanForRow(selection, abs, frame.cols))
        }
    }
}

/** Interactive shell: scrollback + the live grid in a lazily-rendered scroller. */
@Composable
private fun PrimaryScreen(
    frame: TermFrame,
    cursorOn: Boolean,
    rowHeight: Dp,
    cellW: Int,
    measurer: TextMeasurer,
    listState: LazyListState,
    selection: TermSelection?,
    convId: String,
    viewportHeightPx: Int,
) {
    val total = frame.scrollback.size + frame.grid.size

    // Explicit pinned-to-tail intent, tracked independently of `atBottom`'s
    // per-frame geometry check. Plain re-derivation (the old `derivedStateOf`
    // here) broke after an IME show/hide: the resize shrinks/grows the viewport
    // out from under the list, `atBottom` reads false for a beat even though the
    // user never touched the scroller, and once false it never recovered --
    // nothing ever flips it back to true. `pinned` instead only changes on
    // genuine user scroll intent: false when a user drag ends away from the
    // tail, true when the user (drag or programmatic) reaches the tail, or when
    // they tap the jump-to-bottom chip.
    var pinned by remember(convId) { mutableStateOf(true) }
    val totalForBottomCheck = rememberUpdatedState(total)
    LaunchedEffect(convId, listState) {
        snapshotFlow { listState.isScrollInProgress to atBottom(listState, totalForBottomCheck.value) }
            .collect { (inProgress, bottom) ->
                if (bottom) pinned = true else if (inProgress) pinned = false
            }
    }

    // Stick to the bottom when new output arrives, or when the viewport itself
    // resizes (IME show/hide), *if* we're pinned -- never while the user is
    // actively scrolling/dragging (that fight is the main source of jitter).
    // Keying on `viewportHeightPx` (not just `frame`) is what re-engages the pin
    // after a keyboard cycle whose resulting row count happens not to change --
    // `TerminalEmulator.resize` is then a no-op and never republishes `frame`,
    // but the Box's pixel height still moved and the list still needs re-pinning.
    LaunchedEffect(frame, viewportHeightPx) {
        if (pinned && !listState.isScrollInProgress) listState.scrollToItem(total - 1)
    }
    val scope = rememberCoroutineScope()

    val preds = predictionsByRow(frame)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(PAD)) {
            items(frame.scrollback.size) { i ->
                val abs = frame.totalScrolled - frame.scrollback.size + i
                GridRow(frame.scrollback[i], -1, frame, cursorOn, preds, rowHeight, cellW, measurer, selectionSpanForRow(selection, abs, frame.cols))
            }
            items(frame.grid.size) { r ->
                val abs = frame.totalScrolled + r
                GridRow(frame.grid[r], r, frame, cursorOn, preds, rowHeight, cellW, measurer, selectionSpanForRow(selection, abs, frame.cols))
            }
        }
        if (!pinned) {
            ScrollToBottomChip(
                onTap = { pinned = true; scope.launch { listState.scrollToItem(total - 1) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(PAD),
            )
        }
    }
}

/** Whether [listState] is scrolled to (or past) the last item, i.e. pinned to the
 *  tail -- also true before the first layout pass (no visible items yet), so the
 *  initial open-to-bottom behavior is preserved. */
private fun atBottom(listState: LazyListState, total: Int): Boolean {
    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return total > 0 && last >= total - 1
}

/** Send one scrollback-navigation arrow key for [AltScreen]'s swipe/wheel
 *  handling -- honors application-cursor-keys mode, same as [TerminalKeysBar]. */
private fun sendScrollArrow(session: TerminalSession, convId: String, up: Boolean) {
    val appKeys = session.terminalScreen(convId).applicationCursorKeys
    val seq = if (up) {
        if (appKeys) TermSeq.APP_UP else TermSeq.UP
    } else {
        if (appKeys) TermSeq.APP_DOWN else TermSeq.DOWN
    }
    session.terminalRaw(convId, seq)
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
    selSpan: IntRange? = null,
) {
    val cursorHere = cursorOn && gridIndex == frame.cursorRow
    val rowPreds = if (gridIndex >= 0) preds[gridIndex] else null
    // cellW is a remember() key (not just a draw-time offset) because the glyphs are
    // pre-measured with Fonts.mono here: it's read inside this plain (non-@Composable)
    // lambda, so a later font swap (system-fallback -> bundled JetBrains Mono, see
    // LoadFonts) wouldn't otherwise invalidate already-built segments. cellW itself is
    // remember(Fonts.mono)-keyed in LiveTerminalView, so it changes exactly when the
    // font does, piggy-backing that invalidation here. [selSpan] is deliberately NOT
    // in this key -- it's read straight from the draw scope below, alongside the
    // segments, so dragging a selection never forces a re-measure of the row text.
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
        // Selection highlight -- drawn AFTER the glyphs so it reads as a translucent
        // wash over the text, not a solid block hiding it.
        if (selSpan != null) {
            val x = selSpan.first * cellW.toFloat()
            val span = (selSpan.last - selSpan.first + 1).coerceAtLeast(0)
            drawRect(SELECTION_HIGHLIGHT, topLeft = Offset(x, 0f), size = Size(span * cellW.toFloat(), rowHeightPx))
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
private val SELECTION_HIGHLIGHT = AzulaColors.pink.copy(alpha = 0.25f)

/** An absolute row index (see [TermFrame.totalScrolled]) + column, the result of
 *  translating a tap/drag position into terminal-grid coordinates. */
private data class AbsCell(val abs: Long, val col: Int)

/**
 * Translate a pointer position (in this composable's own px coordinate space)
 * into an absolute row + column, for the selection long-press/drag gestures.
 *
 * On the alt screen the grid is a fixed [Column] with no scroll offset, so the
 * row is just `y / rowHeightPx`. On the primary screen the same y has to be
 * resolved against the LazyColumn's current scroll position: `itemIndex =
 * firstVisibleItemIndex + (y − PAD + firstVisibleItemScrollOffset) / rowHeightPx`,
 * and since `scrollback[i]` is abs `totalScrolled − scrollback.size + i` while
 * `grid[r]` is abs `totalScrolled + r` (see [TermFrame.totalScrolled]), the same
 * `totalScrolled − scrollback.size + itemIndex` expression resolves either kind
 * of item -- the scrollback/grid split "falls out" of the arithmetic.
 */
private fun hitTest(
    pos: Offset,
    frame: TermFrame,
    listState: LazyListState,
    rowHeightPx: Float,
    cellW: Int,
    padPx: Float,
): AbsCell {
    val col = if (cellW > 0) ((pos.x - padPx) / cellW).toInt().coerceIn(0, (frame.cols - 1).coerceAtLeast(0)) else 0
    val abs = if (frame.altScreen) {
        val row = if (rowHeightPx > 0f) (pos.y / rowHeightPx).toInt() else 0
        frame.totalScrolled + row.coerceIn(0, (frame.grid.size - 1).coerceAtLeast(0))
    } else {
        val total = frame.scrollback.size + frame.grid.size
        val itemIndex = if (rowHeightPx > 0f) {
            listState.firstVisibleItemIndex + ((pos.y - padPx + listState.firstVisibleItemScrollOffset) / rowHeightPx).toInt()
        } else {
            listState.firstVisibleItemIndex
        }
        frame.totalScrolled - frame.scrollback.size + itemIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
    }
    return AbsCell(abs, col)
}

/** The inclusive [start, end] column span of the "word" touching [col] in
 *  [row]: scans outward to the nearest blank cell on each side (or the row
 *  edge). A press landing on a wide glyph's trailing spacer is treated as its
 *  lead cell, so a wide character is never split across the selection edge. */
private fun wordBoundsAt(row: Row, col: Int): Pair<Int, Int> {
    val clamped = col.coerceIn(0, row.cols - 1)
    val start0 = if (row.flags[clamped] and ATTR_WIDE_TRAIL != 0) (clamped - 1).coerceAtLeast(0) else clamped
    fun isBlank(c: Int) = row.cp[c] <= 0 || row.cp[c] == 0x20
    if (isBlank(start0)) return start0 to start0
    var s = start0
    while (s > 0 && !isBlank(s - 1)) s--
    var e = start0
    while (e < row.cols - 1 && !isBlank(e + 1)) e++
    return s to e
}

/** The inclusive column span of [sel] (normalized) that falls on the row at
 *  absolute index [abs], or null if [abs] isn't part of the selection --
 *  interior rows highlight in full, the first/last rows clip to the exact
 *  columns, mirroring [dev.azula.terminal.textInSelection]'s own clipping. */
private fun selectionSpanForRow(sel: TermSelection?, abs: Long, cols: Int): IntRange? {
    if (sel == null) return null
    val n = sel.normalized()
    if (abs < n.startAbs || abs > n.endAbs) return null
    return when {
        n.startAbs == n.endAbs -> n.startCol..n.endCol
        abs == n.startAbs -> n.startCol..(cols - 1)
        abs == n.endAbs -> 0..n.endCol
        else -> 0..(cols - 1)
    }
}

/** The floating "Copy · Cancel" chip shown while a selection is active (see
 *  [LiveTerminalView]). Styled to match [TerminalKeysBar]'s accessory keys. */
@Composable
private fun SelectionActionChip(copied: Boolean, onCopy: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AzulaColors.bg2)
            .border(1.dp, AzulaColors.border4, RoundedCornerShape(10.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipLabel(if (copied) "✓ copied" else "Copy", active = copied, onTap = onCopy)
        Box(Modifier.width(1.dp).height(18.dp).background(AzulaColors.border4))
        ChipLabel("Cancel", active = false, onTap = onCancel)
    }
}

/** A small "jump to the tail" affordance shown on [PrimaryScreen] when the view
 *  has scrolled away from the bottom (see [atBottom]). */
@Composable
private fun ScrollToBottomChip(onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AzulaColors.bg2)
            .border(1.dp, AzulaColors.border4, RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("↓", color = AzulaColors.pinkLight, fontFamily = Fonts.mono, fontSize = 14.sp)
    }
}

@Composable
private fun ChipLabel(label: String, active: Boolean, onTap: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) AzulaColors.onlineBright else AzulaColors.pinkLight, fontFamily = Fonts.mono, fontSize = 12.sp)
    }
}

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
