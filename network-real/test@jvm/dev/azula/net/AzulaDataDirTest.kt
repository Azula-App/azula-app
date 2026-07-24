package dev.azula.net

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * multi-device-identity testability gap: `jvm-app` hardcoded `~/.azula` for files and
 * the macOS login Keychain (`app.azula.identity`) for keys, with no way to stand up a
 * second, fully isolated desktop instance for real hardware/network testing without
 * mutating the developer's real identity. `AZULA_DATA_DIR` (see [azulaHomeDir] and
 * [defaultDesktopKeyStore] in `IrohFfiTransport.kt`) fixes that; these tests pin its
 * three required safety properties: unset is byte-for-byte the old behavior, the
 * override redirects both files and keys, and two different overrides never interfere.
 *
 * `env` lookups are injected fakes throughout, never real `System.setenv` mutation --
 * the JVM has no supported way to set an env var for a running process.
 */
class AzulaDataDirTest {

    // -------------------------------------------------------------------------------
    // azulaHomeDir: the shared path-resolution primitive
    // -------------------------------------------------------------------------------

    @Test
    fun unsetFallsBackToTheRealUserHome() {
        assertEquals(System.getProperty("user.home"), azulaHomeDir { null })
    }

    @Test
    fun blankOverrideIsTreatedAsUnset() {
        // Defends against `AZULA_DATA_DIR=` (set but empty) silently redirecting
        // everything to the filesystem root via `File("", ...)`.
        assertEquals(System.getProperty("user.home"), azulaHomeDir { "" })
    }

    @Test
    fun overrideWinsOverTheRealUserHome() {
        assertEquals("/scratch/azula-a", azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-a" else null })
    }

    @Test
    fun onlyTheAzulaDataDirVariableIsConsulted() {
        // A same-shaped but differently-named var must never be picked up by accident.
        assertEquals(System.getProperty("user.home"), azulaHomeDir { if (it == "HOME") "/scratch/wrong" else null })
    }

    @Test
    fun twoDifferentOverridesResolveToDifferentHomes() {
        val a = azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-a" else null }
        val b = azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-b" else null }
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------------
    // peerFile: a representative plain-file site in this module
    // -------------------------------------------------------------------------------

    @Test
    fun peerFileUnsetMatchesTheOriginalPath() {
        val expected = java.io.File(System.getProperty("user.home"), ".azula/peers.txt")
        assertEquals(expected, peerFile { null })
    }

    @Test
    fun peerFileRedirectsUnderTheOverride() {
        val expected = java.io.File("/scratch/azula-a", ".azula/peers.txt")
        assertEquals(expected, peerFile { if (it == "AZULA_DATA_DIR") "/scratch/azula-a" else null })
    }

    // -------------------------------------------------------------------------------
    // defaultDesktopKeyStore: the Keychain decision
    // -------------------------------------------------------------------------------

    @Test
    fun unsetOnMacUsesTheRealKeychain() {
        val store = defaultDesktopKeyStore(env = { null }, isMac = true) as LayeredSecretKeyStore
        assertTrue(store.node is MacKeychainSecretKeyStore, "unset must keep today's real-Keychain behavior on macOS")
        assertTrue(store.root is MacKeychainSecretKeyStore)
    }

    @Test
    fun unsetOnNonMacUsesFileStorageAsBefore() {
        val store = defaultDesktopKeyStore(env = { null }, isMac = false) as LayeredSecretKeyStore
        assertTrue(store.node is FileSecretKeyStore)
        assertTrue(store.root is FileSecretKeyStore)
    }

    @Test
    fun noArgsCallMirrorsTheRealEnvironment() {
        // Sanity: the production call site (`createTransport`) uses no args at all --
        // confirm the defaults reproduce whatever the real OS/environment says, so
        // nothing about wiring the override params changed the no-override path.
        val store = defaultDesktopKeyStore() as LayeredSecretKeyStore
        val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
        val overridden = System.getenv("AZULA_DATA_DIR")?.isNotBlank() == true
        if (isMac && !overridden) {
            assertTrue(store.node is MacKeychainSecretKeyStore)
        } else {
            assertTrue(store.node is FileSecretKeyStore)
        }
    }

    @Test
    fun overrideBypassesTheRealKeychainEvenOnMac() {
        // The critical safety check the task calls out explicitly: redirecting only
        // files would be a trap, since the app would still find (and could mutate)
        // the real identity's Keychain entries. Confirm the override path never
        // constructs a MacKeychainSecretKeyStore -- i.e. the real "app.azula.identity"
        // service is never referenced -- even when isMac is true.
        val dir = createTempDirectory("keystore-override-test").toFile()
        try {
            val store = defaultDesktopKeyStore(
                env = { if (it == "AZULA_DATA_DIR") dir.path else null },
                isMac = true,
            ) as LayeredSecretKeyStore
            assertTrue(store.node is FileSecretKeyStore, "override must never touch the real Keychain")
            assertTrue(store.root is FileSecretKeyStore, "override must never touch the real Keychain")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun twoDifferentOverridesProduceIndependentNonInterferingKeyStorage() {
        val dirA = createTempDirectory("keystore-a").toFile()
        val dirB = createTempDirectory("keystore-b").toFile()
        try {
            val storeA = defaultDesktopKeyStore(env = { if (it == "AZULA_DATA_DIR") dirA.path else null }, isMac = true)
            val storeB = defaultDesktopKeyStore(env = { if (it == "AZULA_DATA_DIR") dirB.path else null }, isMac = true)

            storeA.saveNodeKey(ByteArray(32) { 1 })
            storeB.saveNodeKey(ByteArray(32) { 2 })

            assertEquals(List(32) { 1.toByte() }, storeA.loadNodeKey()?.toList(), "instance A must read back its own key")
            assertEquals(List(32) { 2.toByte() }, storeB.loadNodeKey()?.toList(), "instance B must read back its own key")

            // And the two scratch dirs really are on disk as distinct roots.
            assertTrue(dirA.resolve(".azula/endpoint.key").exists())
            assertTrue(dirB.resolve(".azula/endpoint.key").exists())
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }
}
