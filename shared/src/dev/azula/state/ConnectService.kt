package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.core.RecoveryPhrase
import dev.azula.core.AppScope
import dev.azula.link.AzulaLinks
import dev.azula.link.DeepLinkBus
import dev.azula.link.InviteCodec
import dev.azula.link.isInvite
import dev.azula.model.Attachment
import dev.azula.model.ConnectStage
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IncomingConnection
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
    private val inviteService: InviteService,
) {
    /** Set by AzulaState: open a conversation's chat view. */
    var onNavigate: (String) -> Unit = {}

    /**
     * Transition escape hatch (see azula-docs/docs/invitations.md): route
     * invite-less strangers into the pending inbox flagged "unverified" instead
     * of closing the connection outright. Default on for one release.
     */
    var allowLegacyInbound: Boolean = true

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

    /** True when the transport failed to bind — we stay offline (no auto-reconnect). */
    private var offline = false

    fun resetConnect() {
        if (connectStage != ConnectStage.DIALING) {
            connectStage = ConnectStage.IDLE
            connectStep = 0
        }
    }

    fun peerIdForTicket(ticket: String): String? = transport.peerIdForTicket(ticket)

    /** The peer node id for a saved [PeerStore] entry -- a bare ticket, or a full
     *  encoded invite string -- resolving the latter first (see [resolveTicket]). */
    fun peerIdForEntry(entry: String): String? = resolveTicket(entry)?.let { transport.peerIdForTicket(it) }

    /** The peer node id referenced by a pasted connect input (URL or bare ticket). */
    fun peerIdForInput(input: String): String? {
        val ticket = AzulaLinks.parse(input) ?: input.trim()
        if (ticket.isEmpty()) return null
        return transport.peerIdForTicket(ticket)
    }

    /** The peer's mnemonic code for a pasted connect input, if it parses. */
    fun peerCodeForInput(input: String): String? =
        peerIdForInput(input)?.let { convStore.mnemonicCode(it) }

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
        // Wire an accepted stranger's held stream into a live conversation. No
        // sourceTicket is set -- an inbound-accepted peer left no ticket to
        // redial (matches every other inbound-accepted path; see scheduleReconnect).
        // If the user chose to share a persona back on accept, send it once the
        // stream is live (only meaningful on CHAT -- the only alpn a stranger's
        // held stream is ever on, see the incoming loop).
        inviteService.onAccept = { req, profile ->
            val convId = wireStream(req.alpn, req.stream, req.nodeId)
            if (convId != null && profile != null && req.alpn == Alpns.CHAT) {
                convStore.convOrNull(convId)?.stream?.let { s ->
                    scope.launch { runCatching { s.sendLine(FrameCodec.encode(profile)) } }
                }
            }
        }
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
                    if (!isKnownPeer(inc.remoteId)) {
                        // dialAll races CHAT+LLM+TERM, so a stranger's dial opens all
                        // three. Only CHAT is invite-gateable (see
                        // azula-docs/docs/invitations.md) -- hold it pending a valid
                        // Hello.invite. LLM/TERM have no accept UX and no legitimate
                        // reason to be opened by a stranger, so just close them; in
                        // particular don't enqueue them as pending too, or whichever
                        // of the three arrives first (not necessarily CHAT) would
                        // become the wrong held "primary".
                        if (inc.alpn == Alpns.CHAT) {
                            scope.launch { handleStrangerConnection(inc) }
                        } else {
                            scope.launch { runCatching { inc.stream.close() } }
                        }
                        return@collect
                    }
                    wireStream(inc.alpn, inc.stream, inc.remoteId)
                }
            }
        }
        // Register the deeplink handler AFTER bind so a buffered/cold-start token
        // dials an already-bound transport rather than racing the bind.
        DeepLinkBus.setHandler { token -> connectPeer(token) }
    }

    // ---- outbound connect ----

    fun connectPeer(ticketRaw: String, share: Frame.Profile? = null) {
        val raw = AzulaLinks.parse(ticketRaw) ?: ticketRaw.trim()
        if (raw.isEmpty() || connectStage == ConnectStage.DIALING) return
        // An invite carries the ticket rather than being one -- decode it and
        // dial the embedded ticket, while sending the whole encoded invite in
        // every Hello (see ping/dialAll) so the issuer can verify us.
        val invite = raw.takeIf { isInvite(it) }
        val invitePayload = invite?.let { InviteCodec.decode(it) }
        if (invite != null && invitePayload == null) { connectStage = ConnectStage.FAILED; return }
        val ticket = if (invitePayload != null) transport.ticketFromBytes(invitePayload.ticket) else raw
        if (ticket.isNullOrEmpty()) { connectStage = ConnectStage.FAILED; return }
        connectStage = ConnectStage.DIALING
        connectStep = 0
        scope.launch {
            val anim = launch { while (connectStep < 2) { delay(650); connectStep++ } }
            val primary = dialAll(ticket, invite)
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
            // Save the raw pasted/scanned value -- an invite string, not just its
            // embedded ticket -- so reconnects keep re-presenting it until the
            // issuer has accepted (see resolveTicket, reconnectSaved).
            persistPeer(raw)
            convId?.let {
                convStore.convOrNull(it)?.sourceTicket = ticket
                convStore.convOrNull(it)?.sourceInvite = invite
            }
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

    private suspend fun ping(stream: P2pStream, invite: String? = null) {
        stream.sendLine(FrameCodec.encode(Frame.Hello(transport.nodeId ?: "", invite)))
    }

    /**
     * Resolves a saved [peerStore] entry -- a bare ticket, or a full encoded
     * invite string (see [connectPeer]'s [persistPeer] call) -- to a ticket the
     * transport can dial. Null if it's an invite that no longer decodes.
     */
    private fun resolveTicket(saved: String): String? =
        if (!isInvite(saved)) saved else InviteCodec.decode(saved)?.ticket?.let { transport.ticketFromBytes(it) }

    /**
     * Race all ALPNs against [ticket] and return the highest-priority winner
     * (losers are closed), or null if nothing connected. [invite] -- the full
     * encoded invite string, if [ticket] was dialed from one -- rides along on
     * every Hello so a stranger-gated issuer can verify us.
     */
    private suspend fun dialAll(ticket: String, invite: String? = null): Pair<String, P2pStream>? {
        val results = runCatching {
            coroutineScope {
                Alpns.ALL.map { alpn ->
                    async {
                        runCatching { transport.connect(ticket, alpn) }.getOrNull()
                            ?.also { runCatching { ping(it, invite) } }?.let { alpn to it }
                    }
                }.awaitAll().filterNotNull()
            }
        }.getOrDefault(emptyList())
        if (results.isEmpty()) return null
        val primary = results.maxBy { convStore.kindPriority(kindForAlpn(it.first)) }
        results.filter { it !== primary }.forEach { (_, s) -> scope.launch { runCatching { s.close() } } }
        return primary
    }

    /** [entry] is the raw pasted/scanned value -- a bare ticket, or a full
     *  encoded invite string -- exactly as [resolveTicket] expects it back. */
    private fun persistPeer(entry: String) {
        peerStore?.let { store -> store.save((store.load() + entry).distinct().takeLast(10)) }
    }

    fun reconnectSaved() {
        if (offline) return
        val saved = peerStore?.load().orEmpty()
        if (saved.isEmpty()) return
        saved.forEach { entry ->
            val invite = entry.takeIf { isInvite(it) }
            val ticket = resolveTicket(entry) ?: return@forEach
            val peerId = transport.peerIdForTicket(ticket) ?: return@forEach
            if (convStore.conversationOrNull(peerId)?.enabled == false) return@forEach
            scope.launch {
                repeat(3) {
                    val primary = dialAll(ticket, invite)
                    if (primary != null) {
                        val id = wireStream(primary.first, primary.second, peerId, replace = false)
                        id?.let {
                            convStore.convOrNull(it)?.sourceTicket = ticket
                            convStore.convOrNull(it)?.sourceInvite = invite
                        }
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
     * eligible — an inbound-accepted peer left no ticket to redial.
     *
     * A terminal is eligible only when it holds a re-attachable persistent
     * session ([ConversationState.termSessionId] set, [ConversationState.termExited]
     * false) — wireConv's TermAttach will resume it with its scrollback, so
     * auto-reconnect is exactly as safe as for any other conversation kind. A
     * terminal whose shell already exited, or that never attached one (a
     * pre-persistence "legacy" server, or one that hasn't wired yet), stays
     * manual-only: its ending is normal, and re-opening one is an explicit
     * action ([reconnectTerminal]).
     */
    fun scheduleReconnect(convId: String) {
        if (offline) return
        val cs = convStore.convOrNull(convId) ?: return
        if (convStore.conversationOrNull(convId)?.kind == ConversationKind.TERMINAL &&
            (cs.termSessionId == null || cs.termExited)
        ) return
        val ticket = cs.sourceTicket ?: return
        val invite = cs.sourceInvite
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
                    val primary = withTimeoutOrNull(10_000) { dialAll(ticket, invite) }
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

    // ---- stranger gate (inbound peer-ALPN connections) ----

    /**
     * A connecting peer is known if its node id matches an existing
     * conversation (restored from persistence on a known peer, or created
     * earlier this session), a saved [peerStore] entry (a peer we've dialed
     * out to before), or is in [InviteService]'s accepted-contacts list. Known
     * peers connect exactly as before -- no gate (see
     * azula-docs/docs/invitations.md's verification model).
     */
    private fun isKnownPeer(nodeId: String): Boolean =
        convStore.conversationOrNull(nodeId) != null ||
            inviteService.isKnown(nodeId) ||
            peerStore?.load().orEmpty().any { peerIdForEntry(it) == nodeId }

    /**
     * Reads the stranger's first frame (15 s timeout) and requires
     * `Hello.invite` to verify. A valid invite -- or, if [allowLegacyInbound],
     * a missing one -- holds the stream pending an accept/decline decision
     * ([InviteService.enqueuePending]) instead of wiring it immediately;
     * anything else closes the connection.
     */
    private suspend fun handleStrangerConnection(inc: IncomingConnection) {
        val line = withTimeoutOrNull(15_000) { inc.stream.receiveLine() }
        val hello = line?.let { runCatching { FrameCodec.decode(it) }.getOrNull() } as? Frame.Hello
        val issuedInvite = inviteService.verifyInbound(hello?.invite)
        val peerCode = convStore.mnemonicCode(inc.remoteId)
        when {
            issuedInvite != null ->
                inviteService.enqueuePending(inc.remoteId, issuedInvite.id, peerCode, inc.stream, inc.alpn, unverified = false)
            allowLegacyInbound ->
                inviteService.enqueuePending(inc.remoteId, null, peerCode, inc.stream, inc.alpn, unverified = true)
            else -> runCatching { inc.stream.close() }
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
            // Opt into a persistent session (or re-attach the one we last held) as
            // the very first frame on the stream, before any keystroke can flow --
            // the server only honors TermAttach as the first frame (a 500ms window).
            // Old servers don't know this frame and ignore it (Frame::Unknown on the
            // Rust side), so this is always safe: no TermSession ever arrives, and
            // everything behaves exactly as the legacy ephemeral-shell path did.
            scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.TermAttach(cs.termSessionId))) } }
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
