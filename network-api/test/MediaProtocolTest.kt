import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.MAX_MEDIA_BYTES
import dev.azula.net.MEDIA_CHUNK
import dev.azula.net.MediaCodec
import dev.azula.net.MediaFetchRequest
import dev.azula.net.MediaFetchResponse
import dev.azula.net.P2pStream
import dev.azula.net.receiveMediaBody
import dev.azula.net.sendMediaBody
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the `azula/media/0` ALPN wire protocol: [Frame.MediaOffer] framing
 * (via [FrameCodec], since it rides the CHAT stream), the [MediaFetchRequest] /
 * [MediaFetchResponse] handshake (via [MediaCodec]), and the [sendMediaBody] /
 * [receiveMediaBody] chunk loops.
 */
class MediaProtocolTest {

    // ---- in-memory pipe (same pattern as FileTransferTest's Pipe) ----

    /**
     * A connected pair of [P2pStream]s sharing a single line channel and a single
     * byte buffer per direction, so ordering between text and binary data is
     * preserved. [PipeEnd.a] writes → [PipeEnd.b] reads, and vice versa.
     */
    private class Pipe {
        // a -> b
        val linesChanAB = Channel<String?>(Channel.UNLIMITED)
        val bytesMutexAB = Mutex()
        var byteBufAB = ByteArray(0)
        val bytesReadyAB = Channel<Unit>(Channel.CONFLATED)

        // b -> a
        val linesChanBA = Channel<String?>(Channel.UNLIMITED)
        val bytesMutexBA = Mutex()
        var byteBufBA = ByteArray(0)
        val bytesReadyBA = Channel<Unit>(Channel.CONFLATED)

