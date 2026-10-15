import dev.azula.notify.IosNotifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The message-notification identifier is a contract between [IosNotifier.postMessage] and the
 * tap handler in `ios-app/src/iosApp.swift`, which reads the conversation back out of it with
 * [IosNotifier.Companion.conversationIdFor].
 */
class IosNotifierIdentifierTest {
    @Test
    fun aMessageIdentifierRoundTripsToItsConversation() {
        val id = IosNotifier.messageIdentifier("peerA")
        assertEquals("peerA", IosNotifier.conversationIdFor(id))
    }

    @Test
    fun aConversationIdContainingThePrefixSurvivesTheRoundTrip() {
        val id = IosNotifier.messageIdentifier("message:odd")
        assertEquals("message:odd", IosNotifier.conversationIdFor(id))
    }

    @Test
    fun nonMessageIdentifiersNameNoConversation() {
        assertNull(IosNotifier.conversationIdFor(IosNotifier.INVITES_NOTIFICATION_IDENTIFIER))
        assertNull(IosNotifier.conversationIdFor(IosNotifier.MESSAGE_IDENTIFIER_PREFIX))
        assertNull(IosNotifier.conversationIdFor("something-else"))
    }
}
