import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.state.KnownContactDevice
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.allEntries
import dev.azula.sync.decodeMessageOutBody
import dev.azula.sync.toHexLower
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * multi-device-identity task 7.4 ("Delivery to Any Device Counts for the Identity"):
 * [dev.azula.state.ConnectService.dialKnownDeviceForSend]'s dial-ordering/first-success/
 * retry-id contract. Driven end to end through [testAzulaState]'s public
 * [dev.azula.state.AzulaState] surface against a real [FileEventLogStore], same posture
 * as `AccountSyncWiringTest.kt`.
 *
 * These tests seed [dev.azula.state.ConversationState.knownDevices] directly rather than
 * populating it through a real certificate handshake (task 7.3's merge path, which is
 * what would populate it for real -- see `CertKnownPeerTest.kt`): the dial-ordering
 * contract under test here is independent of how the address book got filled in.
 *
 * Each test needs a real conversation to seed [dev.azula.state.ConversationState.knownDevices]
 * onto before simulating "every device just dropped." The scaffold dial's OWN stream is
 * torn down via [DroppableStream.drop] -- a genuine EOF that runs
 * [dev.azula.state.ConnectService.receiveLoop]'s real teardown, which flips
 * `ConversationState.online` and `.stream` together -- rather than poking `.stream = null`
 * directly (`AccountSyncWiringTest`'s own "everything down" test takes that shortcut, but
 * never asserts on `.online` afterward, so it never surfaced that the shortcut leaves
 * `.online` stale; these tests DO assert on `.online`, so they need the faithful drop,
 * matching `ConnectServiceReconnectTest`'s own established `DroppableStream`/`drop()`
 * convention for exactly this scenario).
 */
class MultiDeviceSendTest {
    private val tempDir = createTempDirectory("multi-device-send-test").toFile()

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
    private val myPk = fakePublicKey(mySecret)
    private val myHex = myPk.toHexLower()

    private fun newStore() = FileEventLogStore(File(tempDir, "log-${System.nanoTime()}"), ed25519) { myHex }

    /** Mirrors `ConnectServiceReconnectTest`'s own `DroppableStream` (a different,
     *  non-`@jvm` compilation unit, so it can't be reused directly here): [drop]
     *  simulates the peer hanging up cleanly, so `receiveLine` returns null (EOF) --
     *  exactly what [dev.azula.state.ConnectService.receiveLoop]'s `line ?: break`
     *  expects from a dropped connection, letting its own teardown run for real. */
    private class DroppableStream : P2pStream {
        private val inbox = Channel<String>(Channel.UNLIMITED)
        override suspend fun sendLine(line: String) = Unit
        override suspend fun receiveLine(): String? = inbox.receiveCatching().getOrNull()
        override suspend fun sendBytes(bytes: ByteArray) = Unit
        override suspend fun receiveBytes(n: Int): ByteArray? = null
        override suspend fun close() { inbox.close() }
        fun drop() { inbox.close() }
    }

    /** A fixed, [TestEd25519]-consistent identity (like `CertKnownPeerTest`'s
     *  `IdentityTransport`) whose [connect] additionally fails outright for any ticket
     *  in [downTickets] (simulating an unreachable device) and records every ticket
     *  dialed, in order, into [dialedTickets] -- what these tests assert dial order on.
     *  A CHAT-alpn dial for a ticket in [droppableTickets] gets a [DroppableStream]
     *  instead of a normal loopback, so the test can [DroppableStream.drop] it later
     *  (see this file's class kdoc for why that matters). [delegate] is exposed so a
     *  test can inspect [FakeTransport.sentFrames] to check exactly what was shipped
     *  over the winning connection. */
    private class DialOrderTransport(
        private val secret: ByteArray,
        private val fixedNodeIdHex: String,
        private val downTickets: Set<String>,
        private val droppableTickets: Set<String> = emptySet(),
        val delegate: FakeTransport = FakeTransport(),
    ) : IrohTransport by delegate {
        override val nodeId: String? get() = fixedNodeIdHex
        override fun rootSecretBytes(): ByteArray = secret
        override fun secretKeyBytes(): ByteArray = secret
        override fun publicKeyFor(secret: ByteArray): ByteArray = Sha256.hash(secret)

        val dialedTickets = mutableListOf<String>()
        val droppableChatStreams = mutableMapOf<String, DroppableStream>()

        override suspend fun connect(ticket: String, alpn: String): P2pStream {
            dialedTickets.add(ticket)
            if (ticket in downTickets) error("device down: $ticket")
            if (alpn == Alpns.CHAT && ticket in droppableTickets) {
                return DroppableStream().also { droppableChatStreams[ticket] = it }
            }
            return delegate.connect(ticket, alpn)
        }
    }

