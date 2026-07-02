package dev.azula.net

import app.azula.iroh.IrohEndpoint
import app.azula.iroh.IrohStream
import app.azula.iroh.nodeIdFromTicket
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
 * Real iroh transport for JVM desktop and Android, over the `app.azula.iroh:iroh-kmp`
 * binding. Unlike the published JNA artifact, this Gobley/JNI binding's async calls
 * complete on Android, so [IrohEndpoint.bind] returns and the node comes online.
 */
class IrohFfiTransport(private val keyStore: SecretKeyStore) : IrohTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var endpoint: IrohEndpoint? = null
    private var alpns: List<String> = emptyList()

    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        this.alpns = alpns
        // bind reuses the persisted secret key (if any) and brings relays online
        // in the background; see the Rust crate's `IrohEndpoint::bind`. A failing
        // key store must not fail the bind — degrade to a fresh key, never to demo.
        val stored = runCatching { keyStore.load() }.getOrNull()
        val ep = IrohEndpoint.bind(alpns, stored)
        endpoint = ep
        nodeId = ep.nodeId()
        runCatching { keyStore.save(ep.secretKeyBytes()) }
        IrohConfig.activeTransport = this
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = endpoint?.myTicket() ?: ""

    override fun secretKeyBytes(): ByteArray? =
        runCatching { endpoint?.secretKeyBytes() }.getOrNull() ?: runCatching { keyStore.load() }.getOrNull()

    override suspend fun importSecretKey(bytes: ByteArray) {
        require(bytes.size == 32) { "secret key must be 32 bytes" }
        runCatching { keyStore.save(bytes) }
        // Re-bind with the restored identity: tear down the old endpoint and bind
        // again, which reads the freshly-saved key from the store.
        runCatching { endpoint?.shutdown() }
        endpoint = null
        bind(alpns)
    }

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        val ep = requireNotNull(endpoint) { "transport not bound" }
        return IrohP2pStream(ep.connect(ticket, alpn))
    }

    override fun peerIdForTicket(ticket: String): String? =
        runCatching { nodeIdFromTicket(ticket) }.getOrNull()

    override fun incoming(): Flow<IncomingConnection> = channelFlow {
        val ep = endpoint ?: return@channelFlow
        while (isActive) {
            // acceptNext already completes the handshake and accepts a bi stream.
            val conn = runCatching { ep.acceptNext() }.getOrNull() ?: break
            send(IncomingConnection(conn.remoteId, conn.alpn, IrohP2pStream(conn.stream)))
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
private class IrohP2pStream(private val stream: IrohStream) : P2pStream {
    private val lines = LineBuffer { stream.recv(65536u) }

    override suspend fun sendLine(line: String) {
        stream.sendBytes((line + "\n").encodeToByteArray())
    }

    override suspend fun receiveLine(): String? = lines.readLine()

    override suspend fun sendBytes(bytes: ByteArray) {
        stream.sendBytes(bytes)
    }

    override suspend fun receiveBytes(n: Int): ByteArray? = lines.readBytes(n)

    override suspend fun close() {
        runCatching { stream.finish() }
    }
}

actual fun createTransport(): IrohTransport =
    IrohFfiTransport(IrohConfig.keyStore ?: FileSecretKeyStore(defaultKeyPath()))

private fun defaultKeyPath(): String {
    val home = System.getProperty("user.home") ?: "."
    return home + File.separator + ".azula" + File.separator + "endpoint.key"
}
