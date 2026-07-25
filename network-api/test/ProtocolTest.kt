import dev.azula.net.Contact
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IdentityBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    // --- Sync / link frames (task 5.1) --------------------------------------
    // These literals are pinned against src/proto.rs's serde tests: both
    // languages must produce and parse byte-identical JSON.

    @Test
    fun syncHelloRoundTripsWithTypeTag() {
        val encoded = FrameCodec.encode(Frame.SyncHello("azd-example-cert"))
        assertEquals("""{"type":"sync_hello","cert":"azd-example-cert"}""", encoded)
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.SyncHello)
        assertEquals("azd-example-cert", (decoded as Frame.SyncHello).cert)
    }

    @Test
    fun syncVectorRoundTripsWithTypeTag() {
        val encoded = FrameCodec.encode(Frame.SyncVector(mapOf("aabb" to 3L)))
        assertEquals("""{"type":"sync_vector","vector":{"aabb":3}}""", encoded)
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.SyncVector)
        assertEquals(mapOf("aabb" to 3L), (decoded as Frame.SyncVector).vector)
    }

    @Test
    fun syncEntriesRoundTripsWithTypeTag() {
        val encoded = FrameCodec.encode(Frame.SyncEntries(listOf("ZW50cnkx", "ZW50cnky")))
        assertEquals("""{"type":"sync_entries","entries":["ZW50cnkx","ZW50cnky"]}""", encoded)
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.SyncEntries)
        assertEquals(listOf("ZW50cnkx", "ZW50cnky"), (decoded as Frame.SyncEntries).entries)
    }

    @Test
    fun syncAckRoundTripsWithTypeTag() {
        val encoded = FrameCodec.encode(Frame.SyncAck(mapOf("ccdd" to 7L)))
        assertEquals("""{"type":"sync_ack","vector":{"ccdd":7}}""", encoded)
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.SyncAck)
        assertEquals(mapOf("ccdd" to 7L), (decoded as Frame.SyncAck).vector)
    }

    @Test
    fun linkHelloRoundTripsWithTypeTag() {
        val encoded = FrameCodec.encode(Frame.LinkHello("abcd1234", "laptop", 1))
        assertEquals("""{"type":"link_hello","devicePk":"abcd1234","name":"laptop","roles":1}""", encoded)
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.LinkHello)
        decoded as Frame.LinkHello
        assertEquals("abcd1234", decoded.devicePk)
        assertEquals("laptop", decoded.name)
        assertEquals(1, decoded.roles)
    }

    @Test
    fun linkGrantRoundTripsWithTypeTagAndOmitsAbsentMailbox() {
        val bundle = IdentityBundle(
            rootPk = "root-hex",
            certs = listOf("azd1"),
            revocations = emptyList(),
            contacts = listOf(Contact(rootPk = "contact-root", name = "Alice")),
            mailbox = "mailbox-ticket",
        )
        val encoded = FrameCodec.encode(Frame.LinkGrant("azd-mine", bundle))
        assertEquals(
            """{"type":"link_grant","cert":"azd-mine","bundle":{"rootPk":"root-hex","certs":["azd1"],""" +
                """"revocations":[],"contacts":[{"rootPk":"contact-root","name":"Alice"}],""" +
                """"mailbox":"mailbox-ticket"}}""",
            encoded,
        )
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.LinkGrant)
        decoded as Frame.LinkGrant
        assertEquals("azd-mine", decoded.cert)
        assertEquals(bundle, decoded.bundle)
    }

    @Test
    fun linkGrantBundleOmitsMailboxAndUsesNodeIdContactWhenAbsent() {
        val bundle = IdentityBundle(
            rootPk = "root-hex",
            certs = emptyList(),
            revocations = emptyList(),
            contacts = listOf(Contact(nodeId = "legacy-node")),
        )
        val encoded = FrameCodec.encode(Frame.LinkGrant("azd-mine", bundle))
        assertEquals(
            """{"type":"link_grant","cert":"azd-mine","bundle":{"rootPk":"root-hex","certs":[],""" +
                """"revocations":[],"contacts":[{"nodeId":"legacy-node"}]}}""",
            encoded,
        )
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.LinkGrant)
        assertEquals(bundle, (decoded as Frame.LinkGrant).bundle)
    }

    @Test
    fun contactWithoutNameOmitsNameField() {
        // Exactly one of rootPk/nodeId, no name: pinned as {"nodeId":"legacy-node"}
        // with no other keys present.
        val encoded = FrameCodec.json.encodeToString(Contact.serializer(), Contact(nodeId = "legacy-node"))
        assertEquals("""{"nodeId":"legacy-node"}""", encoded)
    }

    @Test
    fun linkRejectRoundTripsWithTypeTag() {
        val encoded = FrameCodec.encode(Frame.LinkReject("user declined"))
        assertEquals("""{"type":"link_reject","reason":"user declined"}""", encoded)
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.LinkReject)
        assertEquals("user declined", (decoded as Frame.LinkReject).reason)
    }

    // --- Hello.cert / Chat.id (task 7.1) -------------------------------------

    @Test
    fun helloCertRoundTrips() {
        val encoded = FrameCodec.encode(Frame.Hello("nodeabc", cert = "azd-my-cert"))
        assertTrue(encoded.contains(""""cert":"azd-my-cert""""), "missing cert: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.Hello)
        assertEquals("azd-my-cert", (decoded as Frame.Hello).cert)
    }

    @Test
    fun helloCertOmittedFromJsonWhenAbsent() {
        val encoded = FrameCodec.encode(Frame.Hello("nodeabc"))
        assertTrue(!encoded.contains("cert"), "cert should be absent (not even null): $encoded")
    }

    @Test
    fun helloWithoutCertFieldStillParses() {
        // Legacy-peer compatibility: a frame from a build that doesn't know
        // about `cert` at all must still parse, with cert defaulting to null.
        val decoded = FrameCodec.decode("""{"type":"hello","name":"bob"}""")
        assertTrue(decoded is Frame.Hello)
        assertNull((decoded as Frame.Hello).cert)
    }

    @Test
    fun chatIdRoundTrips() {
        val encoded = FrameCodec.encode(Frame.Chat("hello", id = "0123456789abcdef0123456789abcdef"))
        assertTrue(encoded.contains(""""id":"0123456789abcdef0123456789abcdef""""), "missing id: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.Chat)
        assertEquals("0123456789abcdef0123456789abcdef", (decoded as Frame.Chat).id)
    }

    @Test
    fun chatIdOmittedFromJsonWhenAbsent() {
        val encoded = FrameCodec.encode(Frame.Chat("hello"))
        assertTrue(!encoded.contains(""""id""""), "id should be absent (not even null): $encoded")
    }

    @Test
    fun chatWithoutIdFieldStillParses() {
        // Legacy-peer compatibility: a frame from a build that doesn't know
        // about `id` at all must still parse, with id defaulting to null.
        val decoded = FrameCodec.decode("""{"type":"chat","text":"hello"}""")
        assertTrue(decoded is Frame.Chat)
        assertNull((decoded as Frame.Chat).id)
    }

    @Test
    fun relayHintRoundTripsWithTypeTag() {
        val encoded = FrameCodec.encode(Frame.RelayHint("ticket-abc123"))
        assertTrue(encoded.contains(""""type":"relay_hint""""), "missing type tag: $encoded")
        val decoded = FrameCodec.decode(encoded)
        assertTrue(decoded is Frame.RelayHint)
        assertEquals("ticket-abc123", (decoded as Frame.RelayHint).ticket)
    }
}
