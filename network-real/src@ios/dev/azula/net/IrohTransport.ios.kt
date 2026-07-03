package dev.azula.net

import app.azula.iroh.IrohEndpoint
import app.azula.iroh.IrohStream
import app.azula.iroh.nodeIdFromTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private var alpns: List<String> = emptyList()

    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        this.alpns = alpns
        // Reuse the persisted secret key so the node id (and "your code") stays
        // stable across launches, mirroring AndroidSecretKeyStore.
        val ep = IrohEndpoint.bind(alpns, loadSecretKey())
        endpoint = ep
        nodeId = ep.nodeId()
        runCatching { saveSecretKey(ep.secretKeyBytes()) }
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = endpoint?.myTicket() ?: ""

    override fun secretKeyBytes(): ByteArray? =
        runCatching { endpoint?.secretKeyBytes() }.getOrNull() ?: loadSecretKey()

    override suspend fun importSecretKey(bytes: ByteArray) {
        require(bytes.size == 32) { "secret key must be 32 bytes" }
        runCatching { saveSecretKey(bytes) }
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
        var backoffMs = 100L
        while (isActive) {
            // Only a clean close (a null result) should end the loop — a failed
            // inbound handshake surfaces as an exception and must not kill it, or
            // one bad peer permanently stops us from accepting anyone else. Back
            // off on repeated failures so a persistently-failing endpoint can't
            // hot-loop.
            val result = runCatching { ep.acceptNext() }
            if (result.isFailure) {
                println("IosIrohTransport.incoming: acceptNext failed, retrying in ${backoffMs}ms: ${result.exceptionOrNull()}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(1000L)
                continue
            }
            val conn = result.getOrNull() ?: break
            backoffMs = 100L
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

    override fun rttMs(): Long? = runCatching { stream.rttMs()?.toLong() }.getOrNull()

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
