package dev.azula.model

/**
 * One entry in a thread. Either a [text] message (markdown) or an inline [a2ui]
 * surface, optionally carrying file [attachments]. Lives in shared rather than
 * core because it embeds an a2ui surface; the [a2ui] surface is in-memory only,
 * and Message is never serialized.
 */
data class Message(
    val id: String,
    val role: Role,
    val text: String? = null,
    val a2ui: dev.azula.a2ui.A2uiSurface? = null,
    val attachments: List<Attachment> = emptyList(),
)
