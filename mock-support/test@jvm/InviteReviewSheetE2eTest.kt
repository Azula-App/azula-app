@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import dev.azula.model.ConversationKind
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.IncomingConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch

/**
 * Desktop (JVM) Compose UI tests for the inbound accept/decline review path
 * flagged with no coverage in azula-docs/docs/tech-debt.md §5:
 * [dev.azula.ui.InviteReviewSheet]. Driven end to end via
 * [dev.azula.mock.FakeTransport.injectIncomingConnection] -- a test-only hook
 * (see its doc in `mock-support/src/dev/azula/mock/FakeTransport.kt`) that
 * emits an arbitrary inbound [IncomingConnection] on the exact flow
 * `ConnectService`'s accept loop collects via `transport.incoming()`, so a
 * "stranger" dialing in on the CHAT alpn can be simulated without a second
 * real peer -- reaching `ConnectService.handleStrangerConnection` ->
 * `InviteService.enqueuePending` -> `state.pendingInvites` exactly like a real
 * inbound connection would. [PeerPipe] (`mock-support/test/TestSupport.kt`,
 * the same fake `StrangerGateTest`/`ConnectServiceFrameDispatchTest` use at the
 * service layer) plays "the stranger"'s side of the stream: [PeerPipe.sendFrame]
 * pushes a `Frame.Hello` into it, exactly as if the peer had just sent one.
 *
 * Reuses the [desktopE2eTest] harness (`ComposeE2eSupport.kt`), so the same
 * caveat from `DesktopAppE2eTest`'s class doc applies: minting the invite and
 * injecting the connection both do real (non-test-clock) suspend work on
 * [dev.azula.state.AzulaState]'s own background scope, so every step below
 * that depends on it lands via a real `waitUntil*`/`waitUntil` against either
 * the rendered UI or [dev.azula.state.AzulaState]'s own public state.
 */
class InviteReviewSheetE2eTest {

    @Test
    fun acceptingAVerifiedStrangerWiresAConversationAndClearsThePending() = desktopE2eTest { state, transport, scope ->
        // Wait for the transport to actually bind (sets AzulaState.nodeId) before
        // minting: createInvite embeds transport.myTicket() at mint time, and
        // verifyInbound later checks that ticket's peer id against
        // transport.nodeId -- minting before bind would embed a stale
        // "azula-mock-offline" ticket that no longer resolves to the real node
        // id once bound, breaking verification against no fault of the invite
        // itself.
        waitUntil(timeoutMillis = 10_000) { state.nodeId != null }

        // Mint a real invite from this same (issuer) AzulaState -- exactly the
        // loopback shape StrangerGateTest.unknownPeerOnChatWithAValidInviteLandsInPendingVerified
        // uses at the service layer -- so the stranger below can present a
        // `Hello.invite` that verifies.
        var encodedInvite: String? = null
        scope.launch { encodedInvite = state.createInvite(expiresInSeconds = 3600) }
        waitUntil(timeoutMillis = 10_000) { encodedInvite != null }

        val pipe = PeerPipe()
        scope.launch {
            transport.injectIncomingConnection(IncomingConnection(remoteId = "stranger-verified", alpn = Alpns.CHAT, stream = pipe.clientStream))
            pipe.sendFrame(Frame.Hello(name = "stranger-verified", invite = encodedInvite))
        }

        // Click the sidebar's "invites" nav row via its testTag, not its label
        // text: Sidebar.kt's InvitesRow relabels itself "invites · N pending"
        // the instant a request lands, which can race an exact-text click
        // target here since the stranger connection above is injected
        // concurrently on `scope` -- the tag is stable regardless of count.
        waitUntilAtLeastOneExists(hasTestTag("nav-invites"), timeoutMillis = 15_000)
        onNodeWithTag("nav-invites").performClick()
        waitUntilAtLeastOneExists(hasTestTag("pending-request-stranger-verified"), timeoutMillis = 15_000)
        onNodeWithTag("pending-request-stranger-verified").performClick()

        waitUntilAtLeastOneExists(hasTestTag("invite-review-sheet"), timeoutMillis = 10_000)
        onNodeWithText("This peer presented a valid invite you issued.").assertIsDisplayed()

        onNodeWithTag("invite-review-accept").performClick()
        waitUntilDoesNotExist(hasTestTag("pending-request-stranger-verified"), timeoutMillis = 10_000)

        assertTrue(state.pendingInvites.isEmpty(), "accept must remove the request from the pending inbox")
        // ConnectService.wireStream keys the new conversation's id off the
        // stranger's node id (ConversationStore.ensureConv's `id = peerId`).
        assertEquals(ConversationKind.PEER, state.conversations.single { it.id == "stranger-verified" }.kind)
    }

    @Test
    fun decliningAnUnverifiedStrangerClearsThePendingWithoutCreatingAConversation() = desktopE2eTest { state, transport, scope ->
        val pipe = PeerPipe()
        scope.launch {
            transport.injectIncomingConnection(IncomingConnection(remoteId = "stranger-unverified", alpn = Alpns.CHAT, stream = pipe.clientStream))
            // No invite -- allowLegacyInbound (default on, see ConnectService)
            // still holds it pending, but flagged unverified.
            pipe.sendFrame(Frame.Hello(name = "stranger-unverified", invite = null))
        }

        // Click the sidebar's "invites" nav row via its testTag, not its label
        // text: Sidebar.kt's InvitesRow relabels itself "invites · N pending"
        // the instant a request lands, which can race an exact-text click
        // target here since the stranger connection above is injected
        // concurrently on `scope` -- the tag is stable regardless of count.
        waitUntilAtLeastOneExists(hasTestTag("nav-invites"), timeoutMillis = 15_000)
        onNodeWithTag("nav-invites").performClick()
        waitUntilAtLeastOneExists(hasTestTag("pending-request-stranger-unverified"), timeoutMillis = 15_000)
        onNodeWithTag("pending-request-stranger-unverified").performClick()

        waitUntilAtLeastOneExists(hasTestTag("invite-review-sheet"), timeoutMillis = 10_000)
        onNodeWithText(
            "This peer didn't present a valid invite — treat it as unverified. Legacy connections are allowed for now; you can decline if you don't recognize it.",
        ).assertIsDisplayed()

        onNodeWithTag("invite-review-decline").performClick()
        waitUntilDoesNotExist(hasTestTag("pending-request-stranger-unverified"), timeoutMillis = 10_000)

        assertTrue(state.pendingInvites.isEmpty(), "decline must remove the request from the pending inbox")
        assertFalse(
            state.conversations.any { it.id == "stranger-unverified" },
            "decline must never wire the held stream into a conversation",
        )
    }
}
