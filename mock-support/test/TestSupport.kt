import dev.azula.di.AppGraph
import dev.azula.net.IrohTransport
import dev.azula.persist.ConversationDto
import dev.azula.persist.MessageStore
import dev.azula.state.AzulaState
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
): AzulaState =
    createGraphFactory<AppGraph.Factory>().create(
        scope = scope,
        transport = transport,
        notifier = null,
        messageStore = messageStore,
        profileStore = null,
        peerStore = null,
    ).azulaState

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
