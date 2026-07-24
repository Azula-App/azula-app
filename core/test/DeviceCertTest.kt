import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.LinkPayload
import dev.azula.core.LinkPayloadCodec
import dev.azula.core.ROLE_MAILBOX
import dev.azula.core.VerificationWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixed-vector and rejection-path tests for the `azd`/`azr`/`azl` codecs
 * (azula-docs/openspec/changes/multi-device-identity/specs/device-linking/spec.md), implemented
 * from that spec alone. Every key here is a fixed, non-random 32-byte pattern so encodings are
 * fully deterministic; [FakeEd25519] is a deterministic test-only stand-in, never real crypto
 * (see its kdoc). The `*_STR`/`*_HEX` constants were computed independently in Python
 * (SHA-256 + RFC 4648 base32, mirroring this file's algorithms byte for byte) — see this
 * change's task 2.4 for the cross-language vectors these feed.
 */
class DeviceCertTest {
    private val ed25519 = FakeEd25519()

    // Fixed 32-byte keys -- root_secret = [1, 2, .., 32], device_secret = [0x40, 0x41, .., 0x5f].
    private val rootSecret = fixedKey(1)
    private val rootPk = fakePublicKey(rootSecret)
    private val deviceSecret = fixedKey(0x40)
    private val devicePk = fakePublicKey(deviceSecret)
    private val linkDeviceSecret = fixedKey(0x80)
    private val linkDevicePk = fakePublicKey(linkDeviceSecret)

    private val issuedAt = 1_700_000_000L
    private val certName = "Pixel 8"

    private val rootPkHex = "ae216c2ef5247a3782c135efa279a3e4cdc61094270f5d2be58c6204b7a612c9"
    private val devicePkHex = "ca2a4fe727faaecf16ecd130a86e0885c5540c05375340445071c0657555fd42"

    private val certStr = "azdaea24ilmf32si6rxqlatl35cpgr6jtogcckcod25fpsyyyqew6tbfsokfjh6oj72v3hrn3grgcug4c" +
        "efyvkaybjxknaeiudrybsxkvp5ijsvh4iaaaaaaaahkbuxqzlmea4acaqdaqcqmbyibefawdanbyhraeiscmkbkfq" +
        "xdamrugy4dupb6ie4ddwmgiydoc2n6waiaffmpki4qcucfk7jzkioni4e4gl6rvped4"

    private val revocationStr = "azragxcc3bo6ushun4cye267itzupsm3rqqsqtq6xjl4wggebfxuyjmtsrkj7tsp6voz4lozujqvbxarbo" +
        "fkqgakn2tibcfa4oamv2vl7kcmvkxpiabaibqibiga4eascqlbqgq4dyqcejbgfavcylrqgi2dmob2hq7ec75nuprj" +
        "menfo4wfkgz4pfhjr33w54vb4r7s4cuoefb6qgnihrck"

    private val linkStr = "azlagbnqzaikmfxmxsg5p2hqbyjkat6qb54bbtuwdphp3s66l5opucjeckomv3saudin5xgkaawmzqwwzjn" +
        "oruwg23foqwwe6lumvzs2mbrgizq"

    // -- Device certificate (azd) -----------------------------------------------------------

    @Test
    fun certRoundTrips() {
        val cert = DeviceCertCodec.issue(
            ROLE_MAILBOX, rootPk, devicePk, issuedAt, 0L, certName, rootSecret, ed25519,
        )
        val encoded = DeviceCertCodec.encode(cert)
        val decoded = DeviceCertCodec.decode(encoded)!!
        assertEquals(cert.version, decoded.version)
        assertEquals(cert.roles, decoded.roles)
        assertTrue(cert.rootPk.contentEquals(decoded.rootPk))
        assertTrue(cert.devicePk.contentEquals(decoded.devicePk))
        assertEquals(cert.issuedAt, decoded.issuedAt)
        assertEquals(cert.expiresAt, decoded.expiresAt)
        assertEquals(cert.name, decoded.name)
        assertTrue(cert.signature.contentEquals(decoded.signature))
        assertTrue(DeviceCertCodec.verify(decoded, ed25519, nowEpochSeconds = issuedAt))
    }

    @Test
    fun certMatchesFixedVector() {
        assertEquals(rootPkHex, rootPk.toHex())
        assertEquals(devicePkHex, devicePk.toHex())

        val cert = DeviceCertCodec.issue(
            ROLE_MAILBOX, rootPk, devicePk, issuedAt, 0L, certName, rootSecret, ed25519,
        )
        assertEquals(certStr, DeviceCertCodec.encode(cert))

        val decoded = DeviceCertCodec.decode(certStr)!!
        assertEquals(1, decoded.version)
        assertEquals(ROLE_MAILBOX, decoded.roles)
        assertTrue(decoded.isMailbox)
        assertEquals(issuedAt, decoded.issuedAt)
        assertEquals(0L, decoded.expiresAt)
        assertEquals(certName, decoded.name)
        assertTrue(DeviceCertCodec.verify(decoded, ed25519, nowEpochSeconds = issuedAt))
    }

    @Test
    fun certRejectsWrongPrefix() {
        assertNull(DeviceCertCodec.decode("azr" + certStr.removePrefix("azd")))
        assertNull(DeviceCertCodec.decode("not-a-cert"))
        assertNull(DeviceCertCodec.decode(""))
    }

    @Test
    fun certRejectsBadBase32() {
        assertNull(DeviceCertCodec.decode("azd***not-base32***"))
    }

    @Test
    fun certRejectsWrongVersion() {
        val cert = DeviceCertCodec.decode(certStr)!!
        val badVersion = cert.copy(version = 2)
        assertNull(DeviceCertCodec.decode(DeviceCertCodec.encode(badVersion)))
    }

    @Test
    fun certRejectsTruncatedPayload() {
        // Same technique as InviteCodecTest: chop the encoded string well short.
        assertNull(DeviceCertCodec.decode(certStr.take(10)))
        assertNull(DeviceCertCodec.decode(certStr.dropLast(40)))
    }

    @Test
    fun certRejectsOverlongPayload() {
        // A structurally valid azd payload (name_len=0, all-zero keys) plus 5 trailing bytes
        // past what version/roles/keys/timestamps/name_len/signature actually span.
        val overlong = "azdaeaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertNull(DeviceCertCodec.decode(overlong))
    }

    @Test
    fun certRejectsNameLenOver63() {
        // A structurally valid header up to and including a name_len byte of 200 (> 63); the
        // rest of the payload never matters because decode must reject before reading it.
        val badNameLen = "azdaeaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaagi"
        assertNull(DeviceCertCodec.decode(badNameLen))
    }

    @Test
    fun certRejectsNonUtf8Name() {
        // A structurally valid, exact-length (140-byte) payload whose single name byte (0xff)
        // is never a valid UTF-8 lead byte.
        val badName = "azdaeaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab74aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertNull(DeviceCertCodec.decode(badName))
    }

    @Test
    fun certExpiryZeroNeverExpires() {
        val cert = DeviceCertCodec.issue(0, rootPk, devicePk, issuedAt, 0L, certName, rootSecret, ed25519)
        // Even far, far in the future, expires_at=0 means "never".
        assertTrue(DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = issuedAt + 100_000_000_000L))
    }

    @Test
    fun certExpiryPastIsRejected() {
        val expiresAt = issuedAt + 3600
        val cert = DeviceCertCodec.issue(0, rootPk, devicePk, issuedAt, expiresAt, certName, rootSecret, ed25519)
        assertTrue(DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = expiresAt - 1))
        assertFalse(DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = expiresAt))
        assertFalse(DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = expiresAt + 1))
    }

    @Test
    fun certRevokedDeviceIsRejected() {
        val cert = DeviceCertCodec.issue(0, rootPk, devicePk, issuedAt, 0L, certName, rootSecret, ed25519)
        assertTrue(DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = issuedAt))
        assertFalse(
            DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = issuedAt, revokedDevicePks = listOf(devicePk)),
        )
        // A revocation naming a *different* device doesn't affect this one.
        assertTrue(
            DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = issuedAt, revokedDevicePks = listOf(linkDevicePk)),
        )
    }

    @Test
    fun certTamperedFieldFailsVerification() {
        val cert = DeviceCertCodec.issue(0, rootPk, devicePk, issuedAt, 0L, certName, rootSecret, ed25519)
        assertFalse(DeviceCertCodec.verify(cert.copy(name = "Evil Twin"), ed25519, nowEpochSeconds = issuedAt))
        assertFalse(DeviceCertCodec.verify(cert.copy(issuedAt = issuedAt + 1), ed25519, nowEpochSeconds = issuedAt))
        val tamperedSig = cert.signature.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertFalse(DeviceCertCodec.verify(cert.copy(signature = tamperedSig), ed25519, nowEpochSeconds = issuedAt))
    }

    @Test
    fun certRejectsSignatureFromWrongRoot() {
        // A well-formed FakeEd25519 signature, just produced by a different secret -- verify()
        // must reject it because that secret doesn't pair with this cert's embedded root_pk.
        val cert = DeviceCertCodec.issue(0, rootPk, devicePk, issuedAt, 0L, certName, rootSecret, ed25519)
        val otherRootSecret = fixedKey(0xC0)
        val wrongSignature = ed25519.sign(otherRootSecret, "unrelated-data".encodeToByteArray())
        assertFalse(DeviceCertCodec.verify(cert.copy(signature = wrongSignature), ed25519, nowEpochSeconds = issuedAt))
    }

    // -- Revocation (azr) --------------------------------------------------------------------

    @Test
    fun revocationRoundTrips() {
        val revokedAt = issuedAt + 100_000
        val revocation = DeviceRevocationCodec.issue(rootPk, devicePk, revokedAt, rootSecret, ed25519)
        val decoded = DeviceRevocationCodec.decode(DeviceRevocationCodec.encode(revocation))!!
        assertEquals(revocation.version, decoded.version)
        assertTrue(revocation.rootPk.contentEquals(decoded.rootPk))
        assertTrue(revocation.revokedDevicePk.contentEquals(decoded.revokedDevicePk))
        assertEquals(revocation.revokedAt, decoded.revokedAt)
        assertTrue(DeviceRevocationCodec.verify(decoded, ed25519))
    }

    @Test
    fun revocationMatchesFixedVector() {
        val revokedAt = 1_700_100_000L
        val revocation = DeviceRevocationCodec.issue(rootPk, devicePk, revokedAt, rootSecret, ed25519)
        assertEquals(revocationStr, DeviceRevocationCodec.encode(revocation))
        val decoded = DeviceRevocationCodec.decode(revocationStr)!!
        assertTrue(DeviceRevocationCodec.verify(decoded, ed25519))
    }

    @Test
    fun revocationRejectsWrongVersion() {
        val revocation = DeviceRevocationCodec.decode(revocationStr)!!
        assertNull(DeviceRevocationCodec.decode(DeviceRevocationCodec.encode(revocation.copy(version = 7))))
    }

    @Test
    fun revocationRejectsTruncatedAndOverlongPayload() {
        assertNull(DeviceRevocationCodec.decode(revocationStr.take(10)))
        assertNull(DeviceRevocationCodec.decode(revocationStr.dropLast(20)))
        val overlong = "azraeaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertNull(DeviceRevocationCodec.decode(overlong))
    }

    @Test
    fun revocationRejectsBadPrefixAndBase32() {
        assertNull(DeviceRevocationCodec.decode("azd" + revocationStr.removePrefix("azr")))
        assertNull(DeviceRevocationCodec.decode("azr***"))
    }

    @Test
    fun revocationNeverExpires() {
        // No expiry concept exists at all -- verify() takes no "now", only the signature matters.
        val revocation = DeviceRevocationCodec.issue(rootPk, devicePk, 0L, rootSecret, ed25519)
        assertTrue(DeviceRevocationCodec.verify(revocation, ed25519))
    }

    // -- Link payload (azl) ------------------------------------------------------------------

    @Test
    fun linkPayloadRoundTrips() {
        val payload = LinkPayload(1, linkDevicePk, "New Phone", "fake-ticket-bytes-0123".encodeToByteArray())
        val encoded = LinkPayloadCodec.encode(payload)
        val decoded = LinkPayloadCodec.decode(encoded)!!
        assertEquals(payload.version, decoded.version)
        assertTrue(payload.devicePk.contentEquals(decoded.devicePk))
        assertEquals(payload.name, decoded.name)
        assertTrue(payload.ticket.contentEquals(decoded.ticket))
    }

    @Test
    fun linkPayloadMatchesFixedVector() {
        val payload = LinkPayload(1, linkDevicePk, "New Phone", "fake-ticket-bytes-0123".encodeToByteArray())
        assertEquals(linkStr, LinkPayloadCodec.encode(payload))
        val decoded = LinkPayloadCodec.decode(linkStr)!!
        assertEquals("New Phone", decoded.name)
        assertEquals("fake-ticket-bytes-0123", decoded.ticket.decodeToString())
    }

    @Test
    fun linkPayloadRejectsWrongVersion() {
        val payload = LinkPayloadCodec.decode(linkStr)!!
        assertNull(LinkPayloadCodec.decode(LinkPayloadCodec.encode(payload.copy(version = 9))))
    }

    @Test
    fun linkPayloadRejectsTruncatedAndOverlongPayload() {
        assertNull(LinkPayloadCodec.decode(linkStr.take(5)))
        assertNull(LinkPayloadCodec.decode(linkStr.dropLast(20)))
        val overlong = "azlaeaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaeqsci"
        assertNull(LinkPayloadCodec.decode(overlong))
    }

    @Test
    fun linkPayloadRejectsNameLenOver63() {
        val badNameLen = "azlaeaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabsa"
        assertNull(LinkPayloadCodec.decode(badNameLen))
    }

    @Test
    fun linkPayloadRejectsNonUtf8Name() {
        val badName = "azlaeaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaap7aaaa"
        assertNull(LinkPayloadCodec.decode(badName))
    }

    @Test
    fun linkPayloadRejectsBadPrefixAndBase32() {
        assertNull(LinkPayloadCodec.decode("azd" + linkStr.removePrefix("azl")))
        assertNull(LinkPayloadCodec.decode("azl***"))
    }

    // -- Verification words -------------------------------------------------------------------

    @Test
    fun verificationWordsMatchFixedVector() {
        val words = VerificationWords.compute(devicePk, linkDevicePk)
        assertEquals(4, words.size)
        // Pinned as literals, not as BIP39_WORDS[i] lookups: the words are what a user reads off
        // two screens and compares, so the vector has to fail if either the index derivation or
        // the wordlist itself drifts. Indices 371/1145/1065/577 of the BIP-39 English list, for
        // SHA-256(lower_pk || higher_pk) with lower=link_device_pk, higher=device_pk (the two
        // fixed keys above sorted bytewise ascending).
        assertEquals(listOf("common", "monkey", "lunch", "else"), words)
    }

    @Test
    fun verificationWordsAreOrderIndependent() {
        assertEquals(VerificationWords.compute(devicePk, linkDevicePk), VerificationWords.compute(linkDevicePk, devicePk))
    }

    @Test
    fun verificationWordsDifferForDifferentPairs() {
        val other = fixedKey(0x55)
        assertNotEquals(VerificationWords.compute(devicePk, linkDevicePk), VerificationWords.compute(devicePk, fakePublicKey(other)))
    }
}
