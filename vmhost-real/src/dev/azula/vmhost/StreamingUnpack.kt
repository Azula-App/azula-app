package dev.azula.vmhost

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How long an unpack took, for the log. [secs] is all of it: waiting for the download, and both kinds of sync. */
internal class UnpackStats(val bytes: Long, val secs: Double, val syncSecs: Double, val lastSyncSecs: Double) {
    override fun toString() = "%d MB in %.1f s, of it %.1f s syncing (the last %.1f s)".format(bytes shr 20, secs, syncSecs, lastSyncSecs)
}

/** Unsynced bytes an unpack lets build up, so that the sync at its end is short. */
private const val SYNC_EVERY = 128L shl 20

/**
 * Copies `disk.raw` out of the tar on [tar] into a fresh [dest], holes and all, and syncs it.
 * [checkCancelled] runs on every chunk; [progress] gets (stored bytes written, stored bytes in
 * all). On any failure [dest] is gone.
 */
internal fun unpackDisk(tar: InputStream, dest: File, checkCancelled: () -> Unit, progress: (Long, Long) -> Unit): UnpackStats {
    dest.delete()
    try {
        RandomAccessFile(dest, "rw").use { raf ->
            val began = System.nanoTime()
            val reader = TarReader(tar)
            while (true) {
                val e = reader.next() ?: throw ProvisionFailure("the image didn't contain a disk", retryable = false)
                if (!e.isFile || e.name.removePrefix("./") != ImageSource.DISK_ENTRY) continue
                var reported = 0L
                var synced = 0L
                var syncNanos = 0L
                fun sync(): Long {
                    val t = System.nanoTime()
                    raf.fd.sync()
                    return (System.nanoTime() - t).also { syncNanos += it }
                }
                reader.copyData(e, FileSparseSink(raf)) { done ->
                    if (done - reported >= 8L shl 20 || done == e.dataSize) { reported = done; progress(done, e.dataSize) }
                    if (done - synced >= SYNC_EVERY) { synced = done; sync() }
                    checkCancelled()
                }
                val last = sync()
                return UnpackStats(e.dataSize, (System.nanoTime() - began) / 1e9, syncNanos / 1e9, last / 1e9)
            }
        }
    } catch (e: Throwable) {
        dest.delete()
        throw e
    }
}

/**
 * The first [total] bytes of [file], which a download is still appending to: a read of bytes
 * that aren't there yet blocks until [grew] says they are, or throws once [cancel]led.
 *
 * It hashes exactly the bytes it hands out. The download may throw the file away and start
 * over under a reader (a server that ignores the range), so the finished file having the
 * right checksum doesn't say that what was read along the way did; [drainAndHash] does.
 */
internal class TailingInputStream(file: File, private val total: Long) : InputStream() {
    private val input = FileInputStream(file)
    private val digest = Sha512Sums.newDigest()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var available = 0L
    private var cancelled = false
    private var pos = 0L
    private val one = ByteArray(1)

    fun grew(bytes: Long) = lock.withLock { available = bytes; changed.signalAll() }

    fun cancel() = lock.withLock { cancelled = true; changed.signalAll() }

    fun checkCancelled() = lock.withLock { if (cancelled) throw CancelledException() }

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos < total) {
            val ready = awaitMore()
            val n = input.read(b, off, minOf(len.toLong(), minOf(ready, total) - pos).toInt())
            if (n > 0) {
                digest.update(b, off, n)
                pos += n
                return n
            }
            // Cut short since [grew]: the download is starting over and will say how far it gets.
            lock.withLock {
                if (available >= total) throw EOFException("the archive shrank while it was being unpacked")
                if (available == ready) available = pos
            }
        }
        checkCancelled()
        return -1
    }

    private fun awaitMore(): Long = lock.withLock {
        while (available <= pos && !cancelled) changed.await()
        if (cancelled) throw CancelledException()
        available
    }

    /** Reads to the end, and returns the SHA-512 of all [total] bytes as they were read. */
    fun drainAndHash(): String {
        val buf = ByteArray(256 * 1024)
        while (read(buf, 0, buf.size) >= 0) { /* hashed as it's read */ }
        return Sha512Sums.hex(digest.digest())
    }

    override fun close() = input.close()
}

/**
 * Unpacks `disk.raw` from [archive] into [dest] on its own thread while the archive is still
 * downloading (design D9 S3), always from byte 0: after a restart it catches up with what's
 * already on disk. Nothing it writes is to be trusted until [Outcome.Unpacked.sha512] is the
 * archive's published checksum.
 */
internal class StreamingUnpack(
    archive: File, total: Long, private val dest: File, private val openXz: (InputStream) -> InputStream,
) {
    sealed interface Outcome {
        /** [sha512] is of every byte of the archive that this unpack read. */
        class Unpacked(val sha512: String, val stats: UnpackStats) : Outcome
        class Failed(val cause: Throwable) : Outcome
    }

    private val tail = TailingInputStream(archive, total)
    @Volatile private var outcome: Outcome? = null
    @Volatile private var done = 0L
    @Volatile private var size = 0L
    private val thread = Thread({ outcome = unpack() }, "vmhost-unpack").apply { isDaemon = true }

    fun start() {
        try { thread.start() } catch (e: Throwable) { tail.close(); throw e }
    }

    /** The download now has [bytes] bytes on disk: fewer than before, if it started over. */
    fun grew(bytes: Long) = tail.grew(bytes)

    /** Stops, and deletes whatever was written. Its thread is gone when this returns. */
    fun cancel() {
        tail.cancel()
        var interrupted = false
        while (thread.isAlive) try { thread.join() } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
        dest.delete()
    }

    /** Waits for the end. [poll] gets (stored bytes written, stored bytes in all) meanwhile, and may throw. */
    fun await(poll: (Long, Long) -> Unit): Outcome {
        while (true) {
            thread.join(250)
            poll(done, size)
            if (!thread.isAlive) return outcome ?: Outcome.Failed(IllegalStateException("the unpack thread died"))
        }
    }

    private fun unpack(): Outcome = try {
        tail.use {
            openXz(tail).use { xz ->
                val stats = unpackDisk(xz, dest, tail::checkCancelled) { d, s -> done = d; size = s }
                Outcome.Unpacked(tail.drainAndHash(), stats)
            }
        }
    } catch (e: Throwable) {
        Outcome.Failed(e)
    }
}
