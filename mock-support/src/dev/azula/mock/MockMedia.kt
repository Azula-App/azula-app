package dev.azula.mock

import kotlin.math.PI
import kotlin.math.sin

/**
 * Media bodies for the seed fixtures, **synthesised at runtime** rather than
 * committed as binary assets.
 *
 * The `-mock` apps previously seeded no media at all, which quietly blocked
 * every media verification that needs something to look at: the image viewer
 * never opened (so pinch-zoom/pan/double-tap/swipe had no subject), and the
 * desktop poster + system-player pass had no poster. `FakeTransport` has always
 * had the machinery for this (`injectIncoming`, [InMemoryBlobStore]) -- nothing
 * called it.
 *
 * Generating the bytes here keeps binary blobs out of the repo entirely, and
 * makes the fixtures deterministic: same bytes on every platform and every run,
 * so a screenshot diff is meaningful.
 *
 * Both encoders are deliberately the dumbest correct thing:
 * - PNG uses **stored** (uncompressed) deflate blocks, so there is no compressor
 *   to get wrong -- just CRC-32, Adler-32 and a chunk framer.
 * - WAV is uncompressed 16-bit PCM, which is a header and some samples.
 *
 * Neither needs a platform API, so they work identically on jvm, android and iOS.
 */

// ---------------------------------------------------------------------------
// PNG
// ---------------------------------------------------------------------------

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
    c
}

private fun crc32(bytes: ByteArray): Int {
    var c = -1
    for (b in bytes) c = CRC_TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
    return c.inv()
}

/** zlib's checksum -- two rolling sums mod 65521, high half first. */
private fun adler32(bytes: ByteArray): Int {
    var a = 1
    var b = 0
    for (byte in bytes) {
        a = (a + (byte.toInt() and 0xFF)) % 65521
        b = (b + a) % 65521
    }
    return (b shl 16) or a
}

private fun beInt(value: Int) = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

/** length + type + data + CRC(type+data), PNG's chunk framing. */
private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.encodeToByteArray()
    return beInt(data.size) + typeBytes + data + beInt(crc32(typeBytes + data))
}

/**
 * Wraps [raw] in a zlib stream built entirely from stored deflate blocks: a
 * 2-byte header, then `[final flag][LEN][~LEN][bytes]` per block (max 65535
 * bytes each), then the Adler-32 of the *uncompressed* data. Larger on the wire
 * than real deflate, and completely sufficient for a fixture.
 */
private fun zlibStored(raw: ByteArray): ByteArray {
    val blocks = maxOf(1, (raw.size + 65534) / 65535)
    val out = ByteArray(2 + blocks * 5 + raw.size + 4)
    var o = 0
    out[o++] = 0x78 // CMF: deflate, 32K window
    out[o++] = 0x01 // FLG: no preset dict, fastest
    var i = 0
    do {
        val n = minOf(65535, raw.size - i)
        val isLast = i + n >= raw.size
        out[o++] = if (isLast) 1 else 0
        out[o++] = (n and 0xFF).toByte()
        out[o++] = ((n ushr 8) and 0xFF).toByte()
        val nlen = n.inv() and 0xFFFF
        out[o++] = (nlen and 0xFF).toByte()
        out[o++] = ((nlen ushr 8) and 0xFF).toByte()
        raw.copyInto(out, o, i, i + n)
        o += n
        i += n
    } while (i < raw.size)
    beInt(adler32(raw)).copyInto(out, o)
    return out
}

/**
 * A deterministic test card, [width] x [height], 8-bit truecolour PNG.
 *
 * Deliberately *not* a flat gradient: it carries a 16px grid, a 1px
 * checkerboard patch and hard-edged blocks, because the thing it exists to
 * verify is zoom. A gradient looks identical at every scale, so it cannot show
 * whether pinch-zoom actually resampled, whether pan moved the right distance,
 * or whether double-tap-to-zoom landed where the finger was. Fine detail makes
 * all three obvious at a glance.
 *
 * The colours here are decorative fixture values, not design-system tokens --
 * nothing should read them as brand palette.
 */
const val TEST_CARD_WIDTH = 240
const val TEST_CARD_HEIGHT = 180

/**
 * Blob ids for the seeded bodies.
 *
 * The fixtures are deliberately **blob-backed rather than inline**. `Chat.kt`
 * routes any attachment with `blobId == null && bytes != null` to
 * `LegacyAttachmentView`, which renders the picture but wires **no tap handler**
 * -- so an inline-bytes fixture looks right and is a dead end: the fullscreen
 * viewer (and therefore pinch-zoom, pan, double-tap and swipe-between) can only
 * be reached from the blob-backed path. Learned the hard way; don't "simplify"
 * these back to `bytesB64`.
 */
const val TEST_CARD_BLOB_ID = "seed-blob-tavi-screenshot"
const val TEST_TONE_BLOB_ID = "seed-blob-nori-voice-note"

