package dev.azula.net

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [MacKeychainSecretKeyStore] against the *real* macOS login Keychain
 * via the `security` CLI — skipped everywhere else, since there's nothing to
 * exercise. Each test uses a unique service/account so runs never collide with
 * a real azula install's identity, and every test cleans up its own Keychain
 * entry.
 */
class MacKeychainSecretKeyStoreTest {
    private val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true

    private val tempDir = createTempDirectory("keychain-test").toFile()
    private val service = "app.azula.identity.test.${System.nanoTime()}"
    private val account = "endpoint_key_test"

    private fun filePath() = File(tempDir, "endpoint.key").absolutePath
    private fun newStore() = MacKeychainSecretKeyStore(service, account, FileSecretKeyStore(filePath()))

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
        if (isMac) {
            runCatching {
                ProcessBuilder("security", "delete-generic-password", "-a", account, "-s", service)
                    .start().waitFor()
            }
        }
    }

    @Test
    fun saveThenLoadRoundTrips() {
        if (!isMac) return
        val store = newStore()
        val key = ByteArray(32) { it.toByte() }

        store.save(key)
        val loaded = store.load()

        assertEquals(key.toList(), loaded?.toList())
    }

    @Test
    fun saveOverwritesPriorValue() {
        if (!isMac) return
        val store = newStore()
        store.save(ByteArray(32) { 1 })
        store.save(ByteArray(32) { 2 })

        assertEquals(List(32) { 2.toByte() }, store.load()?.toList())
    }

    @Test
    fun migratesPlaintextFileIntoKeychainAndVerifiesBeforeDeleting() {
        if (!isMac) return
        val key = ByteArray(32) { (it * 3).toByte() }
        val file = File(filePath())
        file.parentFile.mkdirs()
        file.writeBytes(key)
        assertTrue(file.exists())

        val store = newStore()
        val loaded = store.load()

        assertEquals(key.toList(), loaded?.toList())
        // Verified migration: the plaintext file is gone, and a fresh store
        // instance (no in-memory state) reads the same bytes back from the
        // Keychain alone.
        assertFalse(file.exists())
        assertEquals(key.toList(), newStore().load()?.toList())
    }

    @Test
    fun loadReturnsNullWhenNeitherKeychainNorFileHasAKey() {
        if (!isMac) return
        assertNull(newStore().load())
    }
}
