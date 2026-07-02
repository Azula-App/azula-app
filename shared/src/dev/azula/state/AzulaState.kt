package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.azula.a2ui.A2uiAction
import dev.azula.a2ui.A2uiCodec
import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.CreateSurface
import dev.azula.a2ui.DeleteSurface
import dev.azula.model.Attachment
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.model.ConnectStage
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.net.Alpns
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
import dev.azula.terminal.PredictionEngine
import dev.azula.terminal.TerminalEmulator
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

/**
 * All terminal-emulator mutation (feed / resize / clear / prediction) is confined to
 * this single serial dispatcher, so the background receive loop, the UI-thread resize,
 * and the keystroke path never touch the grid concurrently. The UI reads only the
 * emulator's immutable published [dev.azula.terminal.TermFrame], so rendering is safe.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal val terminalDispatcher = Dispatchers.Default.limitedParallelism(1)

/** Per-conversation mutable state: the thread, the draft, and any live stream. */
class ConversationState(seed: List<Message>) {
    val messages = mutableStateListOf<Message>().also { it.addAll(seed) }
    var draft by mutableStateOf("")
    var online by mutableStateOf(false)
    var stream: P2pStream? = null
    var streamingMsgId: String? = null
    /** Each terminal session gets its own emulator (grid + parser) with a
     *  speculative local-echo predictor attached. */
    val termScreen = TerminalEmulator().also { it.predictor = PredictionEngine(it) }
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
    private val scope: CoroutineScope,
    private val peerStore: PeerStore? = null,
    private val personaService: PersonaService = PersonaService(null),
    private val convStore: ConversationStore = ConversationStore(),
    private val persistence: PersistenceCoordinator = PersistenceCoordinator(convStore, null, scope),
    private val surfaceStore: SurfaceStore = SurfaceStore(),
    private val a2uiService: A2uiService = A2uiService(convStore, surfaceStore, persistence, scope),
    private val chatService: ChatService = ChatService(convStore, persistence, scope),
    // The connect + transport core (owns the swappable transport, dial/receive/
    // dispatch). AzulaState wires its navigation + foreground callbacks in init.
    private val connectService: ConnectService,
) : TerminalSession {
    // Conversation state lives in ConversationStore; expose the list for the UI.
    // Conversations are created per-peer when you connect.
    val conversations get() = convStore.conversations
    private val convState get() = convStore.convState

    // Desktop: a conversation id, or "connect" (the default landing — and the only
    // valid view when the list is empty).
    var desktopActive by mutableStateOf("connect")
        private set

    // Mobile navigation.
    var mScreen by mutableStateOf(MScreen.HOME)
        private set
    var mActive by mutableStateOf("")
        private set

    val thinking get() = chatService.thinking
    /** Whether the app is in the foreground. The platform updates this on
     *  lifecycle changes; incoming messages post a [Notifier] only when false. */
    var inForeground by mutableStateOf(true)
        private set
    fun setForeground(value: Boolean) { inForeground = value }
    var copied by mutableStateOf(false)
        private set
    // Connect UI state is owned by ConnectService; delegate for the connect screen.
    val myCode get() = connectService.myCode
    val nodeId get() = connectService.nodeId
    val connectStage get() = connectService.connectStage
    val connectStep get() = connectService.connectStep
    var connectInput: String
        get() = connectService.connectInput
        set(v) { connectService.connectInput = v }
    val rttMs get() = connectService.rttMs
    /** Resolve the peer being connected to from the current connect input (for the
     *  setup screen): their node id and mnemonic code. */
    fun peerIdForInput(input: String): String? = connectService.peerIdForInput(input)
    fun peerCodeForInput(input: String): String? = connectService.peerCodeForInput(input)

    // ---- identity backup / restore (recovery phrase) ----
    /** This device's secret key as a 24-word recovery phrase, or null if unavailable. */
    fun exportRecoveryPhrase(): String? = connectService.exportRecoveryPhrase()
    /** Restore identity from a recovery phrase; false if the phrase is invalid. */
    suspend fun importRecoveryPhrase(phrase: String): Boolean = connectService.importRecoveryPhrase(phrase)

    /** All live A2UI surfaces, keyed by surfaceId (owned by SurfaceStore). */
    val surfaces get() = surfaceStore.surfaces

    init {
        // Give the connect core its app-shell hooks (navigation + foreground).
        connectService.onNavigate = { id -> desktopActive = id; mActive = id; mScreen = MScreen.CHAT }
        connectService.foreground = { inForeground }
    }
    private fun nextId(p: String): String = convStore.nextId(p)

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
    private fun persist(convId: String) = persistence.save(convId)

    /** A human-friendly label derived from any node id (our own or a peer's). */
    fun mnemonicCode(id: String): String = convStore.mnemonicCode(id)

    /** Our own code for display. Copy uses [myCode]. */
    val shortCode: String get() = connectService.shortCode

    // ---- lifecycle ----

    suspend fun start() {
        // Restore persisted conversation history (shipping build only; the demo
        // build shows seeds instead). Restored A2UI surfaces render frozen.
        val max = persistence.restoreAll(); if (max >= 0) convStore.setIdFloor(max + 1)
        personaService.load()
        // Bind the transport and run the connect/receive core.
        connectService.start()
    }

    // ---- navigation ----

    fun selectDesktop(id: String) { desktopActive = id }
    fun openConnectDesktop() { desktopActive = "connect"; connectService.resetConnect() }
    fun openSettingsDesktop() { desktopActive = "settings" }
    fun openChatMobile(id: String) { mActive = id; mScreen = MScreen.CHAT }
    fun openConnectMobile() { mScreen = MScreen.CONNECT; connectService.resetConnect() }
    fun openSettingsMobile() { mScreen = MScreen.SETTINGS }
    fun mobileBack() { mScreen = MScreen.HOME }

    // ---- input / sending ----

    fun updateDraft(convId: String, text: String) { conv(convId).draft = text }

    fun send(convId: String) {
        val cs = conv(convId)
        val txt = cs.draft.trim()
        if (txt.isEmpty()) return
        // Terminal conversations send keystrokes, not chat messages.
        if (conversation(convId).kind == ConversationKind.TERMINAL) { cs.draft = ""; terminalInput(convId, txt); return }
        chatService.send(convId, txt)
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
    ) = chatService.sendFile(convId, bytes, name, mime, encoding, caption)

    fun markCopied() {
        copied = true
        scope.launch { delay(1400); copied = false }
    }

    // ---- terminal ----

    /** Line-mode terminal input (send a whole command + newline). The live UI uses
     *  the raw path instead; kept for any line-based sender. */
    fun terminalInput(convId: String, cmdRaw: String) {
        val stream = conv(convId).stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(cmdRaw.trim() + "\n"))) } }
    }

    /**
     * Re-open the remote shell for a terminal whose session ended (e.g. the user
     * typed `exit`). Dials the saved ticket again and rewires this same
     * conversation with a fresh stream. No-op if we have no ticket to redial.
     */
    fun reconnectTerminal(convId: String) = connectService.reconnectTerminal(convId)

    // ---- TerminalSession: the narrow surface the terminal UI depends on ----
    override fun terminalScreen(convId: String): TerminalEmulator = conv(convId).termScreen
    override fun terminalOnline(convId: String): Boolean = convOrNull(convId)?.online == true

    /** Send Ctrl-C to the remote shell when a live term stream is open. */
    override fun terminalInterrupt(convId: String) {
        terminalRaw(convId, dev.azula.terminal.TermSeq.CTRL_C)
    }

    /** Send raw keystroke bytes to the live PTY, verbatim (no trim / no newline).
     *  This is the "type directly in the terminal" path — the shell echoes back.
     *  The predictor optimistically echoes the keystroke locally first (on the
     *  terminal dispatcher, serialized with feed) so it shows before the round-trip. */
    override fun terminalRaw(convId: String, bytes: String) {
        if (bytes.isEmpty()) return
        val cs = convOrNull(convId) ?: return
        scope.launch(terminalDispatcher) { cs.termScreen.predictor?.onInput(bytes) }
        val stream = cs.stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(bytes))) } }
    }

    /** Paste text into the PTY, wrapping in bracketed-paste markers when the remote
     *  app enabled it (`ESC[200~ … ESC[201~`). Never predicted. */
    override fun terminalPaste(convId: String, text: String) {
        if (text.isEmpty()) return
        val cs = convOrNull(convId) ?: return
        val wrapped = if (cs.termScreen.bracketedPaste) "\u001b[200~$text\u001b[201~" else text
        val stream = cs.stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(wrapped))) } }
    }

    /** Report the terminal's viewport size (character cells) so the PTY wraps to
     *  match; deduped so we only send on real changes. Also reflows the local grid
     *  (on the terminal dispatcher) so it matches before the server's next output. */
    private val termSizes = mutableMapOf<String, Pair<Int, Int>>()
    override fun terminalResize(convId: String, cols: Int, rows: Int) {
        if (cols < 1 || rows < 1) return
        if (termSizes[convId] == (cols to rows)) return
        termSizes[convId] = cols to rows
        val cs = convOrNull(convId) ?: return
        scope.launch(terminalDispatcher) { cs.termScreen.resize(cols, rows) }
        val stream = cs.stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Resize(cols, rows))) } }
    }

    // ---- A2UI actions ----

    /**
     * Called when the user triggers an action on an A2UI surface.
     * If a live stream is open, encodes and sends it; otherwise interprets locally.
     */
    fun onA2uiAction(convId: String, action: A2uiAction) = a2uiService.onAction(convId, action)

    /** Called when a two-way input (TextField, CheckBox, etc.) changes its value. */
    fun onA2uiDataChange(convId: String, surface: A2uiSurface, path: String, value: JsonElement) =
        a2uiService.onDataChange(convId, surface, path, value)

    // ---- connecting (delegated to ConnectService) ----

    fun connectPeer(ticketRaw: String, share: Frame.Profile? = null) = connectService.connectPeer(ticketRaw, share)
    fun newSessionFrom(convId: String) = connectService.newSessionFrom(convId)
    fun reconnectSaved() = connectService.reconnectSaved()

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
            store.save(store.load().filter { (connectService.peerIdForTicket(it) ?: "") != id })
        }
        persistence.delete(id)
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

    /** Inbound connections awaiting a "share my profile?" decision (owned by ConnectService). */
    val pendingShares get() = connectService.pendingShares

    fun newPersonaId(): String = personaService.newId()
    fun upsertPersona(persona: Persona) = personaService.upsert(persona)
    fun deletePersona(id: String) = personaService.delete(id)
    fun setDefaultPersona(id: String) = personaService.setDefault(id)
    fun personaById(id: String?): Persona? = personaService.byId(id)

    /** Respond to a pending share prompt: send [frame] back (or nothing to decline). */
    fun respondShare(convId: String, frame: Frame.Profile?) = connectService.respondShare(convId, frame)

}
