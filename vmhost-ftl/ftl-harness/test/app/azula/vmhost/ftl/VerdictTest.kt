package app.azula.vmhost.ftl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerdictTest {
    private val all = listOf("azula", "grants", "boot", "doctor", "cloud")

    private fun verdict(vararg o: Pair<String, Outcome>) =
        Verdict().apply { o.forEach { (n, v) -> record(n, v, "why $n") } }

    private fun passed(vararg names: String) = verdict(*names.map { it to Outcome.PASS }.toTypedArray())

    @Test fun passNeedsEveryStage() {
        val v = passed("azula", "grants", "boot", "doctor", "cloud")
        assertEquals(Outcome.PASS, v.overall(all))
        assertNull(v.decisive(all))
        assertEquals(Outcome.FAIL, passed("azula", "grants", "boot", "doctor").overall(all))
        assertEquals(Outcome.FAIL, passed("azula", "grants", "boot", "cloud").overall(all))
        assertEquals(Outcome.FAIL, Verdict().overall(all))
    }

    @Test fun aRunThatStoppedEarlySaysWhere() {
        assertEquals(Verdict.Stage("cloud", Outcome.NOT_RUN, "never run", 0.0), passed("azula", "grants", "boot", "doctor").decisive(all))
        assertEquals("azula", Verdict().decisive(all)?.name)
    }

    @Test fun cannotHostWithTheRestNotRun() {
        val v = verdict("azula" to Outcome.PASS, "grants" to Outcome.PASS, "boot" to Outcome.CANNOT_HOST, "doctor" to Outcome.NOT_RUN, "cloud" to Outcome.NOT_RUN)
        assertEquals(Outcome.CANNOT_HOST, v.overall(all))
        assertEquals("why boot", v.decisive(all)?.reason)
    }

    @Test fun aFailureOutranksCannotHost() {
        val v = verdict("azula" to Outcome.FAIL, "grants" to Outcome.PASS, "boot" to Outcome.CANNOT_HOST, "doctor" to Outcome.NOT_RUN, "cloud" to Outcome.NOT_RUN)
        assertEquals(Outcome.FAIL, v.overall(all))
        assertEquals("azula", v.decisive(all)?.name)
    }

    @Test fun theLastStageAloneFailsTheRun() {
        val v = passed("azula", "grants", "boot", "doctor").apply { record("cloud", Outcome.FAIL, "1 of 9 steps failed: streams (exit 0, want 7)") }
        assertEquals(Outcome.FAIL, v.overall(all))
        assertEquals("1 of 9 steps failed: streams (exit 0, want 7)", v.decisive(all)?.reason)
    }
}
