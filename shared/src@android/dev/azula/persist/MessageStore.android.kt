package dev.azula.persist

import android.content.Context
import java.io.File

// Android needs a Context, so the default is null and MainActivity injects an
// [AndroidMessageStore] into [MessageStoreConfig].
actual fun platformMessageStore(): MessageStore? = null

/** Stores each conversation as JSON under filesDir/conversations/<id>.json. */
class AndroidMessageStore(context: Context) : MessageStore {
    private val dir = File(context.filesDir, "conversations")

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
}

private fun sanitize(s: String): String =
    s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
