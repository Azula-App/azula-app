import app.azula.vmhost.NativeXz
import dev.azula.vmhost.NativeXzException
import dev.azula.vmhost.NativeXzInputStream
import dev.azula.vmhost.Xz
import dev.azula.vmhost.XzCodec
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The decoder's stream over a stand-in for the library, and the fallback for when there is none. */
class XzTest {
    /** "Decodes" by doubling: each input byte comes out twice. Takes and gives in awkward amounts. */
    private class Doubler(private val maxIn: Int = Int.MAX_VALUE, private val maxOut: Int = Int.MAX_VALUE, private val failAt: Int = -1) : XzCodec {
        private val held = ArrayDeque<Byte>()
        private var taken = 0
        val destroyed = ArrayList<Long>()
        override fun create(threads: Int, memLimit: Long) = 7L
        override fun decode(handle: Long, input: ByteArray, inOff: Int, inLen: Int, output: ByteArray, outOff: Int, outLen: Int, finish: Boolean): Long {
            assertEquals(7L, handle)
            assertTrue(inOff >= 0 && inLen >= 0 && inOff + inLen <= input.size && outOff >= 0 && outLen > 0 && outOff + outLen <= output.size)
            val n = minOf(inLen, maxIn)
            for (i in inOff until inOff + n) { held += input[i]; held += input[i] }
            taken += n
            if (failAt in 0..taken) return -2
            val out = minOf(outLen, maxOut, held.size)
            for (i in 0 until out) output[outOff + i] = held.removeFirst()
            val end = finish && n == inLen && held.isEmpty()
            return n.toLong() or (out.toLong() shl 31) or (if (end) 1L shl 62 else 0)
        }
        override fun destroy(handle: Long) { destroyed += handle }
    }

    private class Tracked(private val b: ByteArray, private val perRead: Int) : InputStream() {
        private val inner = ByteArrayInputStream(b)
        var reads = 0; var closed = 0
        override fun read() = inner.read()
        override fun read(buf: ByteArray, off: Int, len: Int): Int { reads++; return inner.read(buf, off, minOf(len, perRead)) }
        override fun close() { closed++ }
    }

    private val data = Random(1).nextBytes(20_000)
    private val doubled = ByteArray(40_000) { data[it / 2] }

    @Test fun decodesWhateverTheChunks() {
        val lots = 1 shl 20
        for (perRead in listOf(1, 7, 4096, lots)) for ((i, o) in listOf(1 to 1, 3 to 1000, lots to 5, lots to lots)) {
            val codec = Doubler(i, o)
            NativeXzInputStream(Tracked(data, perRead), codec).use { assertContentEquals(doubled, it.readBytes(), "$perRead/$i/$o") }
            assertEquals(listOf(7L), codec.destroyed)
        }
    }

    @Test fun readsSingleBytesAndSlices() {
        val s = NativeXzInputStream(Tracked(data, 100), Doubler())
        assertEquals(data[0].toInt() and 0xff, s.read())
        val buf = ByteArray(50)
        assertEquals(10, s.read(buf, 20, 10))
        assertContentEquals(doubled.copyOfRange(1, 11), buf.copyOfRange(20, 30))
        assertTrue(buf.copyOfRange(0, 20).all { it == 0.toByte() } && buf.copyOfRange(30, 50).all { it == 0.toByte() })
        assertEquals(0, s.read(buf, 0, 0))
        assertFailsWith<IndexOutOfBoundsException> { s.read(buf, 45, 10) }
        assertFailsWith<IndexOutOfBoundsException> { s.read(buf, -1, 10) }
        s.readBytes()
        assertEquals(-1, s.read())
        assertEquals(-1, s.read(buf))
    }

    @Test fun doesNotGoBackToTheSourceWhileOutputIsReady() {
        val src = Tracked(data, 100)
        val s = NativeXzInputStream(src, Doubler())
        val buf = ByteArray(64)
        assertEquals(64, s.read(buf))
        assertEquals(64, s.read(buf))
        assertEquals(64, s.read(buf))
        assertEquals(8, s.read(buf))
        assertEquals(1, src.reads, "a source that blocks would have held back decoded bytes")
        assertEquals(64, s.read(buf))
        assertEquals(2, src.reads)
    }

    @Test fun anEmptySourceEnds() {
        assertEquals(-1, NativeXzInputStream(Tracked(ByteArray(0), 1), Doubler()).read())
    }

    @Test fun failuresAreIOExceptionsThatSayWhat() {
        val e = assertFailsWith<NativeXzException> { NativeXzInputStream(Tracked(data, 100), Doubler(failAt = 5000)).readBytes() }
        assertIs<IOException>(e)
        assertEquals("xz: corrupt data", e.message)
        assertEquals(-2L, e.code)
        val none = object : XzCodec by Doubler() { override fun create(threads: Int, memLimit: Long) = 0L }
        assertFailsWith<NativeXzException> { NativeXzInputStream(Tracked(data, 100), none) }
    }

    @Test fun closingFreesTheDecoderOnceAndClosesTheSource() {
        val codec = Doubler(); val src = Tracked(data, 100)
        val s = NativeXzInputStream(src, codec)
        s.read()
        s.close(); s.close()
        assertEquals(listOf(7L), codec.destroyed)
        assertTrue(src.closed > 0)
    }

    @Test fun withoutTheLibraryTheJavaDecoderStandsIn() {
        assertFalse(NativeXz.available, "there is no libazula_host on a JVM")
        val xz = ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options(1)).use { it.write(data) } }.toByteArray()
        assertEquals("java xz", Xz().name)
        assertContentEquals(data, Xz().open(ByteArrayInputStream(xz)).use { it.readBytes() })
    }

    @Test fun withTheLibraryTheNativeDecoderIsUsed() {
        val x = Xz { Doubler() }
        assertTrue(x.name.startsWith("native xz"))
        assertIs<NativeXzInputStream>(x.open(ByteArrayInputStream(data)))
        assertTrue(Xz.THREADS in 1..8)
    }
}
