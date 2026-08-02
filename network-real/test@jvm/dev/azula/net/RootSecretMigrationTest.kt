package dev.azula.net

import app.azula.iroh.ed25519PublicFromSecret
import dev.azula.core.DeviceCertCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * multi-device-identity task 3.2: on first launch with an existing endpoint key and no root
 * entry, the endpoint secret becomes the root secret unchanged and a self-certificate with
 * `device_pk == root_pk` is issued (design.md Migration Plan step 2). [adoptRootSecretIfNeeded]
 * is the storage-aware wrapper around [dev.azula.core.RootIdentityMigration] that
 * [IrohFfiTransport.bind] calls; this exercises it end to end against the real
 * [IrohKmpEd25519] (rather than `core`'s test-only fake), the same real crypto production
 * uses.
 */
class RootSecretMigrationTest {
    private val endpointSecret = ByteArray(32) { (it + 11).toByte() }
    private val devicePk = ed25519PublicFromSecret(endpointSecret)
    private val now = 1_700_000_000L

    @Test
    fun adoptsTheEndpointSecretAsRootWhenNoneIsPresent() {
        val store = InMemorySecretKeyStore()

        val cert = adoptRootSecretIfNeeded(store, endpointSecret, devicePk, now)

        assertTrue(endpointSecret.contentEquals(store.loadRootSecret()), "the endpoint secret must become the root secret unchanged")
        requireNotNull(cert) { "migration must produce a self-certificate" }
        assertTrue(cert.rootPk.contentEquals(cert.devicePk), "device_pk must equal root_pk")
        assertTrue(cert.rootPk.contentEquals(devicePk))
        assertTrue(DeviceCertCodec.verify(cert, IrohKmpEd25519, nowEpochSeconds = now), "the self-cert must verify against the real Ed25519")
    }

    @Test
    fun isANoOpWhenARootSecretAlreadyExists() {
        val store = InMemorySecretKeyStore()
        val existingRoot = ByteArray(32) { 0x7f }
        store.saveRootSecret(existingRoot)

        val cert = adoptRootSecretIfNeeded(store, endpointSecret, devicePk, now)

        assertNull(cert, "migration must not re-run once a root secret exists")
        assertEquals(existingRoot.toList(), store.loadRootSecret()?.toList(), "an existing root secret must be left alone")
    }

    @Test
    fun neverTouchesTheEndpointKeyEntry() {
        val store = InMemorySecretKeyStore()
        val endpointKey = ByteArray(32) { 0x22 }
        store.saveEndpointKey(endpointKey)

        adoptRootSecretIfNeeded(store, endpointSecret, devicePk, now)

        assertEquals(endpointKey.toList(), store.loadEndpointKey()?.toList(), "migration must never touch the endpoint-key entry")
    }
}

/** A trivial in-memory [SecretKeyStore] fake -- no real platform key-at-rest mechanism
 *  needed to test the storage decision itself. */
private class InMemorySecretKeyStore : SecretKeyStore {
    private var endpoint: ByteArray? = null
    private var root: ByteArray? = null

    override fun loadEndpointKey(): ByteArray? = endpoint
    override fun saveEndpointKey(bytes: ByteArray) { endpoint = bytes }
    override fun loadRootSecret(): ByteArray? = root
    override fun saveRootSecret(bytes: ByteArray) { root = bytes }
}
