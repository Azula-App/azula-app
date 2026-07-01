package dev.azula.persist

import java.io.File

actual fun platformMessageStore(): MessageStore? = FileMessageStore()

/** Stores each conversation as JSON under ~/.azula/conversations/<id>.json. */
class FileMessageStore : MessageStore {
    private val dir = File(System.getProperty("user.home"), ".azula/conversations")

    private fun file(id: String) = File(dir, sanitize(id) + ".json")

    override fun load(conversationId: String): ConversationDto? = runCatching {
        val f = file(conversationId)
        if (!f.exists()) null else decodeConversation(f.readText())
    }.getOrNull()

    override fun save(conversationId: String, conversation: ConversationDto) {
        runCatching {
            dir.mkdirs()
            file(conversationId).writeText(encodeConversation(conversation))
        }
    }

    override fun list(): List<String> = runCatching {
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") } ?: emptyList()
    }.getOrDefault(emptyList())

    override fun delete(conversationId: String) {
        runCatching { file(conversationId).delete() }
    }
}

private fun sanitize(s: String): String =
    s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
