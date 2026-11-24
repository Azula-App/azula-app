package app.azula.vmhost.ftl

import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictTest {
    private val all = listOf("azula", "grants", "boot", "doctor")

    private fun verdict(vararg o: Pair<String, Outcome>) =
        Verdict().apply { o.forEach { (n, v) -> record(n, v, "why $n") } }

    @Test fun passNeedsEveryStage() {
        assertEquals(Outcome.PASS, verdict("azula" to Outcome.PASS, "grants" to Outcome.PASS, "boot" to Outcome.PASS, "doctor" to Outcome.PASS).overall(all))
        assertEquals(Outcome.FAIL, verdict("azula" to Outcome.PASS, "grants" to Outcome.PASS, "boot" to Outcome.PASS).overall(all))
        assertEquals(Outcome.FAIL, Verdict().overall(all))
    }

    @Test fun cannotHostWithTheRestNotRun() {
        val v = verdict("azula" to Outcome.PASS, "grants" to Outcome.PASS, "boot" to Outcome.CANNOT_HOST, "doctor" to Outcome.NOT_RUN)
        assertEquals(Outcome.CANNOT_HOST, v.overall(all))
        assertEquals("why boot", v.decisive()?.reason)
    }

    @Test fun aFailureOutranksCannotHost() {
        val v = verdict("azula" to Outcome.FAIL, "grants" to Outcome.PASS, "boot" to Outcome.CANNOT_HOST, "doctor" to Outcome.NOT_RUN)
        assertEquals(Outcome.FAIL, v.overall(all))
        assertEquals("azula", v.decisive()?.name)
    }
}
