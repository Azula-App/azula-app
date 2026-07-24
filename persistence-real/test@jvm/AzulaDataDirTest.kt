package dev.azula.persist

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * multi-device-identity testability gap: `jvm-app` hardcoded `~/.azula` for every
 * persistence-real store, with no way to redirect a second, fully isolated desktop
 * instance's settings/profiles/blobs/messages/invitations/exports elsewhere.
 * `AZULA_DATA_DIR` (see [azulaHomeDir]) fixes that. These tests pin the three
 * required safety properties: unset is byte-for-byte the old behavior, the override
 * redirects file storage, and two different overrides never interfere -- the last
 * one proven with a real on-disk round trip through [FileBlobStore], not just string
 * comparison of the resolved paths.
 *
 * `env` lookups are injected fakes throughout, never real `System.setenv` mutation --
 * the JVM has no supported way to set an env var for a running process.
 */
class AzulaDataDirTest {

    @Test
    fun unsetFallsBackToTheRealUserHome() {
        assertEquals(System.getProperty("user.home"), azulaHomeDir { null })
    }

    @Test
    fun blankOverrideIsTreatedAsUnset() {
        assertEquals(System.getProperty("user.home"), azulaHomeDir { "" })
    }

    @Test
    fun twoDifferentOverridesResolveToDifferentHomes() {
        val a = azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-a" else null }
        val b = azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-b" else null }
        assertNotEquals(a, b)
    }

    // -- Every *File()/*Dir() helper in this module redirects the same way -----------

    @Test
    fun everyStoreLocationUnsetMatchesItsOriginalPath() {
        val home = System.getProperty("user.home")
        assertEquals(File(home, ".azula/settings.json"), settingsFile { null })
        assertEquals(File(home, ".azula/profiles.json"), profileFile { null })
        assertEquals(File(home, ".azula/invitations.json"), invitationsFile { null })
        assertEquals(File(home, ".azula"), blobStoreDir { null })
        assertEquals(File(home, ".azula"), messageStoreDir { null })
        assertEquals(File(home, "Downloads/Azula"), mediaExportDir { null })
    }

    @Test
    fun everyStoreLocationRedirectsUnderTheOverride() {
        val env: (String) -> String? = { if (it == "AZULA_DATA_DIR") "/scratch/azula-a" else null }
        assertEquals(File("/scratch/azula-a", ".azula/settings.json"), settingsFile(env))
        assertEquals(File("/scratch/azula-a", ".azula/profiles.json"), profileFile(env))
        assertEquals(File("/scratch/azula-a", ".azula/invitations.json"), invitationsFile(env))
        assertEquals(File("/scratch/azula-a", ".azula"), blobStoreDir(env))
        assertEquals(File("/scratch/azula-a", ".azula"), messageStoreDir(env))
        assertEquals(File("/scratch/azula-a", "Downloads/Azula"), mediaExportDir(env))
    }

    // -- A real on-disk isolation proof, not just path-string comparison ------------

    private val dirA = createTempDirectory("azula-data-dir-a").toFile()
    private val dirB = createTempDirectory("azula-data-dir-b").toFile()

    @AfterTest
    fun cleanup() {
        dirA.deleteRecursively()
        dirB.deleteRecursively()
    }

    @Test
    fun twoOverridesGiveFullyIndependentBlobStoresOnDisk() {
        val storeA = FileBlobStore(blobStoreDir { if (it == "AZULA_DATA_DIR") dirA.path else null })
        val storeB = FileBlobStore(blobStoreDir { if (it == "AZULA_DATA_DIR") dirB.path else null })

        storeA.openWriter("shared-id").use { it.write(byteArrayOf(1, 2, 3)) }

        assertTrue(storeA.exists("shared-id"), "instance A must see the blob it wrote")
        assertFalse(storeB.exists("shared-id"), "instance B must not see instance A's blob -- same id, different scratch dir")
    }

    @Test
    fun twoOverridesGiveFullyIndependentSettingsFilesOnDisk() {
        val fileA = settingsFile { if (it == "AZULA_DATA_DIR") dirA.path else null }
        val fileB = settingsFile { if (it == "AZULA_DATA_DIR") dirB.path else null }

        fileA.parentFile.mkdirs()
        fileA.writeText("""{"instance":"a"}""")

        assertTrue(fileA.exists())
        assertFalse(fileB.exists(), "writing instance A's settings must not create anything under instance B's scratch dir")
    }
}
