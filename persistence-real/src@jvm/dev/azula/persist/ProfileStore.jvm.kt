package dev.azula.persist

import java.io.File

actual fun platformProfileStore(): ProfileStore? = FileProfileStore()

/** Stores the whole ProfileBook as JSON in ~/.azula/profiles.json. */
class FileProfileStore : ProfileStore {
    private val file = File(System.getProperty("user.home"), ".azula/profiles.json")

    override fun load(): ProfileBook = runCatching {
        if (!file.exists()) ProfileBook() else decodeProfileBook(file.readText()) ?: ProfileBook()
    }.getOrDefault(ProfileBook())

    override fun save(book: ProfileBook) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(encodeProfileBook(book))
        }
    }
}
