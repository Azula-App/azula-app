@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.link.InviteCodec
import dev.azula.link.InvitePayload
import dev.azula.net.Alpns
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers ConnectService's invite-aware outbound dialing end to end through
 * [AzulaState]'s public surface: `connectPeer("azi…")` must dial the ticket
 * EMBEDDED in the invite (not the encoded invite string itself) and persist
 * the raw invite string to [dev.azula.net.PeerStore] -- not just the bare
 * ticket -- so a later reconnect can keep re-presenting it (see
 * ConnectService.resolveTicket / persistPeer and
 * azula-docs/openspec/specs/invitations/design.md's "keeps re-presenting it on auto-reconnect"
 * rule).
 */
class InviteDialingTest {
    @Test
    fun connectPeerWithAnInviteDialsTheEmbeddedTicketAndPersistsTheInviteString() = runTest {
        val transport = InviteAwareTransport(peerId = "peer-invited")
        val peerStore = InMemoryPeerStore()
        val azulaState = testAzulaState(backgroundScope, transport, peerStore = peerStore)
        azulaState.start()
        runCurrent()

        val ticketBytes = "peer-ticket-xyz".encodeToByteArray()
        val payload = InvitePayload(1, 0, ByteArray(8) { it.toByte() }, 1_700_000_000L, 0L, ticketBytes, null)
        val encoded = InviteCodec.encode(payload)

        azulaState.connectPeer(encoded)
        runCurrent()

        assertEquals(
            listOf("peer-ticket-xyz"), transport.dialedTickets,
            "must dial the ticket embedded in the invite, not the encoded invite string itself",
        )
        assertEquals(
            listOf(encoded), peerStore.load(),
            "PeerStore must hold the raw invite string, not just the resolved ticket, so reconnect can re-present it",
        )
    }
}

/** Faithfully round-trips ticketBytes/ticketFromBytes (unlike [dev.azula.mock.FakeTransport]'s
 *  string-only mock) and records every dialed ticket, so a test can assert exactly what
 *  ConnectService handed to [connect] -- the decoded ticket, not the encoded invite. */
private class InviteAwareTransport(private val peerId: String) : IrohTransport {
    override var endpointId: String? = "self"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = ""
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override fun peerIdForTicket(ticket: String): String = peerId
    override fun incoming(): Flow<IncomingConnection> = flow {}
    override suspend fun shutdown() = Unit

    override fun ticketBytes(ticket: String): ByteArray = ticket.encodeToByteArray()
    override fun ticketFromBytes(bytes: ByteArray): String = bytes.decodeToString()

    val dialedTickets = mutableListOf<String>()

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        if (alpn != Alpns.CHAT) throw RuntimeException("no $alpn service for this peer")
        dialedTickets.add(ticket)
        return NoopStream()
    }
}

private class NoopStream : P2pStream {
    override suspend fun sendLine(line: String) = Unit
    override suspend fun receiveLine(): String? = null
    override suspend fun sendBytes(bytes: ByteArray) = Unit
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() = Unit
}
