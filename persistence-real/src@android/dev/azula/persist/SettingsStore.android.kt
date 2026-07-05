package dev.azula.persist

import android.content.Context

// Android needs a Context, so the default is null; AzulaApplication builds the
// AppGraph with an AndroidSettingsStore(applicationContext) instead.
actual fun platformSettingsStore(): SettingsStore? = null

/** Stores the whole AppSettings as a JSON string in SharedPreferences. */
fun AndroidSettingsStore(context: Context): SettingsStore {
    val prefs = context.getSharedPreferences("azula_settings", Context.MODE_PRIVATE)
    return RawSettingsStore(
        readRaw = { prefs.getString("book", null) },
        writeRaw = { prefs.edit().putString("book", it).apply() },
    )
}
