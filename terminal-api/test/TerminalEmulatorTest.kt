import dev.azula.terminal.ATTR_BOLD
import dev.azula.terminal.ATTR_DIM
import dev.azula.terminal.ATTR_ITALIC
import dev.azula.terminal.ATTR_REVERSE
import dev.azula.terminal.ATTR_UNDERLINE
import dev.azula.terminal.ATTR_WIDE_LEAD
import dev.azula.terminal.ATTR_WIDE_TRAIL
import dev.azula.terminal.MouseButton
import dev.azula.terminal.MouseCell
import dev.azula.terminal.MouseTrackingMode
import dev.azula.terminal.PredictedCell
import dev.azula.terminal.PredictionEngine
import dev.azula.terminal.TermSeq
import dev.azula.terminal.TerminalEmulator
import dev.azula.terminal.encodeMouseReport
import dev.azula.terminal.mouseCellAt
import dev.azula.terminal.reportsDragMotion
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

    // DEL is a no-op on output (ECMA-48 / xterm) -- never a glyph. It used to fail the
    // `code < 0x20` control check and get *printed*, so a shell echoing the client's
    // Backspace byte (TermSeq.BACKSPACE is DEL) made Backspace look like it typed a
    // character instead of deleting one.
    @Test
    fun delOnOutputIsIgnoredNotPrinted() {
        val e = TerminalEmulator(80, 24)
        e.feed("a\u007fb")
        assertEquals("ab", e.rowText(0))
        assertEquals(0 to 2, e.cursor)
    }

    // The erase a real shell's readline echoes for a Backspace: back up, overwrite the
    // glyph with a space, back up again.
    @Test
    fun backspaceSpaceBackspaceErasesTheLastGlyph() {
        val e = TerminalEmulator(80, 24)
        e.feed("abc")
        e.feed("\b \b")
        assertEquals("ab", e.rowText(0))
        assertEquals(0 to 2, e.cursor)
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

    // ---- discontinuityEpoch ----

    @Test
    fun discontinuityEpochUnchangedOnSameRowEcho() {
        val e = TerminalEmulator(80, 24)
        val epoch0 = e.frame.discontinuityEpoch
        e.feed("hello")
        assertEquals(epoch0, e.frame.discontinuityEpoch)
    }

    @Test
    fun discontinuityEpochBumpsWhenCursorRowChanges() {
        val e = TerminalEmulator(80, 24)
        val epoch0 = e.frame.discontinuityEpoch
        e.feed("a\r\nb")
        assertTrue(e.frame.discontinuityEpoch != epoch0)
    }

    @Test
    fun discontinuityEpochBumpsOnAltToggle() {
        val e = TerminalEmulator(80, 24)
        val epoch0 = e.frame.discontinuityEpoch
        e.feed("$ESC[?1049h")
        assertTrue(e.frame.discontinuityEpoch != epoch0)
    }

    @Test
    fun discontinuityEpochBumpsOnFullScreenClear() {
        val e = TerminalEmulator(80, 24)
        e.feed("hello")
        val epoch0 = e.frame.discontinuityEpoch
        e.feed("$ESC[2J")
        assertTrue(e.frame.discontinuityEpoch != epoch0)
    }

    @Test
    fun discontinuityEpochUnchangedOnPartialErase() {
        val e = TerminalEmulator(80, 24)
        e.feed("hello")
        val epoch0 = e.frame.discontinuityEpoch
        e.feed("$ESC[K") // erase-to-end-of-line — not a full clear, same row
        assertEquals(epoch0, e.frame.discontinuityEpoch)
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

    // ---- SGR underline-style colon form (`4:x`) ----
    // Modern apps (claude included) send curly/single underline as a colon
    // sub-parameter of `4` (`4:3`, `4:1`, …) and clear it the same way (`4:0`),
    // alongside the classic bare `24`. `applySgr` used to route ANY colon-form
    // group to the extended-color parser (`applyColonColor`, which only
    // recognizes head 38/48) and silently no-op otherwise -- so `4:3` never set
    // underline, and worse, `4:0` never cleared it, leaving underline stuck on
    // from a prior plain `ESC[4m` until a hard reset.

    @Test
    fun xtmodkeysCsiPrivateMIsNotAppliedAsSgr() {
        // `CSI > 4 ; 2 m` is XTMODKEYS (modifyOtherKeys), NOT SGR. claude emits it
        // alongside its keyboard-protocol setup. It must not set underline(4)/dim(2).
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[>4;2mX")
        val row = e.line(0)
        assertEquals(0, row.flags[0] and ATTR_UNDERLINE)
        assertEquals(0, row.flags[0] and ATTR_DIM)
    }

    @Test
    fun sgrColonUnderlineStyleSetsUnderlineOnceNotItalic() {
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[4:3mX")
        val row = e.line(0)
        assertTrue(row.flags[0] and ATTR_UNDERLINE != 0)
        assertEquals(0, row.flags[0] and ATTR_ITALIC) // colon sub-param must not leak into SGR 3
    }

    @Test
    fun sgrColonUnderlineOffClearsIt() {
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[4mA${ESC}[4:0mB")
        val row = e.line(0)
        assertTrue(row.flags[0] and ATTR_UNDERLINE != 0)   // A: plain underline on
        assertEquals(0, row.flags[1] and ATTR_UNDERLINE)   // B: colon-form off actually clears it
    }

    @Test
    fun sgrPlainUnderlineOffStillWorks() {
        val e = TerminalEmulator(80, 24)
        e.feed("${ESC}[4mA${ESC}[24mB")
        val row = e.line(0)
        assertTrue(row.flags[0] and ATTR_UNDERLINE != 0)
        assertEquals(0, row.flags[1] and ATTR_UNDERLINE)
    }

    @Test
    fun sgrUnderlineColorDoesNotStrandUnderlineOrCorruptFollowingAttrs() {
        val e = TerminalEmulator(80, 24)
        // Set a curly underline + explicit underline color, clear the color (59),
        // then plain-clear the underline (24) and print a green-fg char after --
        // none of the colon-form groups above may leak digits into later codes.
        e.feed("${ESC}[4:3;58:2::255:0:0mA${ESC}[59;24m${ESC}[32mB")
        val row = e.line(0)
        assertTrue(row.flags[0] and ATTR_UNDERLINE != 0)
        assertEquals(0, row.flags[1] and ATTR_UNDERLINE)
        assertEquals(0xFF98C379, row.fg[1]) // ANSI 32 green, unaffected by the preceding colon groups
    }

    @Test
    fun sgrColonFormDoesNotBleedIntoAdjacentSemicolonCode() {
        val e = TerminalEmulator(80, 24)
        // `4:0` (colon group) followed by `;1` (bold) -- the `1` must be parsed as
        // its own top-level SGR code, not swallowed into the preceding colon group.
        e.feed("${ESC}[4:0;1mX")
        val row = e.line(0)
        assertEquals(0, row.flags[0] and ATTR_UNDERLINE)
        assertTrue(row.flags[0] and ATTR_BOLD != 0)
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

    // ---- save/restore cursor (DECSC/DECRC) ----

    @Test
    fun bareCsiURestoresCursor() {
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}7")          // DECSC: save cursor at (0,0)
        e.feed("${ESC}[4;3H")      // move to row 4, col 3 (1-based)
        e.feed("${ESC}[u")         // bare CSI u: ANSI.SYS-style restore-cursor
        assertEquals(0 to 0, e.cursor)
    }

    @Test
    fun kittyKeyboardProtocolCsiUDoesNotMoveCursor() {
        // `CSI < u` / `CSI > Ps u` / `CSI = Ps ; Pu u` / `CSI ? u` are the Kitty keyboard
        // protocol (pop/push/set/query keyboard flags) -- same final byte 'u' as the bare
        // ANSI.SYS restore-cursor, but with a marker prefix. They must NOT restore the
        // cursor: a prior bug dispatched all of them to restoreCursor(), so a keyboard-flags
        // toggle mid-redraw (as claude's CLI emits once after painting the trust dialog)
        // silently snapped the cursor back to wherever DECSC last saved -- corrupting the
        // row anchor for every subsequent relative-cursor redraw (arrow-key menu redraws
        // landing option text near the top of the screen instead of at the menu rows).
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}7")          // DECSC: save cursor at (0,0)
        e.feed("${ESC}[4;3H")      // move to row 4, col 3 (1-based)
        e.feed("${ESC}[<u")        // kitty: pop keyboard flags
        assertEquals(3 to 2, e.cursor)
        e.feed("${ESC}[>1u")       // kitty: push keyboard flags
        assertEquals(3 to 2, e.cursor)
        e.feed("${ESC}[?u")        // kitty: query keyboard flags
        assertEquals(3 to 2, e.cursor)
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

    @Test
    fun scrollRegionRedrawWithPromptChevronStaysOnExpectedRow() {
        // Regression for the real bug: pressing up/down arrows in claude (history
        // recall / the slash-command menu) re-echoes a `❯ <text>` prompt line
        // padded with spaces to the full terminal width, inside a temporarily
        // narrowed DECSTBM scroll region used to make room above a fixed footer
        // (captured verbatim from a real `claude` session: `CSI top;bot r` +
        // `CSI n S` + `CSI r` to scroll, then absolute-cursor-addressed redraws).
        // If `❯` (U+276F) is ever miscounted as double-width, the padded line
        // overflows one column early in a real 80-col terminal, deferred autowrap
        // fires a character too soon, a phantom row is inserted, and every
        // subsequent relatively-addressed line in the redraw lands one row lower
        // than a real terminal shows -- which is exactly what made claude's
        // arrow-key-triggered redraws "render in unexpected places."
        val cols = 80
        val e = TerminalEmulator(cols, 24)
        // Narrow the scroll region (rows 2..19 1-based), scroll up 2, reset full --
        // the exact pattern claude emits to make room for new transcript lines.
        e.feed("${ESC}[2;19r${ESC}[2S${ESC}[r")
        // Absolute reset lands the cursor at (0,0); move down to row 10 and print
        // a `❯ say ok2`-shaped line padded to the full column width, exactly as
        // claude re-echoes a recalled/highlighted prompt line.
        e.feed("${ESC}[H")
        val prompt = "❯ say ok2"           // ❯ say ok2
        e.feed("\r${ESC}[10B$prompt${" ".repeat(cols - prompt.length)}")
        // Then CR + down 2 rows and print a marker -- on a real terminal this
        // always lands on row 12 (10 + 2), regardless of the chevron's width.
        e.feed("\r${ESC}[2BMARKER")
        assertEquals("MARKER", e.rowText(12))
        assertEquals("", e.rowText(13))
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

    // ---- mouse reporting (openspec/specs/terminal/design.md, "Mouse reporting") ----

    @Test
    fun mouseTrackingModeAndSgrEncodingTracked() {
        val e = TerminalEmulator(20, 6)
        assertEquals(MouseTrackingMode.OFF, e.mouseTrackingMode)
        assertFalse(e.mouseSgrEncoding)

        e.feed("${ESC}[?1000h")
        assertEquals(MouseTrackingMode.NORMAL, e.mouseTrackingMode)
        e.feed("${ESC}[?1006h")
        assertTrue(e.mouseSgrEncoding)

        // Switching to ?1002/?1003 replaces the mode (they're mutually exclusive
        // in real xterm); resetting the mode that's NOT currently active is a
        // no-op, matching real xterm's independent per-mode DECRST.
        e.feed("${ESC}[?1002h")
        assertEquals(MouseTrackingMode.BUTTON_EVENT, e.mouseTrackingMode)
        e.feed("${ESC}[?1000l") // ?1000 isn't the active mode -- no-op
        assertEquals(MouseTrackingMode.BUTTON_EVENT, e.mouseTrackingMode)
        e.feed("${ESC}[?1003h")
        assertEquals(MouseTrackingMode.ANY_EVENT, e.mouseTrackingMode)

        e.feed("${ESC}[?1003l")
        assertEquals(MouseTrackingMode.OFF, e.mouseTrackingMode)
        e.feed("${ESC}[?1006l")
        assertFalse(e.mouseSgrEncoding)
    }

    @Test
    fun risResetsMouseTracking() {
        val e = TerminalEmulator(20, 6)
        e.feed("${ESC}[?1000h${ESC}[?1006h")
        assertEquals(MouseTrackingMode.NORMAL, e.mouseTrackingMode)
        assertTrue(e.mouseSgrEncoding)

        e.clear() // RIS
        assertEquals(MouseTrackingMode.OFF, e.mouseTrackingMode)
        assertFalse(e.mouseSgrEncoding)
    }

    @Test
    fun sgrMouseReportEncodesAClickAtAKnownCell() {
        // A left-button press then release at column 10, row 5 (1-based) -- the
        // pair xterm calls a "click". Regression-pins the SGR wire format:
        // `ESC [ < Cb ; Cx ; Cy M` for press, the same with lowercase `m` for
        // release, Cb=0 for the plain left button (no modifiers).
        val press = encodeMouseReport(MouseButton.LEFT, col = 10, row = 5, pressed = true, sgr = true)
        assertEquals("$ESC[<0;10;5M", press)

        val release = encodeMouseReport(MouseButton.LEFT, col = 10, row = 5, pressed = false, sgr = true)
        assertEquals("$ESC[<0;10;5m", release)
    }

    @Test
    fun sgrMouseReportEncodesAWheelNotchAtAKnownCell() {
        // Wheel notches are always a single "press" report (Cb 64/65) -- there's
        // no release pairing for a wheel tick.
        val up = encodeMouseReport(MouseButton.WHEEL_UP, col = 3, row = 8, pressed = true, sgr = true)
        assertEquals("$ESC[<64;3;8M", up)

        val down = encodeMouseReport(MouseButton.WHEEL_DOWN, col = 3, row = 8, pressed = true, sgr = true)
        assertEquals("$ESC[<65;3;8M", down)
    }

    @Test
    fun legacyX10MouseReportEncodesAClickAtAKnownCell() {
        // Fallback when ?1006 isn't enabled: ESC [ M Cb Cx Cy, one byte each,
        // value+32; release has no real button id (xterm's fixed code 3).
        val press = encodeMouseReport(MouseButton.LEFT, col = 10, row = 5, pressed = true, sgr = false)
        assertEquals("$ESC[M" + (0 + 32).toChar() + (10 + 32).toChar() + (5 + 32).toChar(), press)

        val release = encodeMouseReport(MouseButton.LEFT, col = 10, row = 5, pressed = false, sgr = false)
        assertEquals("$ESC[M" + (3 + 32).toChar() + (10 + 32).toChar() + (5 + 32).toChar(), release)
    }

    @Test
    fun motionReportAddsThirtyTwoToTheButtonCode() {
        val drag = encodeMouseReport(MouseButton.LEFT, col = 1, row = 1, pressed = true, sgr = true, motion = true)
        assertEquals("$ESC[<32;1;1M", drag)

        // A wheel notch is never a "motion" report even if the flag is passed.
        val wheel = encodeMouseReport(MouseButton.WHEEL_UP, col = 1, row = 1, pressed = true, sgr = true, motion = true)
        assertEquals("$ESC[<64;1;1M", wheel)
    }

    @Test
    fun legacyX10MotionReportAddsThirtyTwoToTheButtonCode() {
        // The +32 motion flag applies to the X10 fallback too -- Cb 0+32, then
        // the wire's own +32 offset on every byte, so the Cb byte is 64.
        val drag = encodeMouseReport(MouseButton.LEFT, col = 2, row = 3, pressed = true, sgr = false, motion = true)
        assertEquals("$ESC[M" + (32 + 32).toChar() + (2 + 32).toChar() + (3 + 32).toChar(), drag)
    }

    @Test
    fun onlyButtonAndAnyEventModesReportDragMotion() {
        // ?1000 reports button transitions only; ?1002/?1003 add motion while a
        // button is held. This is what gates the drag-motion reports the alt
        // screen's pointer gesture sends.
        assertFalse(MouseTrackingMode.OFF.reportsDragMotion())
        assertFalse(MouseTrackingMode.NORMAL.reportsDragMotion())
        assertTrue(MouseTrackingMode.BUTTON_EVENT.reportsDragMotion())
        assertTrue(MouseTrackingMode.ANY_EVENT.reportsDragMotion())
    }

    @Test
    fun mouseCellMapsPixelsToOneBasedCells() {
        // 10px wide, 20px tall cells on an 80x24 grid. The origin pixel is cell
        // (1,1), and anywhere inside a cell maps to that same cell -- the
        // property the drag-motion path relies on to only report on a real
        // cell crossing.
        assertEquals(MouseCell(1, 1), mouseCellAt(0f, 0f, 10f, 20f, 80, 24))
        assertEquals(MouseCell(1, 1), mouseCellAt(9.9f, 19.9f, 10f, 20f, 80, 24))
        assertEquals(MouseCell(2, 2), mouseCellAt(10f, 20f, 10f, 20f, 80, 24))
        assertEquals(MouseCell(4, 3), mouseCellAt(35f, 55f, 10f, 20f, 80, 24))
    }

    @Test
    fun mouseCellClampsOutOfGridPositionsToARealCell() {
        // Past the last column/row (a touch in the padding, or a drag that ran
        // off the edge) clamps instead of naming a cell the program can't have.
        assertEquals(MouseCell(80, 24), mouseCellAt(10_000f, 10_000f, 10f, 20f, 80, 24))
        // Negative coordinates (a drag off the top-left) clamp to the origin.
        assertEquals(MouseCell(1, 1), mouseCellAt(-50f, -50f, 10f, 20f, 80, 24))
        // Cell not measured yet: fall back to the origin, don't divide by zero.
        assertEquals(MouseCell(1, 1), mouseCellAt(35f, 55f, 0f, 0f, 80, 24))
        // A degenerate grid still yields a valid 1-based cell.
        assertEquals(MouseCell(1, 1), mouseCellAt(35f, 55f, 10f, 20f, 0, 0))
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
    fun dingbatSpinnerGlyphIsSingleWidth() {
        // Regression: real terminals (verified against tmux/wcwidth) advance the
        // cursor only one column for claude's spinner glyphs in 0x2600-27BF —
        // treating them as double-width desyncs column accounting from what
        // claude assumes (see charWidth's doc comment).
        val e = TerminalEmulator(20, 6)
        e.feed("✻")   // ✻ — claude's spinner glyph
        assertEquals(0 to 1, e.cursor)
    }

    @Test
    fun promptChevronIsSingleWidth() {
        // Regression: `❯` (claude's prompt marker, U+276F) is in the same
        // Misc Symbols & Dingbats block and must stay single-width too — this is
        // the specific glyph that triggered the arrow-key/scroll-region redraw
        // corruption (see ScrollRegionArrowKeyRedraw below).
        val e = TerminalEmulator(20, 6)
        e.feed("❯")
        assertEquals(0 to 1, e.cursor)
    }

    @Test
    fun geometricBulletIsSingleWidth() {
        // Regression: same as dingbats above — Geometric Shapes (0x25A0-25FF)
        // are single-width on real terminals despite visually looking "big."
        val e = TerminalEmulator(20, 6)
        e.feed("●")   // ● black circle
        assertEquals(0 to 1, e.cursor)
    }

    @Test
    fun boxDrawingStaysSingleWidth() {
        // Box-drawing (0x2500-257F) is a different block from Geometric Shapes
        // (0x25A0-25FF) and always stayed 1-cell in JetBrains Mono; keep the
        // regression now that Geometric Shapes is single-width too.
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
