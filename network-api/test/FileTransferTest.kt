import dev.azula.net.FileEncoding
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.OutgoingFile
import dev.azula.net.P2pStream
import dev.azula.net.receiveFile
import dev.azula.net.sendFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for [sendFile] / [receiveFile] over an in-memory pipe.
 *
 * The pipe is backed by two channels: one for text lines (shared with binary frames
 * to preserve ordering) and one byte queue for raw binary bodies. Both ends of the
 * pipe implement [P2pStream], mirroring how the real transport works.
 */
class FileTransferTest {

    // ---- in-memory pipe ----

    /**
     * A connected pair of [P2pStream]s sharing a single line channel and a single
     * byte buffer so ordering between text and binary data is preserved.
     *
     * [PipeEnd.A] writes → [PipeEnd.B] reads, and vice-versa (full duplex).
     */
    private class Pipe {
        // A→B
        val linesChanAB = Channel<String?>(Channel.UNLIMITED)
        val bytesMutexAB = Mutex()
        var byteBufAB = ByteArray(0)
        val bytesReadyAB = Channel<Unit>(Channel.CONFLATED)

        // B→A
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

    // ---- helpers ----

    private fun pseudoRandom(size: Int, seed: Int = 42): ByteArray {
        var s = seed.toLong()
        return ByteArray(size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            s.toByte()
        }
    }

    // Minimal 8-byte PNG signature as a "tiny PNG" fixture.
    private val tinyPngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    // ---- tests ----

    @Test
    fun base64RoundTripLargePayload() = runTest {
        val pipe = Pipe()
        val payload = pseudoRandom(50 * 1024) // 50 KB
        val file = OutgoingFile(name = "data.bin", mime = "application/octet-stream", bytes = payload)
        val chunkSize = 8 * 1024 // 8 KB → expect multiple chunks

        // Collect lines sent by A so we can count file_chunk frames.
        val sentLines = mutableListOf<String>()
        val senderStream = object : P2pStream by pipe.a {
            override suspend fun sendLine(line: String) {
                sentLines.add(line)
                pipe.a.sendLine(line)
            }
        }

        // Send from A, receive on B.
        var received: dev.azula.net.ReceivedFile? = null
        val receiver = launch {
            while (true) {
                val line = pipe.b.receiveLine() ?: break
                if (line.isBlank()) continue
                val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                if (frame is Frame.FileBegin) {
                    received = receiveFile(pipe.b, frame) { pipe.b.receiveLine() }
                    break
                }
            }
        }

        sendFile(senderStream, file, FileEncoding.BASE64, chunkSize)
        receiver.join()

        val got = received!!
        assertTrue(got.bytes.contentEquals(payload), "bytes differ for base64 transfer")
        assertEquals("data.bin", got.name)
        assertEquals("application/octet-stream", got.mime)

        // Verify chunking: 50 KB / 8 KB = at least 6 chunks.
        val chunkCount = sentLines.count { line ->
            runCatching { FrameCodec.decode(line) }.getOrNull() is Frame.FileChunk
        }
        assertTrue(chunkCount >= 6, "expected multiple chunks, got $chunkCount")
    }

    @Test
    fun binaryRoundTripLargePayload() = runTest {
        val pipe = Pipe()
        val payload = pseudoRandom(50 * 1024, seed = 99)
        val file = OutgoingFile(name = "image.bin", mime = "application/octet-stream", bytes = payload)

        var received: dev.azula.net.ReceivedFile? = null
        val receiver = launch {
            while (true) {
                val line = pipe.b.receiveLine() ?: break
                if (line.isBlank()) continue
                val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                if (frame is Frame.FileBegin) {
                    received = receiveFile(pipe.b, frame) { pipe.b.receiveLine() }
                    break
                }
            }
        }

        sendFile(pipe.a, file, FileEncoding.BINARY)
        receiver.join()

        val got = received!!
        assertTrue(got.bytes.contentEquals(payload), "bytes differ for binary transfer")
        assertEquals("image.bin", got.name)
    }

    @Test
    fun base64RoundTripTinyPng() = runTest {
        val pipe = Pipe()
        val file = OutgoingFile(
            name = "tiny.png",
            mime = "image/png",
            bytes = tinyPngHeader,
            caption = "PNG magic bytes",
        )

        var received: dev.azula.net.ReceivedFile? = null
        val receiver = launch {
            while (true) {
                val line = pipe.b.receiveLine() ?: break
                if (line.isBlank()) continue
                val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                if (frame is Frame.FileBegin) {
                    received = receiveFile(pipe.b, frame) { pipe.b.receiveLine() }
                    break
                }
            }
        }

        sendFile(pipe.a, file, FileEncoding.BASE64)
        receiver.join()

        val got = received!!
        assertTrue(got.bytes.contentEquals(tinyPngHeader), "PNG header bytes differ")
        assertEquals("tiny.png", got.name)
        assertEquals("image/png", got.mime)
        assertEquals("PNG magic bytes", got.caption)
    }

    @Test
    fun binaryRoundTripTinyPng() = runTest {
        val pipe = Pipe()
        val file = OutgoingFile(
            name = "tiny.png",
            mime = "image/png",
            bytes = tinyPngHeader,
        )

        var received: dev.azula.net.ReceivedFile? = null
        val receiver = launch {
            while (true) {
                val line = pipe.b.receiveLine() ?: break
                if (line.isBlank()) continue
                val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                if (frame is Frame.FileBegin) {
                    received = receiveFile(pipe.b, frame) { pipe.b.receiveLine() }
                    break
                }
            }
        }

        sendFile(pipe.a, file, FileEncoding.BINARY)
        receiver.join()

        val got = received!!
        assertTrue(got.bytes.contentEquals(tinyPngHeader), "PNG header bytes differ for binary transfer")
    }

    @Test
    fun base64ProducesMultipleChunksForLargePayload() = runTest {
        val pipe = Pipe()
        val payload = pseudoRandom(10 * 1024)
        val chunkSize = 1024 // 10 chunks expected
        val file = OutgoingFile(name = "f.bin", mime = "application/octet-stream", bytes = payload)

        val sentLines = mutableListOf<String>()
        val capturingStream = object : P2pStream by pipe.a {
            override suspend fun sendLine(line: String) {
                sentLines.add(line)
                pipe.a.sendLine(line)
            }
        }

        val receiver = launch {
            while (true) {
                val line = pipe.b.receiveLine() ?: break
                if (line.isBlank()) continue
                val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                if (frame is Frame.FileBegin) {
                    receiveFile(pipe.b, frame) { pipe.b.receiveLine() }
                    break
                }
            }
        }

        sendFile(capturingStream, file, FileEncoding.BASE64, chunkSize)
        receiver.join()

        val chunks = sentLines
            .mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
            .filterIsInstance<Frame.FileChunk>()

        assertTrue(chunks.size >= 10, "expected at least 10 chunks, got ${chunks.size}")
        // Sequence numbers must be sequential starting at 0.
        chunks.forEachIndexed { idx, c -> assertEquals(idx, c.seq, "seq out of order at $idx") }
    }
}
