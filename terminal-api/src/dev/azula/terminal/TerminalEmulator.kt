package dev.azula.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ---- cell attribute flags (packed into Row.flags[col]) ----
const val ATTR_BOLD = 1
const val ATTR_DIM = 1 shl 1
const val ATTR_ITALIC = 1 shl 2
const val ATTR_UNDERLINE = 1 shl 3
const val ATTR_REVERSE = 1 shl 4
const val ATTR_HIDDEN = 1 shl 5
const val ATTR_STRIKE = 1 shl 6
const val ATTR_WIDE_LEAD = 1 shl 7   // first cell of a double-width glyph
const val ATTR_WIDE_TRAIL = 1 shl 8  // spacer cell after a wide glyph (skipped when rendering)

private const val SP = 0x20

/**
 * One row of the terminal grid: parallel arrays of code point + fg/bg color +
 * attribute flags per column. [gen] bumps on every mutation so the renderer can
 * cache the built line and skip untouched rows. Colors are ARGB `Long`s, or `0L`
 * for "use the theme default" (the renderer substitutes fg/bg).
 */
class Row(cols: Int) {
    var cp = IntArray(cols) { SP }
        private set
    var fg = LongArray(cols)
        private set
    var bg = LongArray(cols)
        private set
    var flags = IntArray(cols)
        private set
    var gen = 0
        private set

    val cols get() = cp.size

    fun touch() { gen++ }

    fun setCell(c: Int, cpv: Int, fgv: Long, bgv: Long, fl: Int) {
        cp[c] = cpv; fg[c] = fgv; bg[c] = bgv; flags[c] = fl
    }

    /** Blank cells [from..toIncl] to a space on background [bgv] (BCE), no attrs. */
    fun clearRange(from: Int, toIncl: Int, bgv: Long) {
        var c = from.coerceAtLeast(0)
        val end = toIncl.coerceAtMost(cols - 1)
        while (c <= end) { setCell(c, SP, 0L, bgv, 0); c++ }
        touch()
    }

    fun clearAll(bgv: Long) = clearRange(0, cols - 1, bgv)

    /** Insert [n] blank cells at [at], shifting the tail right (ICH). */
    fun insertChars(at: Int, n: Int, bgv: Long) {
        if (at >= cols) return
        val count = n.coerceAtMost(cols - at)
        var c = cols - 1
        while (c >= at + count) {
            setCell(c, cp[c - count], fg[c - count], bg[c - count], flags[c - count]); c--
        }
        clearRange(at, at + count - 1, bgv)
    }

    /** Delete [n] cells at [at], shifting the tail left (DCH). */
    fun deleteChars(at: Int, n: Int, bgv: Long) {
        if (at >= cols) return
        val count = n.coerceAtMost(cols - at)
        var c = at
        while (c < cols - count) {
            setCell(c, cp[c + count], fg[c + count], bg[c + count], flags[c + count]); c++
        }
        clearRange(cols - count, cols - 1, bgv)
    }

    /** Grow/shrink to [newCols], preserving overlapping cells. */
    fun resizeCols(newCols: Int) {
        if (newCols == cols) return
        val ncp = IntArray(newCols) { SP }; val nfg = LongArray(newCols)
        val nbg = LongArray(newCols); val nfl = IntArray(newCols)
        val keep = minOf(cols, newCols)
        cp.copyInto(ncp, 0, 0, keep); fg.copyInto(nfg, 0, 0, keep)
        bg.copyInto(nbg, 0, 0, keep); flags.copyInto(nfl, 0, 0, keep)
        cp = ncp; fg = nfg; bg = nbg; flags = nfl; touch()
    }

    /** An immutable copy — used when publishing a render snapshot off the feed thread. */
    fun copy(): Row {
        val r = Row(cols)
        cp.copyInto(r.cp); fg.copyInto(r.fg); bg.copyInto(r.bg); flags.copyInto(r.flags)
        r.gen = gen
        return r
    }

    /** Plain text of the row (skips wide-trail spacers), trailing blanks trimmed. */
    fun text(): String = text(0, cols)

    /** Plain text of columns [fromCol, toColExcl) (skips wide-trail spacers, so a
     *  range starting mid-wide-glyph never emits a bare trailing half), trailing
     *  blanks trimmed. Used for selection extraction, where the caller supplies
     *  the exact column bounds (see `TermSelection.textInSelection`). */
    fun text(fromCol: Int, toColExcl: Int): String {
        val sb = StringBuilder()
        val start = fromCol.coerceIn(0, cols)
        val end = toColExcl.coerceIn(0, cols)
        for (c in start until end) {
            if (flags[c] and ATTR_WIDE_TRAIL != 0) continue
            sb.append(codePointToString(if (cp[c] <= 0) SP else cp[c]))
        }
        return sb.toString().trimEnd(' ')
    }
}

/** A speculative local-echo cell: a glyph shown at [row]/[col] before the server
 *  confirms it. Rendered in a distinct (dim/underlined) style until reconciled. */
data class PredictedCell(val row: Int, val col: Int, val cp: Int)

/**
 * The speculative local-echo layer, owned by a [TerminalEmulator]. Its [overlays]
 * are merged into every published [TermFrame]; [onServerOutput] reconciles pending
 * predictions against real output; [reset] drops them on a full clear/resize. The
 * app also calls the concrete engine's `onInput` from the keystroke path.
 */
