import dev.azula.core.RecoveryPhrase
import dev.azula.net.Alpns
import dev.azula.mock.FakeTransport
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the network layer is mockable in isolation: everything above the transport
 * depends only on the [IrohTransport] interface, so a test can drive the whole path
 * with [FakeTransport] — no iroh binding, no native lib, no real peer.
 */
class FakeTransportTest {
    @Test fun mockedTransportBindsAndLoopsBackChat() = runTest {
        val transport: IrohTransport = FakeTransport()
        transport.bind(listOf(Alpns.CHAT))
        assertNotNull(transport.nodeId, "bind should assign a node id")

        val stream = transport.connect(ticket = "any-ticket", alpn = Alpns.CHAT)
        stream.sendLine(FrameCodec.encode(Frame.Chat("hi")))

        // The loopback greets on first send, then echoes the message back.
        val greeting = FrameCodec.decode(stream.receiveLine()!!)
        val echo = FrameCodec.decode(stream.receiveLine()!!)
        assertTrue(greeting is Frame.Chat, "first frame should be the loopback greeting")
        assertTrue(echo is Frame.Chat && echo.text.contains("echo"), "second frame should echo the message")
    }

    @Test fun secretKeyRoundTripsAsRecoveryPhrase() = runTest {
        // The transport seam (secretKeyBytes) + the BIP-39 codec round-trip: the
        // key a device exports as a phrase decodes back to the same 32 bytes.
        val transport: IrohTransport = FakeTransport()
        val key = transport.secretKeyBytes()!!
        assertEquals(32, key.size)
        val phrase = RecoveryPhrase.encode(key)
        assertEquals(24, phrase.trim().split(" ").size)
        assertTrue(key.contentEquals(RecoveryPhrase.decode(phrase)), "phrase should decode to the original key")
        transport.importSecretKey(key) // mock no-op — must not throw
    }

    @Test fun signAndTicketStubsAreDeterministicAndRoundTrip() = runTest {
        val transport: IrohTransport = FakeTransport()

        val data = "hello".encodeToByteArray()
        val sig1 = transport.signWithNodeKey(data)
        val sig2 = transport.signWithNodeKey(data)
        assertNotNull(sig1)
        assertEquals(64, sig1.size)
        assertTrue(sig1.contentEquals(sig2), "signing the same data twice should be deterministic")
        assertTrue(transport.verifyNodeSignature("anynode", data, sig1), "mock always trusts")

        val ticket = "azula-mock-ticket-abc123"
        val bytes = transport.ticketBytes(ticket)
        assertNotNull(bytes)
        assertEquals(ticket, transport.ticketFromBytes(bytes))
    }
}
