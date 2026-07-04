import dev.azula.terminal.PredictionEngine
import dev.azula.terminal.TerminalEmulator
import dev.azula.terminal.TermSeq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"

/** A controllable clock for deterministic quiet-window/timeout tests — no real
 *  waiting, the test just moves [now] forward. */
private class FakeClock(var now: Long = 0L)

class PredictionEngineTest {

    private fun engine(emu: TerminalEmulator, clock: FakeClock, timeoutMs: Long = 1000) =
        PredictionEngine(emu, timeoutMs = timeoutMs, now = { clock.now })

    // ---- homogeneous multi-char prediction (primary screen) ----

    @Test
    fun homogeneousPrintableRunPredictsEachChar() {
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        e.onInput("git")
        assertEquals(3, e.overlays().size)
        assertEquals(0 to 3, e.cursor())
    }

    @Test
    fun homogeneousBackspaceRunUndoesEachChar() {
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        e.onInput("abc")
        e.onInput(TermSeq.BACKSPACE.repeat(2))
        assertEquals(1, e.overlays().size)
        assertEquals(0 to 1, e.cursor())
    }

    @Test
    fun mixedStringPausesRatherThanPredicting() {
        // Not a shape the input layer is supposed to send (it always sends one
        // homogeneous call), but the engine must still degrade safely.
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        e.onInput("a${TermSeq.BACKSPACE}b")
        assertEquals(0, e.overlays().size)
    }

    @Test
    fun singleCharAndArrowPredictionsStillWork() {
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        e.onInput("x")
        assertEquals(1, e.overlays().size)
        e.onInput(TermSeq.LEFT)
        assertEquals(0 to 0, e.cursor())
    }

    @Test
    fun enterFlushesAndPauses() {
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        e.onInput("x")
        e.onInput(TermSeq.ENTER)
        assertEquals(0, e.overlays().size)
        e.onInput("y") // paused — no new prediction until server output resumes it
        assertEquals(0, e.overlays().size)
    }

    // ---- alt-screen prediction ----

    private fun altEmu(): TerminalEmulator {
        val emu = TerminalEmulator(80, 24)
        emu.feed("$ESC[?1049h") // enter alt screen
        assertTrue(emu.altScreen)
        return emu
    }

    @Test
    fun altQuietWindowGatesPrediction() {
        val emu = altEmu()
        val clock = FakeClock()
        val e = engine(emu, clock)
        e.onServerOutput("x") // lastOutputAt = 0
        e.onInput("a") // still inside the 200ms quiet window
        assertEquals(0, e.overlays().size)
        clock.now = 201
        e.onInput("a")
        assertEquals(1, e.overlays().size)
    }

    @Test
    fun altFlushesInstantlyOnAnyServerOutput() {
        val emu = altEmu()
        val clock = FakeClock()
        val e = engine(emu, clock)
        e.onServerOutput("x")
        clock.now = 300
        e.onInput("a")
        assertEquals(1, e.overlays().size)
        e.onServerOutput("y") // any output while on alt flushes, no reconciliation
        assertEquals(0, e.overlays().size)
    }

    @Test
    fun altPredictionCappedAtEightPending() {
        val emu = altEmu()
        val clock = FakeClock()
        val e = engine(emu, clock)
        e.onServerOutput("x")
        clock.now = 300
        repeat(12) { e.onInput("a") }
        assertEquals(8, e.overlays().size)
    }

    @Test
    fun altPredictionDropsAfterTimeoutBackstop() {
        val emu = altEmu()
        val clock = FakeClock()
        val e = engine(emu, clock)
        e.onServerOutput("x")
        clock.now = 300
        e.onInput("a")
        assertEquals(1, e.overlays().size)
        clock.now = 701 // > 400ms since the (300ms) prediction was made
        e.onInput("b") // the stale prediction is dropped before considering new input
        assertEquals(1, e.overlays().size) // only "b"'s prediction remains
    }

    @Test
    fun altNeverPredictsArrows() {
        val emu = altEmu()
        val clock = FakeClock()
        val e = engine(emu, clock)
        e.onServerOutput("x")
        clock.now = 300
        e.onInput(TermSeq.LEFT)
        assertEquals(0, e.overlays().size)
        assertNull(e.cursor())
    }

