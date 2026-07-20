import dev.azula.link.InviteCodec
import dev.azula.link.isInvite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared V1/V2 test vectors from azula-docs/openspec/specs/invitations/design.md. Every
 * suite (Kotlin here, Rust `azula-cli`, TS `azula-site`) must decode these
 * byte-for-byte identically and re-encode to the exact same string. The
 * ticket field is 32 opaque ASCII bytes ("azula-test-endpoint-ticket-bytes")
 * -- not a real ticket -- so this test never tries to dial or parse it.
 */
class InviteCodecTest {
    private val ticket = "azula-test-endpoint-ticket-bytes"
    private val inviteIdHex = "0123456789abcdef"
    private val issuedAt = 1767225600L // 2026-01-01T00:00:00Z

    private val v1Encoded = "aziaeaaci2fm6e2xtppnfk3saaaaaaaaabamf5hk3dbfv2gk43ufvsw4zdqn5uw45bnor" +
        "uwg23foqwwe6lumvzq"

    private val v2Encoded = "aziaebqci2fm6e2xtppnfk3sadjk4fiaabamf5hk3dbfv2gk43ufvsw4zdqn5uw4" +
        "5bnoruwg23foqwwe6lumvzz5oheqtxgf5642yvbxjxi3isnrlf3s6hhaoigqtcv2" +
        "ygzh7vzusgb3vwkh462lv36uw5677ocbvwjax6qyehqsocbmznec563lgkja4"

    private val v2SignatureHex = "9eb8e484ee62f7dcd62a1ba6e8da24d8acbb978e70390684c55d60d93feb9a48" +
        "c1dd6ca3f3da5d77ea5bbeffdc20d6c905fd0c10f093841665a4177db5994907"

    @Test
    fun decodesV1() {
        val p = InviteCodec.decode(v1Encoded)!!
        assertEquals(1, p.version)
        assertEquals(0, p.flags)
        assertEquals(inviteIdHex, p.fingerprint)
        assertEquals(issuedAt, p.issuedAt)
        assertEquals(0L, p.expiresAt)
        assertEquals(ticket, p.ticket.decodeToString())
        assertNull(p.signature)
        assertEquals(false, p.signed)
        assertEquals(false, p.singleUse)
    }

    @Test
    fun v1ReencodesByteIdentically() {
        val p = InviteCodec.decode(v1Encoded)!!
        assertEquals(v1Encoded, InviteCodec.encode(p))
    }

    @Test
    fun decodesV2() {
        val p = InviteCodec.decode(v2Encoded)!!
        assertEquals(1, p.version)
        assertEquals(3, p.flags)
        assertEquals(inviteIdHex, p.fingerprint)
        assertEquals(issuedAt, p.issuedAt)
        assertEquals(1767312000L, p.expiresAt)
        assertEquals(ticket, p.ticket.decodeToString())
        assertEquals(true, p.signed)
        assertEquals(true, p.singleUse)
        val sig = p.signature!!
        assertEquals(v2SignatureHex, sig.toHex())
        assertEquals(64, sig.size)
    }

    @Test
    fun v2ReencodesByteIdentically() {
        val p = InviteCodec.decode(v2Encoded)!!
        assertEquals(v2Encoded, InviteCodec.encode(p))
    }

    @Test
    fun rejectsUnsupportedVersion() {
        // Same as V1 but with the version byte flipped to 0x02.
        val p = InviteCodec.decode(v1Encoded)!!
        val badVersion = p.copy(version = 2)
        val reencoded = InviteCodec.encode(badVersion) // rebuild bytes with version=2, then re-decode
        assertNull(InviteCodec.decode(reencoded))
    }

    @Test
    fun rejectsTruncatedPayload() {
        // Chop the encoded string well short -- decodes to fewer bytes than the
        // fixed header, or a ticket_len that overruns what's left.
        assertNull(InviteCodec.decode(v1Encoded.take(10)))
        assertNull(InviteCodec.decode(v2Encoded.dropLast(20)))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(InviteCodec.decode("not-an-invite"))
        assertNull(InviteCodec.decode(""))
        assertNull(InviteCodec.decode("azi***"))
    }

    @Test
    fun isInviteRecognizesPrefix() {
        assertTrue(isInvite(v1Encoded))
        assertTrue(isInvite("  $v1Encoded  "))
        assertEquals(false, isInvite("https://azula.app/s/abc"))
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
