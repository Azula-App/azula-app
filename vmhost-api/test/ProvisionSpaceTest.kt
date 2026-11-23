import dev.azula.vmhost.CannotHostReason
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostState
import dev.azula.vmhost.HostStateCodec
import dev.azula.vmhost.PROVISION_HEADROOM_BYTES
import dev.azula.vmhost.ProvisionStep
import dev.azula.vmhost.RAW_IMAGE_BYTES
import dev.azula.vmhost.diskSizeGb
import dev.azula.vmhost.evaluateProvisionSpace
import dev.azula.vmhost.provisionNeededBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvisionSpaceTest {
    private val gib = 1L shl 30

    @Test fun diskIsAQuarterOfFreeSpaceCappedAt16() {
        assertEquals(16, diskSizeGb(200 * gib))
        assertEquals(16, diskSizeGb(64 * gib))
        assertEquals(10, diskSizeGb(40 * gib))
        assertEquals(5, diskSizeGb(20 * gib))
        assertEquals(0, diskSizeGb(0))
        assertEquals(0, diskSizeGb(3 * gib))
    }

    @Test fun neededIsArchivePlusRawPlusHeadroom() {
        val archive = 300_020_608L
        assertEquals(archive + RAW_IMAGE_BYTES + PROVISION_HEADROOM_BYTES, provisionNeededBytes(archive))
        // Bytes already downloaded shrink the need; a negative remainder can't make it negative.
        assertEquals(RAW_IMAGE_BYTES + PROVISION_HEADROOM_BYTES, provisionNeededBytes(-5))
    }

    @Test fun enoughSpaceIsNull() {
        assertNull(evaluateProvisionSpace(10 * gib, 300_020_608L))
        // Exactly the need is enough.
        assertNull(evaluateProvisionSpace(provisionNeededBytes(1000), 1000))
    }

    @Test fun lowSpaceSaysNeedsXFreeYNow() {
        val r = assertNotNull(evaluateProvisionSpace(2 * gib, 300_020_608L))
        assertEquals(CannotHostReason.LOW_STORAGE, r.reason)
        assertEquals("needs 4.3 GB free · 2 GB free now", r.message)
        assertTrue(r is CapabilityResult.CannotHost)
    }

    @Test fun provisioningStateRoundTripsAndIsActive() {
        val s = HostState(HostPhase.PROVISIONING, step = ProvisionStep.DOWNLOADING, bytesDone = 5, bytesTotal = 9, pid = 3)
        assertEquals(s, HostStateCodec.decode(HostStateCodec.encode(s)))
        assertTrue(s.active)
        // Older state files (no step fields) still read.
        assertEquals(HostPhase.RUNNING, HostStateCodec.decode("{\"phase\":\"RUNNING\",\"pid\":1}")?.phase)
    }
}
