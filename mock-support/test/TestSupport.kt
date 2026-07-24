import dev.azula.core.Ed25519
import dev.azula.di.AppGraph
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.net.PeerStore
import dev.azula.persist.BlobStore
import dev.azula.persist.ConversationDto
import dev.azula.persist.InvitationsStore
import dev.azula.persist.MediaExporter
import dev.azula.persist.MessageStore
import dev.azula.persist.AppSettings
import dev.azula.persist.ProfileStore
import dev.azula.persist.SettingsStore
import dev.azula.state.AzulaState
import dev.azula.sync.EventLogStore
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

/**
 * Builds a real [AzulaState] through the same composition root production uses
 * ([AppGraph.Factory], exactly like [dev.azula.mock.buildMockState]) but with a
 * test-supplied transport/store — so state-layer tests drive [ConnectService] /
 * [ChatService] / [PersistenceCoordinator] through AzulaState's public surface
 * instead of poking their (private) internals.
 */
internal fun testAzulaState(
    scope: CoroutineScope,
    transport: IrohTransport,
    messageStore: MessageStore? = null,
    blobStore: BlobStore? = null,
    peerStore: PeerStore? = null,
    invitationsStore: InvitationsStore? = null,
    mediaExporter: MediaExporter? = null,
    settingsStore: SettingsStore? = null,
    profileStore: ProfileStore? = null,
    // multi-device-identity tasks 4.4/4.5: null by default (no behavior change for
    // any existing test), but a test that wants to exercise the real event-log
    // append/fold/rebuild path end-to-end can supply both -- see
    // AccountSyncWiringTest.kt for why that needs its own identity-consistent
    // IrohTransport rather than the shared FakeTransport (whose nodeId isn't real
    // key material paired with any Ed25519).
    eventLogStore: EventLogStore? = null,
    ed25519: Ed25519? = null,
): AzulaState =
    createGraphFactory<AppGraph.Factory>().create(
        scope = scope,
        transport = transport,
        notifier = null,
        messageStore = messageStore,
        profileStore = profileStore,
        settingsStore = settingsStore,
        peerStore = peerStore,
        blobStore = blobStore,
        invitationsStore = invitationsStore,
        mediaExporter = mediaExporter,
        eventLogStore = eventLogStore,
        ed25519 = ed25519,
    ).azulaState

/** An in-memory [PeerStore], so a test can inspect exactly what ConnectService persisted. */
internal class InMemoryPeerStore : PeerStore {
    private var tickets: List<String> = emptyList()
    override fun load(): List<String> = tickets
    override fun save(tickets: List<String>) { this.tickets = tickets }
}

/**
 * An in-memory [MessageStore] fake. [PersistenceCoordinator.save]/[PersistenceCoordinator.delete]
 * run on a real (non-test) background dispatcher, so [saves]/[deletes] are channels
 * a test can suspend-`receive()` on to observe a write deterministically instead of
 * racing the test's virtual clock with the real one.
 */
internal class InMemoryMessageStore(seed: Map<String, ConversationDto> = emptyMap()) : MessageStore {
    private val data = LinkedHashMap<String, ConversationDto>(seed)
    val saves = Channel<Pair<String, ConversationDto>>(Channel.UNLIMITED)
    val deletes = Channel<String>(Channel.UNLIMITED)

    override fun load(conversationId: String): ConversationDto? = data[conversationId]

    override fun save(conversationId: String, conversation: ConversationDto) {
        data[conversationId] = conversation
        saves.trySend(conversationId to conversation)
    }

    override fun list(): List<String> = data.keys.toList()

    override fun delete(conversationId: String) {
        data.remove(conversationId)
        deletes.trySend(conversationId)
    }
}

/** An in-memory [SettingsStore] fake, so a test can seed/inspect exactly what was
 *  persisted without a real file/NSUserDefaults/SharedPreferences backing. `null`
 *  initial state mirrors "nothing saved yet" (a fresh install). */
internal class InMemorySettingsStore(initial: AppSettings? = null) : SettingsStore {
    private var saved: AppSettings? = initial
    override fun load(): AppSettings? = saved
    override fun save(settings: AppSettings) { saved = settings }
}

/** An in-memory [ProfileStore] fake, so a test can seed a [dev.azula.persist.ProfileBook]
 *  (e.g. to exercise `SettingsService`'s legacy-terminalSmartInput migration) without
 *  a real file/NSUserDefaults/SharedPreferences backing. */
internal class InMemoryProfileStore(initial: dev.azula.persist.ProfileBook = dev.azula.persist.ProfileBook()) : ProfileStore {
    private var book = initial
    override fun load(): dev.azula.persist.ProfileBook = book
    override fun save(book: dev.azula.persist.ProfileBook) { this.book = book }
}

/**
 * A one-shot inbound stream a test drives as "the peer": frames sent via
 * [sendFrame] arrive at a real receive loop (e.g. [dev.azula.state.ConnectService.receiveLoop])
 * exactly like a real peer's frames would. The client -> peer direction isn't
 * needed by the tests that use this (see [clientStream]'s no-op `sendLine`).
 * Extracted from ConnectServiceFrameDispatchTest.kt so other tests can reuse it.
 */
internal class PeerPipe {
    private val toClient = Channel<String>(Channel.UNLIMITED)

    val clientStream: P2pStream = object : P2pStream {
        override suspend fun sendLine(line: String) = Unit
        override suspend fun receiveLine(): String = toClient.receive()
        override suspend fun sendBytes(bytes: ByteArray) = Unit
        override suspend fun receiveBytes(n: Int): ByteArray? = null
        override suspend fun close() = Unit
    }

    suspend fun sendFrame(frame: Frame) = toClient.send(FrameCodec.encode(frame))

    /** Simulates the peer hanging up: closes the inbound channel, so
     *  [clientStream]'s `receiveLine()` throws a closed-channel exception rather
     *  than returning null. [dev.azula.state.ConnectService.receiveLoop] treats
     *  any thrown exception the same as a clean EOF (see its `catch (_: Throwable)`),
     *  so this exercises the same "stream dropped" path as a real abrupt close. */
    fun drop() = toClient.close()
}
