package dev.azula.persist

/**
 * Stores the whole [ProfileBook] as a JSON string via a platform-supplied raw
 * string accessor (SharedPreferences on Android, a file on JVM).
 */
class RawProfileStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) : ProfileStore {
    override fun load(): ProfileBook {
        val s = readRaw() ?: return ProfileBook()
        return decodeProfileBook(s) ?: ProfileBook()
    }

    override fun save(book: ProfileBook) {
        writeRaw(encodeProfileBook(book))
    }
}
