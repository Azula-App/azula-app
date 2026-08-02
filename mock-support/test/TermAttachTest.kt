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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the app half of the persistent-session-attach feature (Feature 5 in
 * the terminal plan): [dev.azula.state.ConnectService.wireConv] sending
 * `Frame.TermAttach` as the first frame on every TERM stream,
 * [dev.azula.state.FrameDispatcher.applyFrame]'s `TermSession`/`TermExit`
 * handling, and [dev.azula.state.ConnectService.scheduleReconnect]'s gating on
 * [dev.azula.state.ConversationState.termSessionId] / `termExited`. Mirrors
 * [ConnectServiceReconnectTest]'s virtual-clock discipline (`advanceTimeBy` +
 * `runCurrent()`, never `advanceUntilIdle()` -- `pollRtt` runs forever).
 */
class TermAttachTest {

    // ---- 1: wireConv sends TermAttach(null) as the first frame on a fresh connect ----

    @Test
    fun wireConvSendsTermAttachWithNullSessionOnFirstConnect() = runTest {
        val transport = TermTestTransport("peer-1")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-1")
        runCurrent()
        val convId = azulaState.conversations.single().id
        assertEquals(ConversationKind.TERMINAL, azulaState.conversation(convId).kind)
        assertTrue(azulaState.conv(convId).online)

        // dialAll's `ping()` sends a Frame.Hello presence frame on every ALPN it
        // races (including TERM) before wireConv ever runs -- so TermAttach isn't
        // literally byte 1 on the wire, but it must be the first *term-specific*
        // frame: nothing (in particular no Frame.Input) may precede it, matching
        // the server's 500ms first-frame gate (see term.rs's accept_gate replay).
        val sent = transport.lastStream!!.sentFrames
        assertTrue(sent.isNotEmpty(), "wireConv must send at least one frame")
        val firstTermFrame = sent.first { it !is Frame.Hello }
        assertTrue(firstTermFrame is Frame.TermAttach, "the first term-specific frame must be TermAttach, got $firstTermFrame")
        assertEquals(null, firstTermFrame.session, "a fresh conversation has no stored session to attach")
    }

    // ---- 2: TermSession updates state + persists ----

    @Test
    fun termSessionUpdatesStateAndPersists() = runTest {
        val transport = TermTestTransport("peer-2")
        val store = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore = store)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-2")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        assertNull(cs.termSessionId)

        transport.lastStream!!.push(Frame.TermSession("sess-abc", resumed = false))
        runCurrent()

        assertEquals("sess-abc", cs.termSessionId)
        assertFalse(cs.termExited)

