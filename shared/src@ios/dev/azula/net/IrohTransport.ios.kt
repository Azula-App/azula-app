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
import platform.Foundation.NSUserDefaults

/**
 * iOS transport over the `app.azula.iroh:iroh-kmp` Kotlin/Native binding — the
 * same SDK the JVM/Android targets use, so the implementation mirrors
 * [IrohFfiTransport]. This replaces the older injected Swift `IrohLib` bridge.
 */
class IosIrohTransport : IrohTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var endpoint: IrohEndpoint? = null

    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        // Reuse the persisted secret key so the node id (and "your code") stays
        // stable across launches, mirroring AndroidSecretKeyStore.
        val ep = IrohEndpoint.bind(alpns, loadSecretKey())
        endpoint = ep
        nodeId = ep.nodeId()
        runCatching { saveSecretKey(ep.secretKeyBytes()) }
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = endpoint?.myTicket() ?: ""

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        val ep = requireNotNull(endpoint) { "transport not bound" }
        return IrohP2pStream(ep.connect(ticket, alpn))
    }

    override fun peerIdForTicket(ticket: String): String? =
        runCatching { nodeIdFromTicket(ticket) }.getOrNull()

    override fun incoming(): Flow<IncomingConnection> = channelFlow {
        val ep = endpoint ?: return@channelFlow
        while (isActive) {
            val conn = runCatching { ep.acceptNext() }.getOrNull() ?: break
            send(IncomingConnection(conn.remoteId, conn.alpn, IrohP2pStream(conn.stream)))
        }
    }

    override suspend fun shutdown() {
        runCatching { endpoint?.shutdown() }
        endpoint = null
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

actual fun createTransport(): IrohTransport = IosIrohTransport()

// --- Secret-key persistence (NSUserDefaults, hex-encoded) -------------------

private const val SECRET_KEY_PREF = "azula_iroh_secret_key"

private fun loadSecretKey(): ByteArray? =
    NSUserDefaults.standardUserDefaults.stringForKey(SECRET_KEY_PREF)?.hexToBytesOrNull()

private fun saveSecretKey(bytes: ByteArray) {
    NSUserDefaults.standardUserDefaults.setObject(bytes.toHex(), SECRET_KEY_PREF)
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
