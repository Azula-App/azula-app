package dev.azula.persist

import platform.Foundation.NSUserDefaults

actual fun platformProfileStore(): ProfileStore? = NsUserDefaultsProfileStore()

/** Stores the whole ProfileBook as a JSON string in NSUserDefaults. */
class NsUserDefaultsProfileStore : ProfileStore {
    private val key = "azula_profiles"
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): ProfileBook {
        val s = defaults.stringForKey(key) ?: return ProfileBook()
        return decodeProfileBook(s) ?: ProfileBook()
    }

    override fun save(book: ProfileBook) {
        runCatching { defaults.setObject(encodeProfileBook(book), key) }
    }
}
