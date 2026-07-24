package dev.azula.persist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app's small collection of standalone user settings -- not persona/profile
 * data, not per-conversation history. Currently just the terminal input mode;
 * more flags should land here rather than piggybacking on [ProfileBook] (see
 * azula-docs/openspec/changes/settings-store-extraction/, "Settings live in ProfileBook").
 */
@Serializable
data class AppSettings(
    /** Whether the terminal uses the rearchitected smart input (swipe typing,
     *  autocorrect, suggestions) or the legacy raw sentinel-reset fallback. */
    val terminalSmartInput: Boolean = true,
    /** Whether the first-run setup flow has been completed (finish or skip of
     *  its final step); gates whether setup is shown — see the onboarding
     *  capability. */
    val setupCompleted: Boolean = false,
    /** Whether the user chose "back up later" in setup, deferring the
     *  recovery-phrase backup; lets the app surface a reminder later. */
    val backupDeferred: Boolean = false,
    /**
     * Whether this device's pre-existing (pre-multi-device-identity) message history
     * has been backfilled into the event log (see
     * `dev.azula.state.AccountSyncService.backfillLegacyHistory`). Defaults `false`
     * so every existing persisted blob (which predates this field) runs the backfill
     * exactly once on first launch after upgrading; set `true` right after that pass
     * so it never re-runs and re-duplicates already-backfilled entries.
     */
    val eventLogBackfilled: Boolean = false,
    /**
     * This device's own granted device certificate (`"azd…"`), set exactly once by a
     * successful QR-link enrollment (multi-device-identity task 6.1) and never touched by
     * any other path. Null on every device that isn't QR-linked (the common case: a
     * root-holding device self-certifies via the event log instead -- see
     * `AccountSyncService.ensureSelfCertified` -- and never writes this field).
     */
    val linkedDeviceCert: String? = null,
    /**
     * The identity bundle ([dev.azula.net.IdentityBundle], JSON-encoded via
     * `dev.azula.net.FrameCodec.encodeBundle`/`decodeBundle`) delivered alongside
     * [linkedDeviceCert] by the same `LinkGrant` -- root public key, known certificates,
     * revocation set, contacts snapshot, and mailbox hint (device-linking spec's "Device
     * Registry Persistence"). `persistence-api` can't reference `network-api`'s
     * `IdentityBundle` type directly (dependency points the other way), so this is kept as
     * an opaque JSON blob here; `shared` (which depends on both) is where it's decoded back.
     */
    val linkedIdentityBundleJson: String? = null,
    /**
     * Sibling device tickets learned so this device can auto-dial them for sync
     * (multi-device-identity task 5.4): device public key (lowercase hex) to a
     * dialable ticket. Learned from exactly two places -- (a) the mailbox hint inside
     * a QR-link grant's identity bundle (`dev.azula.net.IdentityBundle.mailbox`), keyed
     * to whichever certified device in that same bundle carries the mailbox role, and
     * (b) this device granting a link to a new device (task 6.2): the very ticket it
     * just dialed to reach it. See `dev.azula.state.ConnectService.reconnectSiblings`'s
     * kdoc for why neither the bundle nor the sync wire protocol carries a general
     * sibling-ticket list beyond those two. Losing this map only degrades reachability
     * (a sibling stops being auto-dialed until it's learned again some other way); the
     * device set and identity itself come from the fold, never from this map.
     */
    val siblingTickets: Map<String, String> = emptyMap(),
    /**
     * Local-only display-name overrides for devices in this identity's device set
     * (multi-device-identity task 6.3): device public key (lowercase hex) to a
     * user-chosen name shown instead of the certificate's own `name` field. Renaming
     * here never mints a new certificate and is never seen by any other device --
     * device-linking's "renaming is local-only" reading.
     */
    val deviceLocalNames: Map<String, String> = emptyMap(),
)

/**
 * Persists [AppSettings]. Mirrors the [ProfileStore]/[MessageStore] expect/actual
 * + injectable-config pattern: a single small blob (the whole [AppSettings]).
 *
 * Unlike [ProfileStore.load] (which defaults to an empty [ProfileBook] when
 * nothing's saved yet), [load] returns `null` when nothing has ever been written
 * -- callers need to tell "fresh install" apart from "explicitly set" to run the
 * one-time ProfileBook migration (see `dev.azula.state.SettingsService`).
 */
interface SettingsStore {
    fun load(): AppSettings?
    fun save(settings: AppSettings)
}

val SettingsJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val settingsSerializer = AppSettings.serializer()

fun encodeAppSettings(settings: AppSettings): String = SettingsJson.encodeToString(settingsSerializer, settings)

fun decodeAppSettings(text: String): AppSettings? =
    runCatching { SettingsJson.decodeFromString(settingsSerializer, text) }.getOrNull()
