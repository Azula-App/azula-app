import dev.azula.model.ConversationKind
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.persist.ConversationDto
import dev.azula.persist.MessageDto
import dev.azula.state.ConversationStore
import dev.azula.state.PersistenceCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PersistenceCoordinator] takes plain collaborators (a [ConversationStore] and a
 * [dev.azula.persist.MessageStore]?), so it's directly constructible -- no graph,
 * no fake network needed. Uses [InMemoryMessageStore] from TestSupport.kt.
 */
class PersistenceCoordinatorTest {

    @Test
    fun restoreAllSeedsConversationsAndComputesTheIdFloor() = runTest {
        val seed = mapOf(
            "peerA" to ConversationDto(
                name = "peerA", kind = ConversationKind.PEER, glyph = "P",
                messages = listOf(
                    MessageDto(id = "p0", role = Role.ME, text = "hi"),
                    MessageDto(id = "p3", role = Role.THEM, text = "yo"),
                ),
            ),
            "peerB" to ConversationDto(
                name = "peerB", kind = ConversationKind.LLM, glyph = "L",
                messages = listOf(MessageDto(id = "s7", role = Role.AZULA, text = "hey")),
            ),
        )
        val store = InMemoryMessageStore(seed)
        val convStore = ConversationStore()
        val coordinator = PersistenceCoordinator(convStore, store, backgroundScope)

        val maxSuffix = coordinator.restoreAll()

        assertEquals(7, maxSuffix, "should report the highest numeric id suffix across all restored messages")
        assertEquals(2, convStore.conversations.size)
        assertEquals("hi", convStore.conv("peerA").messages.first().text)
        assertEquals("hey", convStore.conv("peerB").messages.first().text)
    }

    @Test
    fun restoreAllIsANoOpWithNoStore() = runTest {
        val convStore = ConversationStore()
        val coordinator = PersistenceCoordinator(convStore, null, backgroundScope)

        assertEquals(-1, coordinator.restoreAll())
        assertTrue(convStore.conversations.isEmpty())
    }

    @Test
    fun saveWritesAndDeleteRemoves() = runTest {
        val store = InMemoryMessageStore()
        val convStore = ConversationStore()
        val coordinator = PersistenceCoordinator(convStore, store, backgroundScope)
        val id = convStore.ensureConv("peerC", ConversationKind.PEER)
        convStore.conv(id).messages.add(Message(id = "m0", role = Role.ME, text = "saved?"))

        coordinator.save(id)
        val (savedId, dto) = store.saves.receive()
        assertEquals(id, savedId)
        assertEquals("saved?", dto.messages.single().text)

        coordinator.delete(id)
        assertEquals(id, store.deletes.receive())
    }

    @Test
    fun localNameSurvivesSaveAndRestore() = runTest {
        val store = InMemoryMessageStore()
        val convStore = ConversationStore()
        val coordinator = PersistenceCoordinator(convStore, store, backgroundScope)
        val id = convStore.ensureConv("peerR", ConversationKind.PEER, announcedName = "Rex")
        convStore.renameConversation(id, "Buddy")

        coordinator.save(id)
        val (_, dto) = store.saves.receive()
        assertEquals("Buddy", dto.localName, "the local rename is persisted")
        assertEquals("Rex", dto.name, "the shared name is persisted underneath the rename")

        // Restore into a fresh store from the same backing data.
        val convStore2 = ConversationStore()
        PersistenceCoordinator(convStore2, store, backgroundScope).restoreAll()
        val restored = convStore2.conversation(id)
        assertEquals("Buddy", restored.localName, "the local rename comes back")
        assertEquals("Buddy", restored.displayName, "the local name still wins after restore")
        assertEquals("Rex", restored.name)
    }

    @Test
    fun sessionExpiresAtSurvivesSaveAndRestoreAndDefaultsToNullOnOldBlobs() = runTest {
        // cli-multi-session-relay 5.2: the session-cert expiry set by ConnectService's
        // accept gate (ConversationState.sessionExpiresAt) must round-trip through the
        // DTO exactly like every other per-conversation field, AND an old persisted
        // blob written before this field existed must still decode -- "not a session".
        val store = InMemoryMessageStore()
        val convStore = ConversationStore()
        val coordinator = PersistenceCoordinator(convStore, store, backgroundScope)
        val id = convStore.ensureConv("peerS", ConversationKind.LLM, announcedName = "cli")
        convStore.conv(id).sessionExpiresAt = 1_800_000_000_000L

        coordinator.save(id)
        val (_, dto) = store.saves.receive()
        assertEquals(1_800_000_000_000L, dto.sessionExpiresAt, "the session expiry is persisted")

        val convStore2 = ConversationStore()
        PersistenceCoordinator(convStore2, store, backgroundScope).restoreAll()
        assertEquals(1_800_000_000_000L, convStore2.conv(id).sessionExpiresAt, "the session expiry comes back")

        // An ordinary (never-session) conversation, and a pre-existing blob missing
        // the key entirely, both decode as "not a session" (null).
        val ordinaryId = convStore.ensureConv("peerT", ConversationKind.PEER)
        coordinator.save(ordinaryId)
        val (_, ordinaryDto) = store.saves.receive()
        assertEquals(null, ordinaryDto.sessionExpiresAt)
    }
}
