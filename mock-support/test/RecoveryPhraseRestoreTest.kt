@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.core.RecoveryPhrase
import dev.azula.mock.FakeTransport
import dev.azula.net.Alpns
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
 * Extends [FakeTransportTest.secretKeyRoundTripsAsRecoveryPhrase] (which round-trips
 * the encode/decode codec directly against the transport) up to the actual restore
 * path the Settings UI drives: [AzulaState.exportRecoveryPhrase] /
 * [AzulaState.importRecoveryPhrase] (backed by [dev.azula.state.ConnectService]).
 */
class RecoveryPhraseRestoreTest {

    @Test
    fun exportedPhraseMatchesTheTransportKey() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val phrase = azulaState.exportRecoveryPhrase()
        assertNotNull(phrase, "a bound transport should always have a recovery phrase")
        assertEquals(24, phrase.trim().split(" ").size)
        assertTrue(transport.secretKeyBytes().contentEquals(RecoveryPhrase.decode(phrase)!!))
    }

    @Test
    fun importingAnInvalidPhraseFailsWithoutTouchingIdentity() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()
        val nodeIdBefore = azulaState.nodeId

        val ok = azulaState.importRecoveryPhrase("not a real recovery phrase at all")
        runCurrent()

        assertFalse(ok, "a malformed phrase must be rejected")
        assertEquals(nodeIdBefore, azulaState.nodeId, "a rejected phrase must not touch the identity")
    }

    @Test
    fun importingAValidPhraseRebindsAndRefreshesIdentity() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val key = ByteArray(32) { (it * 7).toByte() }
        val phrase = RecoveryPhrase.encode(key)

        val ok = azulaState.importRecoveryPhrase(phrase)
        runCurrent()

        assertTrue(ok, "a well-formed, checksum-valid phrase must be accepted")
        // FakeTransport.importSecretKey is a no-op, so this asserts that the
        // ConnectService restore glue re-reads nodeId/myCode from the transport
        // after import -- not that the fake transport's identity itself changed.
        assertEquals(transport.nodeId, azulaState.nodeId)
    }
}

private val RESTORE_KEY = ByteArray(32) { (it * 7).toByte() }
private val VALID_PHRASE = RecoveryPhrase.encode(RESTORE_KEY)

/**
 * What [RecoveryPhraseRestoreTest] structurally cannot cover: every test there runs
 * against [FakeTransport], whose `importSecretKey` is a no-op, so the restore glue is
 * only ever exercised against a transport that cannot fail and never actually
 * re-binds. Both bugs below shipped behind exactly that blind spot -- a release build
 * where the FFI layer was unusable crashed the process on restore, and a successful
 * restore silently killed inbound connections until the app was relaunched.
 */
class RecoveryPhraseRestoreRebindTest {

    @Test
    fun aTransportThatFailsToRebindDoesNotPropagateOutOfRestore() = runTest {
        val transport = RebindingTransport(failImport = true)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        // Both callers (the setup step and the Settings dialog) run this inside a
        // `rememberCoroutineScope().launch`, which has no CoroutineExceptionHandler --
        // so anything escaping here terminates the process rather than showing an error.
        val ok = azulaState.importRecoveryPhrase(VALID_PHRASE)
        runCurrent()

        // True, not false: the phrase decoded and importSecretKey persists the key
        // before it re-binds, so the restore did commit -- it's the transport that
        // failed to come back up. Reporting false would surface "that isn't a valid
        // recovery phrase" for a phrase that was perfectly valid.
        assertTrue(ok, "a valid phrase must still report success when only the re-bind failed")
    }

    @Test
    fun startBindsExactlyOnceEvenWhenCalledTwice() = runTest {
        val transport = RebindingTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        // Android does exactly this: AzulaApplication.obtainState() starts the state,
        // then the composition's SetupGate starts it again.
        azulaState.start()
        azulaState.start()
        runCurrent()

        assertEquals(1, transport.bindCount, "a second start() must not bind a second endpoint on the same key")
    }

    @Test
    fun inboundIsStillAcceptedAfterARestoreRebind() = runTest {
        val transport = RebindingTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        assertTrue(azulaState.importRecoveryPhrase(VALID_PHRASE))
        runCurrent()

        // A stranger's LLM stream is closed outright by the gate, which makes it a
        // cheap timeout-free probe that the connection reached ConnectService at all.
        // While the accept loop stayed bound to the torn-down endpoint, nothing ever
        // consumed this and `closed` stayed false until relaunch.
        val stream = ClosableStream()
        transport.emitInbound(IncomingConnection("stranger-after-restore", Alpns.LLM, stream))
        runCurrent()

        assertTrue(stream.closed, "inbound must still be accepted after a restore re-bound the endpoint")
    }
}

private class ClosableStream : P2pStream {
    var closed = false
    override suspend fun sendLine(line: String) = Unit
    override suspend fun receiveLine(): String? = null
    override suspend fun sendBytes(bytes: ByteArray) = Unit
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() { closed = true }
}

/**
 * A transport that actually models the re-bind, which [FakeTransport] does not.
 *
 * The important detail is [incoming]: it hands out a flow over whichever inbound
 * queue is current *at collection time*, so a re-bind strands any existing
 * collector — exactly how the real transport behaves, where the accept loop holds
 * the endpoint it was collected against and a torn-down endpoint just reports a
 * clean close. [failImport] simulates a transport that cannot re-bind at all (the
 * shape of a release build whose FFI layer is unusable).
 */
private class RebindingTransport(private val failImport: Boolean = false) : IrohTransport {
    override var nodeId: String? = "self"
    override var onCameOnline: (() -> Unit)? = null

    private var inbound = Channel<IncomingConnection>(Channel.UNLIMITED)

    var bindCount = 0
        private set

    override suspend fun bind(alpns: List<String>) {
        bindCount++
        onCameOnline?.invoke()
    }

    override suspend fun importSecretKey(bytes: ByteArray) {
        if (failImport) throw RuntimeException("endpoint could not be re-bound")
        // Mirrors IrohFfiTransport: persist, tear the old endpoint down, bind a new one.
        inbound.close()
        inbound = Channel(Channel.UNLIMITED)
        nodeId = "self-restored"
        bind(emptyList())
    }

    suspend fun emitInbound(conn: IncomingConnection) = inbound.send(conn)

    override fun incoming(): Flow<IncomingConnection> = inbound.receiveAsFlow()

    override suspend fun myTicket(): String = "ticket-$nodeId"
    override fun secretKeyBytes(): ByteArray = RESTORE_KEY
    override fun peerIdForTicket(ticket: String): String? = null
    override suspend fun connect(ticket: String, alpn: String): P2pStream =
        throw RuntimeException("this transport dials nothing")
    override suspend fun shutdown() = Unit
}
