package dev.azula.persist

import platform.Foundation.NSUserDefaults

actual fun platformMessageStore(): MessageStore? = NsUserDefaultsMessageStore()

/**
 * Stores each conversation's JSON in NSUserDefaults under "azula_conv_<id>".
 * Mirrors the existing iOS PeerStore. (For very large histories — many big
 * base64 attachments — a NSDocumentDirectory file would be preferable; fine here.)
 */
class NsUserDefaultsMessageStore : MessageStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    private fun key(id: String) = "azula_conv_$id"

    override fun load(conversationId: String): ConversationDto? = runCatching {
        val s = defaults.stringForKey(key(conversationId)) ?: return null
        decodeConversation(s)
    }.getOrNull()

    override fun save(conversationId: String, conversation: ConversationDto) {
        runCatching { defaults.setObject(encodeConversation(conversation), key(conversationId)) }
    }

    override fun list(): List<String> = runCatching {
        val prefix = "azula_conv_"
        defaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    }.getOrDefault(emptyList())
}
