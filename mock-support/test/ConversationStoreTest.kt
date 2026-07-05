import dev.azula.model.ConversationKind
import dev.azula.state.ConversationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ConversationStore] is a plain class (no injected collaborators), so these are
 * direct unit tests -- no transport, no graph, no coroutines needed.
 */
class ConversationStoreTest {

    @Test
    fun ensureConvCreatesOncePerPeer() {
        val store = ConversationStore()
        val id1 = store.ensureConv("peer1", ConversationKind.PEER)
        val id2 = store.ensureConv("peer1", ConversationKind.PEER)
        assertEquals(id1, id2)
        assertEquals(1, store.conversations.size)
    }

    @Test
    fun ensureConvUpgradesKindButNeverDowngrades() {
        val store = ConversationStore()
        val id = store.ensureConv("peer1", ConversationKind.TERMINAL)
        assertEquals(ConversationKind.TERMINAL, store.conversation(id).kind)

        store.ensureConv("peer1", ConversationKind.PEER) // PEER outranks TERMINAL
        assertEquals(ConversationKind.PEER, store.conversation(id).kind)

        store.ensureConv("peer1", ConversationKind.TERMINAL) // must not downgrade
        assertEquals(ConversationKind.PEER, store.conversation(id).kind)
    }

    @Test
    fun ensureConvAppliesAnnouncedNameAndSpecialGlyph() {
        val store = ConversationStore()
        val id = store.ensureConv("peer1", ConversationKind.PEER, announcedName = "Blackjack")
        assertEquals("Blackjack", store.conversation(id).name)
        assertEquals("♠", store.conversation(id).glyph)
    }

    @Test
    fun ensureConvRenamesOnANewAnnouncedName() {
        val store = ConversationStore()
        val id = store.ensureConv("peer1", ConversationKind.PEER)
        val firstName = store.conversation(id).name
        store.ensureConv("peer1", ConversationKind.PEER, announcedName = "Rex")
        assertEquals("Rex", store.conversation(id).name)
        assertEquals(firstName, store.conversation(id).sub, "the original code stays the sub-label")
    }

    @Test
    fun renameConversationSetsLocalNameThatOverridesDisplayName() {
        val store = ConversationStore()
        val id = store.ensureConv("peer1", ConversationKind.PEER, announcedName = "Rex")
        assertEquals("Rex", store.conversation(id).displayName)

        store.renameConversation(id, "  Buddy  ")
        assertEquals("Buddy", store.conversation(id).localName, "the rename is trimmed")
        assertEquals("Buddy", store.conversation(id).displayName, "the local name wins for display")
        assertEquals("Rex", store.conversation(id).name, "the shared name is preserved underneath")
    }

    @Test
    fun renameConversationBlankOrNullClearsBackToDefault() {
        val store = ConversationStore()
        val id = store.ensureConv("peer1", ConversationKind.PEER, announcedName = "Rex")
        store.renameConversation(id, "Buddy")

        store.renameConversation(id, "   ")
        assertNull(store.conversation(id).localName, "a blank rename clears the local name")
        assertEquals("Rex", store.conversation(id).displayName)

        store.renameConversation(id, "Buddy")
        store.renameConversation(id, null)
        assertNull(store.conversation(id).localName, "a null rename clears the local name")
        assertEquals("Rex", store.conversation(id).displayName)
    }

    @Test
    fun nextIdIsMonotonicAndSetIdFloorOnlyAdvances() {
        val store = ConversationStore()
        assertEquals("p0", store.nextId("p"))
        assertEquals("p1", store.nextId("p"))
        store.setIdFloor(0) // lower than the current counter (2) -- must be a no-op
        assertEquals("p2", store.nextId("p"))
        store.setIdFloor(10)
        assertEquals("p10", store.nextId("p"))
    }

    @Test
    fun lookupHelpersReturnNullForUnknownIds() {
        val store = ConversationStore()
        assertNull(store.convOrNull("nope"))
        assertNull(store.conversationOrNull("nope"))
    }

    @Test
    fun mnemonicCodeIsStableForTheSameId() {
        val store = ConversationStore()
        assertEquals(store.mnemonicCode("abc123"), store.mnemonicCode("abc123"))
    }
}