interface TerminalPredictor {
    /** A keystroke was sent — optimistically echo it (printables/backspace/arrows). */
    fun onInput(bytes: String)
    /** Server output arrived (already applied to the grid) — reconcile predictions. */
    fun onServerOutput(chunk: String)
    /** Current speculative overlays, merged into the published frame. */
    fun overlays(): List<PredictedCell>
    /** The predicted cursor position while predictions are outstanding, else null. */
    fun cursor(): Pair<Int, Int>?
    /** Drop all predictions (full clear / resize). */
    fun reset()
}

/**
 * An immutable render snapshot published by [TerminalEmulator] on every change.
 * The emulator mutates its grid on a background (feed) thread; the UI only ever
 * reads this frame, so the two never touch shared mutable state. [grid] rows are
 * deep copies; [scrollback] rows are safe to share by reference (a row is never
 * mutated once it scrolls off the top). [predictions] are speculative local-echo
 * overlays (empty until the prediction layer fills them).
 */
class TermFrame(
    val cols: Int,
    val rows: Int,
    val scrollback: List<Row>,
    val grid: List<Row>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val altScreen: Boolean,
    val predictions: List<PredictedCell> = emptyList(),
    /** Bumped whenever [TerminalEmulator.feed] applies a server-driven
     *  discontinuity: the cursor row moved, the alt screen toggled, or the
     *  screen was cleared (plain same-row echo does NOT bump it). The mobile
     *  input layer resets its stable typing buffer whenever this changes --
     *  see MobileRawTerminalInput's `LaunchedEffect(frame.discontinuityEpoch)`. */
    val discontinuityEpoch: Int = 0,
    /** Monotonic count of rows ever pushed into scrollback (primary screen
     *  only) -- NOT decremented when the deque evicts old rows past its cap.
     *  Gives every row a stable absolute index that survives scrolling:
     *  `scrollback[i]` is `totalScrolled - scrollback.size + i`, `grid[r]` is
     *  `totalScrolled + r`. See `TermSelection`/`rowAt`. */
    val totalScrolled: Long = 0L,
) {
    companion object {
        fun empty(cols: Int, rows: Int) =
            TermFrame(cols, rows, emptyList(), List(rows) { Row(cols) }, 0, 0, true, false)
    }
}

/** One screen (primary or alternate): the grid, cursor, scroll region, saved cursor. */
private class ScreenBuffer(var cols: Int, var rows: Int) {
    val lines = ArrayList<Row>(rows)
    var cursorRow = 0
    var cursorCol = 0
    var wrapNext = false
    var scrollTop = 0
    var scrollBottom = rows - 1

    // Saved cursor (DECSC/DECRC) — per buffer.
    var savedRow = 0
    var savedCol = 0
    var savedFg = 0L
    var savedBg = 0L
    var savedFlags = 0
    var savedOrigin = false
    var savedG0Graphics = false

    init { repeat(rows) { lines.add(Row(cols)) } }
}

/**
 * A cell-grid VT-100 / xterm terminal emulator over Compose snapshot state. Enough
 * of xterm to render full-screen TUIs (vi, top, htop, less, nano): cursor
 * addressing, the alternate screen, scroll regions, insert/delete, full SGR
 * (fg/bg/256/truecolor + bold/dim/italic/underline/reverse), DEC box-drawing, and
 * scrollback on the primary screen.
 *
 * The parser is a persistent state machine — all parser state lives on the
 * instance, so an escape sequence split across [feed] calls (which happens when a
 * `Frame.Term` chunk cuts mid-sequence) resumes correctly.
 *
 * Only [version] is observable snapshot state; it bumps once per [feed]. The
 * renderer subscribes to [version], then reads the plain grid — no per-cell State.
 */
class TerminalEmulator(cols: Int = 80, rows: Int = 24) {
    /** The immutable render snapshot the UI observes. Republished on every change;
     *  the UI never reads the live grid, so background feeds can't race the render. */
    var frame by mutableStateOf(TermFrame.empty(cols.coerceAtLeast(1), rows.coerceAtLeast(1)))
        private set

    /** Write-back for device queries (DA/DSR): server-directed, never predicted. */
    var onResponse: ((String) -> Unit)? = null

    var cols = cols.coerceAtLeast(1)
        private set
    var rows = rows.coerceAtLeast(1)
        private set

    private val primary = ScreenBuffer(this.cols, this.rows)
    private val alt = ScreenBuffer(this.cols, this.rows)
    private var active = primary

    private val scrollbackDeque = ArrayDeque<Row>()
    /** Monotonic count of rows ever pushed into [scrollbackDeque]; see
     *  [TermFrame.totalScrolled]. Only ever increases; a cap eviction removes
     *  the oldest retained row but doesn't touch this count. */
    private var totalScrolled = 0L

    // ---- modes (read by the renderer + key encoder) ----
    var altScreen = false
        private set
    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var bracketedPaste = false
        private set

    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var shiftOut = false
    private var g0Graphics = false
    private var g1Graphics = false

    // ---- pen (current SGR graphic rendition) ----
    private var curFg = 0L
    private var curBg = 0L
    private var curFlags = 0

    // ---- 1049 alt-screen save slot (distinct from DECSC) ----
    private var mainSavedRow = 0
    private var mainSavedCol = 0
    private var mainSavedFg = 0L
    private var mainSavedBg = 0L
    private var mainSavedFlags = 0

