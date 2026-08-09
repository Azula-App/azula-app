@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.mock.FakeTransport
import dev.azula.notify.Notifier
import dev.azula.state.InviteService
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [InviteService] directly (not through the full ConnectService
 * incoming-gate loop, which [ConnectServiceFrameDispatchTest] /
 * [ConnectServiceReconnectTest] already cover for the accept path): minting an
 * invite round-trips through [InviteService.verifyInbound] against the issuer's
 * own transport, revoke/expiry/single-use gate it correctly, and accept/decline
 * update the pending list and contacts as documented in
 * azula-docs/openspec/specs/invitations/design.md.
 */
class InviteServiceTest {
    private suspend fun boundTransport(): FakeTransport = FakeTransport().also { it.bind(emptyList()) }

    @Test
    fun createInviteVerifiesAgainstOwnIssuedStore() = runTest {
        val transport = boundTransport()
        val service = InviteService(invitationsStore = null, transport = transport, notifier = null, scope = backgroundScope)

        val encoded = service.createInvite(expiresInSeconds = 3600, sign = false, singleUse = false, label = "friend")
        assertNotNull(encoded, "createInvite should succeed once bound")
        assertEquals(1, service.issued.size)
        assertEquals("friend", service.issued.single().label)

        val verified = service.verifyInbound(encoded)
        assertNotNull(verified, "a freshly minted invite must verify against the issuer's own store")
        assertEquals(service.issued.single().id, verified.id)
    }

    @Test
    fun expiredInviteFailsVerification() = runTest {
        val transport = boundTransport()
        val service = InviteService(null, transport, null, backgroundScope)
        // expiresInSeconds is negative -- expiresAt lands in the past the instant
        // it's minted, so this doesn't need to fast-forward any clock.
        val encoded = service.createInvite(expiresInSeconds = -10)
        assertNotNull(encoded)
        assertNull(service.verifyInbound(encoded), "an invite whose expiresAt is already past must not verify")
    }

    @Test
    fun verifyInboundRejectsMissingOrMalformed() = runTest {
        val transport = boundTransport()
        val service = InviteService(null, transport, null, backgroundScope)
        assertNull(service.verifyInbound(null))
        assertNull(service.verifyInbound(""))
        assertNull(service.verifyInbound("not-an-invite"))
    }

    @Test
    fun revokeRemovesTheInviteSoItNoLongerVerifies() = runTest {
        val transport = boundTransport()
        val service = InviteService(null, transport, null, backgroundScope)
        val encoded = service.createInvite()!!
        assertNotNull(service.verifyInbound(encoded))

        service.revoke(service.issued.single().id)

        assertTrue(service.issued.isEmpty())
        assertNull(service.verifyInbound(encoded), "a revoked invite must stop verifying")
    }

    @Test
    fun singleUseInviteStopsVerifyingOnceConsumedByAccept() = runTest {
        val transport = boundTransport()
        val service = InviteService(null, transport, null, backgroundScope)
        val encoded = service.createInvite(singleUse = true)!!

        service.enqueuePending("peer-x", service.issued.single().id, "peer-x-code", FakeStream(), "azula/chat/0")
        service.accept(service.pending.single())

        assertTrue(service.issued.single().consumed, "accepting a single-use invite must mark it consumed")
        assertNull(service.verifyInbound(encoded), "a consumed single-use invite must stop verifying")
        assertTrue(service.isKnown("peer-x"), "accepting adds the peer to contacts")
    }

    @Test
    fun enqueuePendingPostsADedicatedConnectionRequestNotificationWhenBackgrounded() = runTest {
        val transport = boundTransport()
        val notifier = RecordingNotifier()
        val service = InviteService(null, transport, notifier, backgroundScope)
        service.foreground = { false }

        service.enqueuePending("peer-z", null, "peer-z-code", FakeStream(), "azula/chat/0")

        assertEquals(1, notifier.connectionRequests.size, "must post via postConnectionRequest, not postMessage")
        assertTrue(notifier.messages.isEmpty(), "must not fall back to the generic postMessage channel")
        val (count, peerCode) = notifier.connectionRequests.single()
        assertEquals(1, count)
        assertEquals("peer-z-code", peerCode)
    }

    @Test
    fun enqueuePendingDoesNotNotifyWhileForegrounded() = runTest {
        val transport = boundTransport()
        val notifier = RecordingNotifier()
        val service = InviteService(null, transport, notifier, backgroundScope)
        service.foreground = { true }

        service.enqueuePending("peer-w", null, "peer-w-code", FakeStream(), "azula/chat/0")

        assertTrue(notifier.connectionRequests.isEmpty(), "the foreground in-app banner covers this case, not a notification")
    }

    @Test
    fun declineClosesTheStreamAndDropsThePendingRequestWithoutAddingAContact() = runTest {
        val transport = boundTransport()
        val service = InviteService(null, transport, null, backgroundScope)
        val stream = FakeStream()
        service.enqueuePending("peer-y", null, "peer-y-code", stream, "azula/chat/0")
        assertEquals(1, service.pending.size)

        service.decline(service.pending.single())
        runCurrent()

        assertTrue(service.pending.isEmpty())
        assertFalse(service.isKnown("peer-y"))
        assertTrue(stream.closed, "decline must close the held stream")
    }
}

/** A no-op [dev.azula.net.P2pStream] that just tracks whether it was closed. */
private class FakeStream : dev.azula.net.P2pStream {
    var closed = false
    override suspend fun sendLine(line: String) = Unit
    override suspend fun receiveLine(): String? = null
    override suspend fun sendBytes(bytes: ByteArray) = Unit
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() { closed = true }
}

/** Records every call instead of posting anything, so tests can assert which
 *  [Notifier] method [InviteService] invoked. */
private class RecordingNotifier : Notifier {
    val messages = mutableListOf<Triple<String, String, String>>()
    val connectionRequests = mutableListOf<Pair<Int, String>>()

    override fun postMessage(conversationId: String, title: String, body: String) {
        messages.add(Triple(conversationId, title, body))
    }

    override fun postConnectionRequest(count: Int, peerCode: String) {
        connectionRequests.add(count to peerCode)
    }
}
