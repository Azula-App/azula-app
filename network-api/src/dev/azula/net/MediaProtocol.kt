package dev.azula.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire protocol for the `azula/media/0` ALPN. A [Frame.MediaOffer] carries
 * only metadata over the existing CHAT stream; the receiver pulls the body by
 * dialing the sender's `fetchTicket` from that offer on this ALPN, sending one
 * [MediaFetchRequest] line, reading one [MediaFetchResponse] line back, then (on
 * [MediaFetchResponse.Ok]) raw binary chunks with no further framing — see
 * [sendMediaBody] / [receiveMediaBody]. This is a separate, minimal JSON codec
 * from [FrameCodec] — media fetch isn't part of the [Frame] sealed hierarchy.
 */

/** Receiver → sender: pull the body of attachment [id], resuming from [offset] bytes in. */
@Serializable
data class MediaFetchRequest(val id: String, val offset: Long = 0)

/** Sender → receiver: the body follows ([Ok]) or the fetch failed ([Error]). */
@Serializable
sealed class MediaFetchResponse {
    @Serializable
    @SerialName("ok")
    data class Ok(val id: String, val size: Long, val offset: Long) : MediaFetchResponse()

    @Serializable
    @SerialName("error")
    data class Error(val id: String, val reason: String) : MediaFetchResponse()
}

/** Encodes/decodes [MediaFetchRequest] and [MediaFetchResponse] lines. */
object MediaCodec {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeRequest(request: MediaFetchRequest): String =
        json.encodeToString(MediaFetchRequest.serializer(), request)

    fun decodeRequest(line: String): MediaFetchRequest =
        json.decodeFromString(MediaFetchRequest.serializer(), line)

    fun encodeResponse(response: MediaFetchResponse): String =
        json.encodeToString(MediaFetchResponse.serializer(), response)

    fun decodeResponse(line: String): MediaFetchResponse =
        json.decodeFromString(MediaFetchResponse.serializer(), line)
}

/** Chunk size used by [sendMediaBody] / [receiveMediaBody] (256 KiB). */
const val MEDIA_CHUNK = 256 * 1024

/** Maximum accepted attachment size (512 MiB) — enforced on send and on offer receipt. */
const val MAX_MEDIA_BYTES = 512L * 1024 * 1024

/**
 * Stream `total - offset` bytes to [stream] in [chunkSize] pieces, pulling each
 * piece from [readChunk] (asked for up to `maxLen` bytes; returns null or an
 * empty array at EOF). The sender-side counterpart of [receiveMediaBody]; used
 * to serve a fetch from a BlobStore reader (or, in tests, from memory).
 */
suspend fun sendMediaBody(
    stream: P2pStream,
    total: Long,
    offset: Long = 0,
    chunkSize: Int = MEDIA_CHUNK,
    readChunk: suspend (maxLen: Int) -> ByteArray?,
) {
    var sent = offset
    while (sent < total) {
        val want = minOf(total - sent, chunkSize.toLong()).toInt()
        val chunk = readChunk(want) ?: break
        if (chunk.isEmpty()) break
        stream.sendBytes(chunk)
        sent += chunk.size
    }
}

/**
 * Receive `total - offset` bytes from [stream] in [chunkSize] pieces — never a
 * single [P2pStream.receiveBytes] call for the whole body, since [LineBuffer]
 * buffers the full requested length in memory. Delivers each piece to [onChunk]
 * as it arrives (e.g. appended to a file on disk, for resumability). Returns
 * true once exactly `total - offset` bytes were received; false if the stream
 * hit EOF early.
 */
suspend fun receiveMediaBody(
    stream: P2pStream,
    total: Long,
    offset: Long = 0,
    chunkSize: Int = MEDIA_CHUNK,
    onChunk: suspend (ByteArray) -> Unit,
): Boolean {
    var received = offset
    while (received < total) {
        val want = minOf(total - received, chunkSize.toLong()).toInt()
        val chunk = stream.receiveBytes(want) ?: return false
        onChunk(chunk)
        received += chunk.size
    }
    return true
}
