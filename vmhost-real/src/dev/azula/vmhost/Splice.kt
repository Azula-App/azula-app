package dev.azula.vmhost

import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Bidirectional byte pump with half-close propagation. */
internal object Splice {
    interface Endpoint {
        fun input(): InputStream
        fun output(): OutputStream
        fun shutdownWrite()
        fun close()
    }

    class Local(private val s: LocalSocket) : Endpoint {
        override fun input(): InputStream = s.inputStream
        override fun output(): OutputStream = s.outputStream
        override fun shutdownWrite() { runCatching { s.shutdownOutput() } }
        override fun close() { runCatching { s.close() } }
    }

    class Fd(private val p: ParcelFileDescriptor) : Endpoint {
        private val inp = FileInputStream(p.fileDescriptor)
        private val out = FileOutputStream(p.fileDescriptor)
        override fun input(): InputStream = inp
        override fun output(): OutputStream = out
        override fun shutdownWrite() { runCatching { Os.shutdown(p.fileDescriptor, OsConstants.SHUT_WR) } }
        override fun close() { runCatching { p.close() } }
    }

    class Tcp(private val s: Socket) : Endpoint {
        override fun input(): InputStream = s.getInputStream()
        override fun output(): OutputStream = s.getOutputStream()
        override fun shutdownWrite() { runCatching { s.shutdownOutput() } }
        override fun close() { runCatching { s.close() } }
    }

    /** Blocks until both directions finish. Returns {a->b bytes, b->a bytes}. Closes both. */
    fun run(a: Endpoint, b: Endpoint): LongArray {
        val n = LongArray(2)
        val t = Thread({ n[0] = pump(a, b) }, "splice-a2b")
        t.start()
        n[1] = pump(b, a)
        try { t.join() } catch (_: InterruptedException) {}
        a.close()
        b.close()
        return n
    }

    /** Copies src to dst until EOF; propagates half-close; on I/O error tears both ends down. */
    private fun pump(src: Endpoint, dst: Endpoint): Long {
        var total = 0L
        val buf = ByteArray(16 * 1024)
        try {
            val inp = src.input()
            val out = dst.output()
            while (true) {
                val r = inp.read(buf)
                if (r < 0) break
                if (r == 0) continue
                out.write(buf, 0, r)
                out.flush()
                total += r
            }
            dst.shutdownWrite()
        } catch (_: IOException) {
            src.close()
            dst.close()
        }
        return total
    }
}
