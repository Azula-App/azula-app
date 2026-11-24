import dev.azula.vmhost.Splice
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpliceTest {
    /** An endpoint over plain streams: [feed] is what the peer sends us, [sink] what we send it. */
    private open class Fake(private val feed: InputStream, val sink: ByteArrayOutputStream = ByteArrayOutputStream()) : Splice.Endpoint {
        val halfCloses = AtomicInteger()
        val closes = AtomicInteger()
        override fun input(): InputStream = feed
        override fun output(): OutputStream = sink
        override fun shutdownWrite() { halfCloses.incrementAndGet() }
        override fun close() { closes.incrementAndGet() }
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 % 251).toByte() }

    /** Gives at most [max] bytes per read, like a socket does. */
    private class Trickle(private val inner: InputStream, private val max: Int) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, max))
    }

    @Test fun copiesBothWaysAtEverySize() {
        for (n in listOf(0, 1, 4095, 16 * 1024, 64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 1 shl 20)) {
            for (max in listOf(1 shl 20, 1500)) {
                if (n > 70_000 && max == 1500 && n == 1 shl 20) continue // slow and no more telling
                val up = bytes(n)
                val down = bytes(n + 7)
                val a = Fake(Trickle(up.inputStream(), max))
                val b = Fake(Trickle(down.inputStream(), max))
                val moved = Splice.run(a, b)
                assertContentEquals(longArrayOf(n.toLong(), (n + 7).toLong()), moved, "size $n, $max per read")
                assertContentEquals(up, b.sink.toByteArray())
                assertContentEquals(down, a.sink.toByteArray())
                assertEquals(1, a.halfCloses.get())
                assertEquals(1, b.halfCloses.get())
                assertEquals(1, a.closes.get())
                assertEquals(1, b.closes.get())
            }
        }
    }

    @Test fun oneSideClosingKeepsTheOtherFlowing() {
        val reply = PipedInputStream(1 shl 16)
        val toReply = PipedOutputStream(reply)
        val a = Fake(bytes(10).inputStream())
        val b = Fake(reply)
        val t = Thread { Splice.run(a, b) }.apply { start() }
        Thread.sleep(150) // a's request is over: a half-close has reached b, and b can still answer
        assertEquals(1, b.halfCloses.get())
        assertEquals(0, a.halfCloses.get())
        toReply.write(bytes(3000)); toReply.close()
        t.join(5000)
        assertTrue(!t.isAlive)
        assertContentEquals(bytes(3000), a.sink.toByteArray())
    }

    @Test fun aBrokenStreamTearsBothEndsDown() {
        val a = Fake(object : InputStream() {
            override fun read(): Int = throw IOException("gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("gone")
        })
        val b = Fake(ByteArray(0).inputStream())
        Splice.run(a, b)
        assertTrue(a.closes.get() >= 1 && b.closes.get() >= 1)
    }
}
