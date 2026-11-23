import dev.azula.vmhost.CannotHostReason
import dev.azula.vmhost.CapabilityInputs
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.evaluateCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilityTest {
    private val gib = 1L shl 30

    private fun ok() = CapabilityInputs(
        sdkInt = 36, avfServicePresent = true, nonProtectedVmSupported = true, protectedVmSupported = true,
        totalRamBytes = 8 * gib, freeStorageBytes = 64 * gib, cpuCount = 8, hiddenApiWorks = true,
    )

    private fun reason(i: CapabilityInputs) = assertIs<CapabilityResult.CannotHost>(evaluateCapability(i)).reason

    @Test fun passesWithDefaults() {
        val plan = assertIs<CapabilityResult.CanHost>(evaluateCapability(ok())).plan
        assertEquals(8, plan.cpus)
        assertEquals(4096, plan.memoryMb)
        assertEquals(16, plan.diskGb)
        assertEquals(400, plan.downloadMb)
    }

    @Test fun diskIsQuarterOfFreeSpaceWhenSmallerThan16() {
        val plan = assertIs<CapabilityResult.CanHost>(evaluateCapability(ok().copy(freeStorageBytes = 24 * gib))).plan
        assertEquals(6, plan.diskGb)
    }

    @Test fun memoryFloorsToStep() {
        // 6 GiB phone: half is 3072 MB, already a multiple of 256.
        assertEquals(3072, assertIs<CapabilityResult.CanHost>(evaluateCapability(ok().copy(totalRamBytes = 6 * gib))).plan.memoryMb)
        // 7.5 GiB reported: half is 3840 MB.
        assertEquals(3840, assertIs<CapabilityResult.CanHost>(evaluateCapability(ok().copy(totalRamBytes = 7680L shl 20))).plan.memoryMb)
    }

    @Test fun oldAndroid() = assertEquals(CannotHostReason.ANDROID_TOO_OLD, reason(ok().copy(sdkInt = 33)))

    @Test fun missingHostLibrary() = assertEquals(CannotHostReason.UNSUPPORTED_ABI, reason(ok().copy(hostLibraryPresent = false)))

    @Test fun noAvfService() = assertEquals(CannotHostReason.AVF_UNAVAILABLE, reason(ok().copy(avfServicePresent = false)))

    @Test fun protectedOnly() {
        val r = evaluateCapability(ok().copy(nonProtectedVmSupported = false))
        val c = assertIs<CapabilityResult.CannotHost>(r)
        assertEquals(CannotHostReason.PROTECTED_VMS_ONLY, c.reason)
        assertTrue("only allows protected vms" in c.message)
    }

    @Test fun noVmsAtAll() =
        assertEquals(CannotHostReason.AVF_UNAVAILABLE, reason(ok().copy(nonProtectedVmSupported = false, protectedVmSupported = false)))

    @Test fun lowRam() = assertEquals(CannotHostReason.LOW_RAM, reason(ok().copy(totalRamBytes = 4 * gib)))

    @Test fun lowStorageMessageSaysWhatIsFree() {
        val c = assertIs<CapabilityResult.CannotHost>(evaluateCapability(ok().copy(freeStorageBytes = 9 * gib)))
        assertEquals(CannotHostReason.LOW_STORAGE, c.reason)
        assertEquals("needs 20 GB free · 9 GB free now", c.message)
    }

    @Test fun hiddenApiBlockedChecksLast() {
        assertEquals(CannotHostReason.HIDDEN_API_BLOCKED, reason(ok().copy(hiddenApiWorks = false)))
        // A phone that fails an earlier, user-fixable check reports that one first.
        assertEquals(CannotHostReason.LOW_STORAGE, reason(ok().copy(hiddenApiWorks = false, freeStorageBytes = gib)))
    }
}
