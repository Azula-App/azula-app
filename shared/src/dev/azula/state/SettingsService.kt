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

    /** Whether the one-time pre-existing-history backfill into the event log has
     *  already run (see [dev.azula.persist.AppSettings.eventLogBackfilled]). */
    var eventLogBackfilled by mutableStateOf(false)
        private set

    /** This device's own granted device certificate, if it was enrolled via QR-link
     *  (multi-device-identity task 6.1) -- see [dev.azula.persist.AppSettings.linkedDeviceCert].
     *  Null on every root-holding device. */
    var linkedDeviceCert by mutableStateOf<String?>(null)
        private set

    /** The JSON-encoded identity bundle delivered alongside [linkedDeviceCert] -- see
     *  [dev.azula.persist.AppSettings.linkedIdentityBundleJson]. Decode with
     *  `dev.azula.net.FrameCodec.decodeBundle`. */
    var linkedIdentityBundleJson by mutableStateOf<String?>(null)
        private set

    /** Sibling device tickets learned for auto-dial (multi-device-identity task 5.4) --
     *  see [dev.azula.persist.AppSettings.siblingTickets]'s kdoc. */
    var siblingTickets by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Local-only device display-name overrides (task 6.3) -- see
     *  [dev.azula.persist.AppSettings.deviceLocalNames]'s kdoc. */
    var deviceLocalNames by mutableStateOf<Map<String, String>>(emptyMap())
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
        eventLogBackfilled = current.eventLogBackfilled
        linkedDeviceCert = current.linkedDeviceCert
        linkedIdentityBundleJson = current.linkedIdentityBundleJson
        siblingTickets = current.siblingTickets
        deviceLocalNames = current.deviceLocalNames
    }

    /**
     * Persists a freshly granted, freshly verified device-link certificate + identity
     * bundle (multi-device-identity task 6.1) -- called exactly once, right after
     * `DeviceLinkProtocol.verifyGrantedCert` accepts the grant. This device holds no root
     * secret on this path; see `IrohTransport.forgetRootSecret`, which the linking flow
     * calls independently of this method so the two invariants ("no root secret", "cert +
     * bundle persisted") don't depend on each other to both hold.
     */
    fun saveLinkedIdentity(cert: String, bundleJson: String) {
        current = current.copy(linkedDeviceCert = cert, linkedIdentityBundleJson = bundleJson)
        settingsStore?.save(current)
        linkedDeviceCert = cert
        linkedIdentityBundleJson = bundleJson
    }

    /**
     * Persists a newly learned dialable ticket for a sibling device (multi-device-identity
     * task 5.4) -- see [dev.azula.persist.AppSettings.siblingTickets]'s kdoc for the two
     * places this is called from. Overwrites any previous ticket for the same device (the
     * freshest known address wins).
     */
    fun saveSiblingTicket(devicePkHex: String, ticket: String) {
        current = current.copy(siblingTickets = current.siblingTickets + (devicePkHex to ticket))
        settingsStore?.save(current)
        siblingTickets = current.siblingTickets
    }

    /** Drops a sibling ticket, e.g. once its device is revoked (task 6.3) -- hygiene only;
     *  `ConnectService.reconnectSiblings` already skips any ticket whose device isn't in the
     *  current fold, so this isn't required for correctness. */
    fun removeSiblingTicket(devicePkHex: String) {
        if (devicePkHex !in current.siblingTickets) return
        current = current.copy(siblingTickets = current.siblingTickets - devicePkHex)
        settingsStore?.save(current)
        siblingTickets = current.siblingTickets
    }

    /** Persists a local-only rename for a device in the fold's device set (task 6.3) --
     *  never mints a new certificate; see [dev.azula.persist.AppSettings.deviceLocalNames].
     *  A blank/null [name] clears the override, falling back to the certificate's own name. */
    fun saveDeviceLocalName(devicePkHex: String, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        current = current.copy(
            deviceLocalNames = if (trimmed == null) current.deviceLocalNames - devicePkHex else current.deviceLocalNames + (devicePkHex to trimmed),
        )
        settingsStore?.save(current)
        deviceLocalNames = current.deviceLocalNames
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

    /** Persist that the one-time event-log backfill has run, so it never repeats
     *  (and never re-duplicates already-backfilled entries) on a later launch. */
    fun markEventLogBackfilled() {
        current = current.copy(eventLogBackfilled = true)
        settingsStore?.save(current)
        eventLogBackfilled = true
    }
}
