package dev.azula.vmhost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Persisted setup progress (`<vm dir>/provision.json`): everything needed to resume a step after process death. */
@Serializable
internal data class ProvisionState(
    val step: ProvisionStep = ProvisionStep.DOWNLOADING,
    /** The archive's real size from the server; 0 until known. */
    val archiveBytes: Long = 0,
    /** Expected SHA-512 of the archive, from `SHA512SUMS`; empty until fetched. */
    val sha512: String = "",
    /** `ETag` or `Last-Modified` of the archive, for `If-Range`. */
    val validator: String = "",
    /** The disk size to grow to, fixed when setup starts so a resume doesn't re-derive it from shrunken free space. */
    val diskGb: Int = 0,
    /** `disk.raw` is unpacked, grown and the seed is staged. */
    val imageReady: Boolean = false,
    /** The VM booted at least once to the login prompt with its broker serving. On its own this doesn't mean the tools are in: see [toolsSeedId]. */
    val firstBootDone: Boolean = false,
    /** The seed (its SHA-256) the guest's tools were seen installed with. Empty in state from before tools were verified, so nothing it says is believed. */
    val toolsSeedId: String = "",
    val corruptDownloads: Int = 0,
) {
    /** Setup is over for the seed [seedId]: the image is ready, and the guest reported its tools in under this very seed. */
    fun doneFor(seedId: String): Boolean = imageReady && firstBootDone && toolsSeedId.isNotEmpty() && toolsSeedId == seedId
}

/** Setup stopped for a reason the user can act on; [retryable] says whether [VmHost.provision] again can fix it. */
internal class ProvisionFailure(message: String, val retryable: Boolean = true) : IOException(message)

/**
 * The image pipeline (design D9 S3, steps 1-2): download `debian-13-generic-arm64.tar.xz`
 * (resumable), verify its SHA-512 against `SHA512SUMS`, unpack `disk.raw`, grow it, stage
 * the seed. Every step is idempotent and persists before it ends, so [run] picks up where a
 * killed process left off: the `.part` keeps its bytes.
 *
 * The unpack runs while the download does ([StreamingUnpack]), so EXTRACTING usually only
 * waits for its last bytes. Its disk counts only once EXTRACTING is reached, with the archive
 * verified, and only if the bytes it read had that same checksum. In every other case (a
 * process killed on the way, a failed or mismatched unpack) the disk is unpacked from the
 * front again: from the `.part` as far as it goes, or from the verified archive. xz can't seek.
 *
 * Pure `java.io` + `java.net` so it runs in JVM tests; Android supplies [freeBytes],
 * [allocatedBytes] and [stageSeed].
 */
