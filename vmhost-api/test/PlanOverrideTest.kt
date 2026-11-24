import dev.azula.vmhost.HostPlan
import dev.azula.vmhost.MIN_PLAN_MEMORY_MB
import dev.azula.vmhost.PlanOverride
import dev.azula.vmhost.maxMemoryMb
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanOverrideTest {
    private val plan = HostPlan(cpus = 8, memoryMb = 3584, diskGb = 16, downloadMb = 300)

    @Test fun nullFieldsKeepThePlan() {
        assertEquals(plan, PlanOverride().applyTo(plan))
    }

    @Test fun anOverrideWithinTheCeilingApplies() {
        assertEquals(4096, PlanOverride(memoryMb = 4096).applyTo(plan).memoryMb)
        assertEquals(1024, PlanOverride(memoryMb = 1024).applyTo(plan).memoryMb)
        assertEquals(20, PlanOverride(diskGb = 20).applyTo(plan).diskGb)
    }

    @Test fun theCeilingHoldsForAStoredOverride() {
        assertEquals(5376, maxMemoryMb(3584)) // the stepper's own limit
        assertEquals(5376, PlanOverride(memoryMb = 5376).applyTo(plan).memoryMb)
        assertEquals(5376, PlanOverride(memoryMb = 5377).applyTo(plan).memoryMb)
        assertEquals(5376, PlanOverride(memoryMb = 1 shl 20).applyTo(plan).memoryMb)
    }

    @Test fun nonsenseDoesNotStarveTheGuest() {
        assertEquals(MIN_PLAN_MEMORY_MB, PlanOverride(memoryMb = 0).applyTo(plan).memoryMb)
        assertEquals(MIN_PLAN_MEMORY_MB, PlanOverride(memoryMb = -5).applyTo(plan).memoryMb)
        assertEquals(MIN_PLAN_MEMORY_MB, PlanOverride(memoryMb = 10).applyTo(plan.copy(memoryMb = 512)).memoryMb)
    }

    @Test fun storedTextRoundTripsAndOldOnesStillApply() {
        val o = PlanOverride.decode("""{"memoryMb":9999,"unknown":1}""")!!
        assertEquals(5376, o.applyTo(plan).memoryMb)
    }
}
