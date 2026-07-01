package dev.azula.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * A no-network transport for the mock / UI-test build (and a graceful fallback
 * when the real iroh binding can't load). Real UI, fake backend: CHAT/LLM dials
 * loop back a friendly chat echo; TERM dials return a fake echo shell. On bind it
 * also surfaces one inbound TERM connection, so a terminal conversation appears in
 * the mock build without needing a live peer.
 */
class FakeTransport : IrohTransport {
    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        nodeId = "mock" + Random.nextInt(0x10000, 0xfffff).toString(16)
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = "azula-mock-${nodeId ?: "offline"}"

    override suspend fun connect(ticket: String, alpn: String): P2pStream = when (alpn) {
        Alpns.TERM -> FakeTerminalStream()
        else -> LoopbackStream() // CHAT + LLM loop back chat
    }

    /** A stable fake peer id derived from the ticket, so mock conversations key consistently. */
    override fun peerIdForTicket(ticket: String): String =
        "mock" + (ticket.hashCode().toLong() and 0xffffffffL).toString(16)

    /** Surface one inbound terminal connection so the mock build has a live terminal. */
    override fun incoming(): Flow<IncomingConnection> = flow {
        delay(300)
        emit(IncomingConnection(remoteId = "mockterm", alpn = Alpns.TERM, stream = FakeTerminalStream()))
    }

    override suspend fun shutdown() {}
}

/** A fake echo shell: prints a prompt, echoes keystrokes back as terminal output,
 *  and drops a fresh prompt after each Enter. Enough to drive the terminal UI. */
private class FakeTerminalStream : P2pStream {
    private val inbox = Channel<String>(Channel.UNLIMITED)

    init {
        inbox.trySend(FrameCodec.encode(Frame.Term("mock shell — type away\r\n[38;5;205m›azula[0m ~ \r\n› ")))
    }

    override suspend fun sendLine(line: String) {
        val frame = runCatching { FrameCodec.decode(line) }.getOrNull()
        if (frame is Frame.Input) {
            // Echo the keystrokes (a real PTY echoes); on Enter, newline + a prompt.
            val echo = if (frame.text.contains('\r') || frame.text.contains('\n')) {
                frame.text.replace("\r", "\r\n") + "› "
            } else frame.text
            inbox.trySend(FrameCodec.encode(Frame.Term(echo)))
        }
    }

    override suspend fun receiveLine(): String? = runCatching { inbox.receive() }.getOrNull()
    override suspend fun sendBytes(bytes: ByteArray) {}
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() { inbox.close() }
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
            inbox.send(FrameCodec.encode(Frame.Chat("hey! we're linked over iroh 🎉 (mock loopback)")))
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
