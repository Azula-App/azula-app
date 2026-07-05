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
 * Covers `dev.azula.state.SettingsService` (azula-docs/docs/tech-debt.md §6,
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
        assertEquals(AppSettings(terminalSmartInput = false), store.load(), "the flip must persist to the SettingsStore")
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
            AppSettings(terminalSmartInput = false),
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