    @Test
    fun altToPrimaryFlipFlushesEverything() {
        val emu = altEmu()
        val clock = FakeClock()
        val e = engine(emu, clock)
        e.onServerOutput("x")
        clock.now = 300
        e.onInput("a")
        assertEquals(1, e.overlays().size)
        // The engine isn't wired as emu.predictor in this unit test, so drive
        // both halves by hand: apply the toggle to the emulator, then hand the
        // engine the same output (as TerminalEmulator.feed does automatically
        // via `predictor?.onServerOutput` when wired for real).
        val toggle = "$ESC[?1049l"
        emu.feed(toggle) // back to primary
        e.onServerOutput(toggle)
        assertEquals(0, e.overlays().size)
    }

    @Test
    fun primaryPredictionUnboundedAndUnaffectedByAltCap() {
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        repeat(12) { e.onInput("a") }
        assertEquals(12, e.overlays().size)
    }

    // ---- stranded-overlay regression (autocorrect's two-call backspace+suffix diff) ----

    /**
     * Reproduces the device-observed "phantom d past pwd" bug: after the real
     * text is fully echoed (no overlays pending, so predCol is fully caught up
     * to the live cursor), the mobile input's diff sends a same-length
     * fix-up as TWO separate onInput calls -- an all-backspace call, then an
     * all-printable call (see [dev.azula.terminal.InputDiff]) -- e.g.
     * autocorrect replacing the last char with itself, or a homoglyph.
     * `predictBackspace` correctly steps predCol back by one, but that step
     * erases a REAL (already-confirmed) cell, not an overlay -- there's
     * nothing left in `overlays`, so the immediately following
     * `predictPrintable`'s `ensureSynced()` sees an empty overlay list and
     * wrongly re-syncs predCol to the *live* cursor (which hasn't moved yet --
     * the real backspace is still in flight to the server), placing the new
     * prediction one cell past the real text instead of on top of the
     * character being corrected.
     */
    @Test
    fun backspaceThenSuffixAfterFullyConfirmedTextDoesNotStrandOverlayPastRealText() {
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        // "pwd" is already for-real on screen (server echoed it already; no
        // overlays pending) -- the live cursor sits at column 3.
        emu.feed("pwd")
        assertEquals(3, emu.cursorCol)

        // The diff for an autocorrect fix-up of the last char: one backspace,
        // then one printable, as two separate terminalRaw/onInput calls.
        e.onInput(TermSeq.BACKSPACE)
        e.onInput("d")

        // The new prediction must land back on column 2 (over the char being
        // replaced), not column 3 (one past "pwd", where it would sit forever
        // since the server will never echo anything there for this edit).
        assertEquals(1, e.overlays().size)
        val p = e.overlays().single()
        assertEquals(2, p.col)
        assertEquals(0, p.row)
    }

    /** Same fix-up as above, but the real backspace+retype echo comes back
     *  split across two server chunks -- the erase lands first, the retyped
     *  char lands in a later chunk -- to confirm reconciliation still clears
     *  the prediction fully once the server has caught up, rather than
     *  stranding it because the first chunk's cell isn't there yet. */
    @Test
    fun backspaceThenSuffixReconciledAcrossSplitServerEcho() {
        val emu = TerminalEmulator(80, 24)
        val e = engine(emu, FakeClock())
        emu.feed("pwd")

        e.onInput(TermSeq.BACKSPACE)
        e.onInput("d")
        assertEquals(1, e.overlays().size)
        assertEquals(2, e.overlays().single().col)

        // Server echoes the erase (backspace + space + backspace) in one
        // chunk, then the retyped char in a second, later chunk.
        val erase = "\b \b"
        emu.feed(erase)
        e.onServerOutput(erase)
        assertEquals(1, e.overlays().size) // 'd' cell not echoed yet -- still pending, not stranded

        emu.feed("d")
        e.onServerOutput("d")
        assertEquals(0, e.overlays().size) // fully reconciled once the server catches up
    }
}
