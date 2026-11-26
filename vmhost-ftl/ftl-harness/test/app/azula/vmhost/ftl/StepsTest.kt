package app.azula.vmhost.ftl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTest {
    private val three = listOf("a", "b", "c")
    private var now = 0.0
    private val logged = mutableListOf<String>()

    private fun steps(deadline: Double = 1000.0, clean: (String) -> String = { it }) =
        Steps({ now }, deadline, clean = clean, log = logged::add)

    private fun Steps.run(name: String, body: () -> String) = run(name, "H1", 100.0, body = body)

    @Test fun allPassed() {
        val s = steps()
        for (n in three) s.run(n) { now += 2; "fine" }
        assertNull(s.problem(three))
        assertEquals(listOf(2.0, 2.0, 2.0), s.all().map { it.seconds })
        assertEquals(listOf("H1", "H1", "H1"), s.all().map { it.covers })
        assertEquals(StepOutcome.PASS, s.outcomeOf("a"))
    }

    @Test fun aFailedStepDoesntStopTheNext() {
        val s = steps()
        s.run("a") { now += 5; error("a went wrong") }
        s.run("b") { "fine" }
        s.run("c") { throw AssertionError("c too") }
        assertEquals(listOf(StepOutcome.FAIL, StepOutcome.PASS, StepOutcome.FAIL), three.map { s.outcomeOf(it) })
        assertEquals(5.0, s.all()[0].seconds, 0.0)
        assertEquals("2 of 3 steps failed: a (a went wrong); c (c too)", s.problem(three))
    }

    @Test fun aThrowableWithNoMessageIsStillAFailure() {
        val s = steps()
        s.run("a") { throw NullPointerException() }
        assertEquals(StepOutcome.FAIL, s.outcomeOf("a"))
        assertTrue("NullPointerException" in s.problem(listOf("a"))!!)
    }

    @Test fun aTimeoutIsItsOwnOutcomeAndNotAPass() {
        val s = steps()
        s.run("a") { throw TimedOutException("push: still running after 720 s") }
        s.run("b") { "fine" }
        s.run("c") { error("wrong") }
        assertEquals(listOf(StepOutcome.TIMED_OUT, StepOutcome.PASS, StepOutcome.FAIL), three.map { s.outcomeOf(it) })
        assertEquals("1 of 3 steps failed: c (wrong). 1 of 3 steps timed out: a (push: still running after 720 s)", s.problem(three))
    }

    @Test fun aThrottledPassPassesAndSaysSo() {
        val s = steps()
        s.run("a", "S7", 720.0, throttledBy = { listOf("client", "host") }) { "fine" }
        s.run("b", "", 10.0, throttledBy = { emptyList() }) { "fine" }
        assertEquals(StepOutcome.PASS_THROTTLED, s.outcomeOf("a"))
        assertEquals(StepOutcome.PASS, s.outcomeOf("b"))
        assertEquals(listOf("client", "host"), s.all()[0].throttledBy)
        assertNull(s.problem(listOf("a", "b")))
        assertTrue(logged[0], "PASS_THROTTLED" in logged[0] && "client, host" in logged[0])
    }

    @Test fun throttlingExcusesNothing() {
        val s = steps()
        s.run("a", "", 10.0, throttledBy = { listOf("client") }) { error("round trip differs") }
        s.run("b", "", 10.0, throttledBy = { listOf("client") }) { throw TimedOutException("pull: still running") }
        s.run("c", "", 10.0, throttledBy = { listOf("client") }) { throw NotRunException("no such thing here") }
        assertEquals(listOf(StepOutcome.FAIL, StepOutcome.TIMED_OUT, StepOutcome.NOT_RUN), three.map { s.outcomeOf(it) })
        // It's recorded all the same.
        assertEquals(listOf("client"), s.all()[0].throttledBy)
        assertEquals(
            "1 of 3 steps failed: a (round trip differs). 1 of 3 steps timed out: b (pull: still running). 1 of 3 steps not run: c (no such thing here)",
            s.problem(three),
        )
    }

    @Test fun notBeingAbleToTellIsNotThrottled() {
        val s = steps()
        s.run("a", "", 10.0, throttledBy = { error("run-as said no") }) { "fine" }
        assertEquals(StepOutcome.PASS, s.outcomeOf("a"))
    }

    @Test fun notRunIsNotAPass() {
        val s = steps()
        s.run("a") { "fine" }
        s.run("b") { throw NotRunException("the phone has no such thing") }
        s.run("c") { "fine" }
        assertEquals(StepOutcome.NOT_RUN, s.outcomeOf("b"))
        assertEquals("1 of 3 steps not run: b (the phone has no such thing)", s.problem(three))
    }

    @Test fun aStepThatWasNeverReachedIsNotAPass() {
        val s = steps()
        s.run("a") { "fine" }
        assertEquals(StepOutcome.NOT_RUN, s.outcomeOf("b"))
        assertEquals("2 of 3 steps not run: b (never reached); c (never reached)", s.problem(three))
        assertEquals("3 of 3 steps not run: a (never reached); b (never reached); c (never reached)", steps().problem(three))
    }

    @Test fun noStepStartsWithTheTimeUp() {
        val s = steps(deadline = 100.0)
        s.run("a") { now = 80.0; "fine" }
        var ran = false
        s.run("b") { ran = true; "fine" }
        assertFalse(ran)
        assertEquals("1 of 2 steps not run: b (out of time: 20 s of the run's time left)", s.problem(listOf("a", "b")))
        now = 500.0
        s.run("c") { "fine" }
        assertTrue("0 s" in s.all().last().detail)
    }

    @Test fun aCommandGetsWhatTheStepAndTheRunHaveLeft() {
        val s = steps(deadline = 1000.0)
        var got = listOf<Long>()
        s.run("a", "", 720.0) {
            val push = s.limit("push", 3600)
            now += 500
            got = listOf(push, s.limit("pull", 3600), s.limit("sum", 60))
            "fine"
        }
        assertEquals(listOf(720L, 220L, 60L), got)
        // The run ends before the step would.
        now = 900.0
        s.run("b", "", 720.0) { got = listOf(s.limit("push", 3600)); "fine" }
        assertEquals(listOf(100L), got)
    }

    @Test fun aCommandWithNoTimeLeftIsATimeout() {
        val s = steps()
        s.run("a", "", 720.0) { now += 719.5; "ran ${s.limit("pull", 60)}" }
        assertEquals(StepOutcome.TIMED_OUT, s.outcomeOf("a"))
        assertEquals("1 of 1 steps timed out: a (no time left for pull)", s.problem(listOf("a")))
        now = 5000.0
        assertThrows(TimedOutException::class.java) { steps(deadline = 100.0).limit("x", 60) }
    }

    @Test fun textWithAPercentSignIsOnlyText() {
        val s = steps()
        s.run("a") { error("100%s of %d, 5% left") }
        assertTrue("100%s of %d, 5% left" in s.problem(listOf("a"))!!)
        assertTrue("100%s of %d, 5% left" in logged.single())
    }

    @Test fun detailsAreScrubbed() {
        val scrub = Scrub()
        val token = "azc1.eyJ2IjoxLCJob3N0IjoiYWJjIn0_-x"
        val s = steps(clean = scrub::clean)
        s.run("a") { error("azula said $token") }
        s.run("b") { "fine with $token" }
        s.run("c") { throw NotRunException(token) }
        s.run("d") { throw TimedOutException(token) }
        assertFalse(scrub.leaks(s.problem(three + "d")!! + s.all() + logged))
    }
}
