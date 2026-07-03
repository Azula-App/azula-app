package dev.azula.persist

import dev.azula.model.ConversationKind
import dev.azula.model.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Persists a conversation's full history — text, file attachments (binary), and
 * A2UI surfaces — so messages survive close/reopen. Keyed by conversation id;
 * each conversation is a separate blob (it can be large). Mirrors the
 * [dev.azula.net.PeerStore] expect/actual + injectable-config pattern.
 */
interface MessageStore {
    fun load(conversationId: String): ConversationDto?
    fun save(conversationId: String, conversation: ConversationDto)

    /**
     * Ids of every conversation with saved history. Used to restore the (now
     * per-peer, dynamically-named) conversations on launch, since the shipping
     * build has no fixed conversation set. Defaults to empty.
     */
    fun list(): List<String> = emptyList()

    /** Remove a conversation's persisted blob. Defaults to a no-op. */
    fun delete(conversationId: String) {}
}

// ---------------------------------------------------------------------------
// Serializable DTOs (the domain types are Compose-state holders, not @Serializable)
// ---------------------------------------------------------------------------

@Serializable
data class AttachmentDto(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val bytesB64: String? = null,
    val caption: String? = null,
    /** Wire string for a `dev.azula.model.MediaKind` (image/audio/video/file); "file"
     *  for legacy rows and non-media attachments. */
    val kind: String = "file",
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** Base64-encoded preview shown before/instead of the full body. */
    val thumbB64: String? = null,
    /** Id of this attachment's body in the BlobStore, if it's blob-backed (vs. the
     *  legacy fully-inline [bytesB64]). */
    val blobId: String? = null,
    val fetchTicket: String? = null,
    /**
     * "complete" or "offered" -- the only two states worth persisting literally.
     * A blob-backed attachment's real state (including an in-progress/failed
     * download) is recomputed from on-disk truth on restore rather than trusted
     * from here (see `PersistenceCoordinator.restoreAll`), so both "in progress"
     * and "failed" round-trip through "offered": whichever bytes actually made it
     * to disk are what matters after a restart.
     */
    val state: String = "complete",
)

/** A frozen snapshot of an A2UI surface: its component tree + data model (both already JSON). */
@Serializable
data class SurfaceDto(
    val surfaceId: String,
    val catalogId: String? = null,
    val components: JsonObject = JsonObject(emptyMap()),
    val dataModel: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class MessageDto(
    val id: String,
    val role: Role,
    val text: String? = null,
    val a2ui: SurfaceDto? = null,
    val attachments: List<AttachmentDto> = emptyList(),
)

/**
 * A persisted conversation: its identity (name/kind/glyph/sub/preview) plus its
 * messages. The identity is stored because, in the per-peer model, a conversation
 * is named after the peer (announced name or code) and must come back the same way
 * on restart — it's not re-derivable from the id alone.
 */
@Serializable
data class ConversationDto(
    val name: String,
    val kind: ConversationKind,
    val glyph: String,
    val sub: String = "",
    val preview: String = "",
    val messages: List<MessageDto> = emptyList(),
    /** Whether the conversation is enabled (see [dev.azula.model.Conversation.enabled]). */
    val enabled: Boolean = true,
    /** Last received peer profile: base64 avatar, its mime, and description. */
    val avatarB64: String? = null,
    val avatarMime: String? = null,
    val peerDescription: String? = null,
)

// ---------------------------------------------------------------------------
// JSON + string codec (explicit serializers — no reified generics in actuals)
// ---------------------------------------------------------------------------

val MessageJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val convSerializer = ConversationDto.serializer()

fun encodeConversation(conversation: ConversationDto): String =
    MessageJson.encodeToString(convSerializer, conversation)

fun decodeConversation(text: String): ConversationDto? =
    runCatching { MessageJson.decodeFromString(convSerializer, text) }.getOrNull()
