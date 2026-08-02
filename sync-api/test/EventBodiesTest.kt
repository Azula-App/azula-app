package dev.azula.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Round-trip + decode-failure coverage for the per-kind JSON bodies (EventBodies.kt). */
class EventBodiesTest {
    @Test
    fun messageOutRoundTrips() {
        val body = MessageOutBody(conversation = "cafe", text = "hi", id = "abc123")
        assertEquals(body, decodeMessageOutBody(encodeMessageOutBody(body)))
    }

    @Test
    fun messageInUsesSnakeCaseFromDevicePkKey() {
        val body = MessageInBody(conversation = "cafe", fromDevicePk = "aa11", text = "hi", id = null)
        val json = encodeMessageInBody(body).decodeToString()
        assertEquals(true, json.contains("\"from_device_pk\":\"aa11\""))
        assertEquals(body, decodeMessageInBody(encodeMessageInBody(body)))
    }

    @Test
    fun readMarkerUsesSnakeCaseUpToLamportKey() {
        val body = ReadMarkerBody(conversation = "cafe", upToLamport = 42)
        val json = encodeReadMarkerBody(body).decodeToString()
        assertEquals(true, json.contains("\"up_to_lamport\":42"))
        assertEquals(body, decodeReadMarkerBody(encodeReadMarkerBody(body)))
    }

    @Test
    fun contactKeyBodyPrefersRootPkOverEndpointId() {
        val body = ContactKeyBody(rootPk = "root-hex", endpointId = "endpoint-hex", name = "Alice")
        assertEquals("root-hex", body.key)
        assertEquals(body, decodeContactKeyBody(encodeContactKeyBody(body)))
    }

    @Test
    fun contactKeyBodyFallsBackToEndpointIdWhenRootPkAbsent() {
        val body = ContactKeyBody(endpointId = "endpoint-hex")
        assertEquals("endpoint-hex", body.key)
    }

    @Test
    fun deviceAddAndRevokeBodiesRoundTrip() {
        val add = DeviceAddBody(cert = "azd-fake-encoded")
        assertEquals(add, decodeDeviceAddBody(encodeDeviceAddBody(add)))
        val revoke = DeviceRevokeBody(revocation = "azr-fake-encoded")
        assertEquals(revoke, decodeDeviceRevokeBody(encodeDeviceRevokeBody(revoke)))
    }

    @Test
    fun profileUpdateRoundTripsWithPartialFields() {
        val onlyName = ProfileUpdateBody(name = "Alice", description = null)
        assertEquals(onlyName, decodeProfileUpdateBody(encodeProfileUpdateBody(onlyName)))
        val onlyDescription = ProfileUpdateBody(name = null, description = "Bio")
        assertEquals(onlyDescription, decodeProfileUpdateBody(encodeProfileUpdateBody(onlyDescription)))
    }

    @Test
    fun malformedJsonDecodesToNullRatherThanThrowing() {
        assertNull(decodeMessageOutBody("not json".encodeToByteArray()))
        assertNull(decodeContactKeyBody("{".encodeToByteArray()))
    }

    // -- Agent kinds (cli-multi-session-relay) -------------------------------------------------

    @Test
    fun agentInRoundTripsAndUsesSnakeCaseFromNameKey() {
        val body = AgentInBody(conversation = "cafebabe", text = "hello from claude", id = "abc123", fromName = "Claude")
        val json = encodeAgentInBody(body).decodeToString()
        assertEquals(true, json.contains("\"from_name\":\"Claude\""))
        assertEquals(body, decodeAgentInBody(encodeAgentInBody(body)))
    }

    @Test
    fun agentInOmitsAbsentIdAndFromName() {
        val body = AgentInBody(conversation = "cafebabe", text = "hi")
        val json = encodeAgentInBody(body).decodeToString()
        assertEquals(false, json.contains("id"))
        assertEquals(false, json.contains("from_name"))
        assertEquals(body, decodeAgentInBody(encodeAgentInBody(body)))
    }

    @Test
    fun agentOutRoundTrips() {
        val body = AgentOutBody(conversation = "cafebabe", text = "on it", id = "xyz789")
        assertEquals(body, decodeAgentOutBody(encodeAgentOutBody(body)))
    }

    @Test
    fun agentBodiesFieldOrderMatchesThePinnedWireFormat() {
        // account-sync spec's Event Kinds delta pins property order exactly:
        // agent_in {conversation, text, id?, from_name?}; agent_out {conversation, text, id?}.
        val inJson = encodeAgentInBody(AgentInBody("c", "t", "i", "n")).decodeToString()
        assertEquals("""{"conversation":"c","text":"t","id":"i","from_name":"n"}""", inJson)
        val outJson = encodeAgentOutBody(AgentOutBody("c", "t", "i")).decodeToString()
        assertEquals("""{"conversation":"c","text":"t","id":"i"}""", outJson)
    }
}
