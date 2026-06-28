import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun chatRoundTrips() {
        val encoded = FrameCodec.encode(Frame.Chat("hello"))
        // The wire format must be internally tagged on "type" so the Rust server
        // (#[serde(tag = "type")]) can parse it.
        assertTrue(encoded.contains("\"type\":\"chat\""), "missing type tag: $encoded")
        assertTrue(encoded.contains("\"text\":\"hello\""), "missing text: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.Chat)
        assertEquals("hello", (decoded as Frame.Chat).text)
    }

    @Test
    fun tokenStreamFrame() {
        val decoded = FrameCodec.decode("""{"type":"token","delta":"hi","done":false}""")
        assertTrue(decoded is Frame.Token)
        decoded as Frame.Token
        assertEquals("hi", decoded.delta)
        assertEquals(false, decoded.done)
    }

    @Test
    fun tokenDoneDefaults() {
        // Rust may omit `done`; default must decode to false.
        val decoded = FrameCodec.decode("""{"type":"token","delta":""}""")
        assertTrue(decoded is Frame.Token)
        assertEquals(false, (decoded as Frame.Token).done)
    }

    @Test
    fun termAndInputFrames() {
        assertTrue(FrameCodec.decode("""{"type":"term","line":"ok"}""") is Frame.Term)
        assertTrue(FrameCodec.encode(Frame.Input("ls\n")).contains("\"type\":\"input\""))
    }
}
