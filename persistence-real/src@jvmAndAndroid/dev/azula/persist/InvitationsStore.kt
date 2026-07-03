package dev.azula.persist

/**
 * Stores the whole [InvitationsBook] as a JSON string via a platform-supplied raw
 * string accessor (SharedPreferences on Android, a file on JVM).
 */
class RawInvitationsStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) : InvitationsStore {
    override fun load(): InvitationsBook {
        val s = readRaw() ?: return InvitationsBook()
        return decodeInvitationsBook(s) ?: InvitationsBook()
    }

    override fun save(book: InvitationsBook) {
        writeRaw(encodeInvitationsBook(book))
    }
}
