package dev.azula.core

// Log-entry wire layout, event kinds, and chain-validation rules from
// azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md.

/**
 * The known log-entry kinds from "Event Kinds". [fromByte] returns `null` for any byte a device
 * doesn't recognize (a newer sibling's extension) — callers use that to store/forward the entry
 * while excluding it from their own fold, per "Unknown kind passes through". [LogEntry.kind] is
 * always the raw wire byte, independent of whether it maps to a known [EventKind].
 */
enum class EventKind(val wireValue: Int) {
    MESSAGE_OUT(0x01), // {conversation, text, id?}
    MESSAGE_IN(0x02), // {conversation, from_device_pk, text, id?}
    READ_MARKER(0x03), // {conversation, up_to_lamport}
    CONTACT_ADD(0x04), // {root_pk | node_id, name?}
    CONTACT_REMOVE(0x05), // same key shape as add
    DEVICE_ADD(0x06), // {cert}
    DEVICE_REVOKE(0x07), // {revocation}
    PROFILE_UPDATE(0x08), // {name?, description?}
    ;

    companion object {
        fun fromByte(b: Int): EventKind? = entries.find { it.wireValue == b }
    }
}

/** All-zero 32-byte sentinel: the required `prev_hash` of a device's first log entry (`seq` 1). */
val ZERO_PREV_HASH: ByteArray = ByteArray(PUBLIC_KEY_LEN)

/**
 * One decoded log entry. [body] is kept as raw bytes rather than a validated UTF-8 string: an
 * unknown [kind] from a newer sibling must still "round-trip and stay re-serveable" (it is
 * stored and forwarded, never rejected or reinterpreted), so this codec only enforces the
 * fixed envelope shape — never the content of a kind it doesn't otherwise validate.
 */
