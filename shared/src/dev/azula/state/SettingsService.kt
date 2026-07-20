package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.core.AppScope
import dev.azula.persist.AppSettings
import dev.azula.persist.SettingsStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Owns small standalone app settings backed by the injected [SettingsStore] --
 * currently just the terminal input mode. Extracted so `terminalSmartInput` has a
 * real dedicated store instead of piggybacking on `ProfileBook` (the personas
 * blob) -- see azula-docs/openspec/changes/settings-store-extraction/, "Settings live in ProfileBook".
 *
 * **Migration.** Pre-`SettingsStore` installs only ever wrote the flag into
 * `ProfileBook.terminalSmartInput` (loaded by [PersonaService]). [load] takes that
 * value as [legacyTerminalSmartInput]: if [settingsStore] has never been written
 * (`load()` returns null -- a fresh install, or an upgrade that hasn't migrated
 * yet), the legacy value is adopted *and* immediately persisted into the new
 * store, so the migration branch only ever runs once per install. A genuinely
 * fresh install has no legacy value either, but `PersonaService`'s snapshot
 * defaults to `true` (matching `AppSettings`'s own default), so the outcome is the
 * same either way.
 */
@Inject
@SingleIn(AppScope::class)
class SettingsService(private val settingsStore: SettingsStore?) {
    /** Terminal input mode: smart (swipe/autocorrect/suggestions) vs. the legacy
     *  raw fallback -- see [dev.azula.persist.AppSettings.terminalSmartInput]. */
    var terminalSmartInput by mutableStateOf(true)
        private set

    fun load(legacyTerminalSmartInput: Boolean) {
        val saved = settingsStore?.load()
        if (saved != null) {
            terminalSmartInput = saved.terminalSmartInput
        } else {
            // Nothing in the new store yet: honor the legacy ProfileBook value (a
            // no-op default of `true` for a truly fresh install) and persist it so
            // this branch doesn't run again on the next launch.
            terminalSmartInput = legacyTerminalSmartInput
            settingsStore?.save(AppSettings(legacyTerminalSmartInput))
        }
    }

    fun setSmartInput(enabled: Boolean) {
        terminalSmartInput = enabled
        settingsStore?.save(AppSettings(enabled))
    }
}
