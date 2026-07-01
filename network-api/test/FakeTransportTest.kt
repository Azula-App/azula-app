import dev.azula.net.Alpns
import dev.azula.net.FakeTransport
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
}
