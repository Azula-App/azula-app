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
}
