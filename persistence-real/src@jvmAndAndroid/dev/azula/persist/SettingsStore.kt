package dev.azula.persist

/**
 * Stores the whole [AppSettings] as a JSON string via a platform-supplied raw
 * string accessor (SharedPreferences on Android, a file on JVM). Returns `null`
 * from [load] (rather than a default instance, unlike [RawProfileStore]) so
 * callers can tell "never saved" apart from "explicitly set to the default" --
 * see [SettingsStore.load].
 */
class RawSettingsStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) : SettingsStore {
    override fun load(): AppSettings? {
        val s = readRaw() ?: return null
        return decodeAppSettings(s)
    }

    override fun save(settings: AppSettings) {
        writeRaw(encodeAppSettings(settings))
    }
}
