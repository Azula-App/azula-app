package dev.azula.net

import computer.iroh.BiStream
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.presetN0
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Persists the iroh secret key so the node id (and "your code") stays stable. */
interface SecretKeyStore {
    fun load(): ByteArray?
    fun save(bytes: ByteArray)
}

/** Default file-backed store (also works on Android via the app's files dir). */
class FileSecretKeyStore(private val path: String) : SecretKeyStore {
    override fun load(): ByteArray? = runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
    override fun save(bytes: ByteArray) {
        runCatching {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
    }
}

/** Injectable hooks so the Android app can supply an encrypted key store + lifecycle. */
object IrohConfig {
    @Volatile var keyStore: SecretKeyStore? = null
    @Volatile var activeTransport: IrohFfiTransport? = null
}

/**
 * Real iroh transport for JVM desktop and Android, over the `computer.iroh:iroh`
 * binding. The API mirrors iroh 1.0: bind an [Endpoint] with a preset + ALPNs,
 * connect by [EndpointTicket], and exchange bytes over a bidirectional [BiStream].
 */
class IrohFfiTransport(private val keyStore: SecretKeyStore) : IrohTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var endpoint: Endpoint? = null
    private var alpns: List<String> = emptyList()

    override var nodeId: String? = null
        private set

    override suspend fun bind(alpns: List<String>) {
        this.alpns = alpns
        val alpnBytes = alpns.map { it.encodeToByteArray() }
        val stored = keyStore.load()
        val options = EndpointOptions().apply {
            preset = presetN0()
            this.alpns = alpnBytes
            if (stored != null) secretKey = stored
        }
        val ep = Endpoint.bind(options)
        endpoint = ep
        nodeId = ep.id().toString()
        runCatching { keyStore.save(ep.secretKey().toBytes()) }
        IrohConfig.activeTransport = this
        // Bring discovery/relays online in the background; not required for addr().
        scope.launch { runCatching { ep.online() } }
    }

    override suspend fun myTicket(): String {
        val ep = endpoint ?: return ""
        return EndpointTicket.fromAddr(ep.addr()).toString()
    }

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        val ep = requireNotNull(endpoint) { "transport not bound" }
        val addr = EndpointTicket.fromString(ticket).endpointAddr()
        val conn = ep.connect(addr, alpn.encodeToByteArray())
        return IrohP2pStream(conn.openBi())
    }

    override fun incoming(): Flow<IncomingConnection> = channelFlow {
        val ep = endpoint ?: return@channelFlow
        while (isActive) {
            val incoming = runCatching { ep.acceptNext() }.getOrNull() ?: break
            launch {
                runCatching {
                    val accepting = incoming.accept()
                    val conn = accepting.connect()
                    val alpn = conn.alpn().decodeToString()
                    val bi = conn.acceptBi()
                    send(IncomingConnection(conn.remoteId().toString(), alpn, IrohP2pStream(bi)))
                }
            }
        }
    }

    override suspend fun shutdown() {
        runCatching { endpoint?.shutdown() }
        endpoint = null
    }

    /** Android: called when the app is backgrounded — close cleanly to free sockets. */
    fun pause() {
        scope.launch { runCatching { endpoint?.shutdown() }; endpoint = null }
    }

    /** Android: called when the app returns — re-bind with the persisted identity. */
    fun resume() {
        if (endpoint != null || alpns.isEmpty()) return
        scope.launch { runCatching { bind(alpns) } }
    }
}

/** Wraps a bidirectional iroh stream as a newline-framed [P2pStream]. */
private class IrohP2pStream(private val bi: BiStream) : P2pStream {
    private val send = bi.send()
    private val recv = bi.recv()
    private val lines = LineBuffer { recv.read(65536u) }

    override suspend fun sendLine(line: String) {
        send.writeAll((line + "\n").encodeToByteArray())
    }

    override suspend fun receiveLine(): String? = lines.readLine()

    override suspend fun close() {
        runCatching { send.finish() }
    }
}

actual fun createTransport(): IrohTransport =
    IrohFfiTransport(IrohConfig.keyStore ?: FileSecretKeyStore(defaultKeyPath()))

private fun defaultKeyPath(): String {
    val home = System.getProperty("user.home") ?: "."
    return home + File.separator + ".azula" + File.separator + "endpoint.key"
}
