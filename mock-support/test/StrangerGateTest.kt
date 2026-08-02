@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the part of ConnectService's stranger gate [ConnectServiceFrameDispatchTest]
 * doesn't: [dev.azula.state.ConnectService.dialAll] races CHAT+LLM+TERM, so an
 * unknown peer dialing us opens all three streams, not just CHAT. Only CHAT is
 * invite-gateable -- LLM/TERM from a stranger must be closed outright, never
 * wired AND never enqueued as pending (enqueuing would risk one of them landing
 * before CHAT and becoming the wrong held "primary" for that endpoint id).
 */
class StrangerGateTest {
    @Test
    fun unknownPeerOnLlmIsClosedWithoutBecomingAConversationOrPending() = runTest {
        val transport = GateTestTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val llmStream = TrackingStream()
        transport.emit(IncomingConnection("stranger-llm", Alpns.LLM, llmStream))
        runCurrent()

        assertTrue(azulaState.conversations.isEmpty(), "an unknown peer's LLM stream must not create a conversation")
        assertTrue(azulaState.pendingInvites.isEmpty(), "an unknown peer's LLM stream must not become a pending request either")
        assertTrue(llmStream.closed, "the stream must be closed, not left dangling")
    }

    @Test
    fun unknownPeerOnTermIsClosedWithoutBecomingAConversationOrPending() = runTest {
        val transport = GateTestTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val termStream = TrackingStream()
        transport.emit(IncomingConnection("stranger-term", Alpns.TERM, termStream))
        runCurrent()

        assertTrue(azulaState.conversations.isEmpty())
        assertTrue(azulaState.pendingInvites.isEmpty())
        assertTrue(termStream.closed)
    }

    @Test
    fun unknownPeerOnChatWithAValidInviteLandsInPendingVerified() = runTest {
        val transport = GateTestTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val encoded = azulaState.createInvite(expiresInSeconds = 3600)
        assertNotNull(encoded, "self-issuing an invite should succeed")
        runCurrent()

        val pipe = PeerPipe()
        transport.emit(IncomingConnection("stranger-chat", Alpns.CHAT, pipe.clientStream))
        runCurrent()
        pipe.sendFrame(Frame.Hello("stranger-chat", invite = encoded))
        runCurrent()

        val req = azulaState.pendingInvites.single()
        assertEquals("stranger-chat", req.endpointId)
        assertFalse(req.unverified, "a stream presenting a valid invite must land in pending as verified")
        assertTrue(azulaState.conversations.isEmpty(), "still holds pending, not wired -- no conversation until accept")
    }
}

/** A no-op [P2pStream] that just tracks whether it was closed. */
private class TrackingStream : P2pStream {
    var closed = false
    override suspend fun sendLine(line: String) = Unit
    override suspend fun receiveLine(): String? = null
    override suspend fun sendBytes(bytes: ByteArray) = Unit
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() { closed = true }
}

/**
 * An [IrohTransport] whose [incoming] events are fed on demand via [emit]
 * (rather than [dev.azula.mock.FakeTransport]'s fixed one-shot TERM emission),
 * so a test can simulate a stranger opening several ALPNs in sequence. Its
 * ticketBytes/ticketFromBytes/peerIdForTicket round-trip faithfully -- like the
 * fixed [dev.azula.mock.FakeTransport] -- so a self-issued invite verifies
 * against [endpointId] the same way it would on a real transport.
 */
private class GateTestTransport : IrohTransport {
    override var endpointId: String? = "self-endpoint"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = "ticket-for-$endpointId"
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override suspend fun connect(ticket: String, alpn: String): P2pStream = error("not used: inbound-only test")
    override fun peerIdForTicket(ticket: String): String? = ticket.removePrefix("ticket-for-").takeIf { it != ticket }
    override fun incoming(): Flow<IncomingConnection> = incomingChannel.receiveAsFlow()
    override suspend fun shutdown() = Unit

    override fun ticketBytes(ticket: String): ByteArray = ticket.encodeToByteArray()
    override fun ticketFromBytes(bytes: ByteArray): String = bytes.decodeToString()

    private val incomingChannel = Channel<IncomingConnection>(Channel.UNLIMITED)
    suspend fun emit(conn: IncomingConnection) = incomingChannel.send(conn)
}