        val (savedId, dto) = store.saves.receive()
        assertEquals(convId, savedId)
        assertEquals("sess-abc", dto.termSessionId, "TermSession must persist the session id")
    }

    // ---- 3: TermExit flips termExited + clears the session id, and persists ----

    @Test
    fun termExitFlipsExitedAndClearsSessionId() = runTest {
        val transport = TermTestTransport("peer-3")
        val store = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore = store)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-3")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)

        transport.lastStream!!.push(Frame.TermSession("sess-xyz", resumed = false))
        runCurrent()
        store.saves.receive() // drain the TermSession save from assertion 2's pattern

        transport.lastStream!!.push(Frame.TermExit("sess-xyz", code = 0))
        runCurrent()

        assertTrue(cs.termExited)
        assertNull(cs.termSessionId, "TermExit must clear the stored session id")

        val (_, dto) = store.saves.receive()
        assertNull(dto.termSessionId, "the persisted blob must not keep a dead session id")
    }

    // ---- 4: scheduleReconnect fires for a re-attachable terminal, and the redial re-sends the stored id ----

    @Test
    fun reAttachableTerminalAutoReconnectsAndResendsStoredSessionId() = runTest {
        val transport = TermTestTransport("peer-4")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-4")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)

        transport.lastStream!!.push(Frame.TermSession("sess-persist", resumed = false))
        runCurrent()
        assertEquals("sess-persist", cs.termSessionId)

        // The connection drops -- the persistent session is still alive server-side.
        transport.lastStream!!.drop()
        runCurrent()

        assertFalse(cs.online)
        assertTrue(cs.reconnecting, "a terminal holding a re-attachable session must auto-reconnect")

        // First (and only, in this scripted transport) backoff attempt succeeds.
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(cs.online, "the redial should rewire the conversation")
        assertFalse(cs.reconnecting)
        assertEquals("sess-persist", cs.termSessionId, "the session id survives the redial (nothing cleared it)")

        val resent = transport.lastStream!!.sentFrames.first { it !is Frame.Hello }
        assertTrue(resent is Frame.TermAttach)
        assertEquals("sess-persist", resent.session, "the redial must re-attach the same stored session")
    }

    // ---- 5: an exited terminal never auto-reconnects ----

    @Test
    fun exitedTerminalDoesNotAutoReconnect() = runTest {
        val transport = TermTestTransport("peer-5")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-5")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)

        transport.lastStream!!.push(Frame.TermSession("sess-dead", resumed = false))
        runCurrent()
        transport.lastStream!!.push(Frame.TermExit("sess-dead", code = 0))
        runCurrent()
        assertTrue(cs.termExited)

        transport.lastStream!!.drop()
        runCurrent()

        assertFalse(cs.online)
        assertFalse(cs.reconnecting, "an exited terminal must stay manual-only")

        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(cs.reconnecting)
        assertEquals(1, transport.connectAttempts, "only the initial dial -- no redial for an exited terminal")
    }

    // ---- 6: legacy mode -- a server that never sends TermSession never auto-reconnects ----

    @Test
    fun legacyTerminalWithNoSessionIdNeverAutoReconnects() = runTest {
        val transport = TermTestTransport("peer-6")
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-6")
        runCurrent()
        val convId = azulaState.conversations.single().id
        val cs = azulaState.conv(convId)
        assertNull(cs.termSessionId, "an old CLI ignores TermAttach and never replies with TermSession")

        transport.lastStream!!.drop()
        runCurrent()

        assertFalse(cs.online)
        assertFalse(cs.reconnecting, "legacy mode (no session id) stays manual-only, exactly like before this feature")

        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(cs.reconnecting)
        assertEquals(1, transport.connectAttempts)
    }
}

/**
 * A single-peer outbound transport whose [connect] only ever succeeds for the
 * TERM alpn (every other alpn throws, so `dialAll`'s CHAT/LLM/TERM race always
 * picks TERM) -- mirrors [ScriptedTransport] in ConnectServiceReconnectTest.kt
 * but hands back a frame-capturing [RecordingStream] instead of a plain
 * [dev.azula.net.P2pStream], so a test can assert exactly what `wireConv` sent
 * (in particular, the `Frame.TermAttach` first frame).
 */
private class TermTestTransport(private val peerId: String) : IrohTransport {
    override var endpointId: String? = "self"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = ""
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override fun peerIdForTicket(ticket: String): String = peerId
    override fun incoming(): Flow<IncomingConnection> = flow {}
    override suspend fun shutdown() = Unit

    var connectAttempts = 0
        private set
    var lastStream: RecordingStream? = null
        private set

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        if (alpn != Alpns.TERM) throw RuntimeException("no $alpn service for this peer")
        connectAttempts++
        return RecordingStream().also { lastStream = it }
    }
}

/**
 * A [P2pStream] a test fully controls, like [DroppableStream] in
 * ConnectServiceReconnectTest.kt, but additionally records every frame the
 * client sends via [sendLine] into [sentFrames] -- ordered, so a test can
 * assert both which frame was sent first (the TermAttach requirement) and its
 * exact content.
 */
private class RecordingStream : P2pStream {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    val sentFrames = mutableListOf<Frame>()

    override suspend fun sendLine(line: String) {
        runCatching { FrameCodec.decode(line) }.getOrNull()?.let { sentFrames.add(it) }
    }
    override suspend fun receiveLine(): String? = inbox.receiveCatching().getOrNull()
    override suspend fun sendBytes(bytes: ByteArray) = Unit
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() { inbox.close() }
    fun drop() { inbox.close() }
    suspend fun push(frame: Frame) = inbox.send(FrameCodec.encode(frame))
}
