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
