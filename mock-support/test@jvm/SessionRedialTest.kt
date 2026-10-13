@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.core.DeviceCertCodec
import dev.azula.core.Ed25519
import dev.azula.core.ROLE_SESSION
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.persist.InvitationsBook
import dev.azula.persist.InvitationsStore
import dev.azula.state.AzulaState
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.toHexLower
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The app redialing a CLI session conversation by its endpoint id
 * ([dev.azula.state.ConnectService.scheduleReconnect] /
 * [dev.azula.state.ConnectService.reconnectSessions]): a session dialed us in, so the
 * conversation holds no ticket, but it is keyed by the session's own device_pk -- its iroh
 * endpoint id -- so the app can dial it back on every reconnect trigger (stream drop,
 * opening the conversation, transport re-bind, foreground resume), re-validating the cert
 * the session answers with exactly as an inbound reconnect's is re-validated.
 *
 * Each test first admits the session the way production does (an inbound LLM connection
 * presenting a valid session cert, [dev.azula.state.ConnectService.admitSessionCert]) so the
 * conversation is a genuine session conversation, then drives the redial through
 * [SessionDialTransport.connectById]. Crypto fixtures mirror `SessionCertGateTest`'s, and
 * are nested here for the same JVM binary-name reason. Backoff timing runs on the virtual
 * clock with `advanceTimeBy` + `runCurrent()`, never `advanceUntilIdle()` (see
 * `ConnectServiceReconnectTest`'s kdoc).
 */
class SessionRedialTest {
    private val tempDir = createTempDirectory("session-redial-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private class TestEd25519 : Ed25519 {
        override fun sign(secret: ByteArray, data: ByteArray): ByteArray {
            require(secret.size == 32) { "fake secret must be 32 bytes" }
            return secret + Sha256.hash(secret + data)
        }

        override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != 64) return false
            val secret = signature.copyOfRange(0, 32)
            val mac = signature.copyOfRange(32, 64)
            if (!Sha256.hash(secret).contentEquals(publicKey)) return false
            return Sha256.hash(secret + data).contentEquals(mac)
        }
    }

    private val ed25519 = TestEd25519()
    private fun fakePublicKey(secret: ByteArray): ByteArray = Sha256.hash(secret)
    private fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }

    private val mySecret = fixedKey(0x11)
    private val myHex = fakePublicKey(mySecret).toHexLower()

    private val machineRootSecret = fixedKey(0x70)
    private val machineRootPk = fakePublicKey(machineRootSecret)
    private val machineRootHex = machineRootPk.toHexLower()

    private val sessionPk = fakePublicKey(fixedKey(0x90))
    private val sessionHex = sessionPk.toHexLower()

    private val issuedAt = 1_700_000_000L

    /** expires_at is checked against the real wall clock, so "unexpired" is 0 ("never") or
     *  an offset from the real now -- see SessionCertGateTest's note on the same. */
    private fun sessionCert(expiresAt: Long = 0L): String =
        DeviceCertCodec.encode(DeviceCertCodec.issue(ROLE_SESSION, machineRootPk, sessionPk, issuedAt, expiresAt, "", machineRootSecret, ed25519))

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private class SeededInvitationsStore(private val contacts: List<String>) : InvitationsStore {
        override fun load(): InvitationsBook = InvitationsBook(contacts = contacts)
        override fun save(book: InvitationsBook) = Unit
    }

    /**
     * `SessionCertGateTest`'s `IdentityTransport` plus a scripted [connectById]: each dial
     * by id is recorded in [byIdDials] and answered by [answer] -- a fresh [SessionPipe]
     * playing the session, or null for "unreachable".
     */
    private class SessionDialTransport(
        private val secret: ByteArray,
        private val fixedEndpointIdHex: String,
        private val delegate: FakeTransport = FakeTransport(),
    ) : IrohTransport by delegate {
        override val endpointId: String? get() = fixedEndpointIdHex
        override suspend fun myTicket(): String = "azula-mock-$fixedEndpointIdHex"
        override fun rootSecretBytes(): ByteArray = secret
        override fun secretKeyBytes(): ByteArray = secret
        override fun publicKeyFor(secret: ByteArray): ByteArray = Sha256.hash(secret)

        private val incomingConnections = Channel<IncomingConnection>(Channel.UNLIMITED)
        override fun incoming(): Flow<IncomingConnection> = incomingConnections.receiveAsFlow()
        suspend fun emit(conn: IncomingConnection) = incomingConnections.send(conn)

        val byIdDials = mutableListOf<Pair<String, String>>()
        var answer: (() -> SessionPipe)? = null
        var lastAnswered: SessionPipe? = null
            private set

        override suspend fun connectById(endpointId: String, alpn: String): P2pStream {
            byIdDials += endpointId to alpn
            val pipe = answer?.invoke() ?: throw RuntimeException("session unreachable")
            lastAnswered = pipe
            return pipe.stream
        }
    }

    /** One stream between the app and "the session": [push] queues a frame for the app to
     *  read, [drop] hangs up (the app reads EOF), and every frame the app sends is kept in
     *  [sent]. */
    private class SessionPipe(vararg queued: Frame) {
        private val toApp = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<Frame>()
        var closed = false
            private set
        val stream: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) {
                runCatching { FrameCodec.decode(line) }.getOrNull()?.let { sent.add(it) }
            }
            override suspend fun receiveLine(): String? = toApp.receiveCatching().getOrNull()
            override suspend fun sendBytes(bytes: ByteArray) = Unit
            override suspend fun receiveBytes(n: Int): ByteArray? = null
            override suspend fun close() {
                closed = true
                toApp.close()
            }
        }

        init {
            queued.forEach { push(it) }
        }

        fun push(frame: Frame) {
            toApp.trySend(FrameCodec.encode(frame))
        }

        fun drop() {
            toApp.close()
        }
    }

    /** A session that answers a dial the way azula-cli's `BridgeAcceptHandler` does: its
     *  own Hello, carrying its current session cert. */
    private fun answeringSession(cert: String = sessionCert()) = SessionPipe(Frame.Hello("bridge-session", cert = cert))

    private class Admitted(val state: AzulaState, val transport: SessionDialTransport, val inbound: SessionPipe)

    /** Starts the app and admits the session over an inbound LLM connection, as production does. */
    private suspend fun TestScope.admitSession(alpn: String = Alpns.LLM): Admitted {
        val transport = SessionDialTransport(mySecret, myHex)
        val azulaState = testAzulaState(
            backgroundScope, transport,
            invitationsStore = SeededInvitationsStore(listOf(machineRootHex)),
            eventLogStore = FileEventLogStore(File(tempDir, "log-${System.nanoTime()}"), ed25519) { myHex },
            ed25519 = ed25519,
        )
        azulaState.start()
        runCurrent()
        val inbound = SessionPipe(Frame.Hello(sessionHex, cert = sessionCert()))
        transport.emit(IncomingConnection(sessionHex, alpn, inbound.stream))
        runCurrent()
        assertTrue(azulaState.conv(sessionHex).online, "sanity: the session is admitted and online")
        return Admitted(azulaState, transport, inbound)
    }

    // ---- stream drop ----

    @Test
    fun droppedSessionIsRedialedByEndpointId() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)
        admitted.transport.answer = { answeringSession() }

        admitted.inbound.drop()
        runCurrent()
        assertFalse(cs.online)
        assertTrue(cs.reconnecting, "a dropped session conversation must start redialing, though it holds no ticket")

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(cs.online, "the session should be back online once it answers with a valid cert")
        assertFalse(cs.reconnecting)
        assertEquals(listOf(sessionHex to Alpns.LLM), admitted.transport.byIdDials, "dialed by the session's endpoint id, on the ALPN it was admitted on")
        val hello = admitted.transport.lastAnswered!!.sent.filterIsInstance<Frame.Hello>().single()
        assertEquals(myHex, hello.name, "the redial opens with our Hello, so the session recognises us")
    }

    @Test
    fun redialedSessionStillReceivesFramesAfterItsHello() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)
        // The session flushes queued mail right after its Hello; nothing behind the Hello may be lost.
        admitted.transport.answer = { answeringSession().also { it.push(Frame.Chat("queued while you were away")) } }

        admitted.inbound.drop()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(cs.online)
        assertTrue(cs.messages.any { it.text == "queued while you were away" }, "frames after the session's Hello must reach the conversation")
    }

    @Test
    fun redialRefreshesTheSessionExpiryFromTheFreshCert() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)
        cs.sessionExpiresAt = (nowSeconds() + 60) * 1000 // the cert the session was admitted with
        val renewedExpiry = nowSeconds() + 7 * 86_400 // a restarted named session minted a new one
        admitted.transport.answer = { answeringSession(sessionCert(expiresAt = renewedExpiry)) }

        admitted.inbound.drop()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(cs.online)
        assertEquals(renewedExpiry * 1000, cs.sessionExpiresAt)
    }

    @Test
    fun sessionAnsweringWithAnExpiredCertIsRefusedAndNotRetried() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)
        admitted.transport.answer = { answeringSession(sessionCert(expiresAt = nowSeconds() - 10)) }

        admitted.inbound.drop()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(cs.online, "a cert that no longer validates must never be wired")
        assertTrue(admitted.transport.lastAnswered!!.closed, "the refused stream is closed")
        assertFalse(cs.reconnecting, "a refusal ends the redial loop -- retrying can't change the answer")
        advanceTimeBy(75_000)
        runCurrent()
        assertEquals(1, admitted.transport.byIdDials.size)
    }

    @Test
    fun sessionThatHangsUpWithoutAHelloIsRetried() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)
        // First answer: accepted, then closed with nothing said (e.g. the process was mid-restart).
        var answers = 0
        admitted.transport.answer = { if (answers++ == 0) SessionPipe().also { it.drop() } else answeringSession() }

        admitted.inbound.drop()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(cs.online)
        assertTrue(cs.reconnecting, "a silent hang-up is unreachable, not a refusal -- keep trying")

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(cs.online)
        assertEquals(2, admitted.transport.byIdDials.size)
    }

    @Test
    fun unreachableSessionGivesUpAfterTheFullBackoff() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)

        admitted.inbound.drop()
        runCurrent()
        advanceTimeBy(75_000) // 1+2+4+8+15+15+15+15
        runCurrent()

        assertFalse(cs.reconnecting)
        assertFalse(cs.online)
        assertEquals(8, admitted.transport.byIdDials.size)
    }

    // ---- what is never redialed ----

    @Test
    fun expiredSessionIsNeverRedialed() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)
        cs.sessionExpiresAt = System.currentTimeMillis() - 1_000
        admitted.transport.answer = { answeringSession() }

        admitted.inbound.drop()
        runCurrent()
        assertFalse(cs.reconnecting, "an expired session is ended for connection purposes")
        admitted.state.openChatMobile(sessionHex)
        admitted.state.setForeground(false)
        admitted.state.setForeground(true)
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        assertFalse(cs.online)
        assertTrue(admitted.transport.byIdDials.isEmpty())
    }

    @Test
    fun disabledSessionIsNeverRedialed() = runTest {
        val admitted = admitSession()
        val cs = admitted.state.conv(sessionHex)
        admitted.transport.answer = { answeringSession() }

        admitted.state.setConversationEnabled(sessionHex, false)
        runCurrent()
        admitted.state.openChatMobile(sessionHex)
        admitted.state.setForeground(false)
        admitted.state.setForeground(true)
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        assertFalse(cs.online)
        assertFalse(cs.reconnecting)
        assertTrue(admitted.transport.byIdDials.isEmpty())
    }

    @Test
    fun terminalSessionIsLeftToItsHostsOwnRedial() = runTest {
        val admitted = admitSession(alpn = Alpns.TERM)
        val cs = admitted.state.conv(sessionHex)
        // Re-attachable, so the ordinary terminal gate alone would let it through.
        cs.termSessionId = "t-1"
        admitted.transport.answer = { answeringSession() }

        admitted.inbound.drop()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        assertFalse(cs.reconnecting, "a terminal host answers no Hello, so there'd be no cert to re-validate")
        assertTrue(admitted.transport.byIdDials.isEmpty())
    }

    // ---- the other triggers: opening, foreground, re-bind ----

    /** Drops the session while it's unreachable and lets the drop's own redial run out, so
     *  the conversation sits offline with nothing in flight -- isolating whichever trigger
     *  the test fires next. */
    private fun TestScope.leaveOfflineAndIdle(admitted: Admitted) {
        admitted.inbound.drop()
        runCurrent()
        advanceTimeBy(75_000)
        runCurrent()
        val cs = admitted.state.conv(sessionHex)
        check(!cs.online && !cs.reconnecting)
        admitted.transport.byIdDials.clear()
    }

    @Test
    fun openingAnOfflineSessionConversationRedialsIt() = runTest {
        val admitted = admitSession()
        leaveOfflineAndIdle(admitted)
        val cs = admitted.state.conv(sessionHex)
        admitted.transport.answer = { answeringSession() }

        admitted.state.openChatMobile(sessionHex)
        assertTrue(cs.reconnecting)
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(cs.online)
    }

    @Test
    fun foregroundResumeRedialsAnOfflineSession() = runTest {
        val admitted = admitSession()
        leaveOfflineAndIdle(admitted)
        val cs = admitted.state.conv(sessionHex)
        admitted.transport.answer = { answeringSession() }

        admitted.state.setForeground(false)
        admitted.state.setForeground(true)
        assertTrue(cs.reconnecting, "the foreground edge must redial offline sessions")
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(cs.online)
    }

    @Test
    fun foregroundResumeGivesEachSessionOnlyAShortBudget() = runTest {
        val admitted = admitSession()
        leaveOfflineAndIdle(admitted)
        val cs = admitted.state.conv(sessionHex)

        admitted.state.setForeground(false)
        admitted.state.setForeground(true)
        advanceTimeBy(75_000)
        runCurrent()

        assertFalse(cs.reconnecting)
        assertEquals(2, admitted.transport.byIdDials.size, "fan-out triggers try each session twice, not the full eight")
    }

    @Test
    fun transportRebindRedialsAnOfflineSession() = runTest {
        val admitted = admitSession()
        leaveOfflineAndIdle(admitted)
        val cs = admitted.state.conv(sessionHex)
        admitted.transport.answer = { answeringSession() }

        // What a real transport does at the end of every re-bind.
        admitted.transport.onCameOnline!!.invoke()
        runCurrent()
        assertTrue(cs.reconnecting, "a transport re-bind must redial offline sessions")
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(cs.online)
    }
}
