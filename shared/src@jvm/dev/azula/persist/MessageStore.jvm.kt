package dev.azula.persist

import java.io.File

actual fun platformMessageStore(): MessageStore? = FileMessageStore()

/** Stores each conversation as JSON under ~/.azula/conversations/<id>.json. */
class FileMessageStore : MessageStore {
    private val dir = File(System.getProperty("user.home"), ".azula/conversations")

    private fun file(id: String) = File(dir, sanitize(id) + ".json")

    override fun load(conversationId: String): List<MessageDto> = runCatching {
        val f = file(conversationId)
        if (!f.exists()) emptyList() else decodeMessages(f.readText())
    }.getOrDefault(emptyList())

    override fun save(conversationId: String, messages: List<MessageDto>) {
        runCatching {
            dir.mkdirs()
            file(conversationId).writeText(encodeMessages(messages))
        }
    }
}

private fun sanitize(s: String): String =
    s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
