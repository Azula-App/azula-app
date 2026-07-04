import dev.azula.terminal.PredictionEngine
import dev.azula.terminal.TerminalEmulator
import dev.azula.terminal.TermSeq
import kotlin.test.Test
import kotlin.test.assertEquals

/** A controllable clock for deterministic timeout tests — no real waiting,
 *  the test just moves [now] forward. */
private class FakeClock(var now: Long = 0L)

class PredictionEngineTest {

    private fun engine(emu: TerminalEmulator, clock: FakeClock, timeoutMs: Long = 1000) =
        PredictionEngine(emu, timeoutMs = timeoutMs, now = { clock.now })

    // ---- homogeneous multi-char prediction ----

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
}
