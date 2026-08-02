package dev.azula.net

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * multi-device-identity task 3.1: the root secret and endpoint key live under distinct entries
 * (see the `identity` capability's modified Per-Platform Key-at-Rest Storage requirement),
 * modeled here as a nullable [SingleSlotKeyStore] per entry composed by
 * [LayeredSecretKeyStore]. Exercised against [FileSecretKeyStore] (desktop's off-mac
 * backing) since it's the simplest real [SingleSlotKeyStore] -- the composition under test
 * is backing-agnostic.
 */
class LayeredSecretKeyStoreTest {
    private val tempDir = createTempDirectory("secret-key-store-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun newStore(): LayeredSecretKeyStore = LayeredSecretKeyStore(
        endpoint = FileSecretKeyStore(File(tempDir, "endpoint.key").absolutePath),
        root = FileSecretKeyStore(File(tempDir, "root.key").absolutePath),
    )

    @Test
    fun endpointKeyAndRootSecretAreIndependentlyReadableAndWritable() {
        val store = newStore()
        val endpointKey = ByteArray(32) { 1 }
        val rootSecret = ByteArray(32) { 2 }

        store.saveEndpointKey(endpointKey)
        store.saveRootSecret(rootSecret)

        assertEquals(endpointKey.toList(), store.loadEndpointKey()?.toList())
        assertEquals(rootSecret.toList(), store.loadRootSecret()?.toList())
    }

    @Test
    fun writingOneEntryDoesNotClobberTheOther() {
        val store = newStore()
        val endpointKey = ByteArray(32) { 3 }
        store.saveEndpointKey(endpointKey)

        // The root entry is untouched by a endpoint-key write (and vice versa) -- distinct
        // entries, not two views onto the same slot.
        assertNull(store.loadRootSecret())
        assertEquals(endpointKey.toList(), store.loadEndpointKey()?.toList())

        val rootSecret = ByteArray(32) { 4 }
        store.saveRootSecret(rootSecret)
        assertEquals(endpointKey.toList(), store.loadEndpointKey()?.toList(), "saving root must not disturb the endpoint entry")
        assertEquals(rootSecret.toList(), store.loadRootSecret()?.toList())
    }

    @Test
    fun aEndpointKeyWithNoRootSecretIsAValidState() {
        // The QR-linked-device shape: a endpoint key exists, no root secret ever has -- this
        // must read as "no root secret" (null), never fall back to the endpoint key.
        val store = newStore()
        store.saveEndpointKey(ByteArray(32) { 5 })

        assertNull(store.loadRootSecret())
    }

    @Test
    fun bothEntriesAbsentOnAFreshStore() {
        val store = newStore()

        assertNull(store.loadEndpointKey())
        assertNull(store.loadRootSecret())
    }

    @Test
    fun rootSecretCanExistWithoutBeingRewrittenByAEndpointKeyRead() {
        // Reads must be side-effect-free: repeatedly reading the endpoint entry must never
        // populate or change the root entry.
        val store = newStore()
        store.saveRootSecret(ByteArray(32) { 6 })
        repeat(3) { store.loadEndpointKey() }

        assertTrue(ByteArray(32) { 6 }.contentEquals(store.loadRootSecret()))
        assertNull(store.loadEndpointKey())
    }
}
