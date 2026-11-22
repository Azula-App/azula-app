package dev.azula.net

/**
 * The longest NDJSON frame line a [LineBuffer] accepts, in bytes, not counting the
 * terminating `\n` -- the same 16 MiB cap azula-cli's `proto::read_frame` enforces
 * (`MAX_FRAME_LINE_BYTES` there). Legitimate frames stay far below it: those with their
 * own bounds top out well under 1 MiB, and batched ones split by [BATCH_BYTES_PER_FRAME].
 * See azula-docs/openspec/specs/wire-protocols/design.md, "Frame size cap".
 */
const val MAX_FRAME_LINE_BYTES: Int = 16 * 1024 * 1024

/**
 * Byte budget for senders that pack a variable number of items into one frame
 * (`sync_entries`): a batch is closed once its items reach this many bytes, so it stays
 * far below [MAX_FRAME_LINE_BYTES] whatever the items' sizes. Mirrors azula-cli's
 * `proto::BATCH_BYTES_PER_FRAME`.
 */
const val BATCH_BYTES_PER_FRAME: Int = MAX_FRAME_LINE_BYTES / 4

/**
 * Thrown by [LineBuffer] once the peer's current line runs past [limit] bytes. A protocol
 * error, not a frame to skip: the buffer stays failed, rethrowing this on every later
 * read, so nothing after the oversize line is ever handed out.
 */
class FrameLineTooLongException(val limit: Int) : Exception("frame line exceeds the $limit byte cap")

/**
 * Reassembles arbitrary byte chunks (as delivered by iroh's `RecvStream.read`)
 * into newline-delimited UTF-8 lines. Splitting on the 0x0A byte is safe for
 * UTF-8 since 0x0A never appears inside a multibyte sequence.
 *
 * A line longer than [maxLineBytes] (excluding its `\n`) fails the buffer with
 * [FrameLineTooLongException] as soon as more than [maxLineBytes] of it is buffered, so
 * a peer can't make this hold an unbounded line: at most [maxLineBytes] plus one chunk.
 * Each byte is scanned for `\n` once and buffered without whole-buffer copies per chunk,
 * so working up to the cap costs linear, not quadratic, time.
 *
 * Also supports raw byte reads via [readBytes], which share the same internal
 * buffer as [readLine] — essential for binary file-transfer bodies that may
 * already be partially buffered after the preceding `file_begin` line was read.
 *
 * [readChunk] returns the next chunk of bytes, or null at end-of-stream.
 */
class LineBuffer(
    private val maxLineBytes: Int = MAX_FRAME_LINE_BYTES,
    private val readChunk: suspend () -> ByteArray?,
) {
    // Unread bytes are buf[start, end); buf[start, scanned) is known to hold no '\n'.
    private var buf = ByteArray(0)
    private var start = 0
    private var end = 0
    private var scanned = 0
    private var eof = false
    private var failure: FrameLineTooLongException? = null

    init {
        require(maxLineBytes in 0 until Int.MAX_VALUE) { "maxLineBytes out of range: $maxLineBytes" }
    }

    /**
     * The next line without its `\n`, or null at end-of-stream (a final unterminated line
     * is returned first). Throws [FrameLineTooLongException] for a line over
     * [maxLineBytes], and from then on.
     */
    suspend fun readLine(): String? {
        failure?.let { throw it }
        while (true) {
            val nl = findNewline()
            if (nl >= 0) {
                if (nl - start > maxLineBytes) fail()
                val line = buf.decodeToString(start, nl)
                consumeTo(nl + 1)
                return line
            }
            if (end - start > maxLineBytes) fail()
            if (eof) {
                if (end == start) return null
                val line = buf.decodeToString(start, end)
                consumeTo(end)
                return line
            }
            pull()
        }
    }

    /**
     * Read exactly [n] bytes from the stream, consuming from the internal buffer
     * first before pulling more chunks. Returns null if EOF is reached before [n]
     * bytes are available. Throws [FrameLineTooLongException] once the buffer has failed.
     */
    suspend fun readBytes(n: Int): ByteArray? {
        failure?.let { throw it }
        while (end - start < n) {
            if (eof) return null
            pull()
        }
        val result = buf.copyOfRange(start, start + n)
        consumeTo(start + n)
        return result
    }

    private fun findNewline(): Int {
        for (i in scanned until end) {
            if (buf[i] == NEWLINE) return i
        }
        scanned = end
        return -1
    }

    private fun consumeTo(index: Int) {
        start = index
        if (scanned < start) scanned = start
        if (start == end) {
            start = 0
            end = 0
            scanned = 0
        }
    }

    private suspend fun pull() {
        val chunk = readChunk()
        if (chunk == null) {
            eof = true
            return
        }
        if (chunk.isEmpty()) return
        makeRoom(chunk.size)
        chunk.copyInto(buf, end)
        end += chunk.size
    }

    /**
     * Ensure [n] more bytes fit after [end]: slide the unread bytes to the front when
     * they'd fill at most half the buffer, else move them into a bigger one. Growth
     * doubles but is clamped near the cap, so a line that's about to fail never makes
     * this allocate twice the cap.
     */
    private fun makeRoom(n: Int) {
        if (end + n <= buf.size) return
        val live = end - start
        val needed = live + n
        val target = if (needed <= buf.size / 2) {
            buf
        } else {
            val doubled = if (buf.size > Int.MAX_VALUE / 2) Int.MAX_VALUE else buf.size * 2
            ByteArray(maxOf(needed, minOf(doubled, maxLineBytes + 1), MIN_CAPACITY))
        }
        buf.copyInto(target, 0, start, end)
        buf = target
        scanned -= start
        start = 0
        end = live
    }

    private fun fail(): Nothing {
        val e = FrameLineTooLongException(maxLineBytes)
        failure = e
        buf = ByteArray(0)
        start = 0
        end = 0
        scanned = 0
        throw e
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
        const val MIN_CAPACITY = 4096
    }
}
