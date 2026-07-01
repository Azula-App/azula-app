package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.azula.a2ui.A2uiAction
import dev.azula.a2ui.A2uiCodec
import dev.azula.a2ui.A2uiExamples
import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.CreateSurface
import dev.azula.a2ui.DeleteSurface
import dev.azula.model.Attachment
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.model.ConnectStage
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.model.TerminalLine
import dev.azula.net.Alpns
import dev.azula.net.FakeTransport
import dev.azula.net.FileEncoding
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import dev.azula.net.OutgoingFile
import dev.azula.net.P2pStream
import dev.azula.net.PeerStore
import dev.azula.net.receiveFile
import dev.azula.net.sendFile
import dev.azula.core.AppScope
import dev.azula.link.DeepLinkBus
import dev.azula.notify.Notifier
import dev.azula.persist.Persona
import dev.azula.terminal.TermScreen
import dev.azula.terminal.TerminalSession
import dev.azula.ui.decodeImageBitmap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

enum class MScreen { HOME, CONNECT, CHAT, SETTINGS }

/** An inbound connection awaiting the user's "share my profile?" decision. */
data class PendingShareRequest(val convId: String, val peerCode: String)

/** Build a [Frame.Profile] from a persona, including only the toggled-on fields. */
fun Persona.toFrame(shareName: Boolean, sharePic: Boolean, shareDesc: Boolean): Frame.Profile =
    Frame.Profile(
        name = if (shareName) name else "",
        description = if (shareDesc) description else null,
        avatar = if (sharePic) avatarB64 else null,
        mime = if (sharePic) mime else null,
    )

/** Per-conversation mutable state: the thread, the draft, and any live stream. */
class ConversationState(seed: List<Message>) {
    val messages = mutableStateListOf<Message>().also { it.addAll(seed) }
    var draft by mutableStateOf("")
    var online by mutableStateOf(false)
    var stream: P2pStream? = null
    var streamingMsgId: String? = null
    /** Each terminal session gets its own screen buffer. */
    val termScreen = TermScreen()
    /** The peer ticket this conversation was dialed from — used to spawn siblings. */
    var sourceTicket: String? = null
    /** Last received peer profile (a "persona"): base64 avatar + mime + description. */
    var peerAvatarB64: String? = null
    var peerAvatarMime: String? = null
    var peerAvatarBitmap by mutableStateOf<ImageBitmap?>(null)
    var peerDescription by mutableStateOf<String?>(null)
}

/**
 * The single source of truth for the whole app — a plain holder of Compose
 * snapshot state, observed identically on every platform. Owns the [transport]
 * and a long-lived [scope]. Conversations stay lively offline (canned azula,
 * local terminal) and go live the moment a real iroh stream is wired in.
 */
