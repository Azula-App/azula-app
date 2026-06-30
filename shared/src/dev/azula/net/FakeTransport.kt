package dev.azula.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * A no-network transport used as a graceful fallback when the real iroh binding
 * can't load (e.g. missing native lib), and for offline UI demos. Peer (CHAT)
 * dials "succeed" with a friendly loopback so the connect flow stays lively;
 * LLM/TERM dials are left to the conversations' rich local behavior.
 */
class FakeTransport : IrohTransport {
    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        nodeId = "demo" + Random.nextInt(0x10000, 0xfffff).toString(16)
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = "azula-demo-${nodeId ?: "offline"}"

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        if (alpn != Alpns.CHAT) throw UnsupportedOperationException("demo transport only loops back peer chat")
        return LoopbackStream()
    }

    /** A stable fake peer id derived from the ticket, so demo conversations key consistently. */
    override fun peerIdForTicket(ticket: String): String =
        "demo" + (ticket.hashCode().toLong() and 0xffffffffL).toString(16)

    override fun incoming(): Flow<IncomingConnection> = emptyFlow()

    override suspend fun shutdown() {}
}

/** Echoes sent chat back as if a peer replied — just enough to demo p2p chat solo. */
private class LoopbackStream : P2pStream {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private var greeted = false

    // Raw byte buffer for binary loopback (not normally used in demo mode, but
    // required so LoopbackStream satisfies the P2pStream contract in tests).
    private val byteMutex = Mutex()
    private var byteBuffer = ByteArray(0)
    private val byteReady = Channel<Unit>(Channel.CONFLATED)

    override suspend fun sendLine(line: String) {
        if (!greeted) {
            greeted = true
            inbox.send(FrameCodec.encode(Frame.Chat("hey! we're linked over iroh 🎉 (demo loopback)")))
        }
        val frame = runCatching { FrameCodec.decode(line) }.getOrNull()
        if (frame is Frame.Chat) {
            inbox.send(FrameCodec.encode(Frame.Chat("echo · ${frame.text}")))
        }
    }

    override suspend fun receiveLine(): String? = runCatching { inbox.receive() }.getOrNull()

    override suspend fun sendBytes(bytes: ByteArray) {
        byteMutex.withLock { byteBuffer += bytes }
        byteReady.send(Unit)
    }

    override suspend fun receiveBytes(n: Int): ByteArray? {
        while (true) {
            val result = byteMutex.withLock {
                if (byteBuffer.size >= n) {
                    val slice = byteBuffer.copyOfRange(0, n)
                    byteBuffer = byteBuffer.copyOfRange(n, byteBuffer.size)
                    slice
                } else null
            }
            if (result != null) return result
            // Wait for more bytes to arrive.
            byteReady.receive()
        }
    }

    override suspend fun close() {
        inbox.close()
    }
}
