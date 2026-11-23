package dev.azula.vmhost

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guest-internet proxy. Keeps [POOL] idle host-to-guest vsock connections to guest
 * port [PORT]; the guest sends an HTTP proxy request over one, and we tunnel it to
 * the real network (the VM has no network of its own).
 */
internal class Proxy(private val vm: VsockConnector) {
    @Volatile private var running = true
    private val ids = AtomicInteger()
    private val lock = Any()
    private val idle = HashSet<ParcelFileDescriptor>()

    fun start() {
        for (i in 0 until POOL) {
            Thread({ slotLoop(i) }, "proxy-slot-$i").apply { isDaemon = true }.start()
        }
    }

    private fun slotLoop(slot: Int) {
        var failures = 0
        var lastLogged = 0L
        while (running) {
            var pfd: ParcelFileDescriptor? = null
            var head: ByteArray? = null
            var why: String? = null
            try {
                pfd = vm.connectVsock(PORT.toLong())
                synchronized(lock) { idle.add(pfd) }
                if (failures > 0) HostLog.to("proxy", "slot $slot connected after $failures retries")
                failures = 0
                head = readHead(FileInputStream(pfd.fileDescriptor))
                if (head == null) why = "closed before request (guest not listening yet?)"
            } catch (e: Throwable) {
                why = e.toString().lineSequence().first()
            } finally {
                synchronized(lock) { if (pfd != null) idle.remove(pfd) }
            }
            if (head == null) {
                runCatching { pfd?.close() }
                failures++
                val now = System.currentTimeMillis()
                if (failures == 1 || now - lastLogged > 30_000) {
                    HostLog.to("proxy", "slot $slot waiting for guest:$PORT (retry $failures): $why")
                    lastLogged = now
                }
                if (!running) return
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
                continue
            }
            // Consumed: hand off to a tunnel thread and immediately replenish this slot.
            val p = pfd!!
            val h = head
            val id = ids.incrementAndGet()
            Thread({ tunnel(id, p, h) }, "proxy-tunnel-$id").apply { isDaemon = true }.start()
        }
    }

    /** Reads until `\r\n\r\n` (inclusive of any over-read bytes). Null on EOF/oversize. */
    private fun readHead(input: InputStream): ByteArray? {
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (acc.size() < ProxyRequest.MAX_HEAD) {
            val r = input.read(buf)
            if (r < 0) return null
            acc.write(buf, 0, r)
            val bytes = acc.toByteArray()
            if (ProxyRequest.indexOfHeadEnd(bytes) >= 0) return bytes
        }
        return null
    }

    private fun tunnel(id: Int, pfd: ParcelFileDescriptor, data: ByteArray) {
        val guest = Splice.Fd(pfd)
        var target = "?"
        var method = "?"
        var s: Socket? = null
        try {
            val t = ProxyRequest.parse(data)
            method = t.method
            target = "${t.host}:${t.port}"
            val sock = Socket().also { s = it }
            sock.connect(InetSocketAddress(t.host, t.port), 15_000)
            sock.tcpNoDelay = true
            if (t.connect) {
                guest.output().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                guest.output().flush()
                // Any client bytes that arrived after the CONNECT head go straight to the target.
                if (data.size > t.headEnd) {
                    sock.getOutputStream().write(data, t.headEnd, data.size - t.headEnd)
                    sock.getOutputStream().flush()
                }
            } else {
                sock.getOutputStream().write(data)
                sock.getOutputStream().flush()
            }
        } catch (e: Throwable) {
            HostLog.to("proxy", "#$id $method $target connect failed: $e")
            try {
                guest.output().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                guest.output().flush()
            } catch (_: Throwable) {}
            guest.close()
            try { s?.close() } catch (_: IOException) {}
            return
        }
        HostLog.to("proxy", "#$id $method $target open")
        val n = Splice.run(guest, Splice.Tcp(s!!))
        HostLog.to("proxy", "#$id $target closed guest->net=${n[0]}B net->guest=${n[1]}B")
    }

    fun stop() {
        running = false
        val l = synchronized(lock) { idle.toList() }
        for (p in l) runCatching { p.close() }
        HostLog.to("proxy", "stopped")
    }

    companion object {
        const val POOL = 4
        const val PORT = 3129
    }
}
