package dev.azula.model

import kotlinx.serialization.Serializable

/** Who authored a message. */
@Serializable
enum class Role { ME, THEM, AZULA, SYSTEM }

/** What kind of conversation a row in the sidebar represents. */
@Serializable
enum class ConversationKind { LLM, PEER, TERMINAL }

/** A conversation shown in the sidebar / mobile home. */
data class Conversation(
    val id: String,
    val name: String,
    val kind: ConversationKind,
    val glyph: String,
    val sub: String,
    val preview: String,
    /**
     * When false the conversation is disabled: it won't auto-reconnect, incoming
     * connections from this peer are refused, and notifications are silenced. The
     * row + history stay (greyed). Defaults true so demo seeds and old persisted
     * rows are unaffected.
     */
    val enabled: Boolean = true,
)

/**
 * What kind of media an [Attachment] carries, driving which player/viewer UI
 * shows it. Mirrors the wire "kind" string carried in a media offer.
 */
enum class MediaKind(val wire: String) {
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
    FILE("file");

    companion object {
        /** Best-effort guess from a MIME type; defaults to [FILE] for anything else. */
        fun fromMime(mime: String): MediaKind = when {
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("audio/") -> AUDIO
            mime.startsWith("video/") -> VIDEO
            else -> FILE
        }

        /** Parses a wire "kind" string, defaulting to [FILE] for unknown values. */
        fun fromWire(w: String): MediaKind = entries.firstOrNull { it.wire == w } ?: FILE
    }
}

/** Where a media [Attachment]'s body currently stands. */
sealed class TransferState {
    /** The full body is available locally (sent, or fetched to completion). */
    data object Complete : TransferState()

    /** Only the offer metadata has arrived; the body hasn't been fetched yet. */
    data object Offered : TransferState()

    /** A fetch is in progress. */
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : TransferState()

    /** The last fetch attempt failed; retry resumes from bytes already on disk. */
    data class Failed(val reason: String) : TransferState()
}

/**
 * A file attachment carried inside a [Message].
 * [bytes] is non-null when the full content is locally available (sent or received);
 * null while a transfer is still in progress. [thumbBytes] is a small decoded preview
 * shown before (or in place of) the full body. [blobId] identifies the on-disk body in
 * the BlobStore once one exists; [fetchTicket] is the sender's ticket for dialing the
 * media ALPN to pull it. [state] defaults from [bytes] for legacy (pre-media) callers.
 */
data class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val bytes: ByteArray?,
    val caption: String?,
    val kind: MediaKind = MediaKind.FILE,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbBytes: ByteArray? = null,
    val blobId: String? = null,
    val fetchTicket: String? = null,
    val state: TransferState = if (bytes != null) TransferState.Complete else TransferState.Offered,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attachment) return false
        return id == other.id && name == other.name && mime == other.mime &&
            size == other.size &&
            (bytes == null && other.bytes == null ||
                bytes != null && other.bytes != null && bytes.contentEquals(other.bytes)) &&
            caption == other.caption &&
            kind == other.kind &&
            width == other.width &&
            height == other.height &&
            durationMs == other.durationMs &&
            (thumbBytes == null && other.thumbBytes == null ||
                thumbBytes != null && other.thumbBytes != null && thumbBytes.contentEquals(other.thumbBytes)) &&
            blobId == other.blobId &&
            fetchTicket == other.fetchTicket &&
            state == other.state
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + (caption?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + (width?.hashCode() ?: 0)
        result = 31 * result + (height?.hashCode() ?: 0)
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + (thumbBytes?.contentHashCode() ?: 0)
        result = 31 * result + (blobId?.hashCode() ?: 0)
        result = 31 * result + (fetchTicket?.hashCode() ?: 0)
        result = 31 * result + state.hashCode()
        return result
    }
}

/** A single line in the terminal view. */
data class TerminalLine(val kind: Kind, val text: String, val tint: Tint = Tint.NORMAL) {
    enum class Kind { CMD, OUT }
    enum class Tint { NORMAL, GREEN, ERROR }
}

/** Stages of the "connect a peer" holepunch flow. */
enum class ConnectStage { IDLE, DIALING, CONNECTED, FAILED }
