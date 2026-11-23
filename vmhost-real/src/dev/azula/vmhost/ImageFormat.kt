package dev.azula.vmhost

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Where the guest image comes from: the same file and verification as the Mac host (`vmmgr.rs` `ensure_image`). */
internal object ImageSource {
    const val BASE_URL = "https://cloud.debian.org/images/cloud/trixie/latest"
    const val ARCHIVE = "debian-13-generic-arm64.tar.xz"

    /** The member of the archive that is the disk. */
    const val DISK_ENTRY = "disk.raw"
}

/** `SHA512SUMS` as published next to the image: `<128 hex>  <name>` per line (`*` before the name marks binary mode). */
internal object Sha512Sums {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val sp = t.indexOf(' ')
            if (sp != 128) continue
            val hash = t.substring(0, sp).lowercase()
            if (!hash.all { it in '0'..'9' || it in 'a'..'f' }) continue
            val name = t.substring(sp).trimStart(' ', '*')
            if (name.isNotEmpty()) out[name] = hash
        }
        return out
    }

    fun expected(text: String, archive: String): String? = parse(text)[archive]

    fun hex(digest: ByteArray): String = buildString(digest.size * 2) {
        for (b in digest) { append("0123456789abcdef"[(b.toInt() shr 4) and 15]); append("0123456789abcdef"[b.toInt() and 15]) }
    }

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-512")
}

/** Resume rules for the `.part` file, pure so they're testable. */
internal object Resume {
    /**
     * The byte to ask the server for next: what's on disk, or 0 when the `.part` is
     * longer than the archive can be (it belongs to a different file) or the size is unknown.
     */
    fun startOffset(partBytes: Long, totalBytes: Long): Long =
        if (totalBytes <= 0 || partBytes < 0 || partBytes > totalBytes) 0 else partBytes

    /** `Content-Range: bytes 100-299/300` → (100, 299, 300); null when it doesn't parse (`*` totals too). */
    fun parseContentRange(header: String?): Triple<Long, Long, Long>? {
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").matchEntire(header?.trim() ?: return null) ?: return null
        return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
    }
}

/** Waits between download attempts: 2 s, 6 s, 18 s, then caps. The caller gives up after [MAX_FAILURES] in a row. */
internal object Backoff {
    const val MAX_FAILURES = 3
    fun delayMs(failuresSoFar: Int): Long = when (failuresSoFar) {
        0, 1 -> 2_000
        2 -> 6_000
        else -> 18_000
    }
}

/** One stored run of an entry's bytes: [length] bytes at [offset] in the unpacked file. */
internal data class TarSegment(val offset: Long, val length: Long)

/**
 * A tar member. For a GNU sparse member (`S`, which is how Debian ships `disk.raw`):
 * [dataSize] is what's stored, [realSize] the unpacked size, [segments] where the stored
 * bytes go; the rest is a hole. A plain file is one segment [0, size).
 */
internal data class TarEntry(
    val name: String,
    val type: Char,
    val dataSize: Long,
    val realSize: Long,
    val segments: List<TarSegment>,
) {
    val isFile get() = type == '0' || type == '\u0000' || type == 'S'
}

internal class TarException(message: String) : IOException(message)

/**
 * A minimal streaming tar reader: ustar, GNU long names (`L`), pax headers (`x`, `g`: path
 * and size only) and GNU old-format sparse files (`S`). It never buffers more than a
 * block and only moves forward, so it reads straight off an xz decoder.
 */
internal class TarReader(private val input: InputStream) {
    private var remainingData = 0L
    private var padding = 0L

