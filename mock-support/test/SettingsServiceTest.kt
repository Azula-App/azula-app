import dev.azula.mock.FakeTransport
import dev.azula.persist.AppSettings
import dev.azula.persist.ProfileBook
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `dev.azula.state.SettingsService` (azula-docs/openspec/changes/settings-store-extraction/,
 * "Settings live in ProfileBook") -- the dedicated `SettingsStore` extracted so
 * `terminalSmartInput` no longer piggybacks on the personas blob: the
 * persist/restore round-trip through `AzulaState.terminalSmartInput` /
 * `setTerminalSmartInput`, and the one-time migration of a pre-`SettingsStore`
 * install's value out of `ProfileBook`.
 */
class SettingsServiceTest {

    @Test
    fun defaultsToSmartInputWithNoStoreAtAll() = runTest {
        val azulaState = testAzulaState(backgroundScope, FakeTransport())
        azulaState.start()
        runCurrent()

        assertTrue(azulaState.terminalSmartInput, "default (no SettingsStore, no legacy ProfileBook) must be smart input on")
    }

    @Test
    fun setTerminalSmartInputPersistsToTheSettingsStore() = runTest {
        val store = InMemorySettingsStore()
        val azulaState = testAzulaState(backgroundScope, FakeTransport(), settingsStore = store)
        azulaState.start()
        runCurrent()
        assertTrue(azulaState.terminalSmartInput)

        azulaState.setTerminalSmartInput(false)

        assertFalse(azulaState.terminalSmartInput, "state.terminalSmartInput must reflect the change immediately")
        // start() also records the setup gate's decision: FakeTransport reports a
        // persisted identity but no setupCompleted flag exists, so the "pre-existing
        // install upgrading" branch writes setupCompleted = true (onboarding D1).
        // The smart-input flip must round-modify-write that blob, not clobber it.
        assertEquals(AppSettings(terminalSmartInput = false, setupCompleted = true), store.load(), "the flip must persist to the SettingsStore without clobbering the setup flag")
    }

    @Test
    fun restoringFromAFreshAzulaStateReadsThePersistedSettingsStoreValue() = runTest {
        val store = InMemorySettingsStore()
        val first = testAzulaState(backgroundScope, FakeTransport(), settingsStore = store)
        first.start()
        runCurrent()
        first.setTerminalSmartInput(false)

        // A brand-new AzulaState over the same (now-populated) store -- simulating
        // an app restart -- must restore the persisted value, not the default.
        val second = testAzulaState(backgroundScope, FakeTransport(), settingsStore = store)
        second.start()
        runCurrent()

        assertFalse(second.terminalSmartInput, "a fresh AzulaState must restore the persisted setting")
    }

    @Test
    fun migratesTheLegacyProfileBookValueWhenTheSettingsStoreHasNeverBeenWritten() = runTest {
        // Simulates a pre-SettingsStore install: the flag only ever lived in
        // ProfileBook, and the new SettingsStore is empty (fresh, never saved).
        val profileStore = InMemoryProfileStore(ProfileBook(terminalSmartInput = false))
        val settingsStore = InMemorySettingsStore(initial = null)
        val azulaState = testAzulaState(
            backgroundScope,
            FakeTransport(),
            settingsStore = settingsStore,
            profileStore = profileStore,
        )

        azulaState.start()
        runCurrent()

        assertFalse(azulaState.terminalSmartInput, "the legacy ProfileBook value must be honored on first read")
        assertEquals(
            // setupCompleted = true: start()'s setup-gate decision marks a mock (which
            // reports a persisted identity) as already onboarded -- see onboarding D1.
            AppSettings(terminalSmartInput = false, setupCompleted = true),
            settingsStore.load(),
            "migration must persist into the new SettingsStore so it only runs once",
        )
    }

    @Test
    fun settingsStoreValueWinsOverAStaleLegacyProfileBookValue() = runTest {
        // Once the SettingsStore has its own value, ProfileBook's is stale/irrelevant
        // (PersonaService only round-trips it verbatim -- see its doc comment).
        val profileStore = InMemoryProfileStore(ProfileBook(terminalSmartInput = false))
        val settingsStore = InMemorySettingsStore(initial = AppSettings(terminalSmartInput = true))
        val azulaState = testAzulaState(
            backgroundScope,
            FakeTransport(),
            settingsStore = settingsStore,
            profileStore = profileStore,
        )

        azulaState.start()
        runCurrent()

        assertTrue(azulaState.terminalSmartInput, "an already-migrated SettingsStore value must win over ProfileBook")
    }
}
