@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.model.ConversationKind
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [dev.azula.state.ConnectService]'s auto-reconnect behavior
 * (`scheduleReconnect`, the drop path in `receiveLoop`'s `finally`, and
 * `wireConv`'s `dropSurfaces` call) plus the surrounding seams
 * ([dev.azula.state.AzulaState.openChatMobile] kicking a reconnect,
 * [dev.azula.state.FrameDispatcher.dropSurfaces]) -- driven entirely through
 * [dev.azula.state.AzulaState]'s public surface, same spirit as
 * [ConnectServiceFrameDispatchTest].
 *
 * All backoff timing is driven with the test's *virtual* clock
 * ([kotlinx.coroutines.test.TestScope.advanceTimeBy] + `runCurrent()`) --
 * never `advanceUntilIdle()`, which would hang forever on
 * [dev.azula.state.ConnectService]'s infinite `pollRtt` loop (see
 * `TestSupport.kt` / `MediaServiceTest.kt`'s docs on this). Since `scheduleReconnect`
 * runs its `delay()` calls on the same `scope` the test hands to `testAzulaState`
 * (backed by the test dispatcher), those delays -- unlike `PersistenceCoordinator`/
 * `MediaService`'s real background-dispatcher work -- *are* on the virtual clock,
 * so `advanceTimeBy` is exactly the right tool here.
 *
 * [ScriptedTransport] plays the "peer" for the outbound-dial scenarios: unlike
 * [dev.azula.mock.FakeTransport], it can be scripted to fail a connect N times
 * before succeeding, which is what a "peer comes back after k attempts" (or
 * "never comes back") test needs. It's kept local to this file (mirroring
 * `OneShotIncomingTransport` in ConnectServiceFrameDispatchTest.kt) rather than
 * folded into the shared `FakeTransport`, since its whole point is to fail
 * connects on demand -- a behavior none of `FakeTransport`'s other consumers
 * (the mock app, the other test suites) want.
 */
class ConnectServiceReconnectTest {

    // ---- 1: drop + successful redial within the attempt budget ----

    @Test
    fun deadPeerReconnectsWithBackoffWhenItComesBackWithinAttempts() = runTest {
        val transport = ScriptedTransport("peer-1")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-1")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        assertTrue(cs.online, "the initial dial should wire the conversation online")
        assertEquals("ticket-1", cs.sourceTicket)

        // The peer is down for exactly one redial attempt, then comes back.
        transport.failuresRemaining = 1
        transport.lastStream!!.drop()
        runCurrent()

        assertFalse(cs.online, "a dropped stream should mark the conversation offline")
        assertTrue(cs.reconnecting, "a conversation with a sourceTicket must start redialing on drop")

        // First backoff attempt fires after ~1s and fails -- still redialing.
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(cs.online)
        assertTrue(cs.reconnecting)

        // Second attempt, 2s later, succeeds -- the conversation is rewired.
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(cs.online, "the redial should rewire the conversation once the peer answers")
        assertFalse(cs.reconnecting, "reconnecting should clear once back online")
        assertEquals(3, transport.connectAttempts, "initial dial + 1 failed redial + 1 successful redial")
    }

    // ---- 2: the peer never comes back -- give up after 8 attempts ----

    @Test
    fun deadPeerGivesUpAfterEightAttempts() = runTest {
        val transport = ScriptedTransport("peer-2")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-2")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        assertTrue(cs.online)

        transport.failuresRemaining = Int.MAX_VALUE // the peer never comes back
        transport.lastStream!!.drop()
        runCurrent()
        assertTrue(cs.reconnecting)

        // Full backoff schedule: 1+2+4+8+15+15+15+15 = 75s.
        advanceTimeBy(75_000)
        runCurrent()

        assertFalse(cs.reconnecting, "should give up once the 8th attempt has failed")
        assertFalse(cs.online)
        assertEquals(9, transport.connectAttempts, "initial dial + exactly 8 failed redials")
    }

