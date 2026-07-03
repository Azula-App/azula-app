package dev.azula.mock

import dev.azula.persist.BlobReader
import dev.azula.persist.BlobStore
import dev.azula.persist.BlobWriter
import kotlinx.coroutines.channels.Channel

/**
 * An in-memory [BlobStore] fake for the `-mock` apps and tests: id -> growable
 * byte array, entirely off-disk. Mirrors `InMemoryMessageStore`
 * (mock-support/test/TestSupport.kt) -- [writes] is a channel a test can
 * suspend-`receive()` on to observe a chunk landing deterministically, since
 * [dev.azula.state.MediaService]'s fetch job runs on a real background
 * dispatcher, not a test's virtual clock.
 */
class InMemoryBlobStore(seed: Map<String, ByteArray> = emptyMap()) : BlobStore {
    private val data = LinkedHashMap<String, ByteArray>(seed)
    val writes: Channel<String> = Channel(Channel.UNLIMITED)

    override fun exists(id: String): Boolean = data.containsKey(id)

    override fun sizeOnDisk(id: String): Long = (data[id]?.size ?: 0).toLong()

    override fun openWriter(id: String): BlobWriter = object : BlobWriter {
        override fun write(chunk: ByteArray) {
            data[id] = (data[id] ?: ByteArray(0)) + chunk
            writes.trySend(id)
        }

        override fun close() = Unit
    }

    override fun openReader(id: String, offset: Long): BlobReader {
        val bytes = data[id] ?: ByteArray(0)
        var pos = offset.toInt()
        return object : BlobReader {
            override fun read(into: ByteArray): Int {
                if (pos >= bytes.size) return -1
                val end = minOf(pos + into.size, bytes.size)
                bytes.copyInto(into, 0, pos, end)
                val n = end - pos
                pos = end
                return n
            }

            override fun close() = Unit
        }
    }

    override fun pathFor(id: String): String? = null // in-memory: no filesystem path

    override fun delete(id: String) {
        data.remove(id)
    }

    /** Test/demo hook: read back everything written for [id] so far, or null if absent. */
    fun bytesFor(id: String): ByteArray? = data[id]
}
