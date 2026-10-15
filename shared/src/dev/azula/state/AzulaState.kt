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
import dev.azula.model.MediaKind
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.model.TransferState
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
import dev.azula.epochSeconds
import dev.azula.link.DeepLinkBus
import dev.azula.notify.Notifier
import dev.azula.persist.BlobStore
import dev.azula.persist.Persona
import dev.azula.terminal.PredictionEngine
import dev.azula.terminal.TerminalEmulator
import dev.azula.terminal.TerminalSession
import dev.azula.ui.PickedFile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

enum class MScreen { HOME, CONNECT, CHAT, SETTINGS, INVITES }

/**
 * The pages backing [AzulaState.fullscreenMedia]: a gallery of [items] to swipe
 * through (all completed images in the conversation, for an image tap; a single
 * entry for video) and which page ([startIndex]) to open on.
 */
data class FullscreenMedia(val items: List<Attachment>, val startIndex: Int)

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

/**
 * One entry in [ConversationState.knownDevices] -- multi-device-identity task 7.4's
 * per-contact dial address book. [ticket] is the address to redial this specific
 * device, when known -- null if this device has only ever been seen as an INBOUND
 * connection (which leaves no ticket to redial, the same rule
 * [ConversationState.sourceTicket] already follows for a legacy peer); an entry with no
 * ticket is kept purely for [lastSeenMs]/[isMailbox] bookkeeping and is never a dial
 * candidate. [isMailbox] mirrors the presented certificate's own role bit.
 */
data class KnownContactDevice(val ticket: String?, val lastSeenMs: Long, val isMailbox: Boolean)

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
    /** The full encoded invite string [sourceTicket] was dialed from, if any --
     *  re-presented in Hello on every redial until the issuer accepts us (see
     *  ConnectService.resolveTicket / azula-docs/openspec/specs/invitations/design.md). */
    var sourceInvite: String? = null
    /** Last received peer profile (a "persona"): base64 avatar + mime + description. */
    var peerAvatarB64: String? = null
    var peerAvatarMime: String? = null
    var peerAvatarBitmap by mutableStateOf<ImageBitmap?>(null)
    var peerDescription by mutableStateOf<String?>(null)
    /** Live round-trip time to the peer (ms), polled off the stream while online;
     *  null when offline or not yet measured. */
    var rttMs by mutableStateOf<Int?>(null)
    /** True while a background redial loop is trying to bring this conversation
     *  back online (see ConnectService.scheduleReconnect). */
    var reconnecting by mutableStateOf(false)
    /** The persistent server-side terminal session id this conversation is bound
     *  to (see Frame.TermSession), or null if it's never attached one -- either
     *  because it's not a terminal, or the server is a pre-persistence CLI that
     *  never replies to Frame.TermAttach ("legacy mode"). Survives disconnect so
     *  [ConnectService.wireConv] can re-attach and replay scrollback on redial;
     *  cleared once the session ends (see [termExited]). */
    var termSessionId: String? by mutableStateOf(null)
    /** True once the server has reported (via Frame.TermExit) that the shell
     *  backing [termSessionId] exited -- there's nothing left to re-attach to,
     *  so scheduleReconnect must not treat this terminal as eligible; the UI
     *  offers an explicit "New shell" instead. */
    var termExited by mutableStateOf(false)
    /** cli-multi-session-relay: this conversation's session-cert expiry, in epoch ms
     *  (`Long.MAX_VALUE` for a cert with `expires_at=0`, "never") -- null for a
     *  conversation never admitted via the session-cert gate (see
     *  `ConnectService.admitSessionCert`). Doubles as the marker `ConnectService`'s
     *  accept gate (`startIncomingLoop`) uses to force cert re-validation on every
     *  reconnect instead of the ordinary "conversation already exists" known-peer
     *  fast path -- a session cert can expire between connections (session-identity
     *  spec's "Session Expiry Bounds Exposure": "reconnects re-present a cert and are
     *  re-validated"). Persisted (`ConversationDto.sessionExpiresAt`) so this survives
     *  an app restart too, not just within one process. */
    var sessionExpiresAt: Long? by mutableStateOf(null)
    /** Media picked (Photo/Video/Audio) but not yet sent, awaiting a caption --
     *  the composer's draft field doubles as the caption input while this is
     *  non-empty. Lives here (rather than as `ChatPane` `remember` state) so a
     *  pick survives recomposition/navigation, and so an out-of-composition
     *  flow (the OS share sheet) can pre-fill a composer. A list to support
     *  sharing multiple items at once; today's UI only ever holds one. */
    val pendingMedia = mutableStateListOf<PickedFile>()
    /** multi-device-identity task 7.4: this certified contact's other known devices,
     *  keyed by device public key hex -- populated by ConnectService's receive loop as
     *  each device's valid, bound, unrevoked certificate is seen. Used purely as a dial
     *  address book for [ConnectService.dialKnownDeviceForSend]'s send-time fallback;
     *  stays empty for a legacy (endpoint-id-keyed) conversation, since there's no second
     *  device to know about. Session-scoped (not persisted) -- rebuilt naturally as
     *  devices reconnect and re-present their `Hello.cert`, same posture as [rttMs]/
     *  [reconnecting] elsewhere on this class. */
    val knownDevices = mutableMapOf<String, KnownContactDevice>()
}

