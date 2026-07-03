package dev.azula.persist

import android.content.Context

// Android needs a Context, so the default is null; AzulaApplication builds the
// AppGraph with an AndroidProfileStore(applicationContext) instead.
actual fun platformProfileStore(): ProfileStore? = null

/** Stores the whole ProfileBook as a JSON string in SharedPreferences. */
fun AndroidProfileStore(context: Context): ProfileStore {
    val prefs = context.getSharedPreferences("azula_profiles", Context.MODE_PRIVATE)
    return RawProfileStore(
        readRaw = { prefs.getString("book", null) },
        writeRaw = { prefs.edit().putString("book", it).apply() },
    )
}
