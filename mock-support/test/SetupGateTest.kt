import dev.azula.mock.FakeTransport
import dev.azula.persist.AppSettings
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the first-run setup gate (`dev.azula.state.AzulaState.start()` /
 * `AzulaState.setupRequired` / `completeSetup` / `deferBackup`) --
 * azula-docs/openspec/changes/initial-setup-flow/, onboarding capability
 * "Setup Flow Gates Fresh Installs" (decision D1): the gate's decision matrix
 * (fresh install / pre-existing install upgrading / already-completed / mock
 * default), mid-flow resume across relaunches, completion/deferral
 * persistence (and that neither clobbers the other or unrelated settings),
 * and the restore-in-setup seam ([AzulaState.exportRecoveryPhrase] /
 * [AzulaState.importRecoveryPhrase]). Modeled on SettingsServiceTest.kt.
 */
class SetupGateTest {

    @Test
    fun freshInstallRequiresSetupAndDoesNotMarkCompleted() = runTest {
        val store = InMemorySettingsStore()
        val state = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false), settingsStore = store)
        state.start()
        runCurrent()

        assertEquals(true, state.setupRequired, "no persisted key and no setupCompleted flag must require setup")
        assertTrue(store.load()?.setupCompleted != true, "a fresh install must not be marked completed just by starting")
    }

    @Test
    fun preExistingInstallUpgradingSkipsSetupAndPersistsTheFlag() = runTest {
        val store = InMemorySettingsStore()
        val state = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = true), settingsStore = store)
        state.start()
        runCurrent()

        assertEquals(false, state.setupRequired, "a persisted identity key (pre-dating setup) must skip setup")
        assertEquals(true, store.load()?.setupCompleted, "the upgrade path must silently persist setupCompleted so this only happens once")
    }

    @Test
    fun alreadyCompletedSkipsSetupEvenWithoutAPersistedKey() = runTest {
        val store = InMemorySettingsStore(AppSettings(setupCompleted = true))
        // No persisted key at all -- proves the flag wins over hasPersistedIdentity,
        // not just that both agree.
        val state = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false), settingsStore = store)
        state.start()
        runCurrent()

        assertEquals(false, state.setupRequired, "an already-completed flag must win even when hasPersistedIdentity is false")
    }

    @Test
    fun mockDefaultTransportSkipsSetup() = runTest {
        // The default FakeTransport() (hasPersistedIdentity = true) is what every
        // `-mock` app and demo caller uses -- it must never show setup.
        val state = testAzulaState(backgroundScope, FakeTransport(), settingsStore = null)
        state.start()
        runCurrent()

        assertEquals(false, state.setupRequired, "the default FakeTransport must skip setup like every other mock/demo caller")
    }

    @Test
    fun midFlowQuitStillShowsSetupOnRelaunch() = runTest {
        val store = InMemorySettingsStore()
        val first = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false), settingsStore = store)
        first.start()
        runCurrent()
        assertEquals(true, first.setupRequired, "first launch must require setup")
        // Quit partway through setup: nothing marks completion, and the mock
        // transport never persists a key either (hasPersistedIdentity stays false).

        val second = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false), settingsStore = store)
        second.start()
        runCurrent()
        assertEquals(true, second.setupRequired, "relaunching mid-setup must show setup again, not the main app")
    }

    @Test
    fun completeSetupPersistsAndDropsTheGateAcrossRestart() = runTest {
        val store = InMemorySettingsStore()
        val first = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false), settingsStore = store)
        first.start()
        runCurrent()
        assertEquals(true, first.setupRequired)

        first.completeSetup()

        assertEquals(false, first.setupRequired, "completeSetup must drop the gate immediately")
        assertEquals(true, store.load()?.setupCompleted, "completeSetup must persist the completion flag")

        // A fresh AzulaState over the same (now-completed) store -- simulating an
        // app restart -- must see the completion, not require setup again.
        val second = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false), settingsStore = store)
        second.start()
        runCurrent()
        assertEquals(false, second.setupRequired, "completion must survive a restart")
    }

    @Test
    fun deferBackupPersistsAndSurvivesCompleteSetup() = runTest {
        val store = InMemorySettingsStore()
        val state = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false), settingsStore = store)
        state.start()
        runCurrent()

        state.deferBackup()

        assertTrue(state.backupDeferred, "deferBackup must flip the in-memory flag")
        assertEquals(true, store.load()?.backupDeferred, "deferBackup must persist immediately, independent of completing setup")

        state.completeSetup()

        val saved = store.load()
        assertEquals(true, saved?.backupDeferred, "completeSetup must not clobber a prior deferral")
        assertEquals(true, saved?.setupCompleted, "completeSetup must still persist its own flag")
    }

    @Test
    fun restoreInSetupAcceptsAValidPhraseAndRejectsGarbage() = runTest {
        val state = testAzulaState(backgroundScope, FakeTransport(hasPersistedIdentity = false))
        state.start()
        runCurrent()

        // FakeTransport.secretKeyBytes() is a fixed 32-byte array regardless of
        // hasPersistedIdentity, so a phrase is available immediately -- no bind needed.
        val phrase = requireNotNull(state.exportRecoveryPhrase()) { "FakeTransport must always expose a recovery phrase" }
        assertTrue(state.importRecoveryPhrase(phrase), "this device's own (valid) phrase must import successfully")
        assertFalse(state.importRecoveryPhrase("not a real phrase"), "garbage input must stay invalid, changing nothing")
    }

    @Test
    fun settingTerminalSmartInputDoesNotClobberTheSetupFlag() = runTest {
        val store = InMemorySettingsStore(AppSettings(setupCompleted = true, terminalSmartInput = true))
        val state = testAzulaState(backgroundScope, FakeTransport(), settingsStore = store)
        state.start()
        runCurrent()

        state.setTerminalSmartInput(false)

        assertEquals(
            AppSettings(terminalSmartInput = false, setupCompleted = true),
            store.load(),
            "toggling smart input must round-modify-write the settings blob, preserving the setup flag",
        )
    }
}
