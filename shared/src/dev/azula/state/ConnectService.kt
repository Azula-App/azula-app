package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.core.RecoveryPhrase
import dev.azula.core.AppScope
import dev.azula.link.AzulaLinks
import dev.azula.link.DeepLinkBus
import dev.azula.model.Attachment
import dev.azula.model.ConnectStage
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.net.PeerStore
import dev.azula.net.receiveFile
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The connect + transport core carved out of AzulaState: owns the (rebindable)
 * [transport], the outbound dial / holepunch flow, silent reconnect, the inbound
 * connection loop, and the per-conversation receive loop that reads frames off the
 * wire. Interpreting a received frame (fan-out to chat/a2ui/terminal, the
 * peer-profile handshake) is [FrameDispatcher]'s job -- this class just hands each
 * frame to it as it arrives; the dependency is one-directional, FrameDispatcher
 * never calls back into this class.
 *
 * One app-shell concern is provided by a callback the owner sets after
 * construction (avoids a circular graph dependency): [onNavigate] to open a
 * conversation. (The other, `foreground`, now lives on [FrameDispatcher] since
 * that's the only place it's read.)
 */
@Inject
@SingleIn(AppScope::class)
class ConnectService(
    private val transport: IrohTransport,
    private val scope: CoroutineScope,
    private val convStore: ConversationStore,
    private val persistence: PersistenceCoordinator,
    private val frameDispatcher: FrameDispatcher,
    private val mediaService: MediaService,
    private val peerStore: PeerStore?,
) {
    /** Set by AzulaState: open a conversation's chat view. */
    var onNavigate: (String) -> Unit = {}

    // ---- connect UI state (read by the connect screen) ----
    var nodeId by mutableStateOf<String?>(null)
        private set
    var myCode by mutableStateOf("")
        private set
    var connectStage by mutableStateOf(ConnectStage.IDLE)
        private set
    var connectStep by mutableStateOf(0)
        private set
    var connectInput by mutableStateOf("")
    val shortCode: String get() = nodeId?.let { convStore.mnemonicCode(it) } ?: "connecting…"

    /** Inbound connections awaiting a "share my profile?" decision (owned by [FrameDispatcher]). */
    val pendingShares get() = frameDispatcher.pendingShares

    /** True when the transport failed to bind — we stay offline (no auto-reconnect). */
    private var offline = false

    fun resetConnect() {
        if (connectStage != ConnectStage.DIALING) {
            connectStage = ConnectStage.IDLE
            connectStep = 0
        }
    }

    fun peerIdForTicket(ticket: String): String? = transport.peerIdForTicket(ticket)

    /** The peer node id referenced by a pasted connect input (URL or bare ticket). */
    fun peerIdForInput(input: String): String? {
        val ticket = AzulaLinks.parse(input) ?: input.trim()
        if (ticket.isEmpty()) return null
        return transport.peerIdForTicket(ticket)
    }

    /** The peer's mnemonic code for a pasted connect input, if it parses. */
    fun peerCodeForInput(input: String): String? =
        peerIdForInput(input)?.let { convStore.mnemonicCode(it) }

    /** Respond to a pending share prompt: send [frame] back (or nothing to decline). */
    fun respondShare(convId: String, frame: Frame.Profile?) = frameDispatcher.respondShare(convId, frame)

    // ---- identity backup / restore ----

    /** This device's secret key as a 24-word BIP-39 recovery phrase, or null if the
     *  key isn't available yet (e.g. offline before the first bind). */
    fun exportRecoveryPhrase(): String? =
        transport.secretKeyBytes()?.takeIf { it.size == 32 }?.let { RecoveryPhrase.encode(it) }

    /** Restore the identity from a recovery phrase: replaces the device key and
     *  re-binds so the node id / code reflect it. Returns false if [phrase] is
     *  malformed or its checksum doesn't verify (nothing changes in that case). */
    suspend fun importRecoveryPhrase(phrase: String): Boolean {
        val key = RecoveryPhrase.decode(phrase) ?: return false
        transport.importSecretKey(key) // persists + rebinds; onCameOnline refreshes nodeId + reconnects
        nodeId = transport.nodeId
        myCode = runCatching { transport.myTicket() }.getOrDefault("")
        return true
    }

    // ---- lifecycle ----

    suspend fun start() {
        transport.onCameOnline = { nodeId = transport.nodeId; scope.launch { reconnectSaved() } }
        try {
            // MEDIA is bind-only (an accept target for on-demand fetch dials), never
            // part of the outbound ALL dial race -- see Alpns.MEDIA's doc.
            transport.bind(Alpns.ALL + Alpns.MEDIA)
        } catch (e: Throwable) {
            // Binding unavailable (e.g. missing native lib) — stay offline.
            offline = true
        }
        nodeId = transport.nodeId
        myCode = runCatching { transport.myTicket() }.getOrDefault("")
        scope.launch {
            runCatching {
                transport.incoming().collect { inc ->
                    if (inc.alpn == Alpns.MEDIA) {
                        scope.launch { runCatching { mediaService.serveIncoming(inc.stream) } }
                        return@collect
                    }
                    if (convStore.conversationOrNull(inc.remoteId)?.enabled == false) {
                        scope.launch { runCatching { inc.stream.close() } }
                        return@collect
                    }
                    val convId = wireStream(inc.alpn, inc.stream, inc.remoteId)
                    if (convId != null && inc.alpn == Alpns.CHAT) frameDispatcher.enqueueShareRequest(convId)
                }
            }
        }
        // Register the deeplink handler AFTER bind so a buffered/cold-start token
        // dials an already-bound transport rather than racing the bind.
        DeepLinkBus.setHandler { token -> connectPeer(token) }
    }

    // ---- outbound connect ----

    fun connectPeer(ticketRaw: String, share: Frame.Profile? = null) {
        val ticket = AzulaLinks.parse(ticketRaw) ?: ticketRaw.trim()
        if (ticket.isEmpty() || connectStage == ConnectStage.DIALING) return
        connectStage = ConnectStage.DIALING
        connectStep = 0
        scope.launch {
            val anim = launch { while (connectStep < 2) { delay(650); connectStep++ } }
            val primary = dialAll(ticket)
            anim.cancel()
            if (primary == null) {
                connectStage = ConnectStage.FAILED
                return@launch
            }
            connectStep = 3
            val peerId = transport.peerIdForTicket(ticket) ?: ("t" + ticket.hashCode().toString(16))
            val convId = wireStream(primary.first, primary.second, peerId)
            if (share != null && primary.first == Alpns.CHAT) {
                runCatching { primary.second.sendLine(FrameCodec.encode(share)) }
            }
            persistPeer(ticket)
            convId?.let { convStore.convOrNull(it)?.sourceTicket = ticket }
            connectStage = ConnectStage.CONNECTED
            delay(1200)
            if (convId != null) onNavigate(convId)
            connectStage = ConnectStage.IDLE
            connectInput = ""
            connectStep = 0
        }
    }

    /**
     * Open a NEW session over the same peer connection as [convId] — a fresh
     * terminal/LLM/peer conversation reusing the known ticket (no re-pairing).
     */
    fun newSessionFrom(convId: String) {
        val ticket = convStore.conv(convId).sourceTicket ?: return
        val kind = convStore.conversation(convId).kind
        val alpn = when (kind) {
            ConversationKind.TERMINAL -> Alpns.TERM
            ConversationKind.LLM -> Alpns.LLM
            ConversationKind.PEER -> Alpns.CHAT
        }
        scope.launch {
            val stream = runCatching { transport.connect(ticket, alpn) }.getOrNull() ?: return@launch
            val base = convStore.conversationOrNull(convId)?.name
                ?: convStore.mnemonicCode(transport.peerIdForTicket(ticket) ?: ticket)
            val n = convStore.conversations.count { convStore.convOrNull(it.id)?.sourceTicket == ticket && it.kind == kind } + 1
            val id = convStore.nextId("sess")
            convStore.convState[id] = ConversationState(emptyList()).also { it.sourceTicket = ticket }
            convStore.conversations.add(Conversation(id, "$base · session $n", kind, convStore.glyphFor(base, kind), sub = "live session", preview = ""))
            wireConv(id, stream, isTerminal = kind == ConversationKind.TERMINAL)
            onNavigate(id)
        }
    }

    /** Re-open a terminal whose session ended: dial the saved ticket + rewire. */
    fun reconnectTerminal(convId: String) {
        val ticket = convStore.convOrNull(convId)?.sourceTicket ?: return
        scope.launch {
            val stream = runCatching { transport.connect(ticket, Alpns.TERM) }.getOrNull() ?: return@launch
            wireConv(convId, stream, isTerminal = true)
        }
    }

    private suspend fun ping(stream: P2pStream) {
        stream.sendLine(FrameCodec.encode(Frame.Hello(transport.nodeId ?: "")))
    }

    /**
     * Race all ALPNs against [ticket] and return the highest-priority winner
     * (losers are closed), or null if nothing connected.
     */
    private suspend fun dialAll(ticket: String): Pair<String, P2pStream>? {
        val results = runCatching {
            coroutineScope {
                Alpns.ALL.map { alpn ->
                    async {
                        runCatching { transport.connect(ticket, alpn) }.getOrNull()
                            ?.also { runCatching { ping(it) } }?.let { alpn to it }
                    }
                }.awaitAll().filterNotNull()
            }
        }.getOrDefault(emptyList())
        if (results.isEmpty()) return null
        val primary = results.maxBy { convStore.kindPriority(kindForAlpn(it.first)) }
        results.filter { it !== primary }.forEach { (_, s) -> scope.launch { runCatching { s.close() } } }
        return primary
    }

    private fun persistPeer(ticket: String) {
        peerStore?.let { store -> store.save((store.load() + ticket).distinct().takeLast(10)) }
    }

    fun reconnectSaved() {
        if (offline) return
        val saved = peerStore?.load().orEmpty()
        if (saved.isEmpty()) return
        saved.forEach { ticket ->
            val peerId = transport.peerIdForTicket(ticket) ?: return@forEach
            if (convStore.conversationOrNull(peerId)?.enabled == false) return@forEach
            scope.launch {
                repeat(3) {
                    val primary = dialAll(ticket)
                    if (primary != null) {
                        val id = wireStream(primary.first, primary.second, peerId, replace = false)
                        id?.let { convStore.convOrNull(it)?.sourceTicket = ticket }
                        return@launch
                    }
                    delay(700)
                }
            }
        }
    }

    /**
     * Redial [convId]'s saved ticket with backoff until the conversation is back
     * online. Started when a live stream drops and when the user opens an offline
     * conversation. Self-terminates once the conversation comes back online
     * through any path (wireConv's `replace = false` guard dedupes races), is
     * disabled, or the attempts run out. Only conversations we dialed are
     * eligible — an inbound-accepted peer left no ticket to redial — and
     * terminals are excluded: a terminal session ending is normal, and re-opening
     * one is an explicit action ([reconnectTerminal]).
     */
    fun scheduleReconnect(convId: String) {
        if (offline) return
        if (convStore.conversationOrNull(convId)?.kind == ConversationKind.TERMINAL) return
        val cs = convStore.convOrNull(convId) ?: return
        val ticket = cs.sourceTicket ?: return
        if (cs.online || cs.reconnecting) return
        cs.reconnecting = true
        scope.launch {
            try {
                var backoffMs = 1000L
                repeat(8) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                    val now = convStore.convOrNull(convId) ?: return@launch
                    if (now.online) return@launch
                    if (convStore.conversationOrNull(convId)?.enabled == false) return@launch
                    val primary = withTimeoutOrNull(10_000) { dialAll(ticket) }
                    if (primary != null) {
                        val id = wireStream(primary.first, primary.second, convId, replace = false)
                        id?.let { convStore.convOrNull(it)?.sourceTicket = ticket }
                        return@launch
                    }
                }
            } finally {
                convStore.convOrNull(convId)?.reconnecting = false
            }
        }
    }

    // ---- stream wiring + incoming frames ----

    fun kindForAlpn(alpn: String): ConversationKind = when (alpn) {
        Alpns.CHAT -> ConversationKind.PEER
        Alpns.LLM -> ConversationKind.LLM
        Alpns.TERM -> ConversationKind.TERMINAL
        else -> ConversationKind.PEER
    }

    private fun wireStream(alpn: String, stream: P2pStream, peerId: String, announcedName: String? = null, replace: Boolean = true): String? {
        if (peerId.isBlank()) { scope.launch { runCatching { stream.close() } }; return null }
        val convId = convStore.ensureConv(peerId, kindForAlpn(alpn), announcedName)
        wireConv(convId, stream, isTerminal = alpn == Alpns.TERM, replace = replace)
        return convId
    }

    private fun wireConv(convId: String, stream: P2pStream, isTerminal: Boolean, replace: Boolean = true) {
        val cs = convStore.conv(convId)
        if (!replace && cs.online && cs.stream != null) {
            scope.launch { runCatching { stream.close() } }
            return
        }
        // A2UI surfaces are session-scoped (never restored from persistence): a new
        // stream means a new session, so drop this conversation's old surfaces. A
        // same-run peer re-creates its surface (same id — the card revives in
        // place); a restarted peer's stale card stays frozen for good.
        frameDispatcher.dropSurfaces(convId)
        cs.stream = stream
        if (isTerminal) {
            // Device-status/attribute replies (DA/DSR) the emulator generates go back
            // to the server on the current stream — server-directed, never predicted.
            cs.termScreen.onResponse = { resp ->
                convStore.convOrNull(convId)?.stream?.let { st ->
                    scope.launch { runCatching { st.sendLine(FrameCodec.encode(Frame.Input(resp))) } }
                }
            }
            scope.launch(terminalDispatcher) { cs.termScreen.clear() }
        }
        cs.online = true
        receiveLoop(convId, stream)
        pollRtt(cs, stream)
    }

    /**
     * Poll the live QUIC RTT off [stream] into [cs] while it's the conversation's
     * active stream. The `cs.stream === stream` identity guard makes the loop
     * self-terminate on rewire/close (receiveLoop's `finally` nulls the stream).
     */
    private fun pollRtt(cs: ConversationState, stream: P2pStream) {
        scope.launch {
            while (cs.stream === stream) {
                cs.rttMs = stream.rttMs()?.toInt()
                delay(2000)
            }
        }
    }

    private fun receiveLoop(convId: String, stream: P2pStream) {
        scope.launch {
            try {
                while (true) {
                    val line = stream.receiveLine() ?: break
                    if (line.isBlank()) continue
                    val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                    if (frame is Frame.Hello) {
                        if (frame.name.isNotBlank() && frame.name != convId) {
                            convStore.ensureConv(convId, convStore.conversationOrNull(convId)?.kind ?: kindForAlpn(Alpns.LLM), frame.name)
                        }
                        continue
                    }
                    if (frame is Frame.FileBegin) {
                        runCatching {
                            val received = receiveFile(stream, frame) { stream.receiveLine() }
                            val attachment = Attachment(received.id, received.name, received.mime, received.bytes.size.toLong(), received.bytes, received.caption)
                            convStore.conv(convId).messages.add(
                                Message(convStore.nextId("r"), Role.THEM, text = received.caption, attachments = listOf(attachment))
                            )
                            persistence.save(convId)
                            frameDispatcher.notify(convId, received.caption ?: "sent ${received.name}")
                        }
                        continue
                    }
                    if (frame is Frame.FileChunk || frame is Frame.FileEnd) continue
                    if (frame is Frame.Profile) { frameDispatcher.applyProfile(convId, frame); continue }
                    frameDispatcher.applyFrame(convId, frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A dropped peer (abrupt close, idle timeout) surfaces as a thrown
                // IrohException from recv(), not a clean EOF — degrade to the
                // offline state below instead of killing the process.
            } finally {
                // Identity guard: if a reconnect already wired a fresh stream, this
                // loop's late-firing finally must not clobber it.
                val cs = convStore.conv(convId)
                if (cs.stream === stream) {
                    cs.online = false
                    cs.stream = null
                    cs.rttMs = null
                    scheduleReconnect(convId)
                }
            }
        }
    }
}