data class LogEntry(
    val version: Int,
    val kind: Int,
    val authorDevicePk: ByteArray,
    val seq: Long,
    val lamport: Long,
    val tsMs: Long,
    val prevHash: ByteArray,
    val body: ByteArray,
    val signature: ByteArray,
) {
    /** [kind] mapped to a known [EventKind], or `null` if it's an extension this build doesn't know. */
    val knownKind: EventKind? get() = EventKind.fromByte(kind)

    // `data class` would give us reference equality on the ByteArray fields, which is wrong here:
    // the fold treats the union of the identity's logs as a set, so two structurally-identical
    // entries arriving from two siblings must compare equal or they'd both be folded in.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogEntry) return false
        return version == other.version &&
            kind == other.kind &&
            seq == other.seq &&
            lamport == other.lamport &&
            tsMs == other.tsMs &&
            authorDevicePk.contentEquals(other.authorDevicePk) &&
            prevHash.contentEquals(other.prevHash) &&
            body.contentEquals(other.body) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + kind
        result = 31 * result + seq.hashCode()
        result = 31 * result + lamport.hashCode()
        result = 31 * result + tsMs.hashCode()
        result = 31 * result + authorDevicePk.contentHashCode()
        result = 31 * result + prevHash.contentHashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * The deterministic cross-device fold order from "Derived State Is a Deterministic Fold":
 * `(lamport, ts_ms, device_pk)` ascending, total and coordination-free.
 */
val LOG_ENTRY_ORDER: Comparator<LogEntry> = Comparator { a, b ->
    var cmp = a.lamport.compareTo(b.lamport)
    if (cmp == 0) cmp = a.tsMs.compareTo(b.tsMs)
    if (cmp == 0) cmp = a.authorDevicePk.compareUnsignedTo(b.authorDevicePk)
    cmp
}

private fun writeU32BE(out: ByteArray, offset: Int, value: Long) {
    out[offset] = (value ushr 24).toByte()
    out[offset + 1] = (value ushr 16).toByte()
    out[offset + 2] = (value ushr 8).toByte()
    out[offset + 3] = value.toByte()
}

private fun readU32BE(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

private fun writeU64BE(out: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) out[offset + i] = (value ushr (8 * (7 - i))).toByte()
}

private fun readU64BE(bytes: ByteArray, offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
    return v
}

/**
 * Encodes/decodes/verifies the account-sync log-entry payload: version(1) | kind(1) |
 * device_pk(32) | seq(8, u64) | lamport(8, u64) | ts_ms(8, u64) | prev_hash(32) | body_len(4,
 * u32) | body(body_len) | signature(64). Unlike the `azd`/`azr`/`azl` formats this is never
 * base32/prefix-encoded — entries travel as raw (base64-in-JSON) bytes per the sync protocol.
 */
object LogEntryCodec {
    // version + kind + authorDevicePk + seq + lamport + tsMs + prevHash + bodyLen
    private const val HEADER_LEN = 1 + 1 + PUBLIC_KEY_LEN + 8 + 8 + 8 + PUBLIC_KEY_LEN + 4 // 94

    private fun signedBytes(
        version: Int,
        kind: Int,
        authorDevicePk: ByteArray,
        seq: Long,
        lamport: Long,
        tsMs: Long,
        prevHash: ByteArray,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArray(HEADER_LEN + body.size)
        out[0] = version.toByte()
        out[1] = kind.toByte()
        authorDevicePk.copyInto(out, 2)
        writeU64BE(out, 34, seq)
        writeU64BE(out, 42, lamport)
        writeU64BE(out, 50, tsMs)
        prevHash.copyInto(out, 58)
        writeU32BE(out, 90, body.size.toLong())
        body.copyInto(out, 94)
        return out
    }

    /** Builds and signs a new entry (always [CURRENT_WIRE_VERSION]) with the author device's secret. */
    fun sign(
        kind: Int,
        authorDevicePk: ByteArray,
        seq: Long,
        lamport: Long,
        tsMs: Long,
        prevHash: ByteArray,
        body: ByteArray,
        authorSecret: ByteArray,
        ed25519: Ed25519,
    ): LogEntry {
        require(kind in 0..255) { "kind must be a single byte" }
        require(authorDevicePk.size == PUBLIC_KEY_LEN) { "authorDevicePk must be $PUBLIC_KEY_LEN bytes" }
        require(prevHash.size == PUBLIC_KEY_LEN) { "prevHash must be $PUBLIC_KEY_LEN bytes" }
        val toSign = signedBytes(CURRENT_WIRE_VERSION, kind, authorDevicePk, seq, lamport, tsMs, prevHash, body)
        val signature = ed25519.sign(authorSecret, toSign)
        return LogEntry(CURRENT_WIRE_VERSION, kind, authorDevicePk, seq, lamport, tsMs, prevHash, body, signature)
    }

    /** The full wire bytes of [entry], the unit [chainHash] hashes and sync transports as base64.
     *  Encodes whatever `entry.version` holds so tests can build an invalid-version entry via
     *  `.copy(version = ...)` without a separate raw encoder. */
    fun encode(entry: LogEntry): ByteArray {
        val signedPart = signedBytes(
            entry.version, entry.kind, entry.authorDevicePk, entry.seq, entry.lamport, entry.tsMs,
            entry.prevHash, entry.body,
        )
        return signedPart + entry.signature
    }

    /** Returns `null` on wrong version or a payload shorter or longer than the exact `94 +
     *  body_len + 64` bytes the fields describe (both truncated and overlong payloads are
     *  rejected, never partially processed). */
    fun decode(bytes: ByteArray): LogEntry? {
        if (bytes.size < HEADER_LEN) return null
        val version = bytes[0].toInt() and 0xFF
        if (version != CURRENT_WIRE_VERSION) return null
        val kind = bytes[1].toInt() and 0xFF
        val authorDevicePk = bytes.copyOfRange(2, 34)
        val seq = readU64BE(bytes, 34)
        val lamport = readU64BE(bytes, 42)
        val tsMs = readU64BE(bytes, 50)
        val prevHash = bytes.copyOfRange(58, 90)
        val bodyLen = readU32BE(bytes, 90)
        if (bodyLen < 0 || bodyLen > Int.MAX_VALUE.toLong()) return null
        val bodyStart = HEADER_LEN
        val bodyEnd = bodyStart + bodyLen.toInt()
        val expectedLen = bodyEnd + SIGNATURE_LEN
        if (bytes.size != expectedLen) return null
        val body = bytes.copyOfRange(bodyStart, bodyEnd)
        val signature = bytes.copyOfRange(bodyEnd, expectedLen)
        return LogEntry(version, kind, authorDevicePk, seq, lamport, tsMs, prevHash, body, signature)
    }

    /** Signature-only check: [entry] was signed by the device key named in `entry.authorDevicePk`. */
    fun verifySignature(entry: LogEntry, ed25519: Ed25519): Boolean {
        val toVerify = signedBytes(
            entry.version, entry.kind, entry.authorDevicePk, entry.seq, entry.lamport, entry.tsMs,
            entry.prevHash, entry.body,
        )
        return ed25519.verify(entry.authorDevicePk, toVerify, entry.signature)
    }

    /** SHA-256 of [entry]'s full encoded bytes — what the *next* entry's `prev_hash` must equal. */
    fun chainHash(entry: LogEntry): ByteArray = Sha256.hash(encode(entry))

    /**
     * Full receiver-side validation for appending [entry] onto a per-device log whose last
     * accepted entry is [previous] (`null` if [entry] would be the first, `seq` 1). Checks
     * exactly the three things "Per-Device Append-Only Signed Logs" requires a receiver to
     * reject on: the signature, `seq` continuity, and the `prev_hash` chain (which also pins
     * the author to the same device key throughout).
     */
    fun verify(entry: LogEntry, previous: LogEntry?, ed25519: Ed25519): Boolean {
        if (entry.version != CURRENT_WIRE_VERSION) return false
        if (!verifySignature(entry, ed25519)) return false
        return if (previous == null) {
            entry.seq == 1L && entry.prevHash.contentEquals(ZERO_PREV_HASH)
        } else {
            entry.seq == previous.seq + 1 &&
                entry.authorDevicePk.contentEquals(previous.authorDevicePk) &&
                entry.prevHash.contentEquals(chainHash(previous))
        }
    }
}
