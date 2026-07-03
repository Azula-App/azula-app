package dev.azula.persist

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBlobStoreTest {
    private val tempDir = createTempDirectory("blobstore-test").toFile()
    private val store = FileBlobStore(tempDir)

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun writeThenReadRoundTrips() {
        val id = "blob-1"
        store.openWriter(id).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }

        assertTrue(store.exists(id))
        assertEquals(5L, store.sizeOnDisk(id))

        val out = ByteArray(5)
        val n = store.openReader(id).use { it.read(out) }
        assertEquals(5, n)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), out.toList())
    }

    @Test
    fun secondWriterAppendsFromPriorSize() {
        val id = "blob-2"
        store.openWriter(id).use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(3L, store.sizeOnDisk(id))

        store.openWriter(id).use { it.write(byteArrayOf(4, 5)) }
        assertEquals(5L, store.sizeOnDisk(id))

        val out = ByteArray(5)
        store.openReader(id).use { it.read(out) }
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), out.toList())
    }

    @Test
    fun readerOffsetReturnsTail() {
        val id = "blob-3"
        store.openWriter(id).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }

        val out = ByteArray(2)
        val n = store.openReader(id, offset = 3).use { it.read(out) }
        assertEquals(2, n)
        assertEquals(listOf<Byte>(4, 5), out.toList())
    }

    @Test
    fun readAtEofReturnsMinusOne() {
        val id = "blob-eof"
        store.openWriter(id).use { it.write(byteArrayOf(1, 2, 3)) }

        val out = ByteArray(4)
        val n = store.openReader(id, offset = 3).use { it.read(out) }
        assertEquals(-1, n)
    }

    @Test
    fun deleteRemovesBlob() {
        val id = "blob-4"
        store.openWriter(id).use { it.write(byteArrayOf(1)) }
        assertTrue(store.exists(id))

        store.delete(id)
        assertFalse(store.exists(id))
        assertEquals(0L, store.sizeOnDisk(id))
    }

    @Test
    fun pathForIsNullForMissingId() {
        assertNull(store.pathFor("nonexistent"))
    }

    @Test
    fun pathForReturnsAbsolutePathWhenPresent() {
        val id = "blob-5"
        store.openWriter(id).use { it.write(byteArrayOf(9)) }

        val path = store.pathFor(id)
        assertTrue(path != null && File(path).exists())
    }
}
