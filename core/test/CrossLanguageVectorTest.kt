import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.EventKind
import dev.azula.core.LogEntryCodec
import dev.azula.core.ROLE_MAILBOX
import dev.azula.core.Sha256
import dev.azula.core.ZERO_PREV_HASH
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-language test vector (task 2.4): the exact `azd`/`azr` strings and the three-entry
 * log Rust produced with real Ed25519 (never `FakeEd25519`) from fixed seeds
 * `certs.rs::fixtures::{ROOT_SEED, DEVICE_SEED}` and `eventlog.rs::fixtures::DEVICE_A_SEED`.
 * See `azula-docs/openspec/changes/multi-device-identity/design.md`'s "Cross-Language Test
 * Vectors" section. Kotlin has no real Ed25519 in `core` (see `FakeEd25519`'s kdoc), so this
 * test does not re-derive these strings -- it decodes them, checks every field, and re-encodes,
 * proving the wire layout (field offsets/widths/endianness, base32 alphabet, base64 framing)
 * agrees byte-for-byte with Rust without needing real signature verification here.
 */
@OptIn(ExperimentalEncodingApi::class)
class CrossLanguageVectorTest {
    private val rootPkHex = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
    private val devicePkHex = "29acbae141bccaf0b22e1a94d34d0bc7361e526d0bfe12c89794bc9322966dd7"
    private val deviceAPkHex = "174553b456dddfc6908ecab1c101fe6ab21e2baa0617795b7d43a63482993fd5"

    private val issuedAt = 1700000000L
    private val revokedAt = 1700100000L
    private val certName = "phone"

    private val certStr = "azdaeaqhiihx7z44ef6dvyn2ghhjpajsz7e2yyjxjinl4o5zbtecjktdobjvs5ocqn4zlylelq2stju2c6hgypfe3il7yjmrf4uxsjsfftn25svh4iaaaaaaaafobug63tfmaoceghoxhw2izrder7gokgmn5mtfm6dcccvqx376ddtmdz5swwdr4wykbkbc6bepxvgiufuqe2anhvsakshdxcriw5q2yly35bjidq"
    private val revocationStr = "azraeb2cb576phbbpq5odorrz2lycmwpzgwgcn2kdk7dxoimzaskuy3qknmxlqudpgk6czc4guu2ngqxrzwdzjg2c76clejpff4smrjm3oxmvkxpiaxs7pd5e3ex3xekzy7yomqy3hyyshxcsw275gv7gbi45c7buxlsjmywp7nnwtixxhfixig32cgjge624upt6tztt2eqovmqiroq4ma4"

    private val baseTsMs = 1700000000000L
    private val body1 = "{\"conversation\":\"cafebabe\",\"text\":\"hello\"}"
    private val body2 = "{\"conversation\":\"cafebabe\",\"text\":\"how are you?\"}"
    private val body3 = "{\"conversation\":\"cafebabe\",\"up_to_lamport\":2}"
    private val e1B64 = "AQEXRVO0Vt3fxpCOyrHBAf5qsh4rqgYXeVt9Q6Y0gpk/1QAAAAAAAAABAAAAAAAAAAEAAAGLz+VoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKnsiY29udmVyc2F0aW9uIjoiY2FmZWJhYmUiLCJ0ZXh0IjoiaGVsbG8ifcGRps3rZKzaqY89ynl1jzSQHWo5uV7rH7c99fvWTKXitnG6toGFF22x7msZ8FfCOM5pI8np8hIEcoKU6zpD3g8="
    private val e2B64 = "AQEXRVO0Vt3fxpCOyrHBAf5qsh4rqgYXeVt9Q6Y0gpk/1QAAAAAAAAACAAAAAAAAAAIAAAGLz+Vr6JPE2ChDzHnzP1NBar+E+2Eot1hBahj0YSL63ueXvfF+AAAAMXsiY29udmVyc2F0aW9uIjoiY2FmZWJhYmUiLCJ0ZXh0IjoiaG93IGFyZSB5b3U/In3kE3/9rXJwLJWL1Pi6E0zhf0U/JhmcFx5J0XMo1c/TXIXHlESSlq7nv/F9a98bySkg0wbjfO6k4BXOKq7Q6ewC"
    private val e3B64 = "AQMXRVO0Vt3fxpCOyrHBAf5qsh4rqgYXeVt9Q6Y0gpk/1QAAAAAAAAADAAAAAAAAAAMAAAGLz+Vv0JKxBpleoFRawCUpkr1rwKJsTtMBX2QLTCs9/CIkuocSAAAALXsiY29udmVyc2F0aW9uIjoiY2FmZWJhYmUiLCJ1cF90b19sYW1wb3J0IjoyfYGB/vAipgFl+KPw+qRjJ1Ei+/BX2gcb7DBqCp5OY/i01eZnE4ixyjLEKCzwG2yZ+pcXpkoPcb6dv0gcpWRPAgE="
    private val h1Hex = "93c4d82843cc79f33f53416abf84fb6128b758416a18f46122fadee797bdf17e"
    private val h2Hex = "92b106995ea0545ac0252992bd6bc0a26c4ed3015f640b4c2b3dfc2224ba8712"
    private val h3Hex = "6afc397dde714ef7c9d8e7811d0f608f3fc9d508ad7873a0112eadd85a93004b"

