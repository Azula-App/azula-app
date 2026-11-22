import dev.azula.net.FrameLineTooLongException
import dev.azula.net.LineBuffer
import dev.azula.net.MAX_FRAME_LINE_BYTES
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LineBuffer]'s line reassembly and its [MAX_FRAME_LINE_BYTES] cap -- the Kotlin half of
 * azula-cli's `proto::size_cap_tests` (wire-protocols spec, "Frame Lines Are Bounded").
 */
class LineBufferTest {

    /** Hands [bytes] out in [chunkSize]-byte chunks, then end-of-stream. */
    private class Feed(private val bytes: ByteArray, private val chunkSize: Int) {
        private var offset = 0

        fun next(): ByteArray? {
            if (offset >= bytes.size) return null
            val end = minOf(offset + chunkSize, bytes.size)
            return bytes.copyOfRange(offset, end).also { offset = end }
        }
    }

    private fun lineBuffer(bytes: ByteArray, chunkSize: Int, maxLineBytes: Int = MAX_FRAME_LINE_BYTES): LineBuffer {
        val feed = Feed(bytes, chunkSize)
        return LineBuffer(maxLineBytes) { feed.next() }
    }

    private fun lineBuffer(text: String, chunkSize: Int = 3, maxLineBytes: Int = MAX_FRAME_LINE_BYTES) =
        lineBuffer(text.encodeToByteArray(), chunkSize, maxLineBytes)

    private fun xs(n: Int) = ByteArray(n) { 'x'.code.toByte() }

    @Test
    fun splitsLinesAcrossChunks() = runTest {
        val lines = lineBuffer("alpha\nbeta\n\ngamma", chunkSize = 3)
        assertEquals("alpha", lines.readLine())
        assertEquals("beta", lines.readLine())
        assertEquals("", lines.readLine())
        assertEquals("gamma", lines.readLine(), "a final unterminated line is still returned")
        assertNull(lines.readLine())
        assertNull(lines.readLine())
    }

    @Test
    fun keepsMultibyteUtf8IntactAcrossChunkBoundaries() = runTest {
        val lines = lineBuffer("héllo ✓\nnext\n", chunkSize = 1)
        assertEquals("héllo ✓", lines.readLine())
        assertEquals("next", lines.readLine())
        assertNull(lines.readLine())
    }

    @Test
    fun readBytesSharesTheLineBuffer() = runTest {
        val lines = lineBuffer("head\nBODY-tail\n", chunkSize = 64)
        assertEquals("head", lines.readLine())
        assertEquals("BODY", lines.readBytes(4)?.decodeToString())
        assertEquals("-tail", lines.readLine())
        assertNull(lines.readBytes(1), "EOF before n bytes is null")
    }

    @Test
    fun manyShortLinesAroundALongOneComeBackInOrder() = runTest {
        // Exercises the buffer's compaction and growth: thousands of small lines in big
        // chunks, with one 10 KB line in the middle, read against a 20 KB cap.
        val expected = (0 until 2000).map { "line $it" }.toMutableList()
        expected.add(1000, "y".repeat(10_000))
        val lines = lineBuffer(expected.joinToString("\n", postfix = "\n"), chunkSize = 777, maxLineBytes = 20_000)
        for (want in expected) assertEquals(want, lines.readLine())
        assertNull(lines.readLine())
    }

    @Test
    fun lineExactlyAtTheCapIsAccepted() = runTest {
        val lines = lineBuffer(xs(MAX_FRAME_LINE_BYTES) + "\nnext\n".encodeToByteArray(), chunkSize = 65536)
        assertEquals(MAX_FRAME_LINE_BYTES, lines.readLine()?.length)
        assertEquals("next", lines.readLine(), "the stream carries on after a line right at the cap")
        assertNull(lines.readLine())
    }

    @Test
    fun lineOneByteOverTheCapIsRejectedAndNeverSkipped() = runTest {
        val lines = lineBuffer(xs(MAX_FRAME_LINE_BYTES + 1) + "\nnext\n".encodeToByteArray(), chunkSize = 65536)
        val e = assertFailsWith<FrameLineTooLongException> { lines.readLine() }
        assertEquals(MAX_FRAME_LINE_BYTES, e.limit)
        // The buffer stays failed: the well-formed line behind it is never handed out.
        assertFailsWith<FrameLineTooLongException> { lines.readLine() }
        assertFailsWith<FrameLineTooLongException> { lines.readBytes(1) }
    }

    @Test
    fun endlessLineFailsWithoutBufferingPastTheCap() = runTest {
        val chunk = xs(65536)
        var pulled = 0L
        val lines = LineBuffer {
            pulled += chunk.size
            chunk
        }
        assertFailsWith<FrameLineTooLongException> { lines.readLine() }
        assertTrue(pulled <= MAX_FRAME_LINE_BYTES.toLong() + chunk.size, "pulled $pulled bytes of an endless line")
    }

    @Test
    fun capCountsUtf8BytesAndExcludesTheNewline() = runTest {
        // "é" is two UTF-8 bytes: a 4-byte cap holds "éé" but not "ééx".
        val lines = lineBuffer("éé\nééx\n", chunkSize = 1, maxLineBytes = 4)
        assertEquals("éé", lines.readLine())
        assertFailsWith<FrameLineTooLongException> { lines.readLine() }
    }

    @Test
    fun unterminatedFinalLineIsCappedToo() = runTest {
        assertEquals("abcd", lineBuffer("abcd", maxLineBytes = 4).readLine())
        assertFailsWith<FrameLineTooLongException> { lineBuffer("abcde", maxLineBytes = 4).readLine() }
    }
}
