package dev.azula.net

/**
 * Reassembles arbitrary byte chunks (as delivered by iroh's `RecvStream.read`)
 * into newline-delimited UTF-8 lines. Splitting on the 0x0A byte is safe for
 * UTF-8 since 0x0A never appears inside a multibyte sequence.
 *
 * Also supports raw byte reads via [readBytes], which share the same internal
 * buffer as [readLine] — essential for binary file-transfer bodies that may
 * already be partially buffered after the preceding `file_begin` line was read.
 *
 * [readChunk] returns the next chunk of bytes, or null at end-of-stream.
 */
class LineBuffer(private val readChunk: suspend () -> ByteArray?) {
    private var buf = ByteArray(0)
    private var eof = false

    suspend fun readLine(): String? {
        while (true) {
            val nl = buf.indexOf('\n'.code.toByte())
            if (nl >= 0) {
                val line = buf.copyOfRange(0, nl).decodeToString()
                buf = buf.copyOfRange(nl + 1, buf.size)
                return line
            }
            if (eof) {
                if (buf.isNotEmpty()) {
                    val line = buf.decodeToString()
                    buf = ByteArray(0)
                    return line
                }
                return null
            }
            val chunk = readChunk()
            if (chunk == null) {
                eof = true
            } else if (chunk.isNotEmpty()) {
                buf += chunk
            }
        }
    }

    /**
     * Read exactly [n] bytes from the stream, consuming from the internal buffer
     * first before pulling more chunks. Returns null if EOF is reached before [n]
     * bytes are available.
     */
    suspend fun readBytes(n: Int): ByteArray? {
        while (buf.size < n) {
            if (eof) return null
            val chunk = readChunk()
            if (chunk == null) {
                eof = true
            } else if (chunk.isNotEmpty()) {
                buf += chunk
            }
        }
        if (buf.size < n) return null // EOF hit before we had enough
        val result = buf.copyOfRange(0, n)
        buf = buf.copyOfRange(n, buf.size)
        return result
    }
}

private fun ByteArray.indexOf(b: Byte): Int {
    for (i in indices) if (this[i] == b) return i
    return -1
}
