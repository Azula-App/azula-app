package dev.azula.persist

import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.UpdateComponents
import dev.azula.model.Attachment
import dev.azula.model.ConversationKind
import dev.azula.model.Message
import dev.azula.model.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
}

object MessageStoreConfig {
    /** Platforms that need a Context (Android) inject their store here. */
    @Volatile
    var store: MessageStore? = null
}

/** The platform default store (file on JVM/iOS). Android returns null and injects one. */
expect fun platformMessageStore(): MessageStore?

/** The active store: an injected one (Android) or the platform default. */
fun activeMessageStore(): MessageStore? = MessageStoreConfig.store ?: platformMessageStore()

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

// ---------------------------------------------------------------------------
// Mapping domain <-> DTO
// ---------------------------------------------------------------------------

@OptIn(ExperimentalEncodingApi::class)
fun Message.toDto(): MessageDto = MessageDto(
    id = id,
    role = role,
    text = text,
    a2ui = a2ui?.let { s -> SurfaceDto(s.surfaceId, s.catalogId, JsonObject(s.components), s.dataModel) },
    attachments = attachments.map { a ->
        AttachmentDto(a.id, a.name, a.mime, a.size, a.bytes?.let { Base64.encode(it) }, a.caption)
    },
)

/**
 * Rebuild a [Message] from disk. The A2UI surface is reconstructed through its
 * existing public API ([A2uiSurface.apply] + [A2uiSurface.setData]). Callers must
 * NOT register restored surfaces in the live `surfaces` registry — that keeps them
 * frozen (see the renderer's `frozen` flag).
 */
@OptIn(ExperimentalEncodingApi::class)
fun MessageDto.toMessage(): Message = Message(
    id = id,
    role = role,
    text = text,
    a2ui = a2ui?.let { dto ->
        A2uiSurface(dto.surfaceId, dto.catalogId).also { s ->
            s.apply(UpdateComponents(dto.surfaceId, dto.components.values.map { it.jsonObject }))
            s.setData("", dto.dataModel)
        }
    },
    attachments = attachments.map { a ->
        Attachment(a.id, a.name, a.mime, a.size, a.bytesB64?.let { Base64.decode(it) }, a.caption)
    },
)
