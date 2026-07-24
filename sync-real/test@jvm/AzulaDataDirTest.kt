package dev.azula.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * multi-device-identity testability gap: `jvm-app` hardcoded `~/.azula` for the
 * per-device event log too (`FileEventLogStore`'s base dir), with no way to redirect
 * a second, fully isolated desktop instance's sync state elsewhere. `AZULA_DATA_DIR`
 * (see [azulaHomeDir], duplicated from network-real's identically-named/documented
 * helper since sync-real is a separate Amper module) fixes that.
 *
 * `env` lookups are injected fakes, never real `System.setenv` mutation -- the JVM
 * has no supported way to set an env var for a running process. [FileEventLogStoreTest]
 * already covers the store's on-disk behavior once given a base dir; these tests only
 * pin the override/unset resolution this module adds.
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
    fun overrideWinsOverTheRealUserHome() {
        assertEquals("/scratch/azula-a", azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-a" else null })
    }

    @Test
    fun twoDifferentOverridesResolveToDifferentEventLogRoots() {
        val a = File(azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-a" else null }, ".azula")
        val b = File(azulaHomeDir { if (it == "AZULA_DATA_DIR") "/scratch/azula-b" else null }, ".azula")
        assertNotEquals(a, b)
    }
}
