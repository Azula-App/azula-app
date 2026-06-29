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

    override fun load(conversationId: String): List<MessageDto> = runCatching {
        val s = defaults.stringForKey(key(conversationId)) ?: return emptyList()
        decodeMessages(s)
    }.getOrDefault(emptyList())

    override fun save(conversationId: String, messages: List<MessageDto>) {
        runCatching { defaults.setObject(encodeMessages(messages), key(conversationId)) }
    }
}
