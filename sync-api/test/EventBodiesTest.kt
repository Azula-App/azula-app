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
    fun contactKeyBodyPrefersRootPkOverNodeId() {
        val body = ContactKeyBody(rootPk = "root-hex", nodeId = "node-hex", name = "Alice")
        assertEquals("root-hex", body.key)
        assertEquals(body, decodeContactKeyBody(encodeContactKeyBody(body)))
    }

    @Test
    fun contactKeyBodyFallsBackToNodeIdWhenRootPkAbsent() {
        val body = ContactKeyBody(nodeId = "node-hex")
        assertEquals("node-hex", body.key)
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
}
