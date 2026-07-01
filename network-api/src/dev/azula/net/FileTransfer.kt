package dev.azula.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Wire encoding for a file transfer. */
enum class FileEncoding(val wire: String) {
    BASE64("base64"),
    BINARY("binary"),
}

/** A file to send over a [P2pStream]. */
data class OutgoingFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val caption: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutgoingFile) return false
        return name == other.name && mime == other.mime &&
            bytes.contentEquals(other.bytes) && caption == other.caption
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (caption?.hashCode() ?: 0)
        return result
    }
}

/** A file reassembled from a transfer. */
data class ReceivedFile(
    val id: String,
    val name: String,
    val mime: String,
    val bytes: ByteArray,
    val caption: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedFile) return false
        return id == other.id && name == other.name && mime == other.mime &&
            bytes.contentEquals(other.bytes) && caption == other.caption
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (caption?.hashCode() ?: 0)
        return result
    }
}

/** Maximum file size accepted by [receiveFile] (64 MiB). */
private const val MAX_FILE_BYTES = 64L * 1024 * 1024

/**
 * Send [file] over [stream] using [encoding]:
 * - BASE64: splits bytes into [chunkSize] slices, sends one `file_chunk` line per slice.
 * - BINARY: sends the raw bytes directly after `file_begin`, then `file_end`.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
suspend fun sendFile(
    stream: P2pStream,
    file: OutgoingFile,
    encoding: FileEncoding,
    chunkSize: Int = 32 * 1024,
) {
    val id = Uuid.random().toString()
    stream.sendLine(
        FrameCodec.encode(
            Frame.FileBegin(
                id = id,
                name = file.name,
                mime = file.mime,
                size = file.bytes.size.toLong(),
                encoding = encoding.wire,
                caption = file.caption,
            )
        )
    )

    when (encoding) {
        FileEncoding.BASE64 -> {
            var seq = 0
            var offset = 0
            while (offset < file.bytes.size) {
                val end = minOf(offset + chunkSize, file.bytes.size)
                val slice = file.bytes.copyOfRange(offset, end)
                val encoded = Base64.encode(slice)
                stream.sendLine(
                    FrameCodec.encode(Frame.FileChunk(id = id, seq = seq, data = encoded))
                )
                offset = end
                seq++
            }
        }
        FileEncoding.BINARY -> {
            stream.sendBytes(file.bytes)
        }
    }

    stream.sendLine(FrameCodec.encode(Frame.FileEnd(id = id)))
}

/**
 * Receive a file whose [Frame.FileBegin] header has already been decoded as [begin].
 * [readLine] should return the next line from the stream (same stream as [stream]).
 *
 * - BASE64: reads `file_chunk` lines (matching [begin].id) until a `file_end` line.
 * - BINARY: reads exactly [begin].size raw bytes from [stream], then the `file_end` line.
 *
 * Stray frames with non-matching ids are silently skipped.
 * Throws [IllegalArgumentException] if the file exceeds [MAX_FILE_BYTES].
 */
@OptIn(ExperimentalEncodingApi::class)
suspend fun receiveFile(
    stream: P2pStream,
    begin: Frame.FileBegin,
    readLine: suspend () -> String?,
): ReceivedFile {
    require(begin.size <= MAX_FILE_BYTES) {
        "File too large: ${begin.size} bytes (max $MAX_FILE_BYTES)"
    }

    val bytes = when (begin.encoding) {
        FileEncoding.BASE64.wire -> {
            val accumulator = mutableListOf<ByteArray>()
            while (true) {
                val line = readLine() ?: break
                if (line.isBlank()) continue
                val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                when {
                    frame is Frame.FileChunk && frame.id == begin.id -> {
                        accumulator.add(Base64.decode(frame.data))
                    }
                    frame is Frame.FileEnd && frame.id == begin.id -> break
                    else -> { /* skip stray frames */ }
                }
            }
            val total = accumulator.sumOf { it.size }
            val result = ByteArray(total)
            var pos = 0
            for (chunk in accumulator) {
                chunk.copyInto(result, pos)
                pos += chunk.size
            }
            result
        }
        FileEncoding.BINARY.wire -> {
            val raw = stream.receiveBytes(begin.size.toInt())
                ?: throw IllegalStateException("EOF before reading ${begin.size} binary bytes for file ${begin.id}")
            // Consume the trailing file_end line.
            while (true) {
                val line = readLine() ?: break
                if (line.isBlank()) continue
                val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                if (frame is Frame.FileEnd && frame.id == begin.id) break
                // Skip stray frames between raw body and file_end.
            }
            raw
        }
        else -> throw IllegalArgumentException("Unknown file encoding: ${begin.encoding}")
    }

    return ReceivedFile(
        id = begin.id,
        name = begin.name,
        mime = begin.mime,
        bytes = bytes,
        caption = begin.caption,
    )
}
