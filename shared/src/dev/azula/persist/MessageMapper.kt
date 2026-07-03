package dev.azula.persist

import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.UpdateComponents
import dev.azula.model.Attachment
import dev.azula.model.MediaKind
import dev.azula.model.Message
import dev.azula.model.TransferState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Message <-> DTO mapping. Lives in shared (not persistence-api) because Message
// embeds an a2ui surface; persistence-api stays free of a2ui.

@OptIn(ExperimentalEncodingApi::class)
fun Message.toDto(): MessageDto = MessageDto(
    id = id,
    role = role,
    text = text,
    a2ui = a2ui?.let { s -> SurfaceDto(s.surfaceId, s.catalogId, JsonObject(s.components), s.dataModel) },
    attachments = attachments.map { a ->
        AttachmentDto(
            id = a.id,
            name = a.name,
            mime = a.mime,
            size = a.size,
            bytesB64 = a.bytes?.let { Base64.encode(it) },
            caption = a.caption,
            kind = a.kind.wire,
            width = a.width,
            height = a.height,
            durationMs = a.durationMs,
            thumbB64 = a.thumbBytes?.let { Base64.encode(it) },
            blobId = a.blobId,
            fetchTicket = a.fetchTicket,
            // Only "complete" is worth persisting literally; Offered/Downloading/Failed
            // all round-trip through "offered" -- see AttachmentDto.state's doc.
            state = if (a.state == TransferState.Complete) "complete" else "offered",
        )
    },
)

/**
 * Rebuild a [Message] from disk. The A2UI surface is reconstructed through its
 * existing public API ([A2uiSurface.apply] + [A2uiSurface.setData]). Callers must
 * NOT register restored surfaces in the live `surfaces` registry — that keeps them
 * frozen (see the renderer's `frozen` flag).
 *
 * A blob-backed attachment's [TransferState] here is only a starting point --
 * [dev.azula.state.PersistenceCoordinator.restoreAll] reconciles it against actual
 * on-disk bytes right after this returns.
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
        Attachment(
            id = a.id,
            name = a.name,
            mime = a.mime,
            size = a.size,
            bytes = a.bytesB64?.let { Base64.decode(it) },
            caption = a.caption,
            kind = MediaKind.fromWire(a.kind),
            width = a.width,
            height = a.height,
            durationMs = a.durationMs,
            thumbBytes = a.thumbB64?.let { Base64.decode(it) },
            blobId = a.blobId,
            fetchTicket = a.fetchTicket,
            state = if (a.state == "complete") TransferState.Complete else TransferState.Offered,
        )
    },
)
