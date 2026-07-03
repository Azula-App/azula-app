package dev.azula.mock

import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.MediaCodec
import dev.azula.net.MediaFetchRequest
import dev.azula.net.MediaFetchResponse
import dev.azula.net.P2pStream

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
 * loop back a friendly chat echo; TERM dials return a fake echo shell; MEDIA
 * dials serve a fetch directly out of [blobs] (see [FakeMediaStream]) since
 * there's no second peer to play "the sender". On bind it also surfaces one
 * inbound TERM connection, so a terminal conversation appears in the mock build
 * without needing a live peer.
 */
class FakeTransport : IrohTransport {
    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    /**
     * In-memory blob bodies keyed by id, served by `connect(ticket, Alpns.MEDIA)`
     * -- stands in for a real peer's `MediaService.serveIncoming` since this
     * transport is a solo loopback. Seed before triggering a fetch (demo or test).
     */
    val blobs: MutableMap<String, ByteArray> = mutableMapOf()

    /**
     * Real-time delay (ms) [FakeMediaStream] waits before serving each chunk
     * after the first, so a caller can interleave a cancel (or just observe
     * progress) between two body chunks landing. 0 (the default) serves
     * everything as fast as possible.
     */
    var mediaChunkDelayMs: Long = 0

    /** Every [MediaFetchRequest] a fetch has sent, in order -- lets a test assert
     *  a retry's resume offset without peeking at BlobStore internals. */
    val mediaRequests: Channel<MediaFetchRequest> = Channel(Channel.UNLIMITED)

    /** Every frame sent on a CHAT/LLM loopback stream (e.g. [MediaService][dev.azula.state.MediaService]'s
     *  outgoing [Frame.MediaOffer]) -- lets a caller assert something was sent
     *  without needing the loopback to echo it back to itself. */
    val sentFrames: Channel<Frame> = Channel(Channel.UNLIMITED)

    // Keyed by (ticket, alpn) rather than just ticket: connectPeer races CHAT/LLM/
    // TERM simultaneously (each gets its own stream instance), and PEER (CHAT)
    // always wins that race (see ConversationStore.kindPriority) -- but keying
    // only by ticket would risk capturing whichever alpn's connect() happened to
    // run last, not necessarily the one that ends up wired as the conversation's
    // live stream.
    private val loopbacks = mutableMapOf<Pair<String, String>, LoopbackStream>()

    override suspend fun bind(alpns: List<String>) {
        nodeId = "mock" + Random.nextInt(0x10000, 0xfffff).toString(16)
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = "azula-mock-${nodeId ?: "offline"}"

    /** A stable fake 32-byte key so the mock build can render a recovery phrase. */
    override fun secretKeyBytes(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    override suspend fun importSecretKey(bytes: ByteArray) { /* mock: no-op */ }

    override suspend fun connect(ticket: String, alpn: String): P2pStream = when (alpn) {
        Alpns.TERM -> FakeTerminalStream()
        Alpns.MEDIA -> FakeMediaStream(
            blobLookup = { blobs[it] },
            onRequest = { mediaRequests.trySend(it) },
            chunkDelayMs = mediaChunkDelayMs,
        )
        else -> LoopbackStream(onSend = { sentFrames.trySend(it) }).also { loopbacks[ticket to alpn] = it }
    }

    /**
     * Pushes [frame] into [ticket]'s CHAT-alpn loopback stream, as if the peer had
     * sent it -- e.g. to demo/test an inbound [Frame.MediaOffer] so a `-mock` app
     * (or a test) can see a "received" image without a second peer. No-op if that
     * loopback doesn't exist yet (dial it first, e.g. via `AzulaState.connectPeer`).
     */
    suspend fun injectIncoming(ticket: String, frame: Frame, alpn: String = Alpns.CHAT) {
        loopbacks[ticket to alpn]?.injectIncoming(frame)
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

/** Echoes sent chat back as if a peer replied — just enough to demo p2p chat solo.
 *  [onSend] fires for every decodable frame passed to [sendLine] (not just
 *  [Frame.Chat]), so a caller can observe e.g. an outgoing [Frame.MediaOffer]
 *  even though this loopback has no special handling for it. */
private class LoopbackStream(private val onSend: (Frame) -> Unit = {}) : P2pStream {
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
        if (frame != null) onSend(frame)
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

    /** Pushes [frame] straight into the inbox, as if the peer had sent it. */
    suspend fun injectIncoming(frame: Frame) {
        inbox.send(FrameCodec.encode(frame))
    }
}

/**
 * Serves the `azula/media/0` fetch protocol directly against an in-memory blob
 * lookup, standing in for a real peer's `MediaService.serveIncoming` -- the
 * mock/test transport is a solo loopback, with no second [FakeTransport]
 * instance to play "the sender".
 *
 * [sendLine] handles exactly one [MediaFetchRequest] (decodes, validates,
 * queues the response line via [MediaCodec]); [receiveBytes] then serves the
 * body lazily out of the blob, one chunk per call, optionally paced by
 * [chunkDelayMs]. This deliberately does NOT reuse `sendMediaBody` — that
 * helper pushes a body eagerly onto a stream's `sendBytes`, which doesn't fit
 * here: the real caller ([dev.azula.state.MediaService.startFetch]) calls
 * `sendLine` then `receiveLine` *before* ever calling `receiveBytes`, so
 * whatever `sendLine` does must finish before any byte can be requested --
 * serving the body from `receiveBytes` instead is what lets pacing/cancellation
 * land at the same per-chunk granularity a real transfer would.
 */
private class FakeMediaStream(
    private val blobLookup: (String) -> ByteArray?,
    private val onRequest: (MediaFetchRequest) -> Unit,
    private val chunkDelayMs: Long,
) : P2pStream {
    private val toClient = Channel<String>(Channel.UNLIMITED)
    private var body: ByteArray? = null
    private var pos = 0
    private var chunksServed = 0

    override suspend fun sendLine(line: String) {
        val request = runCatching { MediaCodec.decodeRequest(line) }.getOrNull() ?: return
        onRequest(request)
        val bytes = blobLookup(request.id)
        when {
            bytes == null ->
                toClient.send(MediaCodec.encodeResponse(MediaFetchResponse.Error(request.id, "unknown blob")))
            request.offset > bytes.size.toLong() ->
                toClient.send(MediaCodec.encodeResponse(MediaFetchResponse.Error(request.id, "offset beyond blob size")))
            else -> {
                toClient.send(MediaCodec.encodeResponse(MediaFetchResponse.Ok(request.id, bytes.size.toLong(), request.offset)))
                body = bytes
                pos = request.offset.toInt()
            }
        }
    }

    override suspend fun receiveLine(): String? = toClient.receive()

    override suspend fun sendBytes(bytes: ByteArray) = Unit // this protocol never sends a body back to the requester

    override suspend fun receiveBytes(n: Int): ByteArray? {
        val bytes = body ?: return null
        if (pos >= bytes.size) return null
        if (chunksServed > 0 && chunkDelayMs > 0) delay(chunkDelayMs)
        chunksServed++
        val end = minOf(pos + n, bytes.size)
        return bytes.copyOfRange(pos, end).also { pos = end }
    }

    override suspend fun close() = Unit
}