    private var tabStops = defaultTabs(this.cols)

    // ---- parser state (persists across feed calls) ----
    private var state = GROUND
    private val csiParam = StringBuilder()
    private val csiInter = StringBuilder()
    private val escInter = StringBuilder()
    private val oscBuf = StringBuilder()

    // ---- discontinuity tracking (see TermFrame.discontinuityEpoch) ----
    private var discontinuityEpoch = 0
    /** Set by [eraseDisplay] when the WHOLE screen is cleared (modes 2/3);
     *  checked and reset at the top/bottom of each [feed] call. */
    private var screenClearedDuringFeed = false

    // ---- rendering accessors ----
    fun line(r: Int): Row = active.lines[r]
    val scrollback: List<Row> get() = scrollbackDeque
    val cursorRow: Int get() = active.cursorRow
    val cursorCol: Int get() = active.cursorCol

    // ---- test / inspection helpers ----
    fun rowText(r: Int): String = active.lines[r].text()
    fun screenText(): List<String> = active.lines.map { it.text() }
    fun scrollbackText(): List<String> = scrollbackDeque.map { it.text() }
    val cursor: Pair<Int, Int> get() = active.cursorRow to active.cursorCol

    /** Optional speculative local-echo layer (see PredictionEngine); set by the app. */
    var predictor: TerminalPredictor? = null

    /** Re-publish the frame (e.g. after the predictor adds/clears an overlay). */
    fun repaint() = publish()

    // Live read accessors for the prediction layer (same-thread as feed).
    internal val liveAltScreen get() = altScreen
    internal val liveCursorRow get() = active.cursorRow
    internal val liveCursorCol get() = active.cursorCol
    internal fun liveRowLength(r: Int): Int {
        if (r !in 0 until rows) return 0
        val row = active.lines[r]
        var last = -1
        for (c in 0 until row.cols) if (row.cp[c] != SP && row.cp[c] != 0) last = c
        return last + 1
    }
    internal fun liveCellCp(r: Int, c: Int): Int {
        if (r !in 0 until rows || c !in 0 until cols) return 0
        return active.lines[r].cp[c]
    }
    internal val liveCols get() = cols

    /** Rebuild and publish the immutable render snapshot. Called after every mutation. */
    private fun publish() {
        // While predictions are outstanding, draw the cursor at the *predicted*
        // position so it visibly advances as you type ahead of the server.
        val predCursor = predictor?.cursor()
        frame = TermFrame(
            cols = cols,
            rows = rows,
            scrollback = ArrayList(scrollbackDeque),
            grid = active.lines.map { it.copy() },
            cursorRow = predCursor?.first ?: active.cursorRow,
            cursorCol = predCursor?.second ?: active.cursorCol,
            cursorVisible = cursorVisible,
            altScreen = altScreen,
            predictions = predictor?.overlays().orEmpty(),
            discontinuityEpoch = discontinuityEpoch,
            totalScrolled = totalScrolled,
        )
    }

    /** Full reset (RIS) — also used when a terminal stream is (re)wired. */
    fun clear() {
        resetBuffer(primary); resetBuffer(alt)
        scrollbackDeque.clear()
        totalScrolled = 0
        active = primary
        altScreen = false
        curFg = 0L; curBg = 0L; curFlags = 0
        autoWrap = true; originMode = false; insertMode = false
        cursorVisible = true; applicationCursorKeys = false; bracketedPaste = false
        shiftOut = false; g0Graphics = false; g1Graphics = false
        tabStops = defaultTabs(cols)
        state = GROUND
        csiParam.setLength(0); csiInter.setLength(0); escInter.setLength(0); oscBuf.setLength(0)
        predictor?.reset()
        discontinuityEpoch++
        publish()
    }

    private fun resetBuffer(b: ScreenBuffer) {
        b.lines.clear(); repeat(b.rows) { b.lines.add(Row(b.cols)) }
        b.cursorRow = 0; b.cursorCol = 0; b.wrapNext = false
        b.scrollTop = 0; b.scrollBottom = b.rows - 1
    }

