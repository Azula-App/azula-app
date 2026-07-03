package dev.azula.persist

import java.io.File
import java.io.RandomAccessFile

/**
 * Stores each blob as a flat file under `<baseDir>/blobs/<sanitized-id>`. Shared
 * by JVM (base dir `~/.azula`) and Android (base dir `context.filesDir`); see
 * each platform's `platformBlobStore()`/`AndroidBlobStore()`.
 */
class FileBlobStore(baseDir: File) : BlobStore {
    private val dir = File(baseDir, "blobs")

    private fun file(id: String) = File(dir, sanitize(id))

    override fun exists(id: String): Boolean = file(id).exists()

    override fun sizeOnDisk(id: String): Long = file(id).let { if (it.exists()) it.length() else 0L }

    override fun openWriter(id: String): BlobWriter {
        dir.mkdirs()
        val raf = RandomAccessFile(file(id), "rw")
        raf.seek(raf.length())
        return object : BlobWriter {
            override fun write(chunk: ByteArray) {
                raf.write(chunk)
            }

            override fun close() {
                raf.close()
            }
        }
    }

    override fun openReader(id: String, offset: Long): BlobReader {
        val raf = RandomAccessFile(file(id), "r")
        raf.seek(offset)
        return object : BlobReader {
            override fun read(into: ByteArray): Int = raf.read(into)

            override fun close() {
                raf.close()
            }
        }
    }

    override fun pathFor(id: String): String? = file(id).takeIf { it.exists() }?.absolutePath

    override fun delete(id: String) {
        runCatching { file(id).delete() }
    }
}
