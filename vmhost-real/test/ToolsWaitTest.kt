package dev.azula.vmhost

import dev.azula.vmhost.ConsoleMarks.Tools
import dev.azula.vmhost.ToolsWait.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ready needs the guest's own rc=0: never a failure, and never the clock running out. */
class ToolsWaitTest {
    private val ok = Tools.Done
    private fun failed(rc: Int, at: String) = Tools.Failed(rc, at)

    @Test
    fun notNeededIsDoneAtOnce() {
        val w = ToolsWait(needed = false)
        assertEquals(Verdict.DONE, w.verdict)
        assertFalse(w.onDeadline(), "no deadline to miss")
        assertEquals(Verdict.DONE, w.verdict)
    }

    @Test
    fun neededWaits() = assertEquals(Verdict.WAITING, ToolsWait(needed = true).verdict)

    @Test
    fun rcZeroEndsTheWait() {
        val w = ToolsWait(needed = true)
        w.on(ok)
        assertEquals(Verdict.DONE, w.verdict)
    }

    @Test
    fun aFailureNeverEndsTheWait() {
        val w = ToolsWait(needed = true)
        assertTrue(w.on(failed(100, "31.0")), "a new failure changes the status line")
        assertEquals(Verdict.WAITING, w.verdict)
        assertEquals(failed(100, "31.0"), w.failure)
    }

    @Test
    fun theSameFailureSeenAgainChangesNothing() {
        val w = ToolsWait(needed = true)
        w.on(failed(100, "31.0"))
        assertFalse(w.on(failed(100, "31.0")), "the same line, read again")
        assertTrue(w.on(failed(100, "47.0")), "the next attempt is a new failure")
    }

    @Test
    fun successAfterFailuresIsReady() {
        val w = ToolsWait(needed = true)
        w.on(failed(100, "31.0"))
        w.on(failed(100, "47.0"))
        w.on(ok)
        assertEquals(Verdict.DONE, w.verdict)
    }

    @Test
    fun noWordByTheDeadlineGivesUp() {
        val w = ToolsWait(needed = true)
        assertTrue(w.onDeadline())
        assertEquals(Verdict.GIVE_UP, w.verdict)
        assertNull(w.failure)
    }

    @Test
    fun failuresUntilTheDeadlineGiveUp() {
        val w = ToolsWait(needed = true)
        w.on(failed(100, "31.0"))
        assertTrue(w.onDeadline())
        assertEquals(Verdict.GIVE_UP, w.verdict)
        assertEquals(100, w.failure?.rc)
    }

    @Test
    fun readyBeforeTheDeadlineKeepsReady() {
        val w = ToolsWait(needed = true)
        w.on(ok)
        assertFalse(w.onDeadline(), "late: the wait is already over")
        assertEquals(Verdict.DONE, w.verdict)
    }

    @Test
    fun nothingRevivesAWaitThatGaveUp() {
        val w = ToolsWait(needed = true)
        w.onDeadline()
        w.on(ok)
        assertEquals(Verdict.GIVE_UP, w.verdict, "the VM is already being stopped; the next start waits afresh")
        assertFalse(w.on(failed(1, "900.0")))
    }
}
