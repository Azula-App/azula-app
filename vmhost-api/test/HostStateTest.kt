import dev.azula.vmhost.GrantJson
import dev.azula.vmhost.HostException
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostState
import dev.azula.vmhost.HostStateCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostStateTest {
    @Test fun roundTrips() {
        val s = HostState(HostPhase.RUNNING, "login prompt", startedAtMs = 1_000, updatedAtMs = 2_000, restarts = 1, pid = 4242)
        assertEquals(s, HostStateCodec.decode(HostStateCodec.encode(s)))
    }

    @Test fun defaultsEncodeExplicitly() {
        val text = HostStateCodec.encode(HostState())
        assertTrue("\"phase\":\"STOPPED\"" in text, text)
    }

    @Test fun garbageAndTornFilesReadAsNull() {
        assertNull(HostStateCodec.decode(""))
        assertNull(HostStateCodec.decode("{\"phase\":\"RUN"))
        assertNull(HostStateCodec.decode("{\"phase\":\"NOT_A_PHASE\"}"))
    }

    @Test fun ignoresUnknownKeysFromANewerWriter() {
        assertEquals(HostPhase.BOOTING, HostStateCodec.decode("{\"phase\":\"BOOTING\",\"future\":1}")?.phase)
    }

    @Test fun activeIsTrueForTheLivePhases() {
        for (p in HostPhase.values()) {
            val expect = p != HostPhase.STOPPED && p != HostPhase.ERROR
            assertEquals(expect, HostState(p).active, p.name)
        }
    }

    @Test fun parsesMint() {
        val g = GrantJson.parseMint(
            """{"grant":{"id":"g1","label":"cloud","created":10,"expires":100,"ops":["exec","vm"],"vms":["*"],"revoked":null,"last_used":null,"last_peer":null},"token":"azc1.abc"}""",
        ).getOrThrow()
        assertEquals("azc1.abc", g.token)
        assertEquals("g1", g.grant.id)
        assertTrue(g.grant.isLive(50))
        assertFalse(g.grant.isLive(100))
    }

    @Test fun parsesListAndRevoked() {
        val l = GrantJson.parseList("""[{"id":"a","label":"x","created":1,"expires":9,"ops":[],"vms":[],"revoked":5,"last_used":3,"last_peer":"p"}]""").getOrThrow()
        assertEquals(1, l.size)
        assertFalse(l[0].isLive(2))
        assertEquals("p", l[0].lastPeer)
        assertEquals(0, GrantJson.parseList("[]").getOrThrow().size)
    }

    @Test fun brokerErrorsBecomeHostException() {
        val e = GrantJson.parseMint("""{"error":"the broker is not running"}""").exceptionOrNull()
        assertIs<HostException>(e)
        assertEquals("the broker is not running", e.message)
        assertIs<HostException>(GrantJson.parseList("""{"error":"x"}""").exceptionOrNull())
    }
}
