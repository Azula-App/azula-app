import dev.azula.terminal.TermSelection
import dev.azula.terminal.TerminalEmulator
import dev.azula.terminal.allText
import dev.azula.terminal.rowAt
import dev.azula.terminal.screenText
import dev.azula.terminal.textInSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TermSelectionTest {

    // ---- normalized() ----

    @Test
    fun normalizedLeavesAlreadyOrderedSelectionAlone() {
        val sel = TermSelection(1, 2, 3, 4)
        assertEquals(sel, sel.normalized())
    }

    @Test
    fun normalizedSwapsBackwardsSelection() {
        val sel = TermSelection(startAbs = 5, startCol = 2, endAbs = 1, endCol = 0)
        assertEquals(TermSelection(1, 0, 5, 2), sel.normalized())
    }

    @Test
    fun normalizedSwapsSameRowBackwardsColumns() {
        val sel = TermSelection(startAbs = 2, startCol = 9, endAbs = 2, endCol = 3)
        assertEquals(TermSelection(2, 3, 2, 9), sel.normalized())
    }

    // ---- absolute-index stability across scrolling ----

    @Test
    fun absIndexIsStableAsARowMigratesFromGridIntoScrollback() {
        val e = TerminalEmulator(20, 6)
        e.feed("target\r\n")
        // "target" is grid row 0 right after the CRLF (before more output pushes it
        // up); note its abs index while it's still live.
        val abs = e.frame.totalScrolled + 0
        assertEquals("target", e.frame.rowAt(abs)?.text())

        // Push more lines through so "target" scrolls up into the grid, then off
        // the top into scrollback -- its abs index (and content) must be unchanged.
        for (i in 0 until 4) e.feed("filler$i\r\n")
        assertEquals("target", e.frame.rowAt(abs)?.text())

        for (i in 0 until 4) e.feed("more$i\r\n")
        assertEquals("target", e.frame.rowAt(abs)?.text())
    }

    @Test
    fun absIndexAtScrollbackAndGridBoundaryLinesUp() {
        val e = TerminalEmulator(20, 6)
        for (i in 0..9) e.feed("L$i\r\n")
        val frame = e.frame
        // Per TerminalEmulatorTest.scrollbackAccumulatesOnPrimary: scrollback is
        // L0..L4, the live grid starts at L5.
        val firstScrollbackAbs = frame.totalScrolled - frame.scrollback.size
        assertEquals("L0", frame.rowAt(firstScrollbackAbs)?.text())
        assertEquals("L4", frame.rowAt(firstScrollbackAbs + 4)?.text())
        assertEquals("L5", frame.rowAt(frame.totalScrolled)?.text())
        assertEquals("L9", frame.rowAt(frame.totalScrolled + 4)?.text())
    }

    @Test
    fun evictedRowIsNullPastTheScrollbackCap() {
        val e = TerminalEmulator(20, 6)
        e.feed("evictMe\r\n")
        val abs = e.frame.totalScrolled + 0
        // Push far more lines than the (5000-row) scrollback cap so "evictMe" is
        // long gone from the retained window.
        repeat(5200) { e.feed("x\r\n") }
        assertNull(e.frame.rowAt(abs))
        // A recent row is still resolvable.
        assertNotNull(e.frame.rowAt(e.frame.totalScrolled))
    }

    // ---- column clipping with wide chars ----

    @Test
    fun rangedTextSkipsWideTrailWhenRangeStartsMidGlyph() {
        val e = TerminalEmulator(20, 6)
        e.feed("a中 b") // 'a', a CJK wide char (cols 1-2), space, 'b'
        val row = e.line(0)
        // Starting the range at column 2 (the wide-trail spacer of the CJK glyph)
        // must not emit a stray half-glyph -- it's skipped entirely.
        assertEquals(" b", row.text(2, 5))
        assertEquals("a中", row.text(0, 3))
    }

    @Test
    fun textInSelectionHonorsWideLeadsAcrossRows() {
        val e = TerminalEmulator(10, 6)
        e.feed("x中y\r\nz")
        val frame = e.frame
        val startAbs = frame.totalScrolled
        val sel = TermSelection(startAbs, 0, startAbs + 1, 0)
        assertEquals("x中y\nz", frame.textInSelection(sel))
    }

    // ---- single-row selection + screenText/allText ----

    @Test
    fun singleRowSelectionClipsBothEnds() {
        val e = TerminalEmulator(20, 6)
        e.feed("hello world")
        val abs = e.frame.totalScrolled
        val sel = TermSelection(abs, 0, abs, 4)
        assertEquals("hello", e.frame.textInSelection(sel))
    }

    @Test
    fun screenTextIsJustTheLiveGrid() {
        val e = TerminalEmulator(20, 3)
        e.feed("one\r\ntwo\r\nthree")
        assertEquals("one\ntwo\nthree", e.frame.screenText())
    }

    @Test
    fun allTextIncludesScrollbackBeforeTheLiveGrid() {
        val e = TerminalEmulator(20, 3)
        for (i in 0..5) e.feed("L$i\r\n")
        val frame = e.frame
        val expected = (frame.scrollback.map { it.text() } + frame.grid.map { it.text() }).joinToString("\n")
        assertEquals(expected, frame.allText())
    }
}
