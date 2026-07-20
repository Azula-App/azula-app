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
    /** The last-loaded/saved settings blob. Every mutator read-modify-writes this
     *  rather than constructing a fresh [AppSettings], so sibling fields (the setup
     *  gate flags vs. the terminal input mode) never clobber each other -- they all
     *  share one persisted blob (see [dev.azula.persist.SettingsStore]). */
    private var current = AppSettings()

    /** Terminal input mode: smart (swipe/autocorrect/suggestions) vs. the legacy
     *  raw fallback -- see [dev.azula.persist.AppSettings.terminalSmartInput]. */
    var terminalSmartInput by mutableStateOf(true)
        private set

    /** Whether the first-run setup flow has been completed -- the setup gate's
     *  persisted flag (see the onboarding capability, decision D1). */
    var setupCompleted by mutableStateOf(false)
        private set

    /** Whether the user chose "back up later" in setup, deferring the
     *  recovery-phrase backup. Persisted independently of [setupCompleted]. */
    var backupDeferred by mutableStateOf(false)
        private set

    fun load(legacyTerminalSmartInput: Boolean) {
        val saved = settingsStore?.load()
        // Nothing in the new store yet (fresh install, or an upgrade that hasn't
        // migrated): honor the legacy ProfileBook value (a no-op default of `true`
        // for a truly fresh install) and persist it so this branch doesn't run
        // again on the next launch.
        current = saved ?: AppSettings(terminalSmartInput = legacyTerminalSmartInput)
        if (saved == null) settingsStore?.save(current)
        terminalSmartInput = current.terminalSmartInput
        setupCompleted = current.setupCompleted
        backupDeferred = current.backupDeferred
    }

    fun setSmartInput(enabled: Boolean) {
        current = current.copy(terminalSmartInput = enabled)
        settingsStore?.save(current)
        terminalSmartInput = enabled
    }

    /** Persist that first-run setup finished (finish or skip of its final step),
     *  preserving [backupDeferred] and any other fields. */
    fun markSetupCompleted() {
        current = current.copy(setupCompleted = true)
        settingsStore?.save(current)
        setupCompleted = true
    }

    /** Persist the "back up later" deferral flag, independent of completion.
     *  (Named `mark…` rather than `set…` to avoid clashing with the JVM setter
     *  generated for the [backupDeferred] property.) */
    fun markBackupDeferred(deferred: Boolean) {
        current = current.copy(backupDeferred = deferred)
        settingsStore?.save(current)
        backupDeferred = deferred
    }
}