internal class ImageProvisioner(
    private val dir: File,
    private val freeBytes: () -> Long,
    private val stageSeed: () -> Unit,
    private val listener: Listener,
    private val cancelled: () -> Boolean,
    private val downloader: ImageDownloader = ImageDownloader(),
    private val http: HttpFetch = HttpFetch(),
    private val baseUrl: String = ImageSource.BASE_URL,
    private val archiveName: String = ImageSource.ARCHIVE,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    /** The user's disk size from S1's "advanced" panel, if any. */
    private val diskGbOverride: () -> Int? = { null },
    /** The storage a sparse file takes up; its length would count the holes. */
    private val allocatedBytes: (File) -> Long = { 0 },
    private val xz: Xz = Xz(),
) {
    fun interface Listener { fun onProgress(step: ProvisionStep, done: Long, total: Long) }

    private val stateFile = File(dir, "provision.json")
    private val archive = File(dir, archiveName)
    private val part = File(dir, "$archiveName.part")
    private val disk = File(dir, ImageSource.DISK_ENTRY)
    private val diskPart = File(dir, "${ImageSource.DISK_ENTRY}.part")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The unpack running alongside the download, if any. Only [run]'s thread touches it. */
    private var streaming: StreamingUnpack? = null

    /** Bytes of earlier attempts still on disk; they don't count against the free-space need. */
    fun footprintBytes(): Long = listOf(part, archive).filter { it.exists() }.sumOf { it.length() } + allocatedBytes(diskPart)

    @Synchronized fun load(): ProvisionState {
        if (stateFile.exists()) {
            runCatching { return json.decodeFromString(ProvisionState.serializer(), stateFile.readText()) }
            return ProvisionState()
        }
        // A disk staged by hand before setup existed (the 3.1 harness): already booted once.
        return if (disk.exists()) ProvisionState(step = ProvisionStep.PREPARING_DISK, imageReady = true, firstBootDone = true)
        else ProvisionState()
    }

    @Synchronized fun save(s: ProvisionState) {
        dir.mkdirs()
        val tmp = File(dir, "provision.json.tmp")
        FileOutputStream(tmp).use { it.write(json.encodeToString(ProvisionState.serializer(), s).toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(stateFile)) throw IOException("rename failed for provision.json")
    }

    fun needsImage(): Boolean = !load().imageReady

    /** The VM is up and the guest reported its tools in, from the seed [seedId]. */
    fun markFirstBootDone(seedId: String) { save(load().copy(firstBootDone = true, toolsSeedId = seedId)) }

    /** Runs every remaining step. Returns when `disk.raw` is ready; throws [ProvisionFailure] or [CancelledException]. */
    fun run() {
        dir.mkdirs()
        var st = load()
        if (st.imageReady) return
        if (st.diskGb == 0) st = st.copy(diskGb = diskGbOverride() ?: diskSizeGb(freeBytes() + footprintBytes())).also(::save)
        try {
            while (!st.imageReady) {
                checkCancelled()
                val began = System.nanoTime()
                val step = st.step
                st = when (step) {
                    ProvisionStep.DOWNLOADING -> download(st)
                    ProvisionStep.VERIFYING -> verify(st)
                    ProvisionStep.EXTRACTING -> extract(st)
                    else -> prepareDisk(st)
                }
                val secs = (System.nanoTime() - began) / 1e9
                log("step $step took ${"%.1f".format(secs)} s -> ${st.step}")
            }
        } finally {
            stopStreaming()
        }
    }

    // ---- 1. download ----

    private fun download(start: ProvisionState): ProvisionState {
        var st = start
        if (archive.exists() && st.archiveBytes > 0 && archive.length() == st.archiveBytes) {
            return st.copy(step = ProvisionStep.VERIFYING).also(::save)
        }
        val url = "$baseUrl/$archiveName"
        var failures = 0
        var have = 0L
        while (true) {
            checkCancelled()
            have = if (part.exists()) part.length() else 0L
            try {
                if (st.archiveBytes == 0L || st.sha512.isEmpty()) {
                    val info = downloader.probe(url)
                    val want = Sha512Sums.expected(http.text("$baseUrl/SHA512SUMS"), archiveName)
                        ?: throw ProvisionFailure("the image list has no entry for $archiveName", retryable = true)
                    // A new archive identity: anything already in the .part belongs to the old one.
                    if (st.validator != info.validator || st.archiveBytes != info.bytes) part.delete()
                    st = st.copy(archiveBytes = info.bytes, sha512 = want, validator = info.validator).also(::save)
                }
                // What's already unpacked is part of the need, not on top of it.
                evaluateProvisionSpace(freeBytes() + allocatedBytes(diskPart), st.archiveBytes - have)?.let { throw ProvisionFailure(it.message) }
                if (streaming == null) streaming = startStreaming(st, have)
                val info = RemoteInfo(st.archiveBytes, st.validator)
                val from = downloader.download(url, part, info, ::isCancelled) { done ->
                    streaming?.grew(done)
                    listener.onProgress(ProvisionStep.DOWNLOADING, done, st.archiveBytes)
                }
                log("download resumed from byte $from of ${st.archiveBytes}")
                if (!part.renameTo(archive)) throw IOException("rename failed for $archiveName")
                return st.copy(step = ProvisionStep.VERIFYING).also(::save)
            } catch (e: ImageChangedException) {
                // Debian replaced `latest` since the size, checksum and bytes on disk were taken down.
                // Keeping them would fail every retry the same way, so start over on the new one.
                log("${e.message}; starting over")
                stopStreaming()
                part.delete(); diskPart.delete()
                st = st.copy(archiveBytes = 0, sha512 = "", validator = "").also(::save)
                failures++
                if (failures >= Backoff.MAX_FAILURES) {
                    throw ProvisionFailure("the image keeps changing on the server · retry", retryable = true)
                }
                sleepCancellable(Backoff.delayMs(failures))
            } catch (e: NetworkException) {
                // Three failures in a row without moving the .part forward end the attempt.
                if ((if (part.exists()) part.length() else 0L) > have) failures = 0
                failures++
                log("network failure $failures: ${e.message}")
                if (failures >= Backoff.MAX_FAILURES) {
                    throw ProvisionFailure("couldn't download the image · check your connection", retryable = true)
                }
                sleepCancellable(Backoff.delayMs(failures))
            }
        }
    }

    // ---- 2. verify ----

    private fun verify(st: ProvisionState): ProvisionState {
        if (!archive.exists()) return st.copy(step = ProvisionStep.DOWNLOADING).also(::save)
        val digest = Sha512Sums.newDigest()
        val total = archive.length()
        var done = 0L
        FileInputStream(archive).use { inp ->
            val buf = ByteArray(256 * 1024)
            var lastReport = 0L
            while (true) {
                checkCancelled()
                val n = inp.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                done += n
                if (done - lastReport >= 4L shl 20 || done == total) { lastReport = done; listener.onProgress(ProvisionStep.VERIFYING, done, total) }
            }
        }
        val got = Sha512Sums.hex(digest.digest())
        if (got == st.sha512) return st.copy(step = ProvisionStep.EXTRACTING, corruptDownloads = 0).also(::save)
        // Reject it: discard everything and start the download from 0, re-reading SHA512SUMS.
        log("checksum mismatch: expected ${st.sha512}, got $got")
        stopStreaming()
        diskPart.delete(); archive.delete(); part.delete()
        val bad = st.corruptDownloads + 1
        if (bad >= Backoff.MAX_FAILURES) {
            save(st.copy(step = ProvisionStep.DOWNLOADING, archiveBytes = 0, sha512 = "", validator = "", corruptDownloads = 0))
            throw ProvisionFailure("the downloaded image is corrupt · retry", retryable = true)
        }
        return st.copy(step = ProvisionStep.DOWNLOADING, archiveBytes = 0, sha512 = "", validator = "", corruptDownloads = bad).also(::save)
    }

    // ---- 3. extract ----

    private fun extract(st: ProvisionState): ProvisionState {
        if (!archive.exists()) {
            // Verified and unpacked before the process died after deleting the archive: the disk is whole,
            // unless this very run is still writing it.
            stopStreaming()
            return st.copy(step = if (diskPart.exists() || disk.exists()) ProvisionStep.PREPARING_DISK else ProvisionStep.DOWNLOADING).also(::save)
        }
        if (!takeStreamed(st)) unpackArchive()
        archive.delete()
        return st.copy(step = ProvisionStep.PREPARING_DISK).also(::save)
    }

    /** Unpacks from the `.part` as it grows. Null if that can't be set up; the archive is then unpacked once it's complete. */
    private fun startStreaming(st: ProvisionState, have: Long): StreamingUnpack? = try {
        log("unpacking while downloading (${xz.name}), ${have shr 20} MB of the archive already here")
        part.createNewFile()
        StreamingUnpack(part, st.archiveBytes, diskPart, xz::open).also { it.start(); it.grew(have) }
    } catch (e: Throwable) {
        log("can't unpack while downloading: $e")
        null
    }

    /** True if the unpack alongside the download finished, from the same bytes [verify] accepted. */
    private fun takeStreamed(st: ProvisionState): Boolean {
        val s = streaming ?: return false
        val began = System.nanoTime()
        var reported = -1L
        val r = s.await { done, size ->
            checkCancelled()
            if (done != reported) { reported = done; listener.onProgress(ProvisionStep.EXTRACTING, done, size) }
        }
        if (r is StreamingUnpack.Outcome.Unpacked && r.sha512 == st.sha512) {
            streaming = null
            log("unpacked while downloading: ${r.stats}; waited ${"%.1f".format((System.nanoTime() - began) / 1e9)} s for it")
            return true
        }
        log("the unpack alongside the download is no good (${if (r is StreamingUnpack.Outcome.Failed) "${r.cause}" else "it read other bytes"}); unpacking the archive")
        stopStreaming()
        return false
    }

    private fun unpackArchive() {
        try {
            try { unpackArchive(xz::open) } catch (e: NativeXzException) {
                // The archive is verified, so it's the decoder's fault, not the data's.
                log("${e.message}; using the java decoder")
                unpackArchive(xz::openJava)
            }
        } catch (e: IOException) {
            if (e is ProvisionFailure) throw e
            // A verified archive that won't unpack: out of space, or a decoder fault. Keep the archive; surface it.
            throw ProvisionFailure("couldn't unpack the image (${e.message}) · retry", retryable = true)
        }
    }

    private fun unpackArchive(open: (InputStream) -> InputStream) {
        FileInputStream(archive).use { inp ->
            open(inp).use { tar ->
                val stats = unpackDisk(tar, diskPart, ::checkCancelled) { done, size -> listener.onProgress(ProvisionStep.EXTRACTING, done, size) }
                log("unpacked the archive: $stats")
            }
        }
    }

    /** Ends the unpack alongside the download, if there is one, and deletes its disk. */
    private fun stopStreaming() {
        streaming?.cancel()
        streaming = null
    }

    // ---- 4. prepare the disk ----

    private fun prepareDisk(st: ProvisionState): ProvisionState {
        listener.onProgress(ProvisionStep.PREPARING_DISK, 0, 0)
        if (diskPart.exists()) {
            disk.delete()
            if (!diskPart.renameTo(disk)) throw IOException("rename failed for ${disk.name}")
        }
        if (!disk.exists()) return st.copy(step = ProvisionStep.DOWNLOADING).also(::save)
        // Grown sparse; cloud-init's growpart expands the root filesystem on first boot.
        RandomAccessFile(disk, "rw").use { raf ->
            val want = st.diskGb.toLong() shl 30
            if (raf.length() < want) raf.setLength(want)
        }
        stageSeed()
        listener.onProgress(ProvisionStep.PREPARING_DISK, 1, 1)
        return st.copy(step = ProvisionStep.PREPARING_DISK, imageReady = true).also(::save)
    }

    private fun isCancelled() = cancelled()
    private fun checkCancelled() { if (cancelled()) throw CancelledException() }

    private fun sleepCancellable(ms: Long) {
        var left = ms
        while (left > 0) { checkCancelled(); val s = minOf(left, 250); sleep(s); left -= s }
    }
}
