import dev.azula.terminal.ATTR_BOLD
import dev.azula.terminal.ATTR_REVERSE
import dev.azula.terminal.ATTR_UNDERLINE
import dev.azula.terminal.ATTR_WIDE_LEAD
import dev.azula.terminal.ATTR_WIDE_TRAIL
import dev.azula.terminal.PredictedCell
import dev.azula.terminal.PredictionEngine
import dev.azula.terminal.TermSeq
import dev.azula.terminal.TerminalEmulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ESC = "\u001b"

class TerminalEmulatorTest {

    // ---- printing / wrap ----

    @Test
    fun printsAndTracksCursor() {
        val e = TerminalEmulator(80, 24)
        e.feed("hello")
        assertEquals("hello", e.rowText(0))
        assertEquals(0 to 5, e.cursor)
    }

    @Test
    fun crlfMovesToNextRow() {
        val e = TerminalEmulator(80, 24)
        e.feed("a\r\nb")
        assertEquals("a", e.rowText(0))
        assertEquals("b", e.rowText(1))
        assertEquals(1 to 1, e.cursor)
    }

    @Test
    fun carriageReturnOverwrites() {
        val e = TerminalEmulator(80, 24)
        e.feed("abc\rX")
        assertEquals("Xbc", e.rowText(0))
        assertEquals(0 to 1, e.cursor)
    }

    @Test
    fun deferredWrapNoSpuriousBlankLine() {
        // Writing exactly `cols` chars must NOT advance to the next line until the
        // next printable arrives (deferred autowrap).
        val e = TerminalEmulator(5, 6)
        e.feed("abcde")
        assertEquals("abcde", e.rowText(0))
        assertEquals(0 to 4, e.cursor)   // cursor parked at last column
        assertEquals("", e.rowText(1))   // no blank line inserted yet
        e.feed("f")
        assertEquals("f", e.rowText(1))
        assertEquals(1 to 1, e.cursor)
    }

    @Test
    fun tabExpandsToNextStop() {
        val e = TerminalEmulator(80, 24)
        e.feed("ab\t!")
        assertEquals("ab      !", e.rowText(0))
        assertEquals(0 to 9, e.cursor)
    }

    // ---- persistent parser: escape split across feeds ----

    @Test
    fun escapeSequenceSplitAcrossFeeds() {
        val e = TerminalEmulator(80, 24)
        e.feed("$ESC[3")        // CSI cut mid-parameter
        e.feed("1mred${ESC}[0m")
        assertEquals("red", e.rowText(0))
        assertEquals(0xFFE06C75, e.line(0).fg[0]) // ANSI 31 red
    }

    // ---- SGR ----

