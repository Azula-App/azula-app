package dev.azula.net

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // The tests below inject a fake `security` runner so the miss-vs-unavailable
    // distinction is exercised deterministically on ANY platform (no real, and no
    // real *locked*, Keychain needed).

    private fun storeWith(run: (List<String>) -> Pair<Int, String>?) =
        MacKeychainSecretKeyStore(service, account, FileSecretKeyStore(filePath()), run)

    @Test
    fun unreadableKeychainWithNoPlaintextThrowsInsteadOfMintingANewIdentity() {
        // A non-44 `security` error (e.g. errSecInteractionNotAllowed on a locked
        // keychain) with no plaintext fallback must THROW, not return null — null
        // would let bind() mint a brand-new identity and orphan the real key.
        val store = storeWith { args ->
            if (args.first() == "find-generic-password") 51 to "" else 0 to ""
        }
        assertFailsWith<KeychainUnavailableException> { store.load() }
    }

    @Test
    fun timeoutIsTreatedAsUnavailableNotAsAMiss() {
        // runSecurity returning null models a timeout / couldn't-run; that's an
        // access failure, not "no such item", so with no plaintext it must throw.
        val store = storeWith { null }
        assertFailsWith<KeychainUnavailableException> { store.load() }
    }

    @Test
    fun unreadableKeychainFallsBackToExistingPlaintextWithoutMigratingOrDeleting() {
        val key = ByteArray(32) { (it + 7).toByte() }
        val file = File(filePath()).apply { parentFile.mkdirs(); writeBytes(key) }
        var savedToKeychain = false
        val store = storeWith { args ->
            when (args.first()) {
                "find-generic-password" -> 51 to "" // unavailable, not a clean miss
                "add-generic-password" -> { savedToKeychain = true; 0 to "" }
                else -> 0 to ""
            }
        }
        assertEquals(key.toList(), store.load()?.toList())
        assertTrue(file.exists(), "must not delete the plaintext while the Keychain is flaky")
        assertFalse(savedToKeychain, "must not migrate into a flaky Keychain")
    }

    @Test
    fun cleanMissWithNoPlaintextReturnsNull() {
        val store = storeWith { args ->
            if (args.first() == "find-generic-password") 44 to "" else 0 to ""
        }
        assertNull(store.load(), "a genuine errSecItemNotFound with no file is a fresh install")
    }

    @Test
    fun cleanMissMigratesPlaintextIntoKeychainAndDeletesTheFile() {
        val key = ByteArray(32) { (it * 5).toByte() }
        val file = File(filePath()).apply { parentFile.mkdirs(); writeBytes(key) }
        val fakeKeychain = HashMap<String, String>() // service -> stored base64
        val store = storeWith { args ->
            when (args.first()) {
                "find-generic-password" -> fakeKeychain[service]?.let { 0 to it } ?: (44 to "")
                "add-generic-password" -> { fakeKeychain[service] = args.last(); 0 to "" }
                else -> 0 to ""
            }
        }
        assertEquals(key.toList(), store.load()?.toList())
        assertFalse(file.exists(), "verified migration deletes the plaintext")
        assertEquals(key.toList(), store.load()?.toList(), "second read comes from the Keychain alone")
    }
}