        val a: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) { linesChanAB.send(line) }
            override suspend fun receiveLine(): String? = linesChanBA.receive()
            override suspend fun sendBytes(bytes: ByteArray) {
                bytesMutexAB.withLock { byteBufAB += bytes }
                bytesReadyAB.send(Unit)
            }
            override suspend fun receiveBytes(n: Int): ByteArray? {
                while (true) {
                    val result = bytesMutexBA.withLock {
                        if (byteBufBA.size >= n) {
                            val slice = byteBufBA.copyOfRange(0, n)
                            byteBufBA = byteBufBA.copyOfRange(n, byteBufBA.size)
                            slice
                        } else null
                    }
                    if (result != null) return result
                    bytesReadyBA.receive()
                }
            }
            override suspend fun close() { linesChanAB.close() }
        }

        val b: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) { linesChanBA.send(line) }
            override suspend fun receiveLine(): String? = linesChanAB.receive()
            override suspend fun sendBytes(bytes: ByteArray) {
                bytesMutexBA.withLock { byteBufBA += bytes }
                bytesReadyBA.send(Unit)
            }
            override suspend fun receiveBytes(n: Int): ByteArray? {
                while (true) {
                    val result = bytesMutexAB.withLock {
                        if (byteBufAB.size >= n) {
                            val slice = byteBufAB.copyOfRange(0, n)
                            byteBufAB = byteBufAB.copyOfRange(n, byteBufAB.size)
                            slice
                        } else null
                    }
                    if (result != null) return result
                    bytesReadyAB.receive()
                }
            }
            override suspend fun close() { linesChanBA.close() }
        }
    }

    /**
     * A one-ended fake stream backed by a fixed byte array, for exercising
     * [receiveMediaBody]'s EOF path: unlike [Pipe] (a live duplex channel that
     * would just block waiting for more data that's never coming), this returns
     * null as soon as fewer than the requested bytes remain — exactly the
     * contract [P2pStream.receiveBytes] documents for a closed connection.
     */
    private class EofAfterStream(private val available: ByteArray) : P2pStream {
        private var pos = 0
        override suspend fun sendLine(line: String) = error("not used by this fake")
        override suspend fun receiveLine(): String? = error("not used by this fake")
        override suspend fun sendBytes(bytes: ByteArray) = error("not used by this fake")
        override suspend fun receiveBytes(n: Int): ByteArray? {
            if (pos + n > available.size) return null
            val slice = available.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }
        override suspend fun close() {}
    }

    // ---- helpers ----

    private fun pseudoRandom(size: Int, seed: Int = 7): ByteArray {
        var s = seed.toLong()
        return ByteArray(size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            s.toByte()
        }
    }

    /** A [sendMediaBody] `readChunk` reader over an in-memory source, honoring `maxLen`. */
    private fun readerOver(data: ByteArray, start: Int): suspend (Int) -> ByteArray? {
        var pos = start
        return { maxLen ->
            if (pos >= data.size) null
            else {
                val end = minOf(pos + maxLen, data.size)
                val slice = data.copyOfRange(pos, end)
                pos = end
                slice
            }
        }
    }

    // ---- Frame.MediaOffer round-trip (rides the CHAT stream / FrameCodec) ----

    @Test
    fun mediaOfferFrameRoundTripsViaFrameCodec() {
        val offer = Frame.MediaOffer(
            id = "att-1",
            kind = "image",
            name = "photo.png",
            mime = "image/png",
            size = 51_200,
            caption = "look at this",
            width = 800,
            height = 600,
            durationMs = null,
            thumbB64 = "Zm9v",
            fetchTicket = "ticket-abc",
        )

        val line = FrameCodec.encode(offer)
        assertTrue(line.contains("\"type\":\"media_offer\""), "wrong type tag: $line")
        assertTrue(line.contains("\"fetchTicket\":\"ticket-abc\""), "missing fetchTicket: $line")

        val decoded = FrameCodec.decode(line)
        assertEquals(offer, decoded)
    }

    @Test
    fun mediaOfferFrameOmitsOptionalNullsFromWireButDecodesDefaults() {
        // Decode a line that omits every optional field (as a minimal peer might send).
        val line = """{"type":"media_offer","id":"a2","kind":"file","name":"n","mime":"m","size":10,"fetchTicket":"t"}"""
        val decoded = FrameCodec.decode(line)
        val offer = decoded as Frame.MediaOffer
        assertEquals("a2", offer.id)
        assertEquals(null, offer.caption)
        assertEquals(null, offer.width)
        assertEquals(null, offer.durationMs)
        assertEquals(null, offer.thumbB64)
    }

    // ---- MediaFetchRequest / MediaFetchResponse round-trips ----

    @Test
    fun fetchRequestRoundTrips() {
        val request = MediaFetchRequest(id = "att-1", offset = 4096)
        val line = MediaCodec.encodeRequest(request)
        val decoded = MediaCodec.decodeRequest(line)
        assertEquals(request, decoded)
    }

    @Test
    fun fetchRequestDefaultOffsetIsZero() {
        val line = """{"id":"att-1"}"""
        val decoded = MediaCodec.decodeRequest(line)
        assertEquals(0L, decoded.offset)
    }

    @Test
    fun fetchResponseOkRoundTripsWithTypeTag() {
        val response: MediaFetchResponse = MediaFetchResponse.Ok(id = "att-1", size = 2048, offset = 0)
        val line = MediaCodec.encodeResponse(response)
        assertTrue(line.contains("\"type\":\"ok\""), "wrong type tag: $line")
        val decoded = MediaCodec.decodeResponse(line)
        assertEquals(response, decoded)
    }

    @Test
    fun fetchResponseErrorRoundTripsWithTypeTag() {
        val response: MediaFetchResponse = MediaFetchResponse.Error(id = "att-1", reason = "not found")
        val line = MediaCodec.encodeResponse(response)
        assertTrue(line.contains("\"type\":\"error\""), "wrong type tag: $line")
        val decoded = MediaCodec.decodeResponse(line)
        assertEquals(response, decoded)
    }

    // ---- sendMediaBody / receiveMediaBody chunk loops ----

    @Test
    fun fullTransferDeliversAllBytesInOrder() = runTest {
        val pipe = Pipe()
        val payload = pseudoRandom(600 * 1024) // spans multiple 256 KiB chunks

        val receivedChunks = mutableListOf<ByteArray>()
        var result = false
        val receiver = launch {
            result = receiveMediaBody(pipe.b, total = payload.size.toLong()) { chunk ->
                receivedChunks.add(chunk)
            }
        }

        sendMediaBody(pipe.a, total = payload.size.toLong(), readChunk = readerOver(payload, 0))
        receiver.join()

        assertTrue(result, "receiveMediaBody should report success")
        val assembled = receivedChunks.fold(ByteArray(0)) { acc, c -> acc + c }
        assertTrue(assembled.contentEquals(payload), "assembled bytes differ from payload")
    }

    @Test
    fun resumeFromOffsetTransfersExactlyTheRemainder() = runTest {
        val pipe = Pipe()
        val payload = pseudoRandom(500 * 1024, seed = 99)
        val offset = 300 * 1024L

        val receivedChunks = mutableListOf<ByteArray>()
        var result = false
        val receiver = launch {
            result = receiveMediaBody(pipe.b, total = payload.size.toLong(), offset = offset) { chunk ->
                receivedChunks.add(chunk)
            }
        }

        sendMediaBody(
            pipe.a,
            total = payload.size.toLong(),
            offset = offset,
            readChunk = readerOver(payload, offset.toInt()),
        )
        receiver.join()

        assertTrue(result)
        val assembled = receivedChunks.fold(ByteArray(0)) { acc, c -> acc + c }
        assertEquals(payload.size - offset.toInt(), assembled.size, "should transfer exactly total - offset bytes")
        assertTrue(assembled.contentEquals(payload.copyOfRange(offset.toInt(), payload.size)))
    }

    @Test
    fun eofBeforeTotalReturnsFalse() = runTest {
        // Only 1000 bytes are actually available, but the caller expects 5000.
        val stream = EofAfterStream(pseudoRandom(1000))
        var chunksReceived = 0
        val result = receiveMediaBody(stream, total = 5000, chunkSize = 256) {
            chunksReceived++
        }
        assertFalse(result, "receiveMediaBody must return false on early EOF")
        assertTrue(chunksReceived > 0, "should have delivered the chunks that were available")
    }

    @Test
    fun chunkCountMatchesExpectedForMultiChunkPayload() = runTest {
        val pipe = Pipe()
        val chunkSize = 1024
        val payload = pseudoRandom(10 * 1024, seed = 5) // exactly 10 chunks

        val sentChunkSizes = mutableListOf<Int>()
        val recordingSender = object : P2pStream by pipe.a {
            override suspend fun sendBytes(bytes: ByteArray) {
                sentChunkSizes.add(bytes.size)
                pipe.a.sendBytes(bytes)
            }
        }

        val receiver = launch {
            receiveMediaBody(pipe.b, total = payload.size.toLong(), chunkSize = chunkSize) { }
        }

        sendMediaBody(
            recordingSender,
            total = payload.size.toLong(),
            chunkSize = chunkSize,
            readChunk = readerOver(payload, 0),
        )
        receiver.join()

        assertEquals(10, sentChunkSizes.size, "expected exactly 10 chunks, got ${sentChunkSizes.size}")
        assertTrue(sentChunkSizes.all { it == chunkSize }, "all chunks should be exactly $chunkSize bytes")
    }

    @Test
    fun mediaConstantsMatchAgreedLimits() {
        assertEquals(256 * 1024, MEDIA_CHUNK)
        assertEquals(512L * 1024 * 1024, MAX_MEDIA_BYTES)
    }
}