/**
 * The single source of truth for the whole app — a plain holder of Compose
 * snapshot state, observed identically on every platform. Owns the [transport]
 * and a long-lived [scope]. Conversations persist offline and go live the
 * moment a real iroh stream is wired in.
 */
@Inject
@SingleIn(AppScope::class)
class AzulaState(
    private val scope: CoroutineScope,
    private val peerStore: PeerStore? = null,
    private val blobStore: BlobStore? = null,
    private val personaService: PersonaService = PersonaService(null),
    private val settingsService: SettingsService = SettingsService(null),
    private val convStore: ConversationStore = ConversationStore(),
    private val persistence: PersistenceCoordinator = PersistenceCoordinator(convStore, null, scope, blobStore),
    private val surfaceStore: SurfaceStore = SurfaceStore(),
    private val a2uiService: A2uiService = A2uiService(convStore, surfaceStore, persistence, scope),
    // The media-attachment core (send/offer/fetch over the BlobStore + the
    // azula/media/0 ALPN, and legacy-file blob ingestion). Needs the transport
    // directly, which AzulaState doesn't hold itself, so -- like connectService
    // below -- it has no usable default and must come from the real DI graph.
    // Declared before chatService: its default constructs a ChatService, which
    // now needs mediaService too.
    private val mediaService: MediaService,
    // The identity/account-sync core (multi-device-identity tasks 4.4/4.5): appends
    // message_out/message_in/read_marker to the event log and projects/backfills
    // MessageStore's plain-text history from the fold. Its signing identity is wired
    // post-construction by ConnectService (see AccountSyncService.deviceIdentity's
    // kdoc), so -- unlike mediaService/inviteService/connectService -- every one of
    // its own constructor params is independently nullable/available, and it keeps a
    // usable default like chatService/frameDispatcher below.
    private val accountSyncService: AccountSyncService = AccountSyncService(null, null, convStore, persistence, scope),
    private val chatService: ChatService = ChatService(convStore, persistence, scope, mediaService, accountSyncService),
    // The inbound-frame reaction core (applyFrame/applyProfile + the share
    // handshake). ConnectService hands it frames as they arrive off the wire.
    private val frameDispatcher: FrameDispatcher = FrameDispatcher(scope, convStore, surfaceStore, persistence, chatService, mediaService, accountSyncService, null),
    // Invitations: minting/revoking, verifying an inbound stranger's Hello.invite,
    // and the pending accept/decline inbox. Needs the transport directly (for
    // ticket/sign/verify), so -- like mediaService -- it has no usable default.
    private val inviteService: InviteService,
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

    /**
     * Called by the platform on every foreground/background lifecycle transition (Android:
     * [ProcessLifecycleOwner]'s `onStart`/`onStop` in `AzulaApplication`; iOS: the `scenePhase`
     * observer in `iosApp.swift`, through [ForegroundBus]). On the false→true
     * edge specifically -- i.e. actually resuming, not every call -- also re-dials siblings
     * (multi-device-identity task 9.6): [ConnectService.reconnectSiblings] otherwise fires only
     * from a transport re-bind ([ConnectService]'s `onCameOnline`) or the one-shot call at the
     * end of `start()`, and a resident app never re-binds, so it would otherwise never re-sync
     * with its siblings while simply backgrounded and resumed -- which, for a phone, is nearly
     * always. Confirmed on-device: backgrounding and returning produced zero sibling dials for
     * ~9 minutes, while a full force-stop + cold relaunch (a genuine re-bind) converged
     * immediately. Guarded to the edge (not true→true or true→false) so repeated foreground
     * re-entry doesn't spam dials; [ConnectService.reconnectSiblings]'s own
     * `siblingDialsInFlight` guard additionally covers two resumes landing close together.
     *
     * The same edge redials offline CLI session conversations
     * ([ConnectService.reconnectSessions]): a session's own redial can only land while this
     * app is reachable, which a backgrounded app often isn't (iOS suspends it outright), so
     * on resume the app dials the session itself rather than waiting out the session's backoff.
     */
    fun setForeground(value: Boolean) {
        val resumed = value && !inForeground
        inForeground = value
        if (resumed) {
            reconnectSiblings()
            connectService.reconnectSessions()
        }
    }
    var copied by mutableStateOf(false)
        private set
    // Connect UI state is owned by ConnectService; delegate for the connect screen.
    val myCode get() = connectService.myCode
    val endpointId get() = connectService.endpointId
    val connectStage get() = connectService.connectStage
    val connectStep get() = connectService.connectStep
    var connectInput: String
        get() = connectService.connectInput
        set(v) { connectService.connectInput = v }
    /** Resolve the peer being connected to from the current connect input (for the
     *  setup screen): their endpoint id and mnemonic code. */
    fun peerIdForInput(input: String): String? = connectService.peerIdForInput(input)
    fun peerCodeForInput(input: String): String? = connectService.peerCodeForInput(input)

    // ---- identity backup / restore (recovery phrase) ----
    /** This device's secret key as a 24-word recovery phrase, or null if unavailable. */
    fun exportRecoveryPhrase(): String? = connectService.exportRecoveryPhrase()
    /** Restore identity from a recovery phrase; false if the phrase is invalid. */
    suspend fun importRecoveryPhrase(phrase: String): Boolean = connectService.importRecoveryPhrase(phrase)

    /** Whether [phrase] would overwrite a *different* identity already held on this
     *  device -- see [ConnectService.isDifferentIdentityRestore]. Read by the restore UI
     *  before committing, to gate a second confirmation (multi-device-identity task 3.5a). */
    fun isDifferentIdentityRestore(phrase: String): Boolean? = connectService.isDifferentIdentityRestore(phrase)

    /**
     * Rebuilds every conversation's plain-text message history from the event log alone
     * (multi-device-identity's "Projection rebuild" scenario: deleting the derived stores
     * and replaying the logs reproduces the same derived state). A no-op with no event log
     * configured (the `-mock` apps, and every device before its first log entry). Exposed
     * for recovery/diagnostic use -- see [AccountSyncService.rebuildProjection].
     */
    suspend fun rebuildMessageHistoryFromLog() = accountSyncService.rebuildProjection()

    // ---- device linking (multi-device-identity tasks 6.1/6.2) ----
    // Thin delegation onto ConnectService, exactly like the connect/recovery-phrase
    // surface above -- all protocol logic lives in DeviceLinkProtocol/ConnectService;
    // AzulaState is just the seam the UI (SetupSteps/Settings) reads and calls through.

    /** This device's own granted device certificate, if it was QR-linked (task 6.1) --
     *  null on every root-holding device. */
    val linkedDeviceCert: String? get() = settingsService.linkedDeviceCert

    /** Whether this device holds the identity's root secret and can therefore grant a
     *  QR-link to a new device (task 6.2) -- the "Link a new device" entry in Settings
     *  must only be offered when this is true. */
    fun canGrantDeviceLinks(): Boolean = connectService.canGrantDeviceLinks()

    // -- new-device side (task 6.1): "Link to existing identity" --

    val deviceLinkStage: ConnectService.DeviceLinkStage get() = connectService.deviceLinkStage
    val deviceLinkPayload: String? get() = connectService.deviceLinkPayload
    val deviceLinkWords: List<String> get() = connectService.deviceLinkWords
    val deviceLinkRejectReason: String? get() = connectService.deviceLinkRejectReason

    /** Begin (or no-op if already in progress): generate this device's `azl…` link
     *  payload and wait for a root-holding device to scan it. See
     *  [ConnectService.beginDeviceLink]. */
    fun beginDeviceLink(name: String) = connectService.beginDeviceLink(name)
    fun cancelDeviceLink() = connectService.cancelDeviceLink()
    fun resetDeviceLink() = connectService.resetDeviceLink()

    // -- root-holding side (task 6.2): "Link a new device" (scan/paste) --

    val grantStage: ConnectService.GrantStage get() = connectService.grantStage
    val grantPeerName: String get() = connectService.grantPeerName
    val grantPeerRoles: Int get() = connectService.grantPeerRoles
    val grantWords: List<String> get() = connectService.grantWords
    val grantFailReason: String? get() = connectService.grantFailReason

    /** Decode a scanned/pasted `azl…` [payload] and dial it. See
     *  [ConnectService.beginGrantDial]. */
    fun beginGrantDial(payload: String) = connectService.beginGrantDial(payload)

    /** Explicit confirmation -- the only thing that can produce a certificate; see
     *  [ConnectService.confirmGrantLink]. */
    fun confirmGrantLink() = connectService.confirmGrantLink()
    fun cancelGrantLink() = connectService.cancelGrantLink()
    fun resetGrantLink() = connectService.resetGrantLink()

    // ---- first-run setup gate (onboarding) ----
    /**
     * The setup-gate signal read by [dev.azula.SetupGate]: null while undecided
     * (before [start] has evaluated it), true to show the setup flow (a fresh
     * install), false to go straight to the main app. Decided exactly once, before
     * the transport binds -- see [start] and the onboarding capability (D1).
     */
    var setupRequired by mutableStateOf<Boolean?>(null)
        private set

    /** Whether the user deferred recovery-phrase backup during setup ("back up
     *  later") -- persisted, so the app can surface a reminder later. */
    val backupDeferred get() = settingsService.backupDeferred

    /** Record that the user chose "back up later": persist the deferral flag
     *  immediately, independent of completing setup. */
    fun deferBackup() = settingsService.markBackupDeferred(true)

    /** Complete the setup flow (finish or skip of its final step): persist the
     *  completion flag and drop the gate so the main app shows. */
    fun completeSetup() {
        settingsService.markSetupCompleted()
        setupRequired = false
    }

    /** All live A2UI surfaces, keyed by surfaceId (owned by SurfaceStore). */
    val surfaces get() = surfaceStore.surfaces

    init {
        // Give the connect core its app-shell hook (navigation); FrameDispatcher
        // and InviteService get the foreground hook since they're the only
        // places that read it.
        connectService.onNavigate = { id -> desktopActive = id; mActive = id; mScreen = MScreen.CHAT }
        frameDispatcher.foreground = { inForeground }
        inviteService.foreground = { inForeground }
        // multi-device-identity task 7.4: ChatService has no dependency on ConnectService
        // (avoids a circular graph edge -- see ChatService.dialKnownDeviceAndSend's kdoc),
        // so the dial-a-known-device-and-send fallback is wired here instead, exactly like
        // every other cross-service callback on this page.
        chatService.dialKnownDeviceAndSend = { convId, frame -> connectService.dialKnownDeviceForSend(convId, frame) }
        // Tap-routing for the "Connection requests" notification (iOS app delegate and
        // Android MainActivity). Both shells, since a tablet or a phone held sideways can
        // be showing either layout (see shareTo).
        InviteBus.setHandler { openInvitesMobile(); openInvitesDesktop() }
        // iOS share-into-app: drainShareInbox() (ShareInbox.kt, src@ios) delivers
        // here via ShareBus -- no-op on Android (MainActivity stages shares
        // directly via AppStateHost) but harmless to register everywhere.
        ShareBus.setHandler { stageShare(it) }
        // iOS foreground/background: the scenePhase observer in iosApp.swift delivers
        // here via ForegroundBus -- no-op on Android (AzulaApplication calls
        // setForeground directly via AppStateHost) but harmless to register everywhere.
        ForegroundBus.setHandler { setForeground(it) }
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

    /** A human-friendly label derived from any endpoint id (our own or a peer's). */
    fun mnemonicCode(id: String): String = convStore.mnemonicCode(id)

    /** Our own code for display. Copy uses [myCode]. */
    val shortCode: String get() = connectService.shortCode

    // ---- lifecycle ----

    private val startMutex = Mutex()
    private var started = false

    /**
     * Idempotent: Android calls this twice (the Application starts the state in
     * `obtainState()`, then the composition's `SetupGate` starts it again), and
     * every step below is once-per-process. Before this guard existed the second
     * call re-ran [ConnectService.start] and bound a SECOND endpoint on the same
     * secret key -- two endpoints sharing one endpoint id, the first orphaned along with
     * its accept loop. The mutex (not a bare flag) because the two callers arrive
     * on different dispatchers.
     */
    suspend fun start() = startMutex.withLock {
        if (started) return@withLock
        started = true
        // Restore persisted conversation history from the injected MessageStore
        // (shipping build: the real on-disk store; the `-mock` apps: mock-support's
        // read-only SeededMessageStore). Restored A2UI surfaces render frozen.
        val max = persistence.restoreAll(); if (max >= 0) convStore.setIdFloor(max + 1)
        personaService.load()
        settingsService.load(personaService.legacyTerminalSmartInputForMigration)
        // Decide the setup gate BEFORE binding: bind eagerly mints+persists an
        // identity key, which would destroy the fresh-vs-upgrade signal
        // (onboarding capability, D1).
        setupRequired = when {
            settingsService.setupCompleted -> false // already onboarded
            connectService.hasPersistedIdentity() -> { // pre-existing install upgrading
                settingsService.markSetupCompleted()
                false
            }
            else -> true // fresh install: no key, no flag
        }
        inviteService.load()
        // Bind the transport and run the connect/receive core. accountSyncService's
        // signing identity (device pk/secret) becomes resolvable only after this --
        // see ConnectService.start's deviceIdentity wiring.
        connectService.start()
        // multi-device-identity task 4.4: an existing (pre-upgrade) install's message
        // history lives only in MessageStore and the event log ships empty -- back it
        // into the log exactly once so it isn't a permanent second, log-invisible
        // channel. Awaited (not fire-and-forget) so it completes before start() returns
        // and the UI can possibly send a first new message; gated on the persisted flag
        // so a relaunch never re-runs (and re-duplicates) it.
        if (!settingsService.eventLogBackfilled && accountSyncService.backfillLegacyHistory()) {
            settingsService.markEventLogBackfilled()
        }
        // Tap-routing for a message notification (iOS app delegate and Android
        // MainActivity, via ConversationTapBus). Registered last rather than in init, so a
        // tap that cold-launched the app is only delivered once history is restored and the
        // transport bound -- see ConversationTapBus.
        ConversationTapBus.setHandler { openChatFromNotification(it) }
    }

    // ---- navigation ----

    fun selectDesktop(id: String) { desktopActive = id; connectService.scheduleReconnect(id); accountSyncService.markRead(id) }
    fun openConnectDesktop() { desktopActive = "connect"; connectService.resetConnect() }
    fun openSettingsDesktop() { desktopActive = "settings" }
    fun openChatMobile(id: String) { mActive = id; mScreen = MScreen.CHAT; connectService.scheduleReconnect(id); accountSyncService.markRead(id) }
    /** Opens [id] from a tapped message notification ([ConversationTapBus]). Sets both
     *  shells' navigation, since a tablet or a phone held sideways can be showing either
     *  layout (see [shareTo]). A notification can outlive its conversation, so an unknown
     *  [id] leaves navigation alone rather than opening an empty chat pane. */
    fun openChatFromNotification(id: String) {
        if (convStore.conversationOrNull(id) == null) return
        openChatMobile(id)
        desktopActive = id
    }
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

    // ---- media attachments (delegated to MediaService) ----

    /**
     * Send a media attachment (image/audio/video/file) via the BlobStore + offer
     * protocol; see [MediaService.sendMedia]. Fire-and-forget from the UI's
     * perspective: [MediaService.sendMedia] writes the blob synchronously on its
     * caller's thread, so this dispatches it onto [scope] (background) rather than
     * block the composition/UI thread. No result is surfaced back on failure
     * (oversized, or no BlobStore bound) -- the caller just sees no message appear.
     */
    fun sendMedia(convId: String, bytes: ByteArray, name: String, mime: String, caption: String? = null) {
        scope.launch(Dispatchers.Default) { mediaService.sendMedia(convId, bytes, name, mime, caption) }
    }

    /** Start (or resume) fetching an offered/failed attachment's body. */
    fun startFetch(convId: String, attachmentId: String) = mediaService.startFetch(convId, attachmentId)

    /** Cancel an in-flight fetch; bytes already on disk stay put for a later retry. */
    fun cancelFetch(attachmentId: String) = mediaService.cancelFetch(attachmentId)

    /** Read a completed blob's bytes for rendering (e.g. a full-size image); see
     *  [MediaService.blobBytes]. */
    fun blobBytes(id: String, maxBytes: Long = 32L * 1024 * 1024): ByteArray? = mediaService.blobBytes(id, maxBytes)

    /** A native filesystem path to a completed blob, for a video/audio player or
     *  "open externally"; see [MediaService.blobPath]. */
    fun blobPath(id: String): String? = mediaService.blobPath(id)

    /** The attachment(s) currently shown fullscreen (image viewer / video overlay), or
     *  null when no overlay is open. Set by [AttachmentView]-side taps; the root
     *  composable (MobileApp/DesktopApp) renders the overlay when non-null.
     *  [FullscreenMedia.items] is the page list to swipe through (all completed
     *  images in the conversation, for an image; a single item for video), and
     *  [FullscreenMedia.startIndex] is which page to open on. */
    var fullscreenMedia by mutableStateOf<FullscreenMedia?>(null)
        private set

    /**
     * Open the fullscreen viewer for [att] in conversation [convId]. Images open
     * onto a pager over every completed image in the conversation (so swiping
     * moves through the rest of the gallery); anything else (video) opens a
     * single-page viewer.
     */
    fun showFullscreenMedia(convId: String, att: Attachment) {
        fullscreenMedia = if (att.kind == MediaKind.IMAGE) {
            val imgs = conv(convId).messages.flatMap { it.attachments }
                .filter { it.kind == MediaKind.IMAGE && it.state == TransferState.Complete }
            val start = imgs.indexOfFirst { it.id == att.id }.coerceAtLeast(0)
            FullscreenMedia(imgs.ifEmpty { listOf(att) }, start)
        } else {
            FullscreenMedia(listOf(att), 0)
        }
    }
    fun dismissFullscreenMedia() { fullscreenMedia = null }

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

    /** Auto-dial this identity's other devices for sync (multi-device-identity task 5.4) --
     *  see [ConnectService.reconnectSiblings]. Fires on its own on every bind/re-bind and on
     *  the foreground false→true edge (task 9.6, see [setForeground]); exposed here for parity
     *  with [reconnectSaved] and so tests can trigger a fresh sibling sync round explicitly. */
    fun reconnectSiblings() = connectService.reconnectSiblings()

    // ---- device management (multi-device-identity task 6.3) ----

    /** The identity's current device set, local-rename-applied; see [ConnectService.devices].
     *  Call [refreshDevices] to (re)populate it. */
    val devices: List<ConnectService.DeviceRow> get() = connectService.devices

    /** (Re)loads [devices] from the current fold -- call when the device management screen
     *  opens, and after every rename/revoke. See [ConnectService.refreshDevices]. */
    suspend fun refreshDevices() = connectService.refreshDevices()

    /** Local-only rename, never touches the certificate; see [ConnectService.renameDeviceLocally]. */
    fun renameDeviceLocally(devicePkHex: String, name: String?) = connectService.renameDeviceLocally(devicePkHex, name)

    /** Whether this device holds the root secret and can therefore revoke another device --
     *  the "Revoke" control must only be offered when this is true; see
     *  [ConnectService.canRevokeDevices]. */
    fun canRevokeDevices(): Boolean = connectService.canRevokeDevices()

    /** Revoke a device by its hex public key; see [ConnectService.revokeDevice]. */
    suspend fun revokeDevice(devicePkHex: String): Boolean = connectService.revokeDevice(devicePkHex)

    // ---- conversation management (enable/disable, delete) ----

    /**
     * Set (or clear, with a null/blank [name]) a user-chosen local rename for a
     * conversation. The local name always wins over the announced/shared/mnemonic
     * title in the UI (see [Conversation.displayName]); clearing falls back to it.
     * Persists immediately so the rename survives restart.
     */
    fun renameConversation(convId: String, name: String?) {
        convStore.renameConversation(convId, name)
        persist(convId)
    }

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
            mediaService.cancelAllForConversation(id)
        } else {
            scope.launch { reconnectSaved() }
        }
        persist(id)
    }

    /**
     * Disable every conversation whose session cert has expired (cli-multi-session-relay's
     * "Session Expiry Bounds Exposure": "the app ... SHALL support bulk-archiving expired
     * session conversations"). This codebase's only "archived" state is disabled ([Conversation.enabled]
     * = false, see [setConversationEnabled]) -- there's no separate archive flag, so
     * "archiving" here is exactly that: closes any live stream, stops auto-reconnect and
     * notifications, and leaves the row + history in place, greyed. A conversation never
     * admitted via the session-cert gate ([ConversationState.sessionExpiresAt] null) is
     * never touched, and an already-disabled one is skipped (nothing new to do).
     */
    fun archiveExpiredSessions() {
        val now = epochSeconds() * 1000
        conversations.toList()
            .filter { it.enabled }
            .filter { id -> (convOrNull(id.id)?.sessionExpiresAt ?: return@filter false) <= now }
            .forEach { setConversationEnabled(it.id, false) }
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
        mediaService.cancelAllForConversation(id)
        // PeerStore has no per-item delete — save a filtered list. Match by the
        // entry's resolved endpoint id (covers inbound-only convs with no sourceTicket,
        // and invite-string entries alongside bare tickets).
        peerStore?.let { store ->
            store.save(store.load().filter { (connectService.peerIdForEntry(it) ?: "") != id })
        }
        persistence.delete(id)
        convState.remove(id)
        conversations.removeAll { it.id == id }
        if (desktopActive == id) desktopActive = conversations.firstOrNull()?.id ?: "connect"
        if (mActive == id) { mActive = ""; mScreen = MScreen.HOME }
    }

    // ---- profiles / personas ----

    // Persona management is delegated to PersonaService; these accessors + methods
    // keep AzulaState's public surface stable so the UI is unchanged.
    val personas get() = personaService.personas
    val defaultPersonaId get() = personaService.defaultPersonaId

    // ---- terminal settings ----

    /** Smart mobile terminal input (swipe typing/autocorrect/suggestions) vs.
     *  the legacy raw fallback -- see [SettingsScreen]'s "Smart input" toggle. */
    val terminalSmartInput get() = settingsService.terminalSmartInput
    fun setTerminalSmartInput(enabled: Boolean) = settingsService.setSmartInput(enabled)

    fun newPersonaId(): String = personaService.newId()
    fun upsertPersona(persona: Persona) = personaService.upsert(persona)
    fun deletePersona(id: String) = personaService.delete(id)
    fun setDefaultPersona(id: String) = personaService.setDefault(id)
    fun personaById(id: String?): Persona? = personaService.byId(id)

    // ---- invitations (delegated to InviteService) ----

    /** Invites this device has minted. */
    val issuedInvites get() = inviteService.issued

    /** Inbound strangers awaiting accept/decline. */
    val pendingInvites get() = inviteService.pending

    /** Mint a new invite for this device's own ticket; see [InviteService.createInvite]. */
    suspend fun createInvite(expiresInSeconds: Long? = null, sign: Boolean = false, singleUse: Boolean = false, label: String? = null): String? =
        inviteService.createInvite(expiresInSeconds, sign, singleUse, label)

    fun revokeInvite(id: String) = inviteService.revoke(id)
    /** [profile] optionally shares a persona back on accept -- see [InviteService.accept]. */
    fun acceptInvite(req: PendingConnectionRequest, profile: Frame.Profile? = null) = inviteService.accept(req, profile)
    fun declineInvite(req: PendingConnectionRequest) = inviteService.decline(req)
    fun acceptAllInvites() = inviteService.acceptAll()
    fun declineAllInvites() = inviteService.declineAll()

    fun openInvitesMobile() { mScreen = MScreen.INVITES }
    fun openInvitesDesktop() { desktopActive = "invites" }

    // ---- share-into-app (OS share sheet: Android intent-filter, iOS Share Extension) ----

    /**
     * Items shared in from the OS (a `SEND`/`SEND_MULTIPLE` intent on Android, the
     * App-Group inbox drained on iOS -- see [dev.azula.state.ShareInbox]/`ShareBus`)
     * awaiting a conversation to land in, or null when no share is in flight.
     * [SharePickerOverlay] renders the "Share to…" picker whenever this is non-null;
     * mounted at the root of both `MobileApp`/`DesktopApp`.
     */
    var pendingShare by mutableStateOf<List<PickedFile>?>(null)
        private set
    /** A plain-text share (`EXTRA_TEXT`) staged alongside/instead of [pendingShare];
     *  [shareTo] pre-fills the target conversation's draft with it rather than
     *  adding it to [ConversationState.pendingMedia]. */
    private var pendingShareText: String? = null

    /** Stage one or more picked files from an inbound OS share; no-op if empty.
     *  [SharePickerOverlay] then prompts for a destination conversation. */
    fun stageShare(items: List<PickedFile>) {
        if (items.isEmpty()) return
        pendingShare = items
        pendingShareText = null
    }

    /** Stage a plain-text share (`EXTRA_TEXT`); no-op if blank. [shareTo] sets the
     *  target conversation's draft to this text instead of staging media. */
    fun stageShareText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        pendingShareText = t
        pendingShare = emptyList()
    }

    /** Deliver the currently staged share (files and/or text) into conversation
     *  [convId]'s composer and navigate to it, clearing the pending share. No-op
     *  if nothing is staged. */
    fun shareTo(convId: String) {
        if (pendingShare == null) return
        val items = pendingShare.orEmpty()
        val text = pendingShareText
        if (items.isNotEmpty()) {
            val cs = conv(convId)
            cs.pendingMedia.clear()
            cs.pendingMedia.addAll(items)
        }
        if (text != null) conv(convId).draft = text
        pendingShare = null
        pendingShareText = null
        // Mobile and desktop navigation are independent fields -- set both
        // unconditionally so the picker works regardless of which shell is active.
        openChatMobile(convId)
        desktopActive = convId
    }

    /** Discard the currently staged share without delivering it anywhere. */
    fun cancelShare() {
        pendingShare = null
        pendingShareText = null
    }
}
