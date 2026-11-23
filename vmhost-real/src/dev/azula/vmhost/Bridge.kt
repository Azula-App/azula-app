package dev.azula.vmhost

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * `<runDir>/worker.sock` (a filesystem Unix socket in a 0700 directory, itself 0600)
 * bridged to the VM's vsock port 1024. The broker dials this socket to reach the
 * guest worker, so only this app's uid can.
 */
internal class Bridge(private val runDir: File, private val vm: VsockConnector) {
    @Volatile private var running = true
    private var bound: LocalSocket? = null
    private var server: LocalServerSocket? = null
    private val ids = AtomicInteger()

    fun start() {
        runDir.mkdirs()
        Os.chmod(runDir.absolutePath, 0b111_000_000) // 0700
        val sock = File(runDir, SOCKET_NAME)
        sock.delete()
        val b = LocalSocket(LocalSocket.SOCKET_STREAM)
        b.bind(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        Os.chmod(sock.absolutePath, 0b110_000_000) // 0600
        bound = b
        val s = LocalServerSocket(b.fileDescriptor)
        server = s
        HostLog.to("bridge", "listening on $sock")
        Thread({ acceptLoop(s) }, "bridge-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(s: LocalServerSocket) {
        while (running) {
            val c = try {
                s.accept()
            } catch (e: Exception) {
                if (running) HostLog.to("bridge", "accept failed: $e")
                return
            }
            val id = ids.incrementAndGet()
            Thread({ handle(id, c) }, "bridge-conn-$id").apply { isDaemon = true }.start()
        }
    }

    private fun handle(id: Int, c: LocalSocket) {
        val t0 = System.currentTimeMillis()
        HostLog.to("bridge", "#$id accepted")
        val pfd = try {
            vm.connectVsock(WORKER_PORT)
        } catch (e: Throwable) {
            HostLog.to("bridge", "#$id connectVsock($WORKER_PORT) failed: $e")
            runCatching { c.close() }
            return
        }
        val n = Splice.run(Splice.Local(c), Splice.Fd(pfd))
        HostLog.to("bridge", "#$id closed unix->vsock=${n[0]}B vsock->unix=${n[1]}B in ${System.currentTimeMillis() - t0}ms")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { bound?.close() }
        File(runDir, SOCKET_NAME).delete()
        HostLog.to("bridge", "stopped")
    }

    companion object {
        const val SOCKET_NAME = "worker.sock"
        const val WORKER_PORT = 1024L
    }
}
