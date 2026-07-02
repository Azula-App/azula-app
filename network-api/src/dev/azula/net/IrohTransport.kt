package dev.azula.net

import kotlinx.coroutines.flow.Flow

/**
 * A single bidirectional, end-to-end-encrypted stream to a peer. Carries
 * newline-delimited JSON [Frame]s at the line level; framing/JSON is applied by
 * callers via [FrameCodec]. Also supports raw byte I/O for binary file transfers.
 */
interface P2pStream {
    suspend fun sendLine(line: String)

    /** Next line from the peer, or null when the stream is closed. */
    suspend fun receiveLine(): String?

    /**
     * Write exactly [bytes].size raw bytes to the stream with no framing.
     * Used for binary file-transfer bodies.
     */
    suspend fun sendBytes(bytes: ByteArray)

    /**
     * Read exactly [n] raw bytes from the stream, sharing the same internal
     * buffer as [receiveLine] so no bytes buffered from a prior line read are
     * lost. Returns null if EOF is reached before [n] bytes are available.
     */
    suspend fun receiveBytes(n: Int): ByteArray?

    /**
     * The live round-trip time to the peer in milliseconds, or null if not yet
     * available (no path established) or unsupported by this transport. Backed by
     * iroh's smoothed per-connection QUIC RTT — a cheap, non-blocking snapshot.
     * The mock and any non-iroh stream return null.
     */
    fun rttMs(): Long? = null

    suspend fun close()
}

/** An inbound connection a peer dialed to us, already negotiated on an ALPN. */
data class IncomingConnection(
    val remoteId: String,
    val alpn: String,
    val stream: P2pStream,
)

/**
 * Platform abstraction over an iroh endpoint. JVM/Android back this with the
 * `computer.iroh:iroh` binding; iOS delegates to an injected Swift `IrohLib`
 * implementation. The `-mock` apps back the UI with a FakeTransport (in the
 * mock-support module) instead, so no real networking is involved.
 */
interface IrohTransport {
    /** Hex node id, available after [bind]. */
    val nodeId: String?

    /** Bind the endpoint, advertising the given ALPNs for incoming connections. */
    suspend fun bind(alpns: List<String>)

    /** A shareable ticket string encoding our address (our "code"). */
    suspend fun myTicket(): String

    /** The current 32-byte iroh secret key, for backup. Null if unavailable. */
    fun secretKeyBytes(): ByteArray?

    /**
     * Replace this device's identity with [bytes] (a 32-byte secret key): persist
     * it and re-bind the endpoint, so [nodeId] / [myTicket] reflect the restored
     * identity. Used by the recovery-phrase restore flow.
     */
    suspend fun importSecretKey(bytes: ByteArray)

    /** Dial a peer by ticket on the given ALPN, opening a fresh stream. */
    suspend fun connect(ticket: String, alpn: String): P2pStream

    /**
     * The peer's hex node id decoded from a ticket, without dialing — so an
     * outbound conversation can be keyed/named after the peer (inbound ones use
     * [IncomingConnection.remoteId]). Returns null if the ticket can't be parsed.
     */
    fun peerIdForTicket(ticket: String): String?

    /** Connections peers dial to us. */
    fun incoming(): Flow<IncomingConnection>

    suspend fun shutdown()

    /**
     * Invoked at the end of every successful [bind] — the initial bind and any
     * re-bind (e.g. Android foreground-resume). The app sets this to re-announce
     * presence to saved peers/bridges so a bridge flushes any queued mail right
     * away. Defaults to null.
     */
    var onCameOnline: (() -> Unit)?
}
