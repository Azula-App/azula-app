import dev.azula.mock.FakeTransport
import dev.azula.model.ConversationKind
import dev.azula.persist.ConversationDto
import dev.azula.state.MScreen
import dev.azula.state.openConversationFromNotification
import dev.azula.state.openInvitesFromNotification
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Notification-tap routing, driven through the entry points the iOS app delegate
 * (`ios-app/src/iosApp.swift`) and Android's `MainActivity` both call:
 * [openConversationFromNotification] for a message notification and
 * [openInvitesFromNotification] for "Connection requests". Both must navigate the mobile and
 * the desktop shell, since a tablet or a phone held sideways can be showing either layout.
 *
 * Not covered: the buffering of a tap that arrives before [dev.azula.state.AzulaState.start]
 * registers the handler. The bus is a process-wide object that keeps the last started state's
 * handler, so whether it is empty depends on which tests ran first.
 */
class ConversationTapTest {
    private val seed = mapOf(
        "peerA" to ConversationDto(name = "peerA", kind = ConversationKind.PEER, glyph = "P"),
    )

    @Test
    fun messageTapOpensItsConversationInBothShells() = runTest {
        val state = testAzulaState(backgroundScope, FakeTransport(), messageStore = InMemoryMessageStore(seed))
        state.start()
        assertEquals(MScreen.HOME, state.mScreen)

        openConversationFromNotification("peerA")

        assertEquals(MScreen.CHAT, state.mScreen)
        assertEquals("peerA", state.mActive)
        assertEquals("peerA", state.desktopActive)
    }

    @Test
    fun tapForAConversationThatNoLongerExistsLeavesNavigationAlone() = runTest {
        val state = testAzulaState(backgroundScope, FakeTransport(), messageStore = InMemoryMessageStore(seed))
        state.start()
        val desktopBefore = state.desktopActive

        openConversationFromNotification("deleted-since")

        assertEquals(MScreen.HOME, state.mScreen)
        assertEquals("", state.mActive)
        assertEquals(desktopBefore, state.desktopActive)
    }

    @Test
    fun connectionRequestTapOpensTheInboxInBothShells() = runTest {
        val state = testAzulaState(backgroundScope, FakeTransport(), messageStore = InMemoryMessageStore(seed))
        state.start()

        openInvitesFromNotification()

        assertEquals(MScreen.INVITES, state.mScreen)
        assertEquals("invites", state.desktopActive)
    }
}
