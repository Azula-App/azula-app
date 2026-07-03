@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.azula.persist

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.posix.memcpy

actual fun platformBlobStore(): BlobStore? = NsFileManagerBlobStore()

/**
 * Stores each blob as a flat file under `NSDocumentDirectory/azula-blobs/<sanitized-id>`
 * — the first plain-file-backed store on iOS (the sibling [MessageStore]/[ProfileStore]
 * there use NSUserDefaults, which isn't suited to media-sized blobs). Writers
 * open the file for appending at its current end, so an interrupted transfer
 * resumes with no separate resume state, matching [FileBlobStore] on JVM/Android.
 */
class NsFileManagerBlobStore : BlobStore {
    private val fileManager = NSFileManager.defaultManager

    private val dir: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: NSTemporaryDirectory()
        val path = "$docs/azula-blobs"
        fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    private fun path(id: String) = "$dir/${sanitize(id)}"

    override fun exists(id: String): Boolean = fileManager.fileExistsAtPath(path(id))

    override fun sizeOnDisk(id: String): Long {
        val p = path(id)
        if (!fileManager.fileExistsAtPath(p)) return 0L
        val handle = NSFileHandle.fileHandleForReadingAtPath(p) ?: return 0L
        return try {
            handle.seekToEndOfFile()
            handle.offsetInFile.toLong()
        } finally {
            handle.closeFile()
        }
    }

    override fun openWriter(id: String): BlobWriter {
        val p = path(id)
        if (!fileManager.fileExistsAtPath(p)) {
            fileManager.createFileAtPath(p, contents = null, attributes = null)
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(p)
            ?: error("NsFileManagerBlobStore: unable to open $p for writing")
        handle.seekToEndOfFile()
        return object : BlobWriter {
            override fun write(chunk: ByteArray) {
                if (chunk.isEmpty()) return
                handle.writeData(chunk.toNSData())
            }

            override fun close() {
                handle.closeFile()
            }
        }
    }

    override fun openReader(id: String, offset: Long): BlobReader {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path(id))
            ?: error("NsFileManagerBlobStore: unable to open ${path(id)} for reading")
        if (offset > 0) handle.seekToFileOffset(offset.toULong())
        return object : BlobReader {
            override fun read(into: ByteArray): Int {
                val data = handle.readDataOfLength(into.size.toULong())
                val n = data.length.toInt()
                if (n == 0) return -1
                data.copyInto(into)
                return n
            }

            override fun close() {
                handle.closeFile()
            }
        }
    }

    override fun pathFor(id: String): String? = path(id).takeIf { fileManager.fileExistsAtPath(it) }

    override fun delete(id: String) {
        runCatching { fileManager.removeItemAtPath(path(id), error = null) }
    }
}

private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}

private fun NSData.copyInto(dest: ByteArray) {
    val n = length.toInt()
    if (n == 0) return
    dest.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
}
