package dev.azula.net

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * multi-device-identity task 3.1: the root secret and node key live under distinct entries
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
        node = FileSecretKeyStore(File(tempDir, "endpoint.key").absolutePath),
        root = FileSecretKeyStore(File(tempDir, "root.key").absolutePath),
    )

    @Test
    fun nodeKeyAndRootSecretAreIndependentlyReadableAndWritable() {
        val store = newStore()
        val nodeKey = ByteArray(32) { 1 }
        val rootSecret = ByteArray(32) { 2 }

        store.saveNodeKey(nodeKey)
        store.saveRootSecret(rootSecret)

        assertEquals(nodeKey.toList(), store.loadNodeKey()?.toList())
        assertEquals(rootSecret.toList(), store.loadRootSecret()?.toList())
    }

    @Test
    fun writingOneEntryDoesNotClobberTheOther() {
        val store = newStore()
        val nodeKey = ByteArray(32) { 3 }
        store.saveNodeKey(nodeKey)

        // The root entry is untouched by a node-key write (and vice versa) -- distinct
        // entries, not two views onto the same slot.
        assertNull(store.loadRootSecret())
        assertEquals(nodeKey.toList(), store.loadNodeKey()?.toList())

        val rootSecret = ByteArray(32) { 4 }
        store.saveRootSecret(rootSecret)
        assertEquals(nodeKey.toList(), store.loadNodeKey()?.toList(), "saving root must not disturb the node entry")
        assertEquals(rootSecret.toList(), store.loadRootSecret()?.toList())
    }

    @Test
    fun aNodeKeyWithNoRootSecretIsAValidState() {
        // The QR-linked-device shape: a node key exists, no root secret ever has -- this
        // must read as "no root secret" (null), never fall back to the node key.
        val store = newStore()
        store.saveNodeKey(ByteArray(32) { 5 })

        assertNull(store.loadRootSecret())
    }

    @Test
    fun bothEntriesAbsentOnAFreshStore() {
        val store = newStore()

        assertNull(store.loadNodeKey())
        assertNull(store.loadRootSecret())
    }

    @Test
    fun rootSecretCanExistWithoutBeingRewrittenByANodeKeyRead() {
        // Reads must be side-effect-free: repeatedly reading the node entry must never
        // populate or change the root entry.
        val store = newStore()
        store.saveRootSecret(ByteArray(32) { 6 })
        repeat(3) { store.loadNodeKey() }

        assertTrue(ByteArray(32) { 6 }.contentEquals(store.loadRootSecret()))
        assertNull(store.loadNodeKey())
    }
}
