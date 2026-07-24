import dev.azula.core.EventKind
import dev.azula.core.LOG_ENTRY_ORDER
import dev.azula.core.LogEntry
import dev.azula.core.LogEntryCodec
import dev.azula.core.ZERO_PREV_HASH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixed-vector, chain-validation, and fold-order tests for the account-sync log entry codec
 * (azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md), implemented
 * from that spec alone. [FakeEd25519] is a deterministic test-only stand-in (see its kdoc) --
 * fixed keys throughout so encodings are reproducible. The `ENTRY*_HEX`/`H*_HEX` constants were
 * computed independently in Python (mirroring this file's algorithms byte for byte); see this
 * change's task 2.4 for the cross-language vectors these feed.
 */
class EventLogTest {
    private val ed25519 = FakeEd25519()
    private val deviceSecret = fixedKey(0x40)
    private val devicePk = fakePublicKey(deviceSecret)
    private val otherDeviceSecret = fixedKey(0x80)
    private val otherDevicePk = fakePublicKey(otherDeviceSecret)

    private val body1 = """{"conversation":"aa","text":"hi","id":"0123456789abcdef0123456789abcdef"}""".encodeToByteArray()
    private val body2 = """{"conversation":"aa","up_to_lamport":1}""".encodeToByteArray()
    private val body3 = """{"unknown":"extension-kind-payload"}""".encodeToByteArray()

    private val entry1Hex = "0101ca2a4fe727faaecf16ecd130a86e0885c5540c05375340445071c0657555fd42000000000000" +
        "000100000000000000010000018bcfe56be80000000000000000000000000000000000000000000000000000" +
        "000000000000000000497b22636f6e766572736174696f6e223a226161222c2274657874223a226869222c226" +
        "964223a223031323334353637383961626364656630313233343536373839616263646566227d404142434445" +
        "464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f5f3fe0692c17312125646eff6def072f94960a" +
        "0deb1f2a1e5b4487e01b18b5c6"
    private val h1Hex = "426ea7c70f5b2a4e176e8e20b022b6b77c098db812c2c584acb7632c16a4a654"
    private val entry2Hex = "0103ca2a4fe727faaecf16ecd130a86e0885c5540c05375340445071c0657555fd420000000000000" +
        "00200000000000000020000018bcfe56fd0426ea7c70f5b2a4e176e8e20b022b6b77c098db812c2c584acb7632" +
        "c16a4a654000000277b22636f6e766572736174696f6e223a226161222c2275705f746f5f6c616d706f7274223" +
        "a317d404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f6b9b4d1e98c4d23fa095f" +
        "2fcd41ccfc18cb5b19188de1f1c66644bc1605dcbe7"
    private val h2Hex = "476106d93ca8d98e9ae3f20f69091e1c7a3e0dffe8eaecf88313fe93f802bfb3"
    private val entry3Hex = "01eeca2a4fe727faaecf16ecd130a86e0885c5540c05375340445071c0657555fd42000000000000" +
        "000300000000000000030000018bcfe573b8476106d93ca8d98e9ae3f20f69091e1c7a3e0dffe8eaecf88313fe" +
        "93f802bfb3000000247b22756e6b6e6f776e223a22657874656e73696f6e2d6b696e642d7061796c6f6164227d" +
        "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f3c68ed99d1723fc38f7fb51557" +
        "220d662be478bf9dffef8a9253a6b7d82435b8"

    private fun buildThreeEntryChain(): Triple<LogEntry, LogEntry, LogEntry> {
        val e1 = LogEntryCodec.sign(
            EventKind.MESSAGE_OUT.wireValue, devicePk, seq = 1, lamport = 1, tsMs = 1_700_000_001_000L,
            prevHash = ZERO_PREV_HASH, body = body1, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        val e2 = LogEntryCodec.sign(
            EventKind.READ_MARKER.wireValue, devicePk, seq = 2, lamport = 2, tsMs = 1_700_000_002_000L,
            prevHash = LogEntryCodec.chainHash(e1), body = body2, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        val e3 = LogEntryCodec.sign(
            0xEE, devicePk, seq = 3, lamport = 3, tsMs = 1_700_000_003_000L,
            prevHash = LogEntryCodec.chainHash(e2), body = body3, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        return Triple(e1, e2, e3)
    }

    // -- Round-trip and fixed vectors --------------------------------------------------------

    @Test
    fun entryRoundTrips() {
        val entry = LogEntryCodec.sign(
            EventKind.MESSAGE_OUT.wireValue, devicePk, seq = 1, lamport = 1, tsMs = 42L,
            prevHash = ZERO_PREV_HASH, body = body1, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        val decoded = LogEntryCodec.decode(LogEntryCodec.encode(entry))!!
        assertEquals(entry.version, decoded.version)
        assertEquals(entry.kind, decoded.kind)
        assertTrue(entry.authorDevicePk.contentEquals(decoded.authorDevicePk))
        assertEquals(entry.seq, decoded.seq)
        assertEquals(entry.lamport, decoded.lamport)
        assertEquals(entry.tsMs, decoded.tsMs)
        assertTrue(entry.prevHash.contentEquals(decoded.prevHash))
        assertTrue(entry.body.contentEquals(decoded.body))
        assertTrue(entry.signature.contentEquals(decoded.signature))
        assertTrue(LogEntryCodec.verify(decoded, previous = null, ed25519 = ed25519))
    }

    @Test
    fun threeEntryChainMatchesFixedVectorAndVerifies() {
        val (e1, e2, e3) = buildThreeEntryChain()

        assertEquals(entry1Hex, LogEntryCodec.encode(e1).toHex())
        assertEquals(entry2Hex, LogEntryCodec.encode(e2).toHex())
        assertEquals(entry3Hex, LogEntryCodec.encode(e3).toHex())
        assertEquals(h1Hex, LogEntryCodec.chainHash(e1).toHex())
        assertEquals(h2Hex, LogEntryCodec.chainHash(e2).toHex())

        assertTrue(LogEntryCodec.verify(e1, previous = null, ed25519 = ed25519))
        assertTrue(LogEntryCodec.verify(e2, previous = e1, ed25519 = ed25519))
        assertTrue(LogEntryCodec.verify(e3, previous = e2, ed25519 = ed25519))

        // Every entry decodes back byte-identically from its own encoding.
        for (e in listOf(e1, e2, e3)) {
            val decoded = LogEntryCodec.decode(LogEntryCodec.encode(e))!!
            assertEquals(e, decoded)
        }
    }

    // -- Rejection paths ----------------------------------------------------------------------

    @Test
    fun rejectsTruncatedPayload() {
        val (e1, _, _) = buildThreeEntryChain()
        val encoded = LogEntryCodec.encode(e1)
        assertNull(LogEntryCodec.decode(encoded.copyOfRange(0, 10)))
        assertNull(LogEntryCodec.decode(encoded.copyOfRange(0, encoded.size - 5)))
    }

    @Test
    fun rejectsOverlongPayload() {
        val (e1, _, _) = buildThreeEntryChain()
        val encoded = LogEntryCodec.encode(e1)
        assertNull(LogEntryCodec.decode(encoded + byteArrayOf(0)))
    }

    @Test
    fun rejectsWrongVersion() {
        val (e1, _, _) = buildThreeEntryChain()
        val badVersion = e1.copy(version = 2)
        assertNull(LogEntryCodec.decode(LogEntryCodec.encode(badVersion)))
    }

    @Test
    fun rejectsBrokenPrevHash() {
        val (e1, e2, _) = buildThreeEntryChain()
        val tampered = e2.copy(prevHash = ZERO_PREV_HASH) // e2's real prev_hash is chainHash(e1), not all-zero
        assertFalse(LogEntryCodec.verify(tampered, previous = e1, ed25519 = ed25519))
    }

    @Test
    fun rejectsSeqGap() {
        val e1 = LogEntryCodec.sign(
            EventKind.MESSAGE_OUT.wireValue, devicePk, seq = 1, lamport = 1, tsMs = 1L,
            prevHash = ZERO_PREV_HASH, body = body1, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        // seq should be 2 (prev.seq + 1); sign one with seq=3 instead, chained correctly otherwise.
        val gapped = LogEntryCodec.sign(
            EventKind.READ_MARKER.wireValue, devicePk, seq = 3, lamport = 2, tsMs = 2L,
            prevHash = LogEntryCodec.chainHash(e1), body = body2, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        assertFalse(LogEntryCodec.verify(gapped, previous = e1, ed25519 = ed25519))
    }

    @Test
    fun rejectsFirstEntryWithSeqNotOne() {
        val entry = LogEntryCodec.sign(
            EventKind.MESSAGE_OUT.wireValue, devicePk, seq = 2, lamport = 1, tsMs = 1L,
            prevHash = ZERO_PREV_HASH, body = body1, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        assertFalse(LogEntryCodec.verify(entry, previous = null, ed25519 = ed25519))
    }

    @Test
    fun rejectsFirstEntryWithNonZeroPrevHash() {
        val entry = LogEntryCodec.sign(
            EventKind.MESSAGE_OUT.wireValue, devicePk, seq = 1, lamport = 1, tsMs = 1L,
            prevHash = fixedKey(0x99), body = body1, authorSecret = deviceSecret, ed25519 = ed25519,
        )
        assertFalse(LogEntryCodec.verify(entry, previous = null, ed25519 = ed25519))
    }

    @Test
    fun rejectsAuthorChangeMidChain() {
        val (e1, e2, _) = buildThreeEntryChain()
        // Same seq/lamport/prev_hash as e2, but signed (and authored) by a different device.
        val impostor = LogEntryCodec.sign(
            e2.kind, otherDevicePk, seq = e2.seq, lamport = e2.lamport, tsMs = e2.tsMs,
            prevHash = e2.prevHash, body = e2.body, authorSecret = otherDeviceSecret, ed25519 = ed25519,
        )
        assertFalse(LogEntryCodec.verify(impostor, previous = e1, ed25519 = ed25519))
    }

    @Test
    fun rejectsTamperedBody() {
        val (e1, e2, _) = buildThreeEntryChain()
        val tampered = e2.copy(body = """{"conversation":"aa","up_to_lamport":999}""".encodeToByteArray())
        assertFalse(LogEntryCodec.verify(tampered, previous = e1, ed25519 = ed25519))
    }

    @Test
    fun rejectsTamperedSignature() {
        val (e1, _, _) = buildThreeEntryChain()
        val tamperedSig = e1.signature.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertFalse(LogEntryCodec.verify(e1.copy(signature = tamperedSig), previous = null, ed25519 = ed25519))
    }

    // -- Unknown kinds ------------------------------------------------------------------------

    @Test
    fun unknownKindRoundTripsAndStaysReServeable() {
        val (_, _, e3) = buildThreeEntryChain()
        assertEquals(0xEE, e3.kind)
        assertNull(e3.knownKind) // not one of the 8 defined kinds
        val decoded = LogEntryCodec.decode(LogEntryCodec.encode(e3))!!
        assertEquals(0xEE, decoded.kind)
        assertTrue(decoded.body.contentEquals(e3.body))
    }

    @Test
    fun eventKindFromByteCoversAllEightKindsAndRejectsUnknown() {
        assertEquals(EventKind.MESSAGE_OUT, EventKind.fromByte(0x01))
        assertEquals(EventKind.MESSAGE_IN, EventKind.fromByte(0x02))
        assertEquals(EventKind.READ_MARKER, EventKind.fromByte(0x03))
        assertEquals(EventKind.CONTACT_ADD, EventKind.fromByte(0x04))
        assertEquals(EventKind.CONTACT_REMOVE, EventKind.fromByte(0x05))
        assertEquals(EventKind.DEVICE_ADD, EventKind.fromByte(0x06))
        assertEquals(EventKind.DEVICE_REVOKE, EventKind.fromByte(0x07))
        assertEquals(EventKind.PROFILE_UPDATE, EventKind.fromByte(0x08))
        assertNull(EventKind.fromByte(0x00))
        assertNull(EventKind.fromByte(0xFF))
    }

    // -- (lamport, ts_ms, device_pk) comparator ------------------------------------------------

    private fun entryWith(lamport: Long, tsMs: Long, author: ByteArray): LogEntry = LogEntryCodec.sign(
        EventKind.MESSAGE_OUT.wireValue, author, seq = 1, lamport = lamport, tsMs = tsMs,
        prevHash = ZERO_PREV_HASH, body = body1, authorSecret = deviceSecret, ed25519 = ed25519,
    )

    @Test
    fun comparatorOrdersByLamportFirst() {
        val a = entryWith(lamport = 1, tsMs = 500, author = devicePk)
        val b = entryWith(lamport = 2, tsMs = 100, author = devicePk) // earlier ts_ms, higher lamport
        assertTrue(LOG_ENTRY_ORDER.compare(a, b) < 0)
        assertTrue(LOG_ENTRY_ORDER.compare(b, a) > 0)
    }

    @Test
    fun comparatorTieBreaksOnTsMsWhenLamportEqual() {
        val a = entryWith(lamport = 5, tsMs = 100, author = devicePk)
        val b = entryWith(lamport = 5, tsMs = 200, author = devicePk)
        assertTrue(LOG_ENTRY_ORDER.compare(a, b) < 0)
        assertTrue(LOG_ENTRY_ORDER.compare(b, a) > 0)
    }

    @Test
    fun comparatorTieBreaksOnDevicePkWhenLamportAndTsMsEqual() {
        val lower = if (devicePk.toHex() < otherDevicePk.toHex()) devicePk else otherDevicePk
        val higher = if (lower === devicePk) otherDevicePk else devicePk
        val a = entryWith(lamport = 9, tsMs = 100, author = lower)
        val b = entryWith(lamport = 9, tsMs = 100, author = higher)
        assertTrue(LOG_ENTRY_ORDER.compare(a, b) < 0)
        assertTrue(LOG_ENTRY_ORDER.compare(b, a) > 0)
    }

    @Test
    fun comparatorIsZeroForIdenticalOrderingKeys() {
        val a = entryWith(lamport = 3, tsMs = 7, author = devicePk)
        val b = entryWith(lamport = 3, tsMs = 7, author = devicePk)
        assertEquals(0, LOG_ENTRY_ORDER.compare(a, b))
    }
}
