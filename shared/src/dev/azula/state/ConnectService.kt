package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.azula.core.RecoveryPhrase
import dev.azula.a2ui.A2uiCodec
import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.CreateSurface
import dev.azula.a2ui.DeleteSurface
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
import dev.azula.notify.Notifier
import dev.azula.ui.decodeImageBitmap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The connect + transport core carved out of AzulaState: owns the (rebindable)
 * [transport], the outbound dial / holepunch flow, silent reconnect, the inbound
 * connection loop, and the per-conversation receive loop + frame dispatcher that
 * fans incoming frames out to the feature services (chat, a2ui, terminal). Also
 * owns the peer-profile handshake (the "share my profile?" prompts).
 *
 * Two app-shell concerns are provided by callbacks the owner sets after
 * construction (avoids a circular graph dependency): [onNavigate] to open a
 * conversation, and [foreground] to gate background notifications.
 */
@Inject
@SingleIn(AppScope::class)
class ConnectService(
    private val transport: IrohTransport,
    private val scope: CoroutineScope,
    private val convStore: ConversationStore,
    private val surfaceStore: SurfaceStore,
    private val persistence: PersistenceCoordinator,
    private val chatService: ChatService,
    private val notifier: Notifier?,
    private val peerStore: PeerStore?,
) {
    /** Set by AzulaState: open a conversation's chat view. */
    var onNavigate: (String) -> Unit = {}
    /** Set by AzulaState: whether the app is currently in the foreground. */
    var foreground: () -> Boolean = { true }

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
    var rttMs by mutableStateOf<Int?>(null)
        private set
    val shortCode: String get() = nodeId?.let { convStore.mnemonicCode(it) } ?: "connecting…"

    /** Inbound connections awaiting a "share my profile?" decision (prompt each time). */
    val pendingShares = mutableStateListOf<PendingShareRequest>()

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
            transport.bind(Alpns.ALL)
        } catch (e: Throwable) {
            // Binding unavailable (e.g. missing native lib) — stay offline.
            offline = true
        }
        nodeId = transport.nodeId
        myCode = runCatching { transport.myTicket() }.getOrDefault("")
        scope.launch {
            runCatching {
                transport.incoming().collect { inc ->
                    if (convStore.conversationOrNull(inc.remoteId)?.enabled == false) {
                        scope.launch { runCatching { inc.stream.close() } }
                        return@collect
                    }
                    val convId = wireStream(inc.alpn, inc.stream, inc.remoteId)
                    if (convId != null && inc.alpn == Alpns.CHAT) enqueueShareRequest(convId)
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
            val results = Alpns.ALL.map { alpn ->
                async {
                    runCatching { transport.connect(ticket, alpn) }.getOrNull()
                        ?.also { runCatching { ping(it) } }?.let { alpn to it }
                }
            }.awaitAll().filterNotNull()
            anim.cancel()
            if (results.isEmpty()) {
                connectStage = ConnectStage.FAILED
                return@launch
            }
            connectStep = 3
            val primary = results.maxBy { convStore.kindPriority(kindForAlpn(it.first)) }
            results.filter { it !== primary }.forEach { (_, s) -> scope.launch { runCatching { s.close() } } }
            val peerId = transport.peerIdForTicket(ticket) ?: ("t" + ticket.hashCode().toString(16))
            val convId = wireStream(primary.first, primary.second, peerId)
            if (share != null && primary.first == Alpns.CHAT) {
                runCatching { primary.second.sendLine(FrameCodec.encode(share)) }
            }
            persistPeer(ticket)
            convId?.let { convStore.convOrNull(it)?.sourceTicket = ticket }
            rttMs = 12
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
                    if (results.isNotEmpty()) {
                        val primary = results.maxBy { convStore.kindPriority(kindForAlpn(it.first)) }
                        results.filter { it !== primary }.forEach { (_, s) -> runCatching { s.close() } }
                        val id = wireStream(primary.first, primary.second, peerId, replace = false)
                        id?.let { convStore.convOrNull(it)?.sourceTicket = ticket }
                        return@launch
                    }
                    delay(700)
                }
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
                            notify(convId, received.caption ?: "sent ${received.name}")
                        }
                        continue
                    }
                    if (frame is Frame.FileChunk || frame is Frame.FileEnd) continue
                    if (frame is Frame.Profile) { applyProfile(convId, frame); continue }
                    applyFrame(convId, frame)
                }
            } finally {
                convStore.conv(convId).online = false
                convStore.conv(convId).stream = null
            }
        }
    }

    private fun applyFrame(convId: String, frame: Frame) {
        val cs = convStore.conv(convId)
        when (frame) {
            is Frame.Hello -> Unit
            is Frame.Chat -> {
                cs.messages.add(Message(convStore.nextId("p"), Role.THEM, text = frame.text))
                notify(convId, frame.text)
            }
            is Frame.Term -> scope.launch(terminalDispatcher) { cs.termScreen.feed(frame.line) }
            is Frame.Thinking -> if (convStore.conversationOrNull(convId)?.kind == ConversationKind.LLM) chatService.updateThinking(frame.on)
            is Frame.A2ui -> {
                val msg = A2uiCodec.parse(frame.message.toString()) ?: return
                when (msg) {
                    is CreateSurface -> {
                        val surface = A2uiSurface(msg.surfaceId, msg.catalogId)
                        surfaceStore.surfaces[msg.surfaceId] = surface
                        val existing = cs.messages.indexOfLast { it.a2ui?.surfaceId == msg.surfaceId }
                        if (existing >= 0) {
                            cs.messages[existing] = cs.messages[existing].copy(a2ui = surface)
                        } else {
                            cs.messages.add(Message(convStore.nextId("p"), Role.AZULA, a2ui = surface))
                            notify(convId, "sent an interactive card")
                        }
                    }
                    is DeleteSurface -> surfaceStore.surfaces.remove(msg.surfaceId)
                    else -> surfaceStore.surfaces[msg.surfaceId]?.apply(msg)
                }
            }
            is Frame.A2uiAction -> Unit
            is Frame.Token -> {
                val role = if (convStore.conversationOrNull(convId)?.kind == ConversationKind.LLM) Role.AZULA else Role.THEM
                if (cs.streamingMsgId == null && !frame.done) {
                    val id = convStore.nextId("s")
                    cs.streamingMsgId = id
                    cs.messages.add(Message(id, role, text = frame.delta))
                } else {
                    val id = cs.streamingMsgId
                    if (id != null) {
                        val idx = cs.messages.indexOfLast { it.id == id }
                        if (idx >= 0) {
                            val cur = cs.messages[idx]
                            cs.messages[idx] = cur.copy(text = (cur.text ?: "") + frame.delta)
                        }
                    }
                }
                if (frame.done) {
                    val finished = cs.streamingMsgId?.let { id -> cs.messages.lastOrNull { it.id == id } }
                    cs.streamingMsgId = null
                    finished?.text?.takeIf { it.isNotBlank() }?.let { notify(convId, it) }
                }
            }
            is Frame.Input, is Frame.Resize -> Unit
            is Frame.FileBegin, is Frame.FileChunk, is Frame.FileEnd, is Frame.Profile -> Unit
        }
        if (frame !is Frame.Token || frame.done) persistence.save(convId)
    }

    // ---- peer profile handshake ----

    private fun applyProfile(convId: String, p: Frame.Profile) {
        if (p.name.isNotBlank()) {
            convStore.ensureConv(convId, convStore.conversationOrNull(convId)?.kind ?: kindForAlpn(Alpns.CHAT), p.name)
        }
        convStore.convOrNull(convId)?.let { cs ->
            cs.peerDescription = p.description
            cs.peerAvatarB64 = p.avatar
            cs.peerAvatarMime = p.mime
            cs.peerAvatarBitmap = p.avatar?.let { decodeAvatar(it) }
        }
        persistence.save(convId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeAvatar(b64: String): ImageBitmap? =
        runCatching { decodeImageBitmap(Base64.decode(b64)) }.getOrNull()

    private fun enqueueShareRequest(convId: String) {
        if (pendingShares.any { it.convId == convId }) return
        pendingShares.add(PendingShareRequest(convId, convStore.mnemonicCode(convId)))
        if (!foreground()) {
            notifier?.postMessage(convId, "Connection request", "${convStore.mnemonicCode(convId)} connected")
        }
    }

    fun respondShare(convId: String, frame: Frame.Profile?) {
        pendingShares.removeAll { it.convId == convId }
        if (frame != null) {
            convStore.convOrNull(convId)?.stream?.let { s ->
                scope.launch { runCatching { s.sendLine(FrameCodec.encode(frame)) } }
            }
        }
    }

    private fun notify(convId: String, body: String) {
        if (foreground()) return
        if (convStore.conversationOrNull(convId)?.enabled == false) return
        val n = notifier ?: return
        val title = runCatching { convStore.conversation(convId).name }.getOrDefault(convId)
        n.postMessage(convId, title, body)
    }
}