/** Computed once: both the attachment metadata and the blob seed need these bytes. */
val TEST_CARD_BYTES: ByteArray by lazy { testCardPng() }
val TEST_TONE_BYTES: ByteArray by lazy { testToneWav() }

/**
 * The blob bodies [SeededMessageStore]'s attachments point at, for
 * `buildMockState` to hand to [InMemoryBlobStore]. Without this the attachments
 * resolve to a blob the store has never heard of and render as empty tiles.
 */
fun mockSeedBlobs(): Map<String, ByteArray> = mapOf(
    TEST_CARD_BLOB_ID to TEST_CARD_BYTES,
    TEST_TONE_BLOB_ID to TEST_TONE_BYTES,
)

fun testCardPng(width: Int = TEST_CARD_WIDTH, height: Int = TEST_CARD_HEIGHT): ByteArray {
    val stride = width * 3
    val raw = ByteArray(height * (1 + stride))
    for (y in 0 until height) {
        val rowStart = y * (1 + stride)
        raw[rowStart] = 0 // filter type 0 (None) -- no prediction to undo
        for (x in 0 until width) {
            val fx = x.toFloat() / width
            val fy = y.toFloat() / height
            // Base wash: magenta rising left-to-right, teal rising top-to-bottom.
            var r = (40 + 180 * fx).toInt()
            var g = (30 + 150 * fy).toInt()
            var b = (60 + 120 * (1 - fx)).toInt()
            // 16px grid so pan distance is countable.
            if (x % 16 == 0 || y % 16 == 0) { r += 45; g += 45; b += 45 }
            // 1px checkerboard patch: aliases hard if zoom resamples badly.
            if (x in 16..79 && y in 16..79 && (x + y) % 2 == 0) { r = 235; g = 235; b = 245 }
            // Solid blocks: unambiguous edges for double-tap-to-zoom targeting.
            if (x in 150..205 && y in 30..85) { r = 250; g = 60; b = 150 }
            if (x in 150..205 && y in 100..155) { r = 60; g = 220; b = 170 }
            val p = rowStart + 1 + x * 3
            raw[p] = r.coerceIn(0, 255).toByte()
            raw[p + 1] = g.coerceIn(0, 255).toByte()
            raw[p + 2] = b.coerceIn(0, 255).toByte()
        }
    }
    val ihdr = beInt(width) + beInt(height) + byteArrayOf(
        8, // bit depth
        2, // colour type 2 = truecolour RGB
        0, // deflate
        0, // adaptive filtering
        0, // no interlace
    )
    return PNG_SIGNATURE +
        pngChunk("IHDR", ihdr) +
        pngChunk("IDAT", zlibStored(raw)) +
        pngChunk("IEND", ByteArray(0))
}

// ---------------------------------------------------------------------------
// WAV
// ---------------------------------------------------------------------------

private fun leInt(value: Int) = byteArrayOf(
    value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
)

private fun leShort(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

/**
 * A [seconds]-long mono 16-bit PCM WAV: a tone sweeping upward under a
 * four-pulse amplitude envelope.
 *
 * The envelope is the point. A constant tone draws a featureless rectangle, so
 * it cannot show whether the waveform view actually computed peaks from the
 * body or just drew a placeholder; four clearly separated humps of *differing*
 * height can only come from real samples, and they make the playback cursor's
 * position self-evident while scrubbing.
 */
fun testToneWav(seconds: Double = 4.0, sampleRate: Int = 22_050): ByteArray {
    val frames = (seconds * sampleRate).toInt()
    val pcm = ByteArray(frames * 2)
    val pulseHeights = floatArrayOf(0.9f, 0.35f, 0.7f, 0.5f)
    for (n in 0 until frames) {
        val t = n.toDouble() / sampleRate
        val progress = n.toDouble() / frames
        val pulse = (progress * pulseHeights.size).toInt().coerceIn(0, pulseHeights.size - 1)
        // Raised-cosine envelope within each pulse, so peaks rise and fall smoothly
        // instead of clicking at the boundaries.
        val within = (progress * pulseHeights.size) - pulse
        val envelope = pulseHeights[pulse] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * within))
        val freq = 220.0 + 440.0 * progress // sweep, so the tone is audibly moving
        val sample = (sin(2 * PI * freq * t) * envelope * Short.MAX_VALUE).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm[n * 2] = sample.toByte()
        pcm[n * 2 + 1] = (sample shr 8).toByte()
    }
    val fmt = leShort(1) + // PCM
        leShort(1) + // mono
        leInt(sampleRate) +
        leInt(sampleRate * 2) + // byte rate = rate * channels * bytesPerSample
        leShort(2) + // block align
        leShort(16) // bits per sample
    val body = "WAVE".encodeToByteArray() +
        "fmt ".encodeToByteArray() + leInt(fmt.size) + fmt +
        "data".encodeToByteArray() + leInt(pcm.size) + pcm
    return "RIFF".encodeToByteArray() + leInt(body.size) + body
}