    @Test
    fun dialsInteractiveDevicesMostRecentlySeenFirstAndStopsAtTheFirstSuccessReusingTheSameChatId() = runTest {
        val store = newStore()
        val transport = DialOrderTransport(mySecret, myHex, downTickets = setOf("ticket-recent"), droppableTickets = setOf("ticket-scaffold"))
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        // A dedicated scaffold ticket -- distinct from the redial candidates below --
        // materializes the conversation; its own stream is then genuinely dropped
        // (see this file's class kdoc) rather than the test poking `.stream` directly.
        azulaState.connectPeer("ticket-scaffold")
        runCurrent()
        val convId = azulaState.conversations.single().id
        transport.droppableChatStreams.getValue("ticket-scaffold").drop()
        runCurrent()
        assertFalse(azulaState.conv(convId).online, "sanity: dropping the scaffold's stream must mark the conversation offline")

        // Simulate every device having just dropped, with a dial address book already
        // populated (task 7.3's merge is the realistic populator -- see
        // CertKnownPeerTest -- this test is purely about dial ORDER).
        transport.dialedTickets.clear()
        azulaState.conv(convId).knownDevices["recenthex"] = KnownContactDevice(ticket = "ticket-recent", lastSeenMs = 3000, isMailbox = false)
        azulaState.conv(convId).knownDevices["olderhex"] = KnownContactDevice(ticket = "ticket-older", lastSeenMs = 1000, isMailbox = false)
        azulaState.conv(convId).knownDevices["mailboxhex"] = KnownContactDevice(ticket = "ticket-mailbox", lastSeenMs = 9999, isMailbox = true)

        azulaState.updateDraft(convId, "are you there?")
        azulaState.send(convId)
        runCurrent()

        assertEquals(
            listOf("ticket-recent", "ticket-older"),
            transport.dialedTickets,
            "must try the most-recently-seen interactive device first, then the next -- and must NOT reach the mailbox device once an interactive one succeeds",
        )
        assertTrue(azulaState.conv(convId).online, "the successful device's stream must be wired in as the conversation's live stream")

        val out = store.allEntries().filter { it.knownKind == EventKind.MESSAGE_OUT }.mapNotNull { decodeMessageOutBody(it.body) }
        val logged = out.singleOrNull { it.conversation == convId && it.text == "are you there?" }
        assertTrue(logged != null, "message_out must be logged regardless of the retry, got $out")

        val sentChat = generateSequence { transport.delegate.sentFrames.tryReceive().getOrNull() }
            .filterIsInstance<Frame.Chat>()
            .firstOrNull { it.text == "are you there?" }
        assertEquals(logged!!.id, sentChat?.id, "the retried wire Chat.id must be the SAME one already logged as message_out -- not a fresh id per attempt")
    }

    @Test
    fun fallsBackToTheMailboxDeviceOnlyAfterEveryInteractiveDeviceIsExhausted() = runTest {
        val store = newStore()
        val transport = DialOrderTransport(
            mySecret, myHex,
            downTickets = setOf("ticket-recent", "ticket-older"),
            droppableTickets = setOf("ticket-scaffold"),
        )
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-scaffold")
        runCurrent()
        val convId = azulaState.conversations.single().id
        transport.droppableChatStreams.getValue("ticket-scaffold").drop()
        runCurrent()
        assertFalse(azulaState.conv(convId).online, "sanity: dropping the scaffold's stream must mark the conversation offline")

        transport.dialedTickets.clear()
        // The mailbox has a HIGHER lastSeenMs than either interactive device, on
        // purpose: mailbox-last is unconditional, not merely "least recently seen".
        azulaState.conv(convId).knownDevices["recenthex"] = KnownContactDevice(ticket = "ticket-recent", lastSeenMs = 3000, isMailbox = false)
        azulaState.conv(convId).knownDevices["olderhex"] = KnownContactDevice(ticket = "ticket-older", lastSeenMs = 1000, isMailbox = false)
        azulaState.conv(convId).knownDevices["mailboxhex"] = KnownContactDevice(ticket = "ticket-mailbox", lastSeenMs = 9999, isMailbox = true)

        azulaState.updateDraft(convId, "phone and laptop are both down")
        azulaState.send(convId)
        runCurrent()

        assertEquals(
            listOf("ticket-recent", "ticket-older", "ticket-mailbox"),
            transport.dialedTickets,
            "both interactive devices must be exhausted first, even though the mailbox's own lastSeenMs is higher",
        )
        assertTrue(azulaState.conv(convId).online, "the mailbox's stream must be wired in once it's the only device left")
    }

    @Test
    fun messageOutIsLoggedEvenWhenEveryKnownDeviceIsDown() = runTest {
        val store = newStore()
        val transport = DialOrderTransport(
            mySecret, myHex,
            downTickets = setOf("ticket-recent", "ticket-older", "ticket-mailbox"),
            droppableTickets = setOf("ticket-scaffold"),
        )
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("ticket-scaffold")
        runCurrent()
        val convId = azulaState.conversations.single().id
        assertTrue(azulaState.conv(convId).online, "sanity: the scaffold dial wired the conversation online")

        // Genuinely drop the scaffold's stream -- not just null out
        // ConversationState.stream directly -- so ConnectService's own receiveLoop
        // teardown runs and flips `online` together with `stream`, exactly like a real
        // dropped connection (see this file's class kdoc).
        transport.droppableChatStreams.getValue("ticket-scaffold").drop()
        runCurrent()
        assertFalse(azulaState.conv(convId).online, "sanity: the scaffold's drop must actually mark the conversation offline")

        transport.dialedTickets.clear()
        azulaState.conv(convId).knownDevices["recenthex"] = KnownContactDevice(ticket = "ticket-recent", lastSeenMs = 3000, isMailbox = false)
        azulaState.conv(convId).knownDevices["olderhex"] = KnownContactDevice(ticket = "ticket-older", lastSeenMs = 1000, isMailbox = false)
        azulaState.conv(convId).knownDevices["mailboxhex"] = KnownContactDevice(ticket = "ticket-mailbox", lastSeenMs = 500, isMailbox = true)

        azulaState.updateDraft(convId, "anyone home?")
        azulaState.send(convId)
        runCurrent()

        assertEquals(listOf("ticket-recent", "ticket-older", "ticket-mailbox"), transport.dialedTickets, "every known device must be tried")
        assertFalse(azulaState.conv(convId).online, "nothing came up -- the conversation stays offline")

        val out = store.allEntries().filter { it.knownKind == EventKind.MESSAGE_OUT }.mapNotNull { decodeMessageOutBody(it.body) }
        assertTrue(out.any { it.conversation == convId && it.text == "anyone home?" }, "message_out must still be logged even though every device is down, got $out")
    }
}