    // -- cli-multi-session-relay phase 6: a fourth entry, chained after the three above,
    // pinning `agent_in` (0x09)'s wire bytes for the Rust relay to reproduce exactly.
    // Same device (deviceAPkHex) and real Ed25519 as e1-e3 -- this entry's author secret
    // is the 32 bytes 0x60..=0x7f (`fixedKey(0x60)` in this codebase's test-fixture
    // convention), which independently confirms `DEVICE_A_SEED` IS that same range: the
    // public key it derives (via iroh-kmp's real `ed25519PublicFromSecret`, the same
    // primitive `IrohKmpEd25519` wraps for production signing) equals [deviceAPkHex]
    // byte-for-byte. seq 4/lamport 4 continue device A's own chain (prev_hash = h3's
    // bytes); body field order and presence are pinned exactly: `conversation`, `text`,
    // `id`, `from_name` -- see account-sync spec's "Event Kinds" delta and
    // sync-api/EventBodies.kt's `AgentInBody`.
    private val body4 = "{\"conversation\":\"cafebabe\",\"text\":\"hello from claude\",\"id\":\"00112233445566778899aabbccddeeff\",\"from_name\":\"Claude\"}"
    private val e4B64 = "AQkXRVO0Vt3fxpCOyrHBAf5qsh4rqgYXeVt9Q6Y0gpk/1QAAAAAAAAAEAAAAAAAAAAQAAAGLz+VzuGr8OX3ecU73ydjngR0PYI8/ydUIrXhzoBEurdhakwBLAAAAc3siY29udmVyc2F0aW9uIjoiY2FmZWJhYmUiLCJ0ZXh0IjoiaGVsbG8gZnJvbSBjbGF1ZGUiLCJpZCI6IjAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmIiwiZnJvbV9uYW1lIjoiQ2xhdWRlIn23LV+7/fV81iMfv/PrprZt9lfbZZY/+OexSqyBPoqhHHt28rCquBnuNQOFpU2Sc1+epS98Fre41O87NDdm/ykP"
    private val h4Hex = "522670c48faff21d9ff3558aa93aef34999ec625e5a1d322082a6ff6a6676433"

    @Test
    fun certDecodesAndReEncodesByteIdentical() {
        val decoded = DeviceCertCodec.decode(certStr)!!
        assertEquals(1, decoded.version)
        assertEquals(ROLE_MAILBOX, decoded.roles)
        assertEquals(rootPkHex, decoded.rootPk.toHex())
        assertEquals(devicePkHex, decoded.devicePk.toHex())
        assertEquals(issuedAt, decoded.issuedAt)
        assertEquals(0L, decoded.expiresAt)
        assertEquals(certName, decoded.name)
        assertEquals(certStr, DeviceCertCodec.encode(decoded))
    }

    @Test
    fun revocationDecodesAndReEncodesByteIdentical() {
        val decoded = DeviceRevocationCodec.decode(revocationStr)!!
        assertEquals(1, decoded.version)
        assertEquals(rootPkHex, decoded.rootPk.toHex())
        assertEquals(devicePkHex, decoded.revokedDevicePk.toHex())
        assertEquals(revokedAt, decoded.revokedAt)
        assertEquals(revocationStr, DeviceRevocationCodec.encode(decoded))
    }