    // ---- 3: an inbound-accepted conversation has no ticket to redial ----

    @Test
    fun inboundConversationWithoutTicketNeverReconnectsOnDrop() = runTest {
        val pipe = PeerPipe()
        val transport = InboundOnlyTransport("peer-3", pipe)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        // A stranger on the CHAT alpn is gated: present a (legacy, invite-less)
        // Hello and accept the resulting pending request before it's wired.
        pipe.sendFrame(Frame.Hello("peer-3"))
        runCurrent()
        azulaState.acceptInvite(azulaState.pendingInvites.single())
        runCurrent()

        pipe.sendFrame(Frame.Chat("hi"))
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        assertNull(cs.sourceTicket, "an inbound-accepted conversation carries no dial ticket")
        assertTrue(cs.online)

        pipe.drop()
        runCurrent()

        assertFalse(cs.online, "the drop should still mark the conversation offline")
        assertFalse(cs.reconnecting, "no ticket to redial -- scheduleReconnect must no-op")

        // Prove it stays that way: if a backoff loop had (incorrectly) started,
        // this advance would land squarely inside its schedule.
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(cs.reconnecting)
    }

    // ---- 4: TERMINAL conversations are excluded from auto-reconnect ----

    @Test
    fun terminalConversationDoesNotAutoReconnectOnDrop() = runTest {
        val transport = ScriptedTransport("peer-4", successAlpn = Alpns.TERM)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-4")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        assertEquals(ConversationKind.TERMINAL, azulaState.conversation(convId).kind)
        assertTrue(cs.online)
        assertEquals("ticket-4", cs.sourceTicket, "a terminal conv still records its ticket, for the explicit reconnectTerminal action")

        transport.lastStream!!.drop()
        runCurrent()

        assertFalse(cs.online)
        assertFalse(cs.reconnecting, "TERMINAL conversations must not auto-reconnect")

        // No redial should ever fire, however long we wait.
        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(cs.reconnecting)
        assertEquals(1, transport.connectAttempts, "only the initial dial -- no redial attempts")
    }

    // ---- 5: wireConv drops stale surfaces; re-creating the same id replaces the message in place ----

    @Test
    fun reconnectDropsStaleSurfacesAndRecreatingSameIdReplacesMessageInPlace() = runTest {
        val transport = ScriptedTransport("peer-5")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-5")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        val firstStream = transport.lastStream!!

        firstStream.push(createSurfaceFrame("s1"))
        runCurrent()

        assertNotNull(azulaState.surfaces["s1"], "CreateSurface should register a live surface")
        val messageCountBeforeDrop = cs.messages.size
        assertTrue(cs.messages.any { it.a2ui?.surfaceId == "s1" })

        // Drop + a successful redial (no scripted failures) -- a fresh stream
        // gets wired for the same conversation.
        firstStream.drop()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(cs.online, "the redial should succeed on the first attempt")

        // The stale surface must be dropped the instant the new stream is
        // wired, even though the message still carries the a2ui reference --
        // it just renders frozen until the peer re-creates it (Chat's
        // `surfaceId !in surfaces` check).
        assertNull(azulaState.surfaces["s1"], "wireConv must drop this conversation's session-scoped surfaces on rewire")
        assertTrue(cs.messages.any { it.a2ui?.surfaceId == "s1" }, "the message itself is untouched by the drop")
        assertEquals(messageCountBeforeDrop, cs.messages.size)

        // The peer re-creates the same surface on the new stream: re-register
        // and replace the existing message in place, not append a new one.
        val secondStream = transport.lastStream!!
        assertTrue(secondStream !== firstStream, "the redial should have wired a genuinely new stream")
        secondStream.push(createSurfaceFrame("s1"))
        runCurrent()

        assertNotNull(azulaState.surfaces["s1"], "re-creating the same id should re-register the surface")
        assertEquals(messageCountBeforeDrop, cs.messages.size, "same surfaceId should replace the existing message in place, not append")
    }

