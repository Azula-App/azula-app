package dev.azula.persist

import android.content.Context

// Android needs a Context, so the default is null and MainActivity injects an
// [AndroidProfileStore] into [ProfileStoreConfig].
actual fun platformProfileStore(): ProfileStore? = null

/** Stores the whole ProfileBook as a JSON string in SharedPreferences. */
class AndroidProfileStore(context: Context) : ProfileStore {
    private val prefs = context.getSharedPreferences("azula_profiles", Context.MODE_PRIVATE)

    override fun load(): ProfileBook {
        val s = prefs.getString("book", null) ?: return ProfileBook()
        return decodeProfileBook(s) ?: ProfileBook()
    }

    override fun save(book: ProfileBook) {
        prefs.edit().putString("book", encodeProfileBook(book)).apply()
    }
}
