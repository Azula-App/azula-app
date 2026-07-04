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

    @Test
    fun helloInviteRoundTrips() {
        val encoded = FrameCodec.encode(Frame.Hello("nodeabc", invite = "azi123"))
        assertTrue(encoded.contains("\"invite\":\"azi123\""), "missing invite: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.Hello)
        assertEquals("azi123", (decoded as Frame.Hello).invite)
    }

    @Test
    fun helloWithoutInviteDefaultsNull() {
        // Old peers omit the field entirely — must stay wire-compatible.
        val decoded = FrameCodec.decode("""{"type":"hello","name":"nodeabc"}""")
        assertTrue(decoded is Frame.Hello)
        assertEquals(null, (decoded as Frame.Hello).invite)
    }

    @Test
    fun termAttachRoundTripsWithSession() {
        val encoded = FrameCodec.encode(Frame.TermAttach("sess-1"))
        assertTrue(encoded.contains("\"type\":\"term_attach\""), "missing type tag: $encoded")
        assertTrue(encoded.contains("\"session\":\"sess-1\""), "missing session: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.TermAttach)
        assertEquals("sess-1", (decoded as Frame.TermAttach).session)
    }

    @Test
    fun termAttachEncodesNullSessionExplicitly() {
        // The wire pin the Rust server relies on: a fresh attach must emit a
        // literal `"session":null`, not omit the field — Rust reads it as `None`.
        val encoded = FrameCodec.encode(Frame.TermAttach())
        assertTrue(encoded.contains("\"session\":null"), "expected explicit null: $encoded")
    }

    @Test
    fun termAttachDecodesExplicitNullSession() {
        val decoded = FrameCodec.decode("""{"type":"term_attach","session":null}""")
        assertTrue(decoded is Frame.TermAttach)
        assertEquals(null, (decoded as Frame.TermAttach).session)
    }

    @Test
    fun termAttachDefaultsSessionWhenOmitted() {
        val decoded = FrameCodec.decode("""{"type":"term_attach"}""")
        assertTrue(decoded is Frame.TermAttach)
        assertEquals(null, (decoded as Frame.TermAttach).session)
    }

    @Test
    fun termSessionRoundTrips() {
        val encoded = FrameCodec.encode(Frame.TermSession("sess-2", resumed = true))
        assertTrue(encoded.contains("\"type\":\"term_session\""), "missing type tag: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.TermSession)
        decoded as Frame.TermSession
        assertEquals("sess-2", decoded.session)
        assertEquals(true, decoded.resumed)
    }

    @Test
    fun termSessionResumedDefaultsFalse() {
        val decoded = FrameCodec.decode("""{"type":"term_session","session":"sess-3"}""")
        assertTrue(decoded is Frame.TermSession)
        assertEquals(false, (decoded as Frame.TermSession).resumed)
    }

    @Test
    fun termExitRoundTripsWithCode() {
        val encoded = FrameCodec.encode(Frame.TermExit("sess-4", code = 0))
        assertTrue(encoded.contains("\"type\":\"term_exit\""), "missing type tag: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.TermExit)
        decoded as Frame.TermExit
        assertEquals("sess-4", decoded.session)
        assertEquals(0, decoded.code)
    }

    @Test
    fun termExitDecodesNullCode() {
        val decoded = FrameCodec.decode("""{"type":"term_exit","session":"sess-5","code":null}""")
        assertTrue(decoded is Frame.TermExit)
        assertEquals(null, (decoded as Frame.TermExit).code)
    }
}