@Inject
@SingleIn(AppScope::class)
class AzulaState(
    private var transport: IrohTransport,
    private val scope: CoroutineScope,
    /** True only in the demo / UI-test build: loads seeded demo content and
     *  skips message persistence. The shipping build defaults this to false. */
    override val isDemo: Boolean = false,
    /** Injected collaborators (were service-locator globals). Nullable so a
     *  platform with no impl — e.g. no notifier on desktop/iOS — simply skips it. */
    private val notifier: Notifier? = null,
    private val peerStore: PeerStore? = null,
    private val personaService: PersonaService = PersonaService(null),
    private val convStore: ConversationStore = ConversationStore(),
    private val persistence: PersistenceCoordinator = PersistenceCoordinator(convStore, null, scope),
) : TerminalSession {
    // Conversation state lives in ConversationStore; expose the list for the UI.
    // The shipping build starts EMPTY (conversations are created per-peer on
    // connect); the demo/UI-test build seeds three (Maestro depends on them) — see
    // init below.
    val conversations get() = convStore.conversations
    private val convState get() = convStore.convState

    // Desktop: a conversation id, or "connect" (the default landing — and the only
    // valid view when the list is empty).
    var desktopActive by mutableStateOf(if (isDemo) "azula" else "connect")
        private set

    // Mobile navigation.
    var mScreen by mutableStateOf(MScreen.HOME)
        private set
    var mActive by mutableStateOf(if (isDemo) "azula" else "")
        private set

    var thinking by mutableStateOf(false)
        private set
    /** Whether the app is in the foreground. The platform updates this on
     *  lifecycle changes; incoming messages post a [Notifier] only when false. */
    var inForeground by mutableStateOf(true)
        private set
    fun setForeground(value: Boolean) { inForeground = value }
    var copied by mutableStateOf(false)
        private set
    var myCode by mutableStateOf("")
        private set
    /** Observable copy of the transport's node id, refreshed on every (re)bind.
     *  Compose can't observe `transport.nodeId` directly, so the UI reads this. */
    var nodeId by mutableStateOf<String?>(null)
        private set
    var connectStage by mutableStateOf(ConnectStage.IDLE)
        private set
    var connectStep by mutableStateOf(0)
        private set
    var connectInput by mutableStateOf("")
    var rttMs by mutableStateOf<Int?>(null)
        private set

    /** All live A2UI surfaces, keyed by surfaceId. */
    val surfaces = mutableStateMapOf<String, A2uiSurface>()

    val termLines = mutableStateListOf<TerminalLine>().also { if (isDemo) it.addAll(seedTerm()) }

    init {
        if (isDemo) {
            convStore.conversations.addAll(
                listOf(
                    Conversation("azula", "azula", ConversationKind.LLM, "✦", "your llm · always on", "tap to ask or play"),
                    Conversation("zuko", "zuko", ConversationKind.PEER, "z", "connected · direct p2p", "12ms?! that's nuts"),
                    Conversation("term", "terminal", ConversationKind.TERMINAL, "›_", "zuko@remote · live", "▸ ready on :5173"),
                )
            )
            convState["azula"] = ConversationState(seedAzula())
            convState["zuko"] = ConversationState(seedZuko())
            convState["term"] = ConversationState(emptyList())
        }
        // Register any surfaces embedded in the seed messages so actions work immediately.
        convState.values.forEach { cs ->
            cs.messages.forEach { m ->
                m.a2ui?.let { surfaces[it.surfaceId] = it }
            }
        }
    }

    private var azIdx = 0
    private var idCounter = 0
    /** True when the real transport failed to bind and we fell back to demo mode. */
    private var demoMode = false
    private fun nextId(p: String): String = "$p${idCounter++}"

    /** Post a system notification for a message that arrived while backgrounded. */
    private fun notifyIncoming(convId: String, body: String) {
        if (inForeground) return
        if (conversationOrNull(convId)?.enabled == false) return
        val n = notifier ?: return
        val title = runCatching { conversation(convId).name }.getOrDefault(convId)
        n.postMessage(convId, title, body)
    }

    // Conversation lookup/creation is delegated to ConversationStore; these keep
    // AzulaState's surface stable for the UI and internal callers.
    fun conv(id: String): ConversationState = convStore.conv(id)
    fun conversation(id: String): Conversation = convStore.conversation(id)
    fun convOrNull(id: String): ConversationState? = convStore.convOrNull(id)
    fun conversationOrNull(id: String): Conversation? = convStore.conversationOrNull(id)
    private fun ensureConv(peerId: String, kind: ConversationKind, announcedName: String? = null): String =
        convStore.ensureConv(peerId, kind, announcedName)
    private fun glyphFor(name: String, kind: ConversationKind): String = convStore.glyphFor(name, kind)
    private fun kindPriority(k: ConversationKind): Int = convStore.kindPriority(k)

    // ---- message persistence (shipping build only) ----

    // Message-history persistence is delegated to PersistenceCoordinator.
    private fun persist(convId: String) = persistence.save(convId, isDemo)

    /** A human-friendly label derived from any node id (our own or a peer's). */
    fun mnemonicCode(id: String): String = convStore.mnemonicCode(id)

    /** Our own code for display. Copy uses [myCode]. */
    val shortCode: String
        get() = nodeId?.let { mnemonicCode(it) } ?: "connecting…"

    // ---- lifecycle ----

    suspend fun start() {
        // Restore persisted conversation history (shipping build only; the demo
        // build shows seeds instead). Restored A2UI surfaces render frozen.
        if (!isDemo) { val max = persistence.restoreAll(); if (max >= 0) idCounter = max + 1 }
        personaService.load()
        // Whenever the transport (re)binds — cold start and Android foreground-
        // resume — re-dial saved peers/bridges and ping them, so a bridge can
        // immediately flush any messages it queued while we were offline.
        transport.onCameOnline = { nodeId = transport.nodeId; scope.launch { reconnectSaved() } }
        try {
            transport.bind(Alpns.ALL)
        } catch (e: Throwable) {
            // Real binding unavailable (e.g. missing native lib) — degrade to demo.
            demoMode = true
            transport = FakeTransport().also { it.bind(Alpns.ALL) }
        }
        nodeId = transport.nodeId
        myCode = runCatching { transport.myTicket() }.getOrDefault("")
        scope.launch {
            runCatching {
                transport.incoming().collect { inc ->
                    // A disabled conversation refuses inbound from that peer.
                    if (conversationOrNull(inc.remoteId)?.enabled == false) {
                        scope.launch { runCatching { inc.stream.close() } }
                        return@collect
                    }
                    val convId = wireStream(inc.alpn, inc.stream, inc.remoteId)
                    // Inbound peer (CHAT) connection: prompt the user to share a
                    // profile back ("prompt each time"). Deduped per conversation.
                    if (convId != null && inc.alpn == Alpns.CHAT) enqueueShareRequest(convId)
                }
            }
        }
        // Register the deeplink handler AFTER bind so a buffered or cold-start
        // token (azula://connect?code= / azula.app/s/<token>, or AZULA_CONNECT)
        // dials an already-bound transport rather than racing the bind.
        DeepLinkBus.setHandler { token -> connectPeer(token) }
    }

    // ---- navigation ----

    fun selectDesktop(id: String) { desktopActive = id }
    fun openConnectDesktop() { desktopActive = "connect"; resetConnect() }
    fun openSettingsDesktop() { desktopActive = "settings" }
    fun openChatMobile(id: String) { mActive = id; mScreen = MScreen.CHAT }
    fun openConnectMobile() { mScreen = MScreen.CONNECT; resetConnect() }
    fun openSettingsMobile() { mScreen = MScreen.SETTINGS }
    fun mobileBack() { mScreen = MScreen.HOME }

    private fun resetConnect() {
        if (connectStage != ConnectStage.DIALING) {
            connectStage = ConnectStage.IDLE
            connectStep = 0
        }
    }

    // ---- input / sending ----

    fun updateDraft(convId: String, text: String) { conv(convId).draft = text }

    fun send(convId: String) {
        val cs = conv(convId)
        val txt = cs.draft.trim()
        if (txt.isEmpty()) return
        if (conversation(convId).kind == ConversationKind.TERMINAL) { cs.draft = ""; terminalInput(convId, txt); return }
        cs.draft = ""
        cs.messages.add(Message(nextId("u"), Role.ME, text = txt))
        persist(convId)
        val stream = cs.stream
        when {
            stream != null -> scope.launch {
                runCatching { stream.sendLine(FrameCodec.encode(Frame.Chat(txt))) }
            }
            // Offline LLM conversation: fall back to the canned local assistant.
            conversationOrNull(convId)?.kind == ConversationKind.LLM -> azulaReply(convId, txt)
        }
    }

    /**
     * Send a file attachment to the conversation identified by [convId].
     * Appends a local [Message] immediately (so the UI reacts), then launches the
     * transfer on the live stream if one is open.
     */
    fun sendFile(
        convId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        encoding: FileEncoding = FileEncoding.BASE64,
        caption: String? = null,
    ) {
        val cs = conv(convId)
        val msgId = nextId("f")
        val attachment = Attachment(
            id = msgId,
            name = name,
            mime = mime,
            size = bytes.size.toLong(),
            bytes = bytes,
            caption = caption,
        )
        cs.messages.add(Message(msgId, Role.ME, text = caption, attachments = listOf(attachment)))
        persist(convId)
        val stream = cs.stream ?: return
        scope.launch {
            runCatching {
                sendFile(stream, OutgoingFile(name = name, mime = mime, bytes = bytes, caption = caption), encoding)
            }
        }
    }

    fun markCopied() {
        copied = true
        scope.launch { delay(1400); copied = false }
    }

    // ---- terminal ----

    fun terminalInput(convId: String, cmdRaw: String) {
        val cmd = cmdRaw.trim()
        val stream = conv(convId).stream
        if (stream != null) {
            // Live mode: send the line raw; the shell echoes it back via termScreen.
            scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(cmd + "\n"))) } }
            return
        }
        // Demo mode only (the seeded offline terminal): canned output. A real
        // terminal that has gone offline (its shell exited) must NOT fall into the
        // canned interpreter — the UI shows a "session ended" state instead.
        if (!isDemo) return
        termLines.add(TerminalLine(TerminalLine.Kind.CMD, cmd))
        termOut(cmd).forEach { termLines.add(it) }
    }

    /**
     * Re-open the remote shell for a terminal whose session ended (e.g. the user
     * typed `exit`). Dials the saved ticket again and rewires this same
     * conversation with a fresh stream. No-op if we have no ticket to redial.
     */
    fun reconnectTerminal(convId: String) {
        val ticket = convOrNull(convId)?.sourceTicket ?: return
        scope.launch {
            val stream = runCatching { transport.connect(ticket, Alpns.TERM) }.getOrNull() ?: return@launch
            wireConv(convId, stream, isTerminal = true)
        }
    }

    // ---- TerminalSession: the narrow surface the terminal UI depends on ----
    override val demoLines: List<TerminalLine> get() = termLines
    override fun terminalScreen(convId: String): TermScreen = conv(convId).termScreen
    override fun terminalOnline(convId: String): Boolean = convOrNull(convId)?.online == true

    /** Send Ctrl-C to the remote shell when a live term stream is open. */
    override fun terminalInterrupt(convId: String) {
        terminalRaw(convId, dev.azula.terminal.TermSeq.CTRL_C)
    }

    /** Send raw keystroke bytes to the live PTY, verbatim (no trim / no newline).
     *  This is the "type directly in the terminal" path — the shell echoes back. */
    override fun terminalRaw(convId: String, bytes: String) {
        if (bytes.isEmpty()) return
        val stream = convOrNull(convId)?.stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(bytes))) } }
    }

    /** Report the terminal's viewport size (character cells) so the PTY wraps to
     *  match; deduped so we only send on real changes. */
    private val termSizes = mutableMapOf<String, Pair<Int, Int>>()
    override fun terminalResize(convId: String, cols: Int, rows: Int) {
        if (cols < 1 || rows < 1) return
        if (termSizes[convId] == (cols to rows)) return
        termSizes[convId] = cols to rows
        val stream = convOrNull(convId)?.stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Resize(cols, rows))) } }
    }

    private fun termOut(cmdRaw: String): List<TerminalLine> {
        val c = cmdRaw.trim().lowercase()
        fun out(t: String, tint: TerminalLine.Tint = TerminalLine.Tint.NORMAL) =
            TerminalLine(TerminalLine.Kind.OUT, t, tint)
        return when {
            c == "ls" -> listOf(out("src/   package.json   iroh.toml   README.md"))
            c == "help" -> listOf(out("commands: ls · whoami · ping · iroh status · clear"))
            c == "whoami" -> listOf(out("zuko"))
            c == "ping" -> listOf(out("64 bytes from peer: time=12.0 ms (direct, e2e)"))
            c.startsWith("iroh") -> listOf(out("✓ direct · 12ms · e2e · 0 relays", TerminalLine.Tint.GREEN))
            c == "clear" -> { scope.launch { termLines.clear() }; emptyList() }
            c.isEmpty() -> emptyList()
            else -> listOf(out("azula: command not found: $cmdRaw (try `help`)", TerminalLine.Tint.ERROR))
        }
    }

    // ---- canned azula (used until a real LLM stream is wired) ----

    private fun azulaReply(convId: String, userTxt: String) {
        thinking = true
        val low = userTxt.lowercase()
        val reply: List<Message> = when {
            Regex("game|dice|play|roll").containsMatchIn(low) -> {
                val surface = A2uiExamples.diceSurface(nextId("ds"))
                surfaces[surface.surfaceId] = surface
                listOf(
                    Message(nextId("r"), Role.AZULA, text = "you're on. **first to roll higher wins.** 🎲"),
                    Message(nextId("r"), Role.AZULA, a2ui = surface),
                )
            }
            Regex("poll|vote").containsMatchIn(low) -> {
                val surface = A2uiExamples.pollSurface(
                    nextId("ps"),
                    "ship the build tonight?",
                    listOf("ship it" to 9, "sleep on it" to 4),
                )
                surfaces[surface.surfaceId] = surface
                listOf(
                    Message(nextId("r"), Role.AZULA, text = "spinning one up — tap to vote:"),
                    Message(nextId("r"), Role.AZULA, a2ui = surface),
                )
            }
            Regex("terminal|shell|ssh").containsMatchIn(low) -> listOf(
                Message(
                    nextId("r"), Role.AZULA,
                    text = "opening zuko's shell — switch to the **terminal** tab and you'll share a live cursor.\n\n```bash\n$ whoami\nzuko\n```",
                ),
            )
            else -> {
                val cycle = listOf(
                    listOf(Message(nextId("r"), Role.AZULA, text = "current round-trips:\n\n| relay | rtt | load |\n|---|---|---|\n| **sfo** | 12ms | low |\n| ams | 88ms | low |\n| tok | 140ms | med |\n\nwe're direct anyway — this is just insurance.")),
                    run {
                        val surface = A2uiExamples.decideSurface(
                            nextId("dc"),
                            "what next?",
                            listOf("play dice", "run a poll", "open terminal"),
                        )
                        surfaces[surface.surfaceId] = surface
                        listOf(
                            Message(nextId("r"), Role.AZULA, text = "sure — pick how we settle it:"),
                            Message(nextId("r"), Role.AZULA, a2ui = surface),
                        )
                    },
                    listOf(Message(nextId("r"), Role.AZULA, text = "> **tip:** type `/terminal` to drive a shell, `/poll`, or `play a game`.\n\nI render **markdown**, tables, code, and spin up little UIs inline. what do you need?")),
                )
                cycle[azIdx % cycle.size].also { azIdx++ }
            }
        }
        scope.launch {
            delay(800)
            thinking = false
            reply.forEach { conv(convId).messages.add(it) }
            persist(convId)
        }
    }

    // ---- A2UI actions ----

    /**
     * Called when the user triggers an action on an A2UI surface.
     * If a live stream is open, encodes and sends it; otherwise interprets locally.
     */
    fun onA2uiAction(convId: String, action: A2uiAction) {
        val stream = conv(convId).stream
        if (stream != null) {
            scope.launch {
                runCatching {
                    stream.sendLine(FrameCodec.encode(Frame.A2uiAction(A2uiCodec.encodeAction(action))))
                }
            }
        } else {
            surfaces[action.surfaceId]?.let { A2uiExamples.handleEvent(it, action) }
            persist(convId)
        }
    }

    /**
     * Called when a two-way input (TextField, CheckBox, etc.) changes its value.
     */
    fun onA2uiDataChange(convId: String, surface: A2uiSurface, path: String, value: JsonElement) {
        surface.setData(path, value)
        persist(convId)
    }

    // ---- connecting ----

    fun connectPeer(ticketRaw: String, share: Frame.Profile? = null) {
        // Accept a raw code or a full azula share URL (azula://connect?code=… /
        // https://azula.app/s/… /connect/…) pasted into the box.
        val ticket = dev.azula.link.AzulaLinks.parse(ticketRaw) ?: ticketRaw.trim()
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
            // One conversation per peer: keep the highest-priority service the peer
            // accepts (CHAT > LLM > TERM) and close the rest, so a peer that accepts
            // every ALPN doesn't spawn three duplicate rows.
            val primary = results.maxBy { kindPriority(kindForAlpn(it.first)) }
            results.filter { it !== primary }.forEach { (_, s) -> scope.launch { runCatching { s.close() } } }
            val peerId = transport.peerIdForTicket(ticket) ?: ("t" + ticket.hashCode().toString(16))
            val convId = wireStream(primary.first, primary.second, peerId)
            // Share the chosen persona — peer-to-peer only (CHAT). Never send to
            // LLM/TERM servers (an older CLI without the Profile frame would drop
            // the stream). Hello (ping) already went first on every stream.
            if (share != null && primary.first == Alpns.CHAT) {
                runCatching { primary.second.sendLine(FrameCodec.encode(share)) }
            }
            persistPeer(ticket)
            // Remember the ticket so we can spawn sibling sessions over it (no re-pair).
            convId?.let { convOrNull(it)?.sourceTicket = ticket }
            rttMs = 12
            connectStage = ConnectStage.CONNECTED
            delay(1200)
            if (convId != null) navigateAfterConnect(convId)
            connectStage = ConnectStage.IDLE
            connectInput = ""
            connectStep = 0
        }
    }

    private fun navigateAfterConnect(target: String) {
        desktopActive = target
        mActive = target
        mScreen = MScreen.CHAT
    }

    /**
     * Open a NEW session over the same peer connection as [convId] — a fresh
     * terminal or LLM conversation that reuses the known ticket (no re-pairing).
     * The server multiplexes it onto the existing connection as a new stream.
     */
    fun newSessionFrom(convId: String) {
        val ticket = conv(convId).sourceTicket ?: return
        val kind = conversation(convId).kind
        val alpn = when (kind) {
            ConversationKind.TERMINAL -> Alpns.TERM
            ConversationKind.LLM -> Alpns.LLM
            ConversationKind.PEER -> Alpns.CHAT
        }
        scope.launch {
            val stream = runCatching { transport.connect(ticket, alpn) }.getOrNull() ?: return@launch
            val base = conversationOrNull(convId)?.name ?: mnemonicCode(transport.peerIdForTicket(ticket) ?: ticket)
            val n = conversations.count { convOrNull(it.id)?.sourceTicket == ticket && it.kind == kind } + 1
            val id = nextId("sess")
            convState[id] = ConversationState(emptyList()).also { it.sourceTicket = ticket }
            conversations.add(Conversation(id, "$base · session $n", kind, glyphFor(base, kind), sub = "live session", preview = ""))
            wireConv(id, stream, isTerminal = kind == ConversationKind.TERMINAL)
            mActive = id
            desktopActive = id
            mScreen = MScreen.CHAT
        }
    }

    /**
     * Announce presence on a freshly-opened outbound stream by sending a [Frame.Hello]
     * as its first frame. This is the "ping": it tells the remote we're online and,
     * for a bridge, unblocks its accept loop so it flushes any mail queued for us.
     * Best-effort — failures are swallowed by the caller.
     */
    private suspend fun ping(stream: P2pStream) {
        stream.sendLine(FrameCodec.encode(Frame.Hello(transport.nodeId ?: "")))
    }

    /** Remember a peer ticket (most-recent 10) so we can auto-reconnect later. */
    private fun persistPeer(ticket: String) {
        peerStore?.let { store ->
            store.save((store.load() + ticket).distinct().takeLast(10))
        }
    }

    /**
     * Re-dial every saved peer silently (no connect UI), filling in any
     * conversation that isn't already live. Retries a few times to ride out the
     * endpoint still coming up (e.g. just after a foreground rebind). No-op in
     * demo mode.
     */
    fun reconnectSaved() {
        if (demoMode) return
        val saved = peerStore?.load().orEmpty()
        if (saved.isEmpty()) return
        saved.forEach { ticket ->
            val peerId = transport.peerIdForTicket(ticket) ?: return@forEach
            // Disabled conversations don't auto-reconnect.
            if (conversationOrNull(peerId)?.enabled == false) return@forEach
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
                        // Same "one conversation per peer" collapse as connectPeer.
                        val primary = results.maxBy { kindPriority(kindForAlpn(it.first)) }
                        results.filter { it !== primary }.forEach { (_, s) -> runCatching { s.close() } }
                        val id = wireStream(primary.first, primary.second, peerId, replace = false)
                        id?.let { convOrNull(it)?.sourceTicket = ticket }
                        return@launch
                    }
                    delay(700)
                }
            }
        }
    }

    // ---- stream wiring + incoming frames ----

    private fun kindForAlpn(alpn: String): ConversationKind = when (alpn) {
        Alpns.CHAT -> ConversationKind.PEER
        Alpns.LLM -> ConversationKind.LLM
        Alpns.TERM -> ConversationKind.TERMINAL
        else -> ConversationKind.PEER
    }

    /** Wire [stream] into the conversation for [peerId] (created/named on demand). */
    private fun wireStream(
        alpn: String,
        stream: P2pStream,
        peerId: String,
        announcedName: String? = null,
        replace: Boolean = true,
    ): String? {
        if (peerId.isBlank()) { scope.launch { runCatching { stream.close() } }; return null }
        val convId = ensureConv(peerId, kindForAlpn(alpn), announcedName)
        wireConv(convId, stream, isTerminal = alpn == Alpns.TERM, replace = replace)
        return convId
    }

    /** Attach a live [stream] to [convId], replacing any current one unless [replace] is false. */
    private fun wireConv(convId: String, stream: P2pStream, isTerminal: Boolean, replace: Boolean = true) {
        val cs = conv(convId)
        // A silent reconnect must never disrupt an already-live conversation.
        if (!replace && cs.online && cs.stream != null) {
            scope.launch { runCatching { stream.close() } }
            return
        }
        cs.stream = stream
        if (isTerminal) cs.termScreen.clear()
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
                    // The conversation is already keyed by the peer's node id. A Hello
                    // whose name differs from that id is a *friendly announcement*
                    // (a bridge "azula", a game "Blackjack") — show it as the title
                    // (the peer code stays the subtitle). A Hello equal to the id is
                    // just the peer's presence ping; ignore it for naming.
                    if (frame is Frame.Hello) {
                        if (frame.name.isNotBlank() && frame.name != convId) {
                            ensureConv(convId, conversationOrNull(convId)?.kind ?: kindForAlpn(Alpns.LLM), frame.name)
                        }
                        continue
                    }
                    // FileBegin must be handled here (suspend context, access to stream).
                    if (frame is Frame.FileBegin) {
                        runCatching {
                            val received = receiveFile(stream, frame) { stream.receiveLine() }
                            val attachment = Attachment(
                                id = received.id,
                                name = received.name,
                                mime = received.mime,
                                size = received.bytes.size.toLong(),
                                bytes = received.bytes,
                                caption = received.caption,
                            )
                            conv(convId).messages.add(
                                Message(
                                    nextId("r"),
                                    Role.THEM,
                                    text = received.caption,
                                    attachments = listOf(attachment),
                                )
                            )
                            persist(convId)
                            notifyIncoming(convId, received.caption ?: "sent ${received.name}")
                        }
                        continue
                    }
                    // Stray file_chunk / file_end outside a transfer are ignored.
                    if (frame is Frame.FileChunk || frame is Frame.FileEnd) continue
                    // A peer's profile: name the conversation + store the avatar.
                    if (frame is Frame.Profile) { applyProfile(convId, frame); continue }
                    applyFrame(convId, frame)
                }
            } finally {
                conv(convId).online = false
                conv(convId).stream = null
            }
        }
    }

    private fun applyFrame(convId: String, frame: Frame) {
        val cs = conv(convId)
        when (frame) {
            is Frame.Hello -> Unit // presence ping from a peer/bridge; identity is handled at connect, nothing to render
            is Frame.Chat -> {
                cs.messages.add(Message(nextId("p"), Role.THEM, text = frame.text))
                notifyIncoming(convId, frame.text)
            }
            is Frame.Term -> cs.termScreen.feed(frame.line)
            is Frame.Thinking -> if (conversationOrNull(convId)?.kind == ConversationKind.LLM) thinking = frame.on
            is Frame.A2ui -> {
                val msg = A2uiCodec.parse(frame.message.toString()) ?: return
                when (msg) {
                    is CreateSurface -> {
                        val surface = A2uiSurface(msg.surfaceId, msg.catalogId)
                        surfaces[msg.surfaceId] = surface
                        // If a card with this surface id already exists (e.g. a
                        // Blackjack game we're reconnecting to, or a restored one),
                        // re-attach the live surface in place instead of stacking a
                        // duplicate — the server re-creates the same id to resume.
                        val existing = cs.messages.indexOfLast { it.a2ui?.surfaceId == msg.surfaceId }
                        if (existing >= 0) {
                            cs.messages[existing] = cs.messages[existing].copy(a2ui = surface)
                        } else {
                            cs.messages.add(Message(nextId("p"), Role.AZULA, a2ui = surface))
                            notifyIncoming(convId, "sent an interactive card")
                        }
                    }
                    is DeleteSurface -> surfaces.remove(msg.surfaceId)
                    else -> surfaces[msg.surfaceId]?.apply(msg)
                }
            }
            is Frame.A2uiAction -> Unit // client→server only; ignored on receive
            is Frame.Token -> {
                val role = if (conversationOrNull(convId)?.kind == ConversationKind.LLM) Role.AZULA else Role.THEM
                if (cs.streamingMsgId == null && !frame.done) {
                    val id = nextId("s")
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
                    // A completed streamed message (e.g. a bridge `send_message`)
                    // arriving while backgrounded is an incoming push — notify.
                    val finished = cs.streamingMsgId?.let { id -> cs.messages.lastOrNull { it.id == id } }
                    cs.streamingMsgId = null
                    finished?.text?.takeIf { it.isNotBlank() }?.let { notifyIncoming(convId, it) }
                }
            }
            is Frame.Input, is Frame.Resize -> Unit // client → server only
            // File + Profile frames are handled in receiveLoop directly; unreachable here.
            is Frame.FileBegin, is Frame.FileChunk, is Frame.FileEnd, is Frame.Profile -> Unit
        }
        // Persist after applying — but not on every streamed token delta (only when done).
        if (frame !is Frame.Token || frame.done) persist(convId)
    }

    // ---- conversation management (enable/disable, delete) ----

    /**
     * Enable or disable a conversation. Disabling closes any live stream and
     * stops auto-reconnect + notifications (the row + history stay, greyed).
     * Enabling re-dials saved peers so it can come back online.
     */
    fun setConversationEnabled(id: String, enabled: Boolean) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        conversations[idx] = conversations[idx].copy(enabled = enabled)
        if (!enabled) {
            convOrNull(id)?.let { cs ->
                cs.stream?.let { s -> scope.launch { runCatching { s.close() } } }
                cs.stream = null
                cs.online = false
            }
        } else {
            scope.launch { reconnectSaved() }
        }
        persist(id)
    }

    /**
     * Delete a conversation entirely: close its stream, drop its saved ticket,
     * remove its on-disk history, and reset the active selection if it pointed
     * here. Demo mode keeps its blob untouched (persistence is a no-op there).
     */
    fun deleteConversation(id: String) {
        convOrNull(id)?.let { cs ->
            cs.stream?.let { s -> scope.launch { runCatching { s.close() } } }
            cs.stream = null
            cs.online = false
        }
        // PeerStore has no per-item delete — save a filtered list. Match by the
        // ticket's resolved node id (covers inbound-only convs with no sourceTicket).
        peerStore?.let { store ->
            store.save(store.load().filter { (transport.peerIdForTicket(it) ?: "") != id })
        }
        persistence.delete(id, isDemo)
        convState.remove(id)
        conversations.removeAll { it.id == id }
        pendingShares.removeAll { it.convId == id }
        if (desktopActive == id) desktopActive = conversations.firstOrNull()?.id ?: "connect"
        if (mActive == id) { mActive = ""; mScreen = MScreen.HOME }
    }

    // ---- profiles / personas ----

    // Persona management is delegated to PersonaService; these accessors + methods
    // keep AzulaState's public surface stable so the UI is unchanged.
    val personas get() = personaService.personas
    val defaultPersonaId get() = personaService.defaultPersonaId

    /** Inbound connections awaiting a "share my profile?" decision (prompt each time). */
    val pendingShares = mutableStateListOf<PendingShareRequest>()

    fun newPersonaId(): String = personaService.newId()
    fun upsertPersona(persona: Persona) = personaService.upsert(persona)
    fun deletePersona(id: String) = personaService.delete(id)
    fun setDefaultPersona(id: String) = personaService.setDefault(id)
    fun personaById(id: String?): Persona? = personaService.byId(id)

    /** Apply a peer's received profile: rename the conversation + store the avatar. */
    private fun applyProfile(convId: String, p: Frame.Profile) {
        if (p.name.isNotBlank()) {
            ensureConv(convId, conversationOrNull(convId)?.kind ?: kindForAlpn(Alpns.CHAT), p.name)
        }
        convOrNull(convId)?.let { cs ->
            cs.peerDescription = p.description
            cs.peerAvatarB64 = p.avatar
            cs.peerAvatarMime = p.mime
            cs.peerAvatarBitmap = p.avatar?.let { decodeAvatar(it) }
        }
        persist(convId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeAvatar(b64: String): ImageBitmap? =
        runCatching { decodeImageBitmap(Base64.decode(b64)) }.getOrNull()

    /** Enqueue a "share my profile?" prompt for a new inbound connection (deduped). */
    private fun enqueueShareRequest(convId: String) {
        if (pendingShares.any { it.convId == convId }) return
        pendingShares.add(PendingShareRequest(convId, mnemonicCode(convId)))
        if (!inForeground) {
            notifier?.postMessage(
                convId, "Connection request", "${mnemonicCode(convId)} connected",
            )
        }
    }

    /** Respond to a pending share prompt: send [frame] back (or nothing to decline). */
    fun respondShare(convId: String, frame: Frame.Profile?) {
        pendingShares.removeAll { it.convId == convId }
        if (frame != null) {
            convOrNull(convId)?.stream?.let { s ->
                scope.launch { runCatching { s.sendLine(FrameCodec.encode(frame)) } }
            }
        }
    }

    companion object {
        private fun seedAzula(): List<Message> {
            val decideSurface = A2uiExamples.decideSurface("a4", "pick a backup relay", listOf("sfo", "ams", "tok"))
            return listOf(
                Message("a1", Role.AZULA, text = "**hey — I'm azula.** your peer that thinks.\n\nask me anything, drive a terminal, or say *play a game* and I'll build one right here in the chat."),
                Message("a2", Role.ME, text = "explain how iroh connects us, briefly"),
                Message("a3", Role.AZULA, text = "**the gist:**\n\n- iroh dials a **direct path** between us via UDP holepunching\n- no path? it falls back to a relay, then *upgrades* to direct\n- all traffic is **end-to-end encrypted** over QUIC\n\n```bash\niroh doctor connect <ticket>\n# ✓ direct · 12ms · e2e — no server in the middle\n```\n\nwant me to lock a backup relay, just in case?"),
                Message("a4", Role.AZULA, a2ui = decideSurface),
            )
        }

        private fun seedZuko(): List<Message> = listOf(
            Message("z1", Role.THEM, text = "did the tunnel come up?"),
            Message("z2", Role.ME, text = "yeah — **fully p2p**, no server between us"),
            Message("z3", Role.THEM, text = "ping?"),
            Message("z4", Role.ME, text = "`12ms`. direct."),
            Message("z5", Role.THEM, text = "**12ms?!** that's nuts 🔥"),
            demoImageMessage(),
        )

        // A small checkerboard BMP, embedded so the inline-image rendering path
        // is visible offline (no live transfer needed). BMP decodes on skiko
        // (JVM/iOS) and Android's BitmapFactory.
        private fun demoImageMessage(): Message {
            val bmp = demoImageBmp()
            return Message(
                "z6", Role.THEM, text = "sent you a pic 📷",
                attachments = listOf(Attachment("z6-img", "azula.bmp", "image/bmp", bmp.size.toLong(), bmp, null)),
            )
        }

        private fun demoImageBmp(): ByteArray {
            val w = 160
            val h = 100
            val rowSize = ((w * 3 + 3) / 4) * 4
            val out = ByteArray(54 + rowSize * h)
            fun le32(off: Int, v: Int) {
                out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v shr 8) and 0xFF).toByte()
                out[off + 2] = ((v shr 16) and 0xFF).toByte(); out[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun le16(off: Int, v: Int) { out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v shr 8) and 0xFF).toByte() }
            out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
            le32(2, out.size); le32(10, 54)
            le32(14, 40); le32(18, w); le32(22, h); le16(26, 1); le16(28, 24)
            le32(34, rowSize * h); le32(38, 2835); le32(42, 2835)
            var off = 54
            for (row in 0 until h) {
                for (x in 0 until w) {
                    val pink = ((x / 20) + (row / 20)) % 2 == 0
                    out[off] = (if (pink) 0x9B else 0x1A).toByte()
                    out[off + 1] = (if (pink) 0x2D else 0x10).toByte()
                    out[off + 2] = (if (pink) 0xFF else 0x16).toByte()
                    off += 3
                }
                off += rowSize - w * 3
            }
            return out
        }

        private fun seedTerm(): List<TerminalLine> = listOf(
            TerminalLine(TerminalLine.Kind.OUT, "azula terminal · sharing zuko@remote — 2 cursors live", TerminalLine.Tint.GREEN),
            TerminalLine(TerminalLine.Kind.CMD, "iroh doctor connect"),
            TerminalLine(TerminalLine.Kind.OUT, "✓ direct path established · 12ms · holepunched"),
            TerminalLine(TerminalLine.Kind.OUT, "✓ end-to-end encrypted · QUIC"),
            TerminalLine(TerminalLine.Kind.CMD, "npm run dev"),
            TerminalLine(TerminalLine.Kind.OUT, "▸ ready on :5173 — forwarded to you"),
        )
    }
}