    @Test
    fun sgrBoldUnderlineFgAndReset() {
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[1;4;31mX${ESC}[0mY")
        val row = e.line(0)
        assertTrue(row.flags[0] and ATTR_BOLD != 0)
        assertTrue(row.flags[0] and ATTR_UNDERLINE != 0)
        assertEquals(0xFFE06C75, row.fg[0])
        // after reset:
        assertEquals(0, row.flags[1])
        assertEquals(0L, row.fg[1])
    }

    @Test
    fun sgrBackgroundAndReverse() {
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[42;7mZ")
        val row = e.line(0)
        assertEquals(0xFF98C379, row.bg[0])           // ANSI 42 green bg
        assertTrue(row.flags[0] and ATTR_REVERSE != 0)
    }

    @Test
    fun sgr256AndTruecolor() {
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[38;5;208mA${ESC}[38;2;10;20;30mB")
        assertTrue(e.line(0).fg[0] != 0L)
        assertEquals(0xFF0A141E, e.line(0).fg[1])     // rgb(10,20,30)
    }

    @Test
    fun sgrColonTruecolor() {
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[38:2::10:20:30mB")
        assertEquals(0xFF0A141E, e.line(0).fg[0])
    }

    // ---- cursor addressing + erase ----

    @Test
    fun cursorAddressingCup() {
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}[3;5HX")   // row 3, col 5 (1-based)
        assertEquals("    X", e.rowText(2))
        assertEquals(2 to 5, e.cursor)
    }

    @Test
    fun eraseDisplayClearsAll() {
        val e = TerminalEmulator(20, 6)
        e.feed("line1\r\nline2\r\nline3")
        e.feed("${ESC}[2J")
        assertTrue(e.screenText().all { it.isEmpty() })
    }

    @Test
    fun eraseLineToEol() {
        val e = TerminalEmulator(20, 6)
        e.feed("hello")
        e.feed("${ESC}[3G")   // col 3 (1-based) -> index 2
        e.feed("${ESC}[0K")
        assertEquals("he", e.rowText(0))
    }

    @Test
    fun eraseCharsEch() {
        val e = TerminalEmulator(20, 6)
        e.feed("abcdef")
        e.feed("${ESC}[3G${ESC}[2X")   // col3, erase 2 chars
        assertEquals("ab  ef", e.rowText(0))
    }

    @Test
    fun insertAndDeleteChars() {
        val e = TerminalEmulator(20, 6)
        e.feed("abcdef${ESC}[3G${ESC}[2@")   // insert 2 blanks at col3
        assertEquals("ab  cdef", e.rowText(0))

        val e2 = TerminalEmulator(20, 6)
        e2.feed("abcdef${ESC}[3G${ESC}[2P")  // delete 2 at col3
        assertEquals("abef", e2.rowText(0))
    }

    @Test
    fun insertAndDeleteLines() {
        val e = TerminalEmulator(20, 6)
        e.feed("A\r\nB\r\nC")
        e.feed("${ESC}[2;1H${ESC}[1L")   // cursor row2, insert 1 line
        assertEquals("A", e.rowText(0))
        assertEquals("", e.rowText(1))
        assertEquals("B", e.rowText(2))

        val e2 = TerminalEmulator(20, 6)
        e2.feed("A\r\nB\r\nC")
        e2.feed("${ESC}[2;1H${ESC}[1M")  // cursor row2, delete 1 line
        assertEquals("A", e2.rowText(0))
        assertEquals("C", e2.rowText(1))
    }

    // ---- scroll region ----

    @Test
    fun scrollRegionConfinesScroll() {
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}[2;4r")            // region rows 2..4 (1-based) -> 1..3
        e.feed("${ESC}[2;1HA")
        e.feed("${ESC}[3;1HB")
        e.feed("${ESC}[4;1HC")
        e.feed("${ESC}[4;1H")            // cursor at bottom of region
        e.feed("\n")                     // LF -> scroll region up only
        assertEquals("B", e.rowText(1))
        assertEquals("C", e.rowText(2))
        assertEquals("", e.rowText(3))
        assertTrue(e.scrollbackText().isEmpty())   // region top != screen top -> no scrollback
    }

    // ---- scrollback ----

    @Test
    fun scrollbackAccumulatesOnPrimary() {
        val e = TerminalEmulator(20, 6)
        for (i in 0..9) e.feed("L$i\r\n")
        assertEquals(listOf("L0", "L1", "L2", "L3", "L4"), e.scrollbackText())
        assertEquals("L5", e.rowText(0))
        assertEquals("L9", e.rowText(4))
    }

    @Test
    fun scrollbackCapped() {
        val e = TerminalEmulator(20, 6)
        repeat(5200) { e.feed("x\r\n") }
        assertTrue(e.scrollback.size <= 5000, "scrollback=${e.scrollback.size}")
    }

    // ---- alternate screen ----

    @Test
    fun altScreenPreservesPrimaryAndClearsAlt() {
        val e = TerminalEmulator(20, 6)
        e.feed("primary\r\n")
        e.feed("${ESC}[?1049h")
        assertTrue(e.altScreen)
        assertTrue(e.screenText().all { it.isEmpty() })   // alt starts blank
        e.feed("ALT")
        assertEquals("ALT", e.rowText(0))
        e.feed("${ESC}[?1049l")
        assertFalse(e.altScreen)
        assertEquals("primary", e.rowText(0))             // primary intact
        assertTrue(e.scrollbackText().isEmpty())
    }

    // ---- modes ----

    @Test
    fun modeFlagsTracked() {
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}[?1h"); assertTrue(e.applicationCursorKeys)
        e.feed("${ESC}[?1l"); assertFalse(e.applicationCursorKeys)
        e.feed("${ESC}[?25l"); assertFalse(e.cursorVisible)
        e.feed("${ESC}[?25h"); assertTrue(e.cursorVisible)
        e.feed("${ESC}[?2004h"); assertTrue(e.bracketedPaste)
    }

    @Test
    fun applicationCursorKeySequences() {
        // DECCKM off vs on — arrows switch between CSI and SS3 form.
        assertEquals("$ESC[A", TermSeq.UP)
        assertEquals("${ESC}OA", TermSeq.APP_UP)
        assertEquals("${ESC}OH", TermSeq.APP_HOME)
    }

    // ---- DEC special graphics (box drawing) ----

    @Test
    fun decSpecialGraphicsBoxDrawing() {
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}(0lqk${ESC}(B")
        assertEquals("┌─┐", e.rowText(0))  // ┌─┐
        e.feed("Z")
        assertTrue(e.rowText(0).endsWith("Z"))            // ASCII restored
    }

    // ---- glyph width (wcwidth-ish) ----

    @Test
    fun asciiIsSingleWidth() {
        val e = TerminalEmulator(20, 6)
        e.feed("A")
        assertEquals(0 to 1, e.cursor)
    }

    @Test
    fun cjkIsDoubleWidthAndReservesLeadTrail() {
        val e = TerminalEmulator(20, 6)
        e.feed("中")   // 中
        assertEquals(0 to 2, e.cursor)
        val row = e.line(0)
        assertTrue(row.flags[0] and ATTR_WIDE_LEAD != 0)
        assertTrue(row.flags[1] and ATTR_WIDE_TRAIL != 0)
    }

    @Test
    fun dingbatSpinnerGlyphIsDoubleWidth() {
        val e = TerminalEmulator(20, 6)
        e.feed("✻")   // ✻ — claude's spinner glyph
        assertEquals(0 to 2, e.cursor)
    }

    @Test
    fun geometricBulletIsDoubleWidth() {
        val e = TerminalEmulator(20, 6)
        e.feed("●")   // ● black circle
        assertEquals(0 to 2, e.cursor)
    }

    @Test
    fun boxDrawingStaysSingleWidth() {
        // Regression: the Geometric Shapes wide-range must not bleed into box-drawing
        // (a different block, 0x2500-257F) — those stay 1-cell in JetBrains Mono.
        val e = TerminalEmulator(20, 6)
        e.feed("─")   // ─
        assertEquals(0 to 1, e.cursor)
    }

    @Test
    fun variationSelector16IsZeroWidth() {
        val e = TerminalEmulator(20, 6)
        e.feed("A\uFE0F")   // A + VS16 -- the selector contributes no column
        assertEquals(0 to 1, e.cursor)
    }

    @Test
    fun combiningMarkIsZeroWidth() {
        val e = TerminalEmulator(20, 6)
        e.feed("e\u0301")   // e + combining acute accent (not the precomposed char)
        assertEquals(0 to 1, e.cursor)
    }

    // ---- OSC 11 background-color query ----

    @Test
    fun osc11QuerySTTerminatedRepliesWithBackground() {
        val e = TerminalEmulator(20, 6)
        val out = StringBuilder()
        e.onResponse = { out.append(it) }
        e.feed("${ESC}]11;?${ESC}\\")
        assertEquals("${ESC}]11;rgb:0a0a/0a0a/1010${ESC}\\", out.toString())
    }

    @Test
    fun osc11QueryBelTerminatedAlsoReplies() {
        val e = TerminalEmulator(20, 6)
        val out = StringBuilder()
        e.onResponse = { out.append(it) }
        e.feed("${ESC}]11;?\u0007")
        assertEquals("${ESC}]11;rgb:0a0a/0a0a/1010${ESC}\\", out.toString())
    }

    // ---- device queries ----

    @Test
    fun cursorPositionReport() {
        val e = TerminalEmulator(20, 6)
        val out = StringBuilder()
        e.onResponse = { out.append(it) }
        e.feed("${ESC}[3;5H")
        e.feed("${ESC}[6n")
        assertEquals("$ESC[3;5R", out.toString())
    }

    @Test
    fun primaryDeviceAttributes() {
        val e = TerminalEmulator(20, 6)
        val out = StringBuilder()
        e.onResponse = { out.append(it) }
        e.feed("${ESC}[c")
        assertEquals("$ESC[?62;1;6c", out.toString())
    }

    // ---- OSC ignored ----

    @Test
    fun oscTitleIgnored() {
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}]0;my titlehello")
        assertEquals("hello", e.rowText(0))
    }

    // ---- reset ----

    @Test
    fun risFullReset() {
        val e = TerminalEmulator(20, 6)
        e.feed("stuff\r\nmore")
        e.feed("${ESC}c")
        assertTrue(e.screenText().all { it.isEmpty() })
        assertEquals(0 to 0, e.cursor)
    }

    // ---- resize ----

    // ---- predictive local echo ----

    private fun withPredictor(e: TerminalEmulator, clock: () -> Long = { 0 }): PredictionEngine {
        val p = PredictionEngine(e, now = clock); e.predictor = p; return p
    }

    @Test
    fun predictionShowsThenConfirms() {
        val e = TerminalEmulator(20, 6)
        val p = withPredictor(e)
        e.feed("> ")                       // prompt, cursor at col 2
        p.onInput("l")
        assertEquals(listOf(PredictedCell(0, 2, 'l'.code)), p.overlays())
        assertEquals(3, e.frame.cursorCol) // predicted cursor advanced
        e.feed("l")                        // server echoes the char
        assertTrue(p.overlays().isEmpty()) // confirmed → overlay dropped
    }

    @Test
    fun predictionFlushesOnMismatch() {
        val e = TerminalEmulator(20, 6)
        val p = withPredictor(e)
        e.feed("> "); p.onInput("l")
        e.feed("X")                        // a different glyph lands at the spot
        assertTrue(p.overlays().isEmpty())
    }

    @Test
    fun neverPredictsOnAltScreen() {
        val e = TerminalEmulator(20, 6)
        val p = withPredictor(e)
        e.feed("${ESC}[?1049h")
        p.onInput("l")
        assertTrue(p.overlays().isEmpty())
    }

    @Test
    fun enterFlushesPredictions() {
        val e = TerminalEmulator(20, 6)
        val p = withPredictor(e)
        e.feed("> "); p.onInput("l")
        assertTrue(p.overlays().isNotEmpty())
        p.onInput("\r")
        assertTrue(p.overlays().isEmpty())
    }

    @Test
    fun disruptiveOutputFlushesPredictions() {
        val e = TerminalEmulator(20, 6)
        val p = withPredictor(e)
        e.feed("> "); p.onInput("l")
        e.feed("${ESC}[2J")                // an erase → disruptive
        assertTrue(p.overlays().isEmpty())
    }

    @Test
    fun predictionTimesOut() {
        val e = TerminalEmulator(20, 6)
        var t = 0L
        val p = withPredictor(e) { t }
        e.feed("> "); p.onInput("l")
        assertTrue(p.overlays().isNotEmpty())
        t = 2000
        e.feed("")                         // reconcile pass with no echo → timeout flush
        assertTrue(p.overlays().isEmpty())
    }

    @Test
    fun resizePreservesTopAnchoredContent() {
        val e = TerminalEmulator(20, 6)
        e.feed("keep")
        e.resize(40, 10)
        assertEquals("keep", e.rowText(0))
        assertEquals(10, e.screenText().size)
    }
}