    /** The next member, or null at the end-of-archive marker (or the end of the stream). */
    fun next(): TarEntry? {
        skipCurrent()
        var longName: String? = null
        var paxPath: String? = null
        var paxSize: Long? = null
        while (true) {
            val h = ByteArray(BLOCK)
            if (!readBlock(h)) return null
            if (h.all { it == 0.toByte() }) return null
            verifyChecksum(h)
            val type = h[156].toInt().toChar()
            var size = numeric(h, 124, 12)
            when (type) {
                'L' -> { longName = String(readExact(size), Charsets.UTF_8).trimEnd('\u0000'); skipPad(size); continue }
                'x', 'g' -> {
                    val rec = parsePax(String(readExact(size), Charsets.UTF_8))
                    skipPad(size)
                    if (type == 'x') {
                        paxPath = rec["path"] ?: paxPath
                        paxSize = rec["size"]?.toLongOrNull() ?: paxSize
                        if (rec.keys.any { it.startsWith("GNU.sparse") }) throw TarException("pax sparse files are not supported")
                    }
                    continue
                }
            }
            var name = cstr(h, 0, 100)
            val ustar = String(h, 257, 6, Charsets.ISO_8859_1)
            if (ustar == "ustar\u0000") {
                val prefix = cstr(h, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }
            name = paxPath ?: longName ?: name
            if (paxSize != null) size = paxSize
            if (type == 'S') {
                val segs = ArrayList<TarSegment>()
                readSparseEntries(h, 386, 4, segs)
                var ext = h[482].toInt() != 0
                val real = numeric(h, 483, 12)
                while (ext) {
                    val e = ByteArray(BLOCK)
                    if (!readBlock(e)) throw EOFException("truncated sparse header")
                    readSparseEntries(e, 0, 21, segs)
                    ext = e[504].toInt() != 0
                }
                remainingData = size
                padding = pad(size)
                val stored = segs.sumOf { it.length }
                if (stored != size) throw TarException("sparse map covers $stored bytes but the member stores $size")
                return TarEntry(name, 'S', size, real, segs)
            }
            remainingData = size
            padding = pad(size)
            return TarEntry(name, type, size, size, listOf(TarSegment(0, size)))
        }
    }

    /**
     * Copies the current member's bytes to [sink] at their file offsets, then tells it the real
     * size. [progress] gets the stored bytes copied so far.
     */
    fun copyData(entry: TarEntry, sink: SparseSink, progress: (Long) -> Unit = {}) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        for (seg in entry.segments) {
            var left = seg.length
            var at = seg.offset
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("the archive ended inside ${entry.name}")
                sink.write(at, buf, 0, n)
                at += n; left -= n; done += n
                remainingData -= n
                progress(done)
            }
        }
        sink.finish(entry.realSize)
    }

    private fun skipCurrent() {
        skipFully(remainingData + padding)
        remainingData = 0; padding = 0
    }

    private fun skipPad(size: Long) = skipFully(pad(size))
    private fun pad(size: Long) = (BLOCK - size % BLOCK) % BLOCK

    private fun skipFully(n: Long) {
        var left = n
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("the archive ended early")
            left -= r
        }
    }

    private fun readExact(n: Long): ByteArray {
        if (n > MAX_META) throw TarException("header too large")
        val b = ByteArray(n.toInt())
        var at = 0
        while (at < b.size) {
            val r = input.read(b, at, b.size - at)
            if (r < 0) throw EOFException("the archive ended early")
            at += r
        }
        return b
    }

    /** False on a clean end of stream before any byte of the block. */
    private fun readBlock(b: ByteArray): Boolean {
        var at = 0
        while (at < b.size) {
            val r = input.read(b, at, b.size - at)
            if (r < 0) { if (at == 0) return false; throw EOFException("truncated tar header") }
            at += r
        }
        return true
    }

    private fun readSparseEntries(b: ByteArray, from: Int, count: Int, out: MutableList<TarSegment>) {
        for (i in 0 until count) {
            val o = from + i * 24
            if (b[o].toInt() == 0) break // unused slot: the rest are empty too
            out += TarSegment(numeric(b, o, 12), numeric(b, o + 12, 12))
        }
    }

    private fun verifyChecksum(h: ByteArray) {
        var sum = 0L
        for (i in 0 until BLOCK) sum += if (i in 148..155) 32 else (h[i].toInt() and 0xff)
        val stored = numeric(h, 148, 8)
        if (sum != stored) throw TarException("tar header checksum mismatch")
    }

    private fun parsePax(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0 || i + len > text.length) break
            val rec = text.substring(sp + 1, i + len).trimEnd('\n')
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return out
    }

    companion object {
        const val BLOCK = 512
        private const val MAX_META = 1L shl 20

        private fun cstr(b: ByteArray, from: Int, len: Int): String {
            var end = from
            while (end < from + len && b[end].toInt() != 0) end++
            return String(b, from, end - from, Charsets.UTF_8)
        }

        /** An octal field (NUL/space padded) or GNU base-256 (high bit of the first byte set). */
        fun numeric(b: ByteArray, from: Int, len: Int): Long {
            if (b[from].toInt() and 0x80 != 0) {
                var v = (b[from].toInt() and 0x7f).toLong()
                for (i in 1 until len) v = (v shl 8) or (b[from + i].toLong() and 0xff)
                return v
            }
            var v = 0L
            var seen = false
            for (i in from until from + len) {
                val c = b[i].toInt()
                if (c == 0 || c == 32) { if (seen) break else continue }
                if (c < '0'.code || c > '7'.code) throw TarException("bad number in tar header")
                v = v * 8 + (c - '0'.code); seen = true
            }
            return v
        }
    }
}

/** Where a member's bytes land: positioned writes (holes stay holes), then the final size. */
internal interface SparseSink {
    fun write(offset: Long, buf: ByteArray, off: Int, len: Int)
    fun finish(realSize: Long)
}

/** A [SparseSink] over a file: seeks past holes, so a 3 GiB image with 1.1 GB of data uses 1.1 GB. */
internal class FileSparseSink(private val raf: RandomAccessFile) : SparseSink {
    private var pos = -1L
    override fun write(offset: Long, buf: ByteArray, off: Int, len: Int) {
        if (offset != pos) raf.seek(offset)
        raf.write(buf, off, len)
        pos = offset + len
    }
    override fun finish(realSize: Long) { if (raf.length() != realSize) raf.setLength(realSize) }
}