    @Test
    fun logEntriesDecodeAndReEncodeByteIdenticalAndChainValidates() {
        val bytes1 = Base64.decode(e1B64)
        val bytes2 = Base64.decode(e2B64)
        val bytes3 = Base64.decode(e3B64)

        val d1 = LogEntryCodec.decode(bytes1)!!
        val d2 = LogEntryCodec.decode(bytes2)!!
        val d3 = LogEntryCodec.decode(bytes3)!!

        assertEquals(deviceAPkHex, d1.authorDevicePk.toHex())
        assertEquals(EventKind.MESSAGE_OUT.wireValue, d1.kind)
        assertEquals(1L, d1.seq)
        assertEquals(1L, d1.lamport)
        assertEquals(baseTsMs, d1.tsMs)
        assertTrue(d1.prevHash.contentEquals(ZERO_PREV_HASH))
        assertEquals(body1, d1.body.decodeToString())

        assertEquals(EventKind.MESSAGE_OUT.wireValue, d2.kind)
        assertEquals(2L, d2.seq)
        assertEquals(2L, d2.lamport)
        assertEquals(baseTsMs + 1000, d2.tsMs)
        assertEquals(h1Hex, d2.prevHash.toHex())
        assertEquals(body2, d2.body.decodeToString())

        assertEquals(EventKind.READ_MARKER.wireValue, d3.kind)
        assertEquals(3L, d3.seq)
        assertEquals(3L, d3.lamport)
        assertEquals(baseTsMs + 2000, d3.tsMs)
        assertEquals(h2Hex, d3.prevHash.toHex())
        assertEquals(body3, d3.body.decodeToString())

        // Byte-identical re-encode: this is the actual cross-language proof -- no field offset,
        // width, endianness, or base64 framing differs between the two implementations.
        assertEquals(e1B64, Base64.encode(LogEntryCodec.encode(d1)))
        assertEquals(e2B64, Base64.encode(LogEntryCodec.encode(d2)))
        assertEquals(e3B64, Base64.encode(LogEntryCodec.encode(d3)))

        // The prev_hash chain validates on the Kotlin side too -- a mismatch in how either side
        // computes "SHA-256 of the previous entry's full bytes" would show up here.
        assertEquals(h1Hex, Sha256.hash(LogEntryCodec.encode(d1)).toHex())
        assertEquals(h2Hex, Sha256.hash(LogEntryCodec.encode(d2)).toHex())
        assertEquals(h3Hex, Sha256.hash(LogEntryCodec.encode(d3)).toHex())

        // TODO(task 2.4 follow-up): this proves the wire layout byte-for-byte but does NOT
        // verify the real Ed25519 signatures above -- `core` has no real Ed25519 (only the
        // deterministic `FakeEd25519` test stand-in, which cannot check a real signature; see
        // its kdoc). Real verification needs iroh-kmp's Ed25519 free functions, which are
        // blocked on a Maven Central publish (task 1.3) still in flight. Once that lands and
        // `network-real` can supply a real `Ed25519` here, add
        // `ed25519_verify(rootPk/devicePk, ..., signature) == true` assertions for the cert,
        // revocation, and all three log entries.
    }

    /**
     * cli-multi-session-relay phase 6: the `agent_in` (0x09) cross-language vector, chained
     * after the three-entry log above -- same shape/posture as
     * [logEntriesDecodeAndReEncodeByteIdenticalAndChainValidates]: `core` has no real Ed25519
     * (see that test's trailing TODO), so this decodes/re-encodes/chain-checks a vector that
     * WAS computed with real Ed25519 -- `network-real`'s `IrohKmpEd25519` (iroh-kmp's real
     * free functions, the same primitive production signing uses), not `FakeEd25519` --
     * against secret `fixedKey(0x60)` (bytes 0x60..=0x7f). That computation is exactly what
     * this test's fixed [e4B64]/[h4Hex] constants record.
     */
    @Test
    fun agentInEntryChainedAfterTheThreeEntryVectorDecodesAndReEncodesByteIdentical() {
        val bytes4 = Base64.decode(e4B64)
        val d4 = LogEntryCodec.decode(bytes4)!!

        assertEquals(deviceAPkHex, d4.authorDevicePk.toHex())
        assertEquals(EventKind.AGENT_IN.wireValue, d4.kind)
        assertEquals(4L, d4.seq)
        assertEquals(4L, d4.lamport)
        assertEquals(baseTsMs + 3000, d4.tsMs)
        assertEquals(h3Hex, d4.prevHash.toHex(), "chained after the existing 3-entry vector's last hash")
        assertEquals(body4, d4.body.decodeToString())

        // Byte-identical re-encode: the actual cross-language proof for this entry.
        assertEquals(e4B64, Base64.encode(LogEntryCodec.encode(d4)))
        assertEquals(h4Hex, Sha256.hash(LogEntryCodec.encode(d4)).toHex())
    }
}
