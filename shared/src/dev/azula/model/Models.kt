package dev.azula.model

import kotlinx.serialization.Serializable

/** Who authored a message. */
@Serializable
enum class Role { ME, THEM, AZULA, SYSTEM }

/** What kind of conversation a row in the sidebar represents. */
enum class ConversationKind { LLM, PEER, TERMINAL }

/** A conversation shown in the sidebar / mobile home. */
data class Conversation(
    val id: String,
    val name: String,
    val kind: ConversationKind,
    val glyph: String,
    val sub: String,
    val preview: String,
)

/**
 * A file attachment carried inside a [Message].
 * [bytes] is non-null when the full content is locally available (sent or received);
 * null while a transfer is still in progress.
 */
data class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val bytes: ByteArray?,
    val caption: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attachment) return false
        return id == other.id && name == other.name && mime == other.mime &&
            size == other.size &&
            (bytes == null && other.bytes == null ||
                bytes != null && other.bytes != null && bytes.contentEquals(other.bytes)) &&
            caption == other.caption
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + (caption?.hashCode() ?: 0)
        return result
    }
}

/**
 * One entry in a thread. Either a [text] message (markdown) or an inline [a2ui]
 * surface, optionally carrying file [attachments].
 * The [a2ui] surface is in-memory only; Message is never serialized.
 */
data class Message(
    val id: String,
    val role: Role,
    val text: String? = null,
    val a2ui: dev.azula.a2ui.A2uiSurface? = null,
    val attachments: List<Attachment> = emptyList(),
)

/** A single line in the terminal view. */
data class TerminalLine(val kind: Kind, val text: String, val tint: Tint = Tint.NORMAL) {
    enum class Kind { CMD, OUT }
    enum class Tint { NORMAL, GREEN, ERROR }
}

/** Stages of the "connect a peer" holepunch flow. */
enum class ConnectStage { IDLE, DIALING, CONNECTED, FAILED }
