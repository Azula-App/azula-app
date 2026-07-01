package dev.azula.persist

import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.UpdateComponents
import dev.azula.model.Attachment
import dev.azula.model.Message
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