    // ---- 6: opening an offline dialed conversation kicks a reconnect ----

    @Test
    fun openChatMobileKicksReconnectForAnOfflineDialedConversation() = runTest {
        val transport = ScriptedTransport("peer-6")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-6")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        assertTrue(cs.online)

        // Simulate a conversation that's offline but still carries its dial
        // ticket (e.g. restored after a cold start, before any redial has run)
        // rather than going through a live drop -- isolates openChatMobile
        // itself as the trigger, rather than the drop path already covered above.
        cs.online = false
        assertFalse(cs.reconnecting)

        azulaState.openChatMobile(convId)

        assertTrue(cs.reconnecting, "opening an offline dialed conversation should kick scheduleReconnect")
    }
}

/**
 * A single-peer outbound transport for exercising ConnectService's redial /
 * backoff loop deterministically: [connect] only ever succeeds for
 * [successAlpn] (every other ALPN throws, so `dialAll`'s CHAT/LLM/TERM race
 * always resolves the same way), and [failuresRemaining] scripts "the peer is
 * down for k attempts, then comes back" (or, set very high, "never comes
 * back"). No inbound connections.
 */
private class ScriptedTransport(
    private val peerId: String,
    private val successAlpn: String = Alpns.CHAT,
) : IrohTransport {
    override var nodeId: String? = "self"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = ""
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override fun peerIdForTicket(ticket: String): String = peerId
    override fun incoming(): Flow<IncomingConnection> = flow {}
    override suspend fun shutdown() = Unit

    /** Remaining dials against [successAlpn] that should fail before one is
     *  let through. */
    var failuresRemaining = 0

    /** Every successful-alpn dial attempt, including the initial connect. */
    var connectAttempts = 0
        private set

    /** The stream handed back by the most recent successful dial. */
    var lastStream: DroppableStream? = null
        private set

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        if (alpn != successAlpn) throw RuntimeException("no $alpn service for this peer")
        connectAttempts++
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw RuntimeException("peer unreachable")
        }
        return DroppableStream().also { lastStream = it }
    }
}

/**
 * A [P2pStream] a test fully controls: [push] delivers a frame as if the peer
 * sent it, and [drop] simulates the peer hanging up cleanly -- `receiveLine`
 * then returns null (EOF), exactly what `ConnectService.receiveLoop`'s
 * `line ?: break` expects from a dropped connection.
 */
private class DroppableStream : P2pStream {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    override suspend fun sendLine(line: String) = Unit
    override suspend fun receiveLine(): String? = runCatching { inbox.receive() }.getOrNull()
    override suspend fun sendBytes(bytes: ByteArray) = Unit
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() { inbox.close() }
    fun drop() { inbox.close() }
    suspend fun push(frame: Frame) = inbox.send(FrameCodec.encode(frame))
}

/** Emits exactly one inbound connection (mirrors `OneShotIncomingTransport` in
 *  ConnectServiceFrameDispatchTest.kt): enough to wire [pipe] as an
 *  inbound-accepted conversation, which never gets a sourceTicket to redial. */
private class InboundOnlyTransport(
    private val remoteId: String,
    private val pipe: PeerPipe,
) : IrohTransport {
    override var nodeId: String? = "test-node"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = ""
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override suspend fun connect(ticket: String, alpn: String): P2pStream = error("not used: inbound-only test")
    override fun peerIdForTicket(ticket: String): String? = null
    override fun incoming(): Flow<IncomingConnection> = flow { emit(IncomingConnection(remoteId, Alpns.CHAT, pipe.clientStream)) }
    override suspend fun shutdown() = Unit
}

/** Builds a `Frame.A2ui(CreateSurface(surfaceId = id))` wire payload, matching
 *  ConnectServiceFrameDispatchTest's construction. */
private fun createSurfaceFrame(id: String) = Frame.A2ui(
    buildJsonObject {
        put("version", "v0.9.1")
        putJsonObject("createSurface") { put("surfaceId", id) }
    },
)
