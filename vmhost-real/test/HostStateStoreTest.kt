import dev.azula.vmhost.BrokerConfig
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostPlan
import dev.azula.vmhost.HostState
import dev.azula.vmhost.HostStateStore
import dev.azula.vmhost.effectiveState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostStateStoreTest {
    private fun tmp(): File = Files.createTempDirectory("vmhost-state").toFile()

    @Test fun missingFileReadsAsStopped() {
        assertEquals(HostState(), HostStateStore(tmp()).read())
    }

    @Test fun writeThenRead() {
        val store = HostStateStore(tmp())
        val s = HostState(HostPhase.RUNNING, "", startedAtMs = 5, updatedAtMs = 9, restarts = 1, pid = 77)
        store.write(s)
        assertEquals(s, store.read())
        store.write(s.copy(phase = HostPhase.STOPPED))
        assertEquals(HostPhase.STOPPED, store.read().phase)
        // The temp file used for the atomic replace is gone.
        assertTrue(store.dir.listFiles()!!.map { it.name } == listOf(HostStateStore.FILE_NAME))
    }

    @Test fun tornFileReadsAsStopped() {
        val store = HostStateStore(tmp())
        store.file.writeText("{\"phase\":\"RUNN")
        assertEquals(HostState(), store.read())
    }

    @Test fun activeStateWithDeadWriterBecomesAnError() {
        val running = HostState(HostPhase.RUNNING, pid = 123)
        assertEquals(running, effectiveState(running) { true })
        val e = effectiveState(running) { false }
        assertEquals(HostPhase.ERROR, e.phase)
        assertTrue("killed" in e.detail)
    }

    @Test fun inactiveStatesAreNeverRewritten() {
        for (p in listOf(HostPhase.STOPPED, HostPhase.ERROR)) {
            val s = HostState(p, "x", pid = 1)
            assertEquals(s, effectiveState(s) { false })
        }
    }

    @Test fun brokerConfigUsesTheNativeFieldNames() {
        val json = BrokerConfig.forHost(File("/data/files"), HostPlan(8, 4096, 16, 400)).toJson()
        for (k in listOf("computeDir", "vmName", "workerSocket", "vmCpus", "vmMemoryMb", "logFile")) {
            assertTrue("\"$k\":" in json, "$k in $json")
        }
        assertTrue("\"vmName\":\"default\"" in json)
        assertTrue("\"workerSocket\":\"/data/files/run/worker.sock\"" in json)
    }
}
