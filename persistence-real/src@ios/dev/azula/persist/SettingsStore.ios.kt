package dev.azula.persist

import platform.Foundation.NSUserDefaults

actual fun platformSettingsStore(): SettingsStore? = NsUserDefaultsSettingsStore()

/** Stores the whole AppSettings as a JSON string in NSUserDefaults. */
class NsUserDefaultsSettingsStore : SettingsStore {
    private val key = "azula_settings"
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): AppSettings? {
        val s = defaults.stringForKey(key) ?: return null
        return decodeAppSettings(s)
    }

    override fun save(settings: AppSettings) {
        runCatching { defaults.setObject(encodeAppSettings(settings), key) }
    }
}