    /** Resize the grid to [newCols]×[newRows] (top-anchored, cursor clamped). */
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(1); val nr = newRows.coerceAtLeast(1)
        if (nc == cols && nr == rows) return
        resizeBuffer(primary, nc, nr); resizeBuffer(alt, nc, nr)
        cols = nc; rows = nr
        tabStops = defaultTabs(nc)
        predictor?.reset()
        publish()
    }

    private fun resizeBuffer(b: ScreenBuffer, nc: Int, nr: Int) {
        for (row in b.lines) row.resizeCols(nc)
        while (b.lines.size > nr) b.lines.removeAt(b.lines.size - 1)
        while (b.lines.size < nr) b.lines.add(Row(nc))
        b.cols = nc; b.rows = nr
        b.scrollTop = 0; b.scrollBottom = nr - 1
        b.cursorRow = b.cursorRow.coerceIn(0, nr - 1)
        b.cursorCol = b.cursorCol.coerceIn(0, nc - 1)
        b.wrapNext = false
    }

    /** Feed a chunk of terminal output through the parser. */
    fun feed(text: String) {
        val rowBefore = active.cursorRow
        val altBefore = altScreen
        screenClearedDuringFeed = false
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val code = ch.code
            if (code == 0x1b) {
                // ST (ESC \) terminates OSC/DCS/etc; if we're mid-OSC and the next byte
                // completes it, dispatch before switching into ESCAPE (otherwise the
                // buffered command — e.g. an OSC 11 query — would be lost unprocessed).
                if (state == OSC && i + 1 < n && text[i + 1] == '\\') {
                    dispatchOsc(); state = GROUND; i += 2; continue
                }
                state = ESCAPE; escInter.setLength(0); i++; continue
            }
            when (state) {
                GROUND ->
                    if (code < 0x20) execC0(code)
                    else {
                        var cp = code
                        if (ch.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) {
                            cp = codePointOf(ch, text[i + 1]); i++
                        }
                        printChar(cp)
                    }
                ESCAPE -> escapeByte(ch, code)
                CSI -> csiByte(ch, code)
                OSC -> if (code == 0x07) { dispatchOsc(); state = GROUND } else if (oscBuf.length < 256) oscBuf.append(ch)
                STRING -> if (code == 0x07) state = GROUND // else swallow (DCS/APC/PM/SOS)
            }
            i++
        }
        if (altScreen != altBefore || active.cursorRow != rowBefore || screenClearedDuringFeed) discontinuityEpoch++
        predictor?.onServerOutput(text)
        publish()
    }

    // ---- C0 controls ----
    private fun execC0(code: Int) {
        when (code) {
            0x08 -> { if (active.cursorCol > 0) active.cursorCol--; active.wrapNext = false } // BS
            0x09 -> { active.cursorCol = nextTab(); active.wrapNext = false }                 // HT
            0x0a, 0x0b, 0x0c -> index()                                                       // LF/VT/FF
            0x0d -> { active.cursorCol = 0; active.wrapNext = false }                          // CR
            0x0e -> shiftOut = true                                                            // SO -> G1
            0x0f -> shiftOut = false                                                           // SI -> G0
            // BEL (0x07) and others ignored
        }
    }

    /** Handle a completed OSC command (buffered in [oscBuf], BEL- or ST-terminated).
     *  Only OSC 11 (query background color) gets a reply — needed so claude's
     *  light/dark theme detection doesn't time out; title/cwd (OSC 0/1/7) and
     *  everything else stay swallowed. */
    private fun dispatchOsc() {
        val s = oscBuf.toString()
        if (s == "11;?") {
            onResponse?.invoke("$ESC]11;rgb:${hex4(TERM_BG_R)}/${hex4(TERM_BG_G)}/${hex4(TERM_BG_B)}$ESC\\")
        }
        oscBuf.setLength(0)
    }

    // ---- ESC dispatch ----
    private fun escapeByte(ch: Char, code: Int) {
        when {
            code in 0x20..0x2f -> escInter.append(ch)
            escInter.isEmpty() && ch == '[' -> { state = CSI; csiParam.setLength(0); csiInter.setLength(0) }
            escInter.isEmpty() && ch == ']' -> { state = OSC; oscBuf.setLength(0) }
            escInter.isEmpty() && (ch == 'P' || ch == 'X' || ch == '^' || ch == '_') -> state = STRING
            else -> { escDispatch(escInter.toString(), ch); state = GROUND }
        }
    }

    private fun escDispatch(inter: String, ch: Char) {
        when (inter) {
            "" -> when (ch) {
                'c' -> clear()                       // RIS
                '7' -> saveCursor()                  // DECSC
                '8' -> restoreCursor()               // DECRC
                'D' -> index()                       // IND
                'M' -> reverseIndex()                // RI
                'E' -> { index(); active.cursorCol = 0; active.wrapNext = false } // NEL
                'H' -> tabStops[active.cursorCol] = true // HTS
                // '=', '>' keypad modes — ignored
            }
            "(" -> g0Graphics = (ch == '0')          // designate G0 (0=DEC graphics, B=ASCII)
            ")" -> g1Graphics = (ch == '0')          // designate G1
            "#" -> if (ch == '8') decAlign()         // DECALN
        }
    }

    // ---- CSI dispatch ----
    private fun csiByte(ch: Char, code: Int) {
        when {
            code in 0x30..0x3f -> csiParam.append(ch)
            code in 0x20..0x2f -> csiInter.append(ch)
            code in 0x40..0x7e -> { csiDispatch(csiParam.toString(), csiInter.toString(), ch); state = GROUND }
            code < 0x20 -> execC0(code)
            else -> state = GROUND
        }
    }

    private fun csiDispatch(paramStr: String, inter: String, final: Char) {
        var s = paramStr
        var marker = ' '
        if (s.isNotEmpty() && s[0] in "<=>?") { marker = s[0]; s = s.substring(1) }
        val private = marker == '?'
        val parts = if (s.isEmpty()) emptyList() else s.split(';')
        fun p(idx: Int, def: Int) = parts.getOrNull(idx)?.toIntOrNull() ?: def

        when (final) {
            'A' -> { moveCursor(-p(0, 1), 0) }
            'B' -> { moveCursor(p(0, 1), 0) }
            'C' -> { moveCursor(0, p(0, 1)) }
            'D' -> { moveCursor(0, -p(0, 1)) }
            'E' -> { moveCursor(p(0, 1), 0); active.cursorCol = 0 }
            'F' -> { moveCursor(-p(0, 1), 0); active.cursorCol = 0 }
            'G', '`' -> { active.cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1); active.wrapNext = false }
            'd' -> setCursorRow(p(0, 1) - 1)
            'H', 'f' -> setCursor(p(0, 1) - 1, p(1, 1) - 1)
            'J' -> eraseDisplay(p(0, 0))
            'K' -> eraseLine(p(0, 0))
            'X' -> { val row = active.lines[active.cursorRow]; row.clearRange(active.cursorCol, active.cursorCol + p(0, 1) - 1, curBg) }
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            '@' -> active.lines[active.cursorRow].insertChars(active.cursorCol, p(0, 1), curBg)
            'P' -> active.lines[active.cursorRow].deleteChars(active.cursorCol, p(0, 1), curBg)
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'r' -> setScrollRegion(p(0, 1), p(1, rows))
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> setModes(parts, private, true)
            'l' -> setModes(parts, private, false)
            'm' -> applySgr(if (s.isEmpty()) "0" else s)
            'g' -> tabClear(p(0, 0))
            'c' -> if (marker == '>') onResponse?.invoke("$ESC[>0;0;0c") else onResponse?.invoke("$ESC[?62;1;6c")
            'n' -> when (p(0, 0)) {
                5 -> onResponse?.invoke("$ESC[0n")
                6 -> onResponse?.invoke("$ESC[${active.cursorRow + 1};${active.cursorCol + 1}R")
            }
            'p' -> if (inter == "!") softReset()  // DECSTR
        }
    }

    // ---- cursor movement ----
    private fun moveCursor(dRow: Int, dCol: Int) {
        if (dRow != 0) {
            val lo = if (active.cursorRow >= active.scrollTop) active.scrollTop else 0
            val hi = if (active.cursorRow <= active.scrollBottom) active.scrollBottom else rows - 1
            active.cursorRow = (active.cursorRow + dRow).coerceIn(lo, hi)
        }
        if (dCol != 0) active.cursorCol = (active.cursorCol + dCol).coerceIn(0, cols - 1)
        active.wrapNext = false
    }

    private fun setCursorRow(row0: Int) {
        active.cursorRow =
            if (originMode) (active.scrollTop + row0).coerceIn(active.scrollTop, active.scrollBottom)
            else row0.coerceIn(0, rows - 1)
        active.wrapNext = false
    }

    private fun setCursor(row0: Int, col0: Int) {
        setCursorRow(row0)
        active.cursorCol = col0.coerceIn(0, cols - 1)
        active.wrapNext = false
    }

    private fun index() {
        if (active.cursorRow == active.scrollBottom) scrollUp(1)
        else if (active.cursorRow < rows - 1) active.cursorRow++
    }

    private fun reverseIndex() {
        if (active.cursorRow == active.scrollTop) scrollDown(1)
        else if (active.cursorRow > 0) active.cursorRow--
    }

    // ---- scrolling ----
    private fun blankRow() = Row(cols)

    private fun scrollUp(n: Int) {
        val top = active.scrollTop; val bot = active.scrollBottom
        val count = n.coerceIn(1, bot - top + 1)
        repeat(count) {
            val removed = active.lines.removeAt(top)
            if (active === primary && top == 0) {
                scrollbackDeque.addLast(removed)
                totalScrolled++
                if (scrollbackDeque.size > SCROLLBACK_CAP) scrollbackDeque.removeFirst()
            }
            active.lines.add(bot, blankRow())
        }
    }

    private fun scrollDown(n: Int) {
        val top = active.scrollTop; val bot = active.scrollBottom
        val count = n.coerceIn(1, bot - top + 1)
        repeat(count) { active.lines.removeAt(bot); active.lines.add(top, blankRow()) }
    }

    private fun insertLines(n: Int) {
        if (active.cursorRow < active.scrollTop || active.cursorRow > active.scrollBottom) return
        val bot = active.scrollBottom
        val count = n.coerceIn(1, bot - active.cursorRow + 1)
        repeat(count) { active.lines.removeAt(bot); active.lines.add(active.cursorRow, blankRow()) }
    }

    private fun deleteLines(n: Int) {
        if (active.cursorRow < active.scrollTop || active.cursorRow > active.scrollBottom) return
        val bot = active.scrollBottom
        val count = n.coerceIn(1, bot - active.cursorRow + 1)
        repeat(count) { active.lines.removeAt(active.cursorRow); active.lines.add(bot, blankRow()) }
    }

    private fun setScrollRegion(top1: Int, bot1: Int) {
        val top = (top1 - 1).coerceIn(0, rows - 1)
        val bot = (bot1 - 1).coerceIn(0, rows - 1)
        if (top < bot) { active.scrollTop = top; active.scrollBottom = bot }
        else { active.scrollTop = 0; active.scrollBottom = rows - 1 }
        active.cursorRow = if (originMode) active.scrollTop else 0
        active.cursorCol = 0
        active.wrapNext = false
    }

    // ---- erase ----
    private fun eraseDisplay(mode: Int) {
        val cur = active.cursorRow
        when (mode) {
            0 -> { active.lines[cur].clearRange(active.cursorCol, cols - 1, curBg); for (r in cur + 1 until rows) active.lines[r].clearAll(curBg) }
            1 -> { for (r in 0 until cur) active.lines[r].clearAll(curBg); active.lines[cur].clearRange(0, active.cursorCol, curBg) }
            2 -> { for (r in 0 until rows) active.lines[r].clearAll(curBg); screenClearedDuringFeed = true }
            3 -> { for (r in 0 until rows) active.lines[r].clearAll(curBg); scrollbackDeque.clear(); screenClearedDuringFeed = true }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = active.lines[active.cursorRow]
        when (mode) {
            0 -> row.clearRange(active.cursorCol, cols - 1, curBg)
            1 -> row.clearRange(0, active.cursorCol, curBg)
            2 -> row.clearAll(curBg)
        }
    }

    private fun decAlign() {
        for (r in 0 until rows) { val row = active.lines[r]; for (c in 0 until cols) row.setCell(c, 'E'.code, 0L, 0L, 0); row.touch() }
        active.cursorRow = 0; active.cursorCol = 0
    }

    // ---- modes ----
    private fun setModes(parts: List<String>, private: Boolean, set: Boolean) {
        for (part in parts) {
            val code = part.toIntOrNull() ?: continue
            if (private) when (code) {
                1 -> applicationCursorKeys = set
                6 -> { originMode = set; active.cursorRow = if (set) active.scrollTop else 0; active.cursorCol = 0 }
                7 -> autoWrap = set
                25 -> cursorVisible = set
                47, 1047 -> switchScreen(set, clearOnEnter = true)
                1048 -> if (set) saveCursor() else restoreCursor()
                1049 -> if (set) {
                    mainSavedRow = active.cursorRow; mainSavedCol = active.cursorCol
                    mainSavedFg = curFg; mainSavedBg = curBg; mainSavedFlags = curFlags
                    switchScreen(true, clearOnEnter = true)
                } else {
                    switchScreen(false, clearOnEnter = false)
                    active.cursorRow = mainSavedRow.coerceIn(0, rows - 1)
                    active.cursorCol = mainSavedCol.coerceIn(0, cols - 1)
                    curFg = mainSavedFg; curBg = mainSavedBg; curFlags = mainSavedFlags
                }
                2004 -> bracketedPaste = set
                // 1000/1002/1003/1006/1004/… mouse & focus — parsed and ignored
            } else when (code) {
                4 -> insertMode = set
                // 20 (LNM) etc. ignored
            }
        }
    }

    private fun switchScreen(toAlt: Boolean, clearOnEnter: Boolean) {
        if (toAlt == altScreen) return
        if (toAlt && clearOnEnter) resetBuffer(alt)
        active = if (toAlt) alt else primary
        altScreen = toAlt
    }

    private fun softReset() {
        originMode = false; insertMode = false
        applicationCursorKeys = false; cursorVisible = true
        active.scrollTop = 0; active.scrollBottom = rows - 1
        curFg = 0L; curBg = 0L; curFlags = 0
    }

    // ---- save / restore cursor (DECSC / DECRC) ----
    private fun saveCursor() {
        active.savedRow = active.cursorRow; active.savedCol = active.cursorCol
        active.savedFg = curFg; active.savedBg = curBg; active.savedFlags = curFlags
        active.savedOrigin = originMode; active.savedG0Graphics = g0Graphics
    }

    private fun restoreCursor() {
        active.cursorRow = active.savedRow.coerceIn(0, rows - 1)
        active.cursorCol = active.savedCol.coerceIn(0, cols - 1)
        curFg = active.savedFg; curBg = active.savedBg; curFlags = active.savedFlags
        originMode = active.savedOrigin; g0Graphics = active.savedG0Graphics
        active.wrapNext = false
    }

    // ---- tab stops ----
    private fun nextTab(): Int {
        var c = active.cursorCol + 1
        while (c < cols && !tabStops[c]) c++
        return c.coerceAtMost(cols - 1)
    }

    private fun tabClear(mode: Int) {
        when (mode) {
            0 -> if (active.cursorCol < tabStops.size) tabStops[active.cursorCol] = false
            3 -> tabStops.fill(false)
        }
    }

    // ---- printing ----
    private fun usingGraphics() = if (shiftOut) g1Graphics else g0Graphics

    private fun printChar(cp0: Int) {
        val cp = if (usingGraphics()) decSpecial(cp0) else cp0
        val w = charWidth(cp)
        // Zero-width (combining marks, VS, ZWJ, …): true grapheme clustering onto the
        // previous cell isn't modeled, so just drop it rather than reserve/clobber a column.
        if (w == 0) return
        if (active.wrapNext && autoWrap) { active.cursorCol = 0; index(); active.wrapNext = false }
        if (w == 2 && active.cursorCol == cols - 1) {
            if (autoWrap) { active.lines[active.cursorRow].setCell(active.cursorCol, SP, 0L, curBg, 0); active.cursorCol = 0; index() }
            else active.cursorCol = cols - 2
        }
        val row = active.lines[active.cursorRow]
        if (insertMode) row.insertChars(active.cursorCol, w, curBg)
        row.setCell(active.cursorCol, cp, curFg, curBg, if (w == 2) curFlags or ATTR_WIDE_LEAD else curFlags)
        if (w == 2 && active.cursorCol + 1 < cols) row.setCell(active.cursorCol + 1, 0, curFg, curBg, curFlags or ATTR_WIDE_TRAIL)
        row.touch()
        val newCol = active.cursorCol + w
        if (newCol >= cols) { active.cursorCol = cols - 1; active.wrapNext = true }
        else active.cursorCol = newCol
    }

    // ---- SGR ----
    private fun applySgr(raw: String) {
        val groups = raw.split(';')
        var i = 0
        while (i < groups.size) {
            val g = groups[i]
            if (g.contains(':')) { applyColonColor(g.split(':')); i++; continue }
            when (val code = g.toIntOrNull() ?: 0) {
                0 -> { curFg = 0L; curBg = 0L; curFlags = 0 }
                1 -> curFlags = curFlags or ATTR_BOLD
                2 -> curFlags = curFlags or ATTR_DIM
                3 -> curFlags = curFlags or ATTR_ITALIC
                4 -> curFlags = curFlags or ATTR_UNDERLINE
                5, 6 -> {} // blink — no visual
                7 -> curFlags = curFlags or ATTR_REVERSE
                8 -> curFlags = curFlags or ATTR_HIDDEN
                9 -> curFlags = curFlags or ATTR_STRIKE
                22 -> curFlags = curFlags and (ATTR_BOLD or ATTR_DIM).inv()
                23 -> curFlags = curFlags and ATTR_ITALIC.inv()
                24 -> curFlags = curFlags and ATTR_UNDERLINE.inv()
                27 -> curFlags = curFlags and ATTR_REVERSE.inv()
                28 -> curFlags = curFlags and ATTR_HIDDEN.inv()
                29 -> curFlags = curFlags and ATTR_STRIKE.inv()
                in 30..37 -> curFg = ANSI16[code - 30]
                38 -> i = extendedColor(groups, i, fg = true)
                39 -> curFg = 0L
                in 40..47 -> curBg = ANSI16[code - 40]
                48 -> i = extendedColor(groups, i, fg = false)
                49 -> curBg = 0L
                in 90..97 -> curFg = ANSI16[8 + code - 90]
                in 100..107 -> curBg = ANSI16[8 + code - 100]
            }
            i++
        }
    }

    /** Semicolon-form `38;5;n` / `38;2;r;g;b`. Returns the new index (last consumed). */
    private fun extendedColor(groups: List<String>, i: Int, fg: Boolean): Int {
        val kind = groups.getOrNull(i + 1)?.toIntOrNull()
        return when (kind) {
            5 -> { val n = groups.getOrNull(i + 2)?.toIntOrNull() ?: 0; setColor(fg, xterm256(n)); i + 2 }
            2 -> {
                val r = groups.getOrNull(i + 2)?.toIntOrNull() ?: 0
                val gg = groups.getOrNull(i + 3)?.toIntOrNull() ?: 0
                val b = groups.getOrNull(i + 4)?.toIntOrNull() ?: 0
                setColor(fg, rgb(r, gg, b)); i + 4
            }
            else -> i
        }
    }

    /** Colon-form `38:5:n` / `38:2:[cs]:r:g:b` (a single SGR token). */
    private fun applyColonColor(sub: List<String>) {
        val head = sub.getOrNull(0)?.toIntOrNull() ?: return
        val fg = head == 38
        if (head != 38 && head != 48) return
        when (sub.getOrNull(1)?.toIntOrNull()) {
            5 -> setColor(fg, xterm256(sub.getOrNull(2)?.toIntOrNull() ?: 0))
            2 -> {
                // 38:2:r:g:b  OR  38:2:<colorspace>:r:g:b
                val base = if (sub.size >= 6) 3 else 2
                val r = sub.getOrNull(base)?.toIntOrNull() ?: 0
                val gg = sub.getOrNull(base + 1)?.toIntOrNull() ?: 0
                val b = sub.getOrNull(base + 2)?.toIntOrNull() ?: 0
                setColor(fg, rgb(r, gg, b))
            }
        }
    }

    private fun setColor(fg: Boolean, color: Long) { if (fg) curFg = color else curBg = color }

    private companion object {
        const val GROUND = 0
        const val ESCAPE = 1
        const val CSI = 2
        const val OSC = 3
        const val STRING = 4
        const val SCROLLBACK_CAP = 5000
        val ESC = 0x1b.toChar().toString()

        // Mirrors AzulaColors.bg1 (0xFF0A0A10) from the theme module — duplicated
        // here (not imported) since terminal-api is dependency-free (module.yaml).
        // Used to answer an OSC 11 background-color query.
        const val TERM_BG_R = 0x0A
        const val TERM_BG_G = 0x0A
        const val TERM_BG_B = 0x10

        /** An 8-bit color byte as the 4-hex-digit component an OSC 11 `rgb:` reply uses. */
        fun hex4(v: Int): String { val h = (v and 0xFF).toString(16).padStart(2, '0'); return h + h }

        // One-Dark-ish 16-color palette (black shown as gray so it stays visible).
        val ANSI16 = longArrayOf(
            0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
            0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
            0xFF7F848E, 0xFFFF6E6E, 0xFF7BE06B, 0xFFFFD267,
            0xFF6FB7FF, 0xFFD98BFF, 0xFF66E0E0, 0xFFF3EEF1,
        )

        fun rgb(r: Int, g: Int, b: Int): Long =
            0xFF000000L or ((r.toLong() and 0xFF) shl 16) or ((g.toLong() and 0xFF) shl 8) or (b.toLong() and 0xFF)

        fun cubeLevel(i: Int) = if (i == 0) 0 else 55 + i * 40

        fun xterm256(n: Int): Long = when (n) {
            in 0..15 -> ANSI16[n]
            in 16..231 -> { val v = n - 16; rgb(cubeLevel((v / 36) % 6), cubeLevel((v / 6) % 6), cubeLevel(v % 6)) }
            in 232..255 -> { val g = 8 + (n - 232) * 10; rgb(g, g, g) }
            else -> ANSI16[7]
        }

        fun defaultTabs(cols: Int) = BooleanArray(cols) { it % 8 == 0 && it != 0 }

        fun codePointOf(hi: Char, lo: Char): Int = 0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)

        /** DEC Special Graphics (box-drawing) — active when G0/G1 is designated `0`. */
        fun decSpecial(cp: Int): Int = when (cp.toChar()) {
            'j' -> 0x2518 // ┘
            'k' -> 0x2510 // ┐
            'l' -> 0x250C // ┌
            'm' -> 0x2514 // └
            'n' -> 0x253C // ┼
            'q' -> 0x2500 // ─
            't' -> 0x251C // ├
            'u' -> 0x2524 // ┤
            'v' -> 0x2534 // ┴
            'w' -> 0x252C // ┬
            'x' -> 0x2502 // │
            '`' -> 0x25C6 // ◆
            'a' -> 0x2592 // ▒
            'f' -> 0x00B0 // °
            'g' -> 0x00B1 // ±
            'o' -> 0x23BA
            'p' -> 0x23BB
            'r' -> 0x23BC
            's' -> 0x23BD
            '~' -> 0x00B7 // ·
            '0' -> 0x2588 // █
            '_' -> 0x0020
            else -> cp
        }

        /**
         * wcwidth-ish column width: 0 for combining/zero-width marks, 2 for
         * East-Asian-wide/fullwidth/emoji-ish symbols, else 1. Kept as a compact,
         * commented table (not a full Unicode wcwidth dump) targeting the blocks
         * real TUIs (claude included) actually emit.
         *
         * Geometric Shapes (0x25A0-25FF) and Misc Symbols & Dingbats (0x2600-27BF)
         * are deliberately NOT wide, despite a prior attempt (see git history) to
         * mark them 2-column to fix a visual glyph-rendering mismatch: real
         * terminals (verified against tmux, which uses the system's wcwidth)
         * advance the cursor only ONE column for claude's spinner/status/prompt
         * glyphs in these blocks (✻✢✳✓➜●○■▲ and critically `❯`, claude's prompt
         * chevron). Reserving a second column desyncs the emulator's column
         * accounting from what claude assumes, so any line claude pads to the
         * full terminal width (e.g. a re-echoed `❯ <cmd>` prompt row after a
         * DECSTBM scroll) fills one column short in real terminals but exactly
         * hits the last column here — firing deferred autowrap a character
         * early, inserting a phantom row, and cascading into every subsequent
         * absolute-cursor-addressed line in that redraw. That phantom-row drift
         * is what made arrow-key-triggered redraws (history recall, menus —
         * anything that reprints a padded `❯ …` line inside a scroll region)
         * "render in unexpected places." The *visual* glyph-width mismatch this
         * was originally trying to fix belongs in the renderer, which already
         * handles it (see terminal.md's "Glyph width" section on coalesced runs
         * pre-measured and painted at `col * cellW`) — not here.
         */
        fun charWidth(cp: Int): Int {
            if (isZeroWidth(cp)) return 0
            if (cp < 0x1100) return 1
            val wide = cp in 0x1100..0x115F ||   // Hangul Jamo
                cp in 0x2E80..0x303E ||          // CJK radicals, Kangxi
                cp in 0x3041..0x33FF ||          // Hiragana … CJK symbols
                cp in 0x3400..0x4DBF ||          // CJK Ext A
                cp in 0x4E00..0x9FFF ||          // CJK Unified
                cp in 0xA000..0xA4CF ||          // Yi
                cp in 0xAC00..0xD7A3 ||          // Hangul syllables
                cp in 0xF900..0xFAFF ||          // CJK compatibility
                cp in 0xFE30..0xFE4F ||          // CJK compat forms
                cp in 0xFF00..0xFF60 ||          // Fullwidth forms
                cp in 0xFFE0..0xFFE6 ||
                cp in 0x1F1E6..0x1F1FF ||        // regional-indicator flag letters
                cp in 0x1F300..0x1FAFF ||        // emoji / symbols
                cp in 0x20000..0x3FFFD           // CJK Ext B+
            return if (wide) 2 else 1
        }

        /** Combining marks, variation selectors (incl. VS16 0xFE0F), ZWJ and other
         *  default-ignorable/zero-width code points — occupy no column. */
        fun isZeroWidth(cp: Int): Boolean = cp in 0x0300..0x036F ||   // combining diacritical marks
            cp in 0x1AB0..0x1AFF ||          // combining diacritical marks extended
            cp in 0x1DC0..0x1DFF ||          // combining diacritical marks supplement
            cp in 0x20D0..0x20FF ||          // combining diacritical marks for symbols
            cp in 0xFE00..0xFE0F ||          // variation selectors 1-16 (incl. VS16, emoji-presentation)
            cp in 0xFE20..0xFE2F ||          // combining half marks
            cp in 0xE0100..0xE01EF ||        // variation selectors supplement
            cp == 0x200B ||                  // zero-width space
            cp == 0x200D ||                  // zero-width joiner
            cp == 0xFEFF                     // zero-width no-break space / BOM
    }
}
