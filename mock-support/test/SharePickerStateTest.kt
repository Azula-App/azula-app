import dev.azula.mock.FakeTransport
import dev.azula.ui.PickedFile
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers Phase 7's share-into-app plumbing on [dev.azula.state.AzulaState]:
 * [dev.azula.state.AzulaState.stageShare]/[dev.azula.state.AzulaState.stageShareText]
 * buffer an inbound OS share (Android `SEND`/`SEND_MULTIPLE` intent, iOS Share
 * Extension) until [dev.azula.state.AzulaState.shareTo] delivers it into a chosen
 * conversation's [dev.azula.state.ConversationState.pendingMedia] (files) or
 * `draft` (text), clearing [dev.azula.state.AzulaState.pendingShare] either way.
 * [dev.azula.ui.SharePickerOverlay] is the UI that drives [shareTo]; not exercised
 * here (Compose UI is covered by DesktopAppE2eTest-style tests elsewhere).
 */
class SharePickerStateTest {

    @Test
    fun stageShareThenShareToLandsItemsInTheTargetConversationsPendingMedia() = runTest {
        val azulaState = testAzulaState(backgroundScope, FakeTransport())
        azulaState.start()
        runCurrent()
        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        val picked = listOf(PickedFile(name = "photo.jpg", mime = "image/jpeg", bytes = byteArrayOf(1, 2, 3)))
        azulaState.stageShare(picked)
        assertEquals(picked, azulaState.pendingShare, "staged items are visible for the picker to preview")

        azulaState.shareTo(convId)

        assertNull(azulaState.pendingShare, "delivering the share clears the pending buffer")
        assertEquals(picked, azulaState.conv(convId).pendingMedia.toList())
    }

    @Test
    fun stageShareIgnoresAnEmptyList() = runTest {
        val azulaState = testAzulaState(backgroundScope, FakeTransport())
        azulaState.start()
        runCurrent()

        azulaState.stageShare(emptyList())
        assertNull(azulaState.pendingShare, "an empty share shouldn't open the picker")
    }

    @Test
    fun stageShareTextThenShareToPrefillsTheTargetConversationsDraftInsteadOfPendingMedia() = runTest {
        val azulaState = testAzulaState(backgroundScope, FakeTransport())
        azulaState.start()
        runCurrent()
        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        azulaState.stageShareText("check this out")
        assertTrue(azulaState.pendingShare != null, "a text-only share still opens the picker")

        azulaState.shareTo(convId)

        assertNull(azulaState.pendingShare)
        assertEquals("check this out", azulaState.conv(convId).draft)
        assertTrue(azulaState.conv(convId).pendingMedia.isEmpty(), "a text share must not stage any media")
    }

    @Test
    fun cancelShareDiscardsTheStagedShareWithoutTouchingAnyConversation() = runTest {
        val azulaState = testAzulaState(backgroundScope, FakeTransport())
        azulaState.start()
        runCurrent()
        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        azulaState.stageShare(listOf(PickedFile(name = "f.txt", mime = "text/plain", bytes = byteArrayOf(9))))
        azulaState.cancelShare()

        assertNull(azulaState.pendingShare)
        assertTrue(azulaState.conv(convId).pendingMedia.isEmpty())
    }
}
