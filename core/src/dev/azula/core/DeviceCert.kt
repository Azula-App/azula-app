package dev.azula.core

// Wire layouts, encodings, and verification rules from
// azula-docs/openspec/changes/multi-device-identity/specs/device-linking/spec.md.
// Mirrors the house style of Invite.kt (fixed big-endian layout, "az…" prefix +
// unpadded lowercase RFC 4648 base32, a hand-rolled dependency-free Base32).

/**
 * The crypto seam this file needs but cannot call directly: `core` has no dependency on
 * iroh-kmp (the only place a real Ed25519 implementation lives), so codecs and verification
 * here take an [Ed25519] instance rather than importing one. Production code wires this
 * against iroh-kmp's Ed25519 free functions elsewhere; tests supply a deterministic fake.
 */
interface Ed25519 {
    /** Signs [data] with the 32-byte [secret], returning a 64-byte Ed25519 signature. */
    fun sign(secret: ByteArray, data: ByteArray): ByteArray

    /** Verifies a 64-byte Ed25519 [signature] over [data] against a 32-byte [publicKey]. */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}

// --- Shared wire constants -------------------------------------------------

internal const val PUBLIC_KEY_LEN = 32
internal const val SIGNATURE_LEN = 64
internal const val MAX_NAME_LEN = 63
internal const val CURRENT_WIRE_VERSION = 0x01

/** Roles bit 0: the device stores-and-forwards for the identity (see the `mailbox` spec). */
const val ROLE_MAILBOX = 0x01

/** Roles bit 1: reserved for a future auto-responder bot role. Never set by this change;
 *  present only so certificates that do carry it (a newer sibling) round-trip losslessly. */
const val ROLE_BOT = 0x02

/** Bits 0–1 are meaningful today; bits 2–7 are reserved (`0` on encode, ignored on decode). */
private const val ROLE_MASK = ROLE_MAILBOX or ROLE_BOT

private const val CERT_PREFIX = "azd"
private const val REVOCATION_PREFIX = "azr"
private const val LINK_PREFIX = "azl"

/**
 * Lexicographic unsigned-byte ordering — used both to pick the `(lower_pk, higher_pk)` order
 * for [VerificationWords] and as the final tie-break of the account-sync `(lamport, ts_ms,
 * device_pk)` fold order ([LOG_ENTRY_ORDER]).
 */
internal fun ByteArray.compareUnsignedTo(other: ByteArray): Int {
    val n = minOf(size, other.size)
    for (i in 0 until n) {
        val a = this[i].toInt() and 0xFF
        val b = other[i].toInt() and 0xFF
        if (a != b) return a - b
    }
    return size - other.size
}

/**
 * Strict UTF-8 decode: `null` on any malformed, overlong, surrogate-half, out-of-range, or
 * truncated sequence. Kotlin's built-in [ByteArray.decodeToString] silently substitutes the
 * replacement character instead of failing, which is wrong for a wire format that must reject
 * a non-UTF-8 name rather than corrupt and accept it.
 */
internal fun decodeStrictUtf8(bytes: ByteArray): String? {
    val sb = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val len: Int
        var cp: Int
        when {
            b0 and 0x80 == 0x00 -> { len = 1; cp = b0 }
            b0 and 0xE0 == 0xC0 -> { len = 2; cp = b0 and 0x1F }
            b0 and 0xF0 == 0xE0 -> { len = 3; cp = b0 and 0x0F }
            b0 and 0xF8 == 0xF0 -> { len = 4; cp = b0 and 0x07 }
            else -> return null
        }
        if (i + len > bytes.size) return null
        for (j in 1 until len) {
            val b = bytes[i + j].toInt() and 0xFF
            if (b and 0xC0 != 0x80) return null
            cp = (cp shl 6) or (b and 0x3F)
        }
        val minCp = when (len) { 1 -> 0x0000; 2 -> 0x0080; 3 -> 0x0800; else -> 0x10000 }
        if (cp < minCp || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val c = cp - 0x10000
            sb.append(((c ushr 10) + 0xD800).toChar())
            sb.append(((c and 0x3FF) + 0xDC00).toChar())
        }
        i += len
    }
    return sb.toString()
}

private fun writeU16BE(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value ushr 8).toByte()
    out[offset + 1] = value.toByte()
}

private fun readU16BE(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

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

// --- Device certificate (`azd`) --------------------------------------------

/**
 * A decoded device certificate: an association claim binding [devicePk] to [rootPk], signed by
 * the root secret. Confers nothing by itself — per the spec, a verifier must additionally check
 * the *transport* node id equals [devicePk] before treating a connection as that device.
 */
data class DeviceCert(
    val version: Int,
    val roles: Int,
    val rootPk: ByteArray,
    val devicePk: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    val name: String,
    val signature: ByteArray,
) {
    val isMailbox: Boolean get() = (roles and ROLE_MAILBOX) != 0

    // `data class` would give us reference equality on the ByteArray fields, which is wrong here:
    // the device set is the union of `device_add` certificates, so the duplicate self-certs the
    // design anticipates ("two devices restored from the same phrase … fold identically") only
    // collapse if structurally-identical certs compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceCert) return false
        return version == other.version &&
            roles == other.roles &&
            issuedAt == other.issuedAt &&
            expiresAt == other.expiresAt &&
            name == other.name &&
            rootPk.contentEquals(other.rootPk) &&
            devicePk.contentEquals(other.devicePk) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + roles
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + rootPk.contentHashCode()
        result = 31 * result + devicePk.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Encodes/decodes/verifies the `azd` device-certificate payload: version(1) | roles(1) |
 * root_pk(32) | device_pk(32) | issued_at(4, u32) | expires_at(4, u32) | name_len(1, 0–63) |
 * name(name_len) | signature(64), as `"azd" + base32(payload)`.
 */
object DeviceCertCodec {
    // version + roles + rootPk + devicePk + issuedAt + expiresAt + nameLen
    private const val HEADER_LEN = 1 + 1 + PUBLIC_KEY_LEN + PUBLIC_KEY_LEN + 4 + 4 + 1 // 75

    private fun signedBytes(
        version: Int,
        roles: Int,
        rootPk: ByteArray,
        devicePk: ByteArray,
        issuedAt: Long,
        expiresAt: Long,
        nameBytes: ByteArray,
    ): ByteArray {
        val out = ByteArray(HEADER_LEN + nameBytes.size)
        out[0] = version.toByte()
        out[1] = (roles and ROLE_MASK).toByte()
        rootPk.copyInto(out, 2)
        devicePk.copyInto(out, 34)
        writeU32BE(out, 66, issuedAt)
        writeU32BE(out, 70, expiresAt)
        out[74] = nameBytes.size.toByte()
        nameBytes.copyInto(out, 75)
        return out
    }

    /** Builds and signs a new certificate (always [CURRENT_WIRE_VERSION]) with the root secret. */
    fun issue(
        roles: Int,
        rootPk: ByteArray,
        devicePk: ByteArray,
        issuedAt: Long,
        expiresAt: Long,
        name: String,
        rootSecret: ByteArray,
        ed25519: Ed25519,
    ): DeviceCert {
        require(rootPk.size == PUBLIC_KEY_LEN) { "rootPk must be $PUBLIC_KEY_LEN bytes" }
        require(devicePk.size == PUBLIC_KEY_LEN) { "devicePk must be $PUBLIC_KEY_LEN bytes" }
        val nameBytes = name.encodeToByteArray()
        require(nameBytes.size <= MAX_NAME_LEN) { "name must encode to at most $MAX_NAME_LEN UTF-8 bytes" }
        val toSign = signedBytes(CURRENT_WIRE_VERSION, roles, rootPk, devicePk, issuedAt, expiresAt, nameBytes)
        val signature = ed25519.sign(rootSecret, toSign)
        return DeviceCert(CURRENT_WIRE_VERSION, roles and ROLE_MASK, rootPk, devicePk, issuedAt, expiresAt, name, signature)
    }

    /** The literal `azd…` string for [cert]. Encodes whatever `cert.version` holds (not
     *  necessarily [CURRENT_WIRE_VERSION]) so tests can build an invalid-version payload by
     *  `cert.copy(version = ...)` without a separate raw encoder. */
    fun encode(cert: DeviceCert): String {
        val nameBytes = cert.name.encodeToByteArray()
        require(nameBytes.size <= MAX_NAME_LEN) { "name must encode to at most $MAX_NAME_LEN UTF-8 bytes" }
        val body = signedBytes(cert.version, cert.roles, cert.rootPk, cert.devicePk, cert.issuedAt, cert.expiresAt, nameBytes)
        return CERT_PREFIX + Base32.encode(body + cert.signature)
    }

    /**
     * Returns `null` on any spec rejection: wrong prefix, bad base32, wrong version, `name_len`
     * over 63, or a payload shorter or longer than the exact `75 + name_len + 64` bytes the
     * fields describe (both truncated and overlong payloads are rejected, never partially
     * processed) — including a name that doesn't decode as valid UTF-8.
     */
    fun decode(s: String): DeviceCert? {
        val trimmed = s.trim()
        if (!trimmed.startsWith(CERT_PREFIX)) return null
        val bytes = Base32.decode(trimmed.substring(CERT_PREFIX.length)) ?: return null
        if (bytes.size < HEADER_LEN) return null
        val version = bytes[0].toInt() and 0xFF
        if (version != CURRENT_WIRE_VERSION) return null
        val roles = bytes[1].toInt() and 0xFF and ROLE_MASK
        val rootPk = bytes.copyOfRange(2, 34)
        val devicePk = bytes.copyOfRange(34, 66)
        val issuedAt = readU32BE(bytes, 66)
        val expiresAt = readU32BE(bytes, 70)
        val nameLen = bytes[74].toInt() and 0xFF
        if (nameLen > MAX_NAME_LEN) return null
        val expectedLen = HEADER_LEN + nameLen + SIGNATURE_LEN
        if (bytes.size != expectedLen) return null
        val name = decodeStrictUtf8(bytes.copyOfRange(75, 75 + nameLen)) ?: return null
        val signature = bytes.copyOfRange(75 + nameLen, expectedLen)
        return DeviceCert(version, roles, rootPk, devicePk, issuedAt, expiresAt, name, signature)
    }

    /**
     * Self-contained verification per "Certificate Verification Is Self-Contained": the
     * signature must verify against the embedded `root_pk`, and `expires_at` must be `0`
     * (never) or strictly in the future of [nowEpochSeconds]. [revokedDevicePks] lets a caller
     * that already holds a verified revocation set reject a revoked device's certificate too
     * (no external lookup is performed — only what the caller passes in is consulted).
     */
    fun verify(
        cert: DeviceCert,
        ed25519: Ed25519,
        nowEpochSeconds: Long,
        revokedDevicePks: List<ByteArray> = emptyList(),
    ): Boolean {
        if (cert.version != CURRENT_WIRE_VERSION) return false
        if (cert.expiresAt != 0L && cert.expiresAt <= nowEpochSeconds) return false
        if (revokedDevicePks.any { it.contentEquals(cert.devicePk) }) return false
        val nameBytes = cert.name.encodeToByteArray()
        val toVerify = signedBytes(cert.version, cert.roles, cert.rootPk, cert.devicePk, cert.issuedAt, cert.expiresAt, nameBytes)
        return ed25519.verify(cert.rootPk, toVerify, cert.signature)
    }
}

// --- Revocation statement (`azr`) -------------------------------------------

/** A signed statement that permanently invalidates [revokedDevicePk]'s certificates for [rootPk],
 *  regardless of any certificate's `expires_at`. */
data class DeviceRevocation(
    val version: Int,
    val rootPk: ByteArray,
    val revokedDevicePk: ByteArray,
    val revokedAt: Long,
    val signature: ByteArray,
) {
    // See DeviceCert.equals — the revocation set is likewise a set, deduped by value.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceRevocation) return false
        return version == other.version &&
            revokedAt == other.revokedAt &&
            rootPk.contentEquals(other.rootPk) &&
            revokedDevicePk.contentEquals(other.revokedDevicePk) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + revokedAt.hashCode()
        result = 31 * result + rootPk.contentHashCode()
        result = 31 * result + revokedDevicePk.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Encodes/decodes/verifies the `azr` revocation payload: version(1) | root_pk(32) |
 * revoked_device_pk(32) | revoked_at(4, u32) | signature(64), as `"azr" + base32(payload)`.
 * Fixed-length (no variable field), so any size other than the exact 133 bytes is rejected.
 */
object DeviceRevocationCodec {
    // version + rootPk + revokedDevicePk + revokedAt
    private const val HEADER_LEN = 1 + PUBLIC_KEY_LEN + PUBLIC_KEY_LEN + 4 // 69
    private const val TOTAL_LEN = HEADER_LEN + SIGNATURE_LEN // 133

    private fun signedBytes(version: Int, rootPk: ByteArray, revokedDevicePk: ByteArray, revokedAt: Long): ByteArray {
        val out = ByteArray(HEADER_LEN)
        out[0] = version.toByte()
        rootPk.copyInto(out, 1)
        revokedDevicePk.copyInto(out, 33)
        writeU32BE(out, 65, revokedAt)
        return out
    }

    /** Builds and signs a new revocation (always [CURRENT_WIRE_VERSION]) with the root secret. */
    fun issue(
        rootPk: ByteArray,
        revokedDevicePk: ByteArray,
        revokedAt: Long,
        rootSecret: ByteArray,
        ed25519: Ed25519,
    ): DeviceRevocation {
        require(rootPk.size == PUBLIC_KEY_LEN) { "rootPk must be $PUBLIC_KEY_LEN bytes" }
        require(revokedDevicePk.size == PUBLIC_KEY_LEN) { "revokedDevicePk must be $PUBLIC_KEY_LEN bytes" }
        val toSign = signedBytes(CURRENT_WIRE_VERSION, rootPk, revokedDevicePk, revokedAt)
        val signature = ed25519.sign(rootSecret, toSign)
        return DeviceRevocation(CURRENT_WIRE_VERSION, rootPk, revokedDevicePk, revokedAt, signature)
    }

    fun encode(revocation: DeviceRevocation): String {
        val body = signedBytes(revocation.version, revocation.rootPk, revocation.revokedDevicePk, revocation.revokedAt)
        return REVOCATION_PREFIX + Base32.encode(body + revocation.signature)
    }

    /** Returns `null` on wrong prefix, bad base32, wrong version, or any length other than the
     *  exact 133 bytes (truncated or overlong). */
    fun decode(s: String): DeviceRevocation? {
        val trimmed = s.trim()
        if (!trimmed.startsWith(REVOCATION_PREFIX)) return null
        val bytes = Base32.decode(trimmed.substring(REVOCATION_PREFIX.length)) ?: return null
        if (bytes.size != TOTAL_LEN) return null
        val version = bytes[0].toInt() and 0xFF
        if (version != CURRENT_WIRE_VERSION) return null
        val rootPk = bytes.copyOfRange(1, 33)
        val revokedDevicePk = bytes.copyOfRange(33, 65)
        val revokedAt = readU32BE(bytes, 65)
        val signature = bytes.copyOfRange(69, TOTAL_LEN)
        return DeviceRevocation(version, rootPk, revokedDevicePk, revokedAt, signature)
    }

    /** Signature check against the embedded root key. Revocations never expire. */
    fun verify(revocation: DeviceRevocation, ed25519: Ed25519): Boolean {
        if (revocation.version != CURRENT_WIRE_VERSION) return false
        val toVerify = signedBytes(revocation.version, revocation.rootPk, revocation.revokedDevicePk, revocation.revokedAt)
        return ed25519.verify(revocation.rootPk, toVerify, revocation.signature)
    }
}

// --- QR-link payload (`azl`) -------------------------------------------------

/** The unsigned payload a new (not-yet-certified) device displays to be scanned by a
 *  root-holding device: its own device key, a display name, and an opaque connect ticket. */
data class LinkPayload(
    val version: Int,
    val devicePk: ByteArray,
    val name: String,
    val ticket: ByteArray,
) {
    // See DeviceCert.equals — ByteArray fields need value equality, not reference equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkPayload) return false
        return version == other.version &&
            name == other.name &&
            devicePk.contentEquals(other.devicePk) &&
            ticket.contentEquals(other.ticket)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + name.hashCode()
        result = 31 * result + devicePk.contentHashCode()
        result = 31 * result + ticket.contentHashCode()
        return result
    }
}

/**
 * Convenience constructor for a new (current-version) link payload -- callers outside
 * `core` (multi-device-identity task 6.1's device-linking flow, in `shared`) don't need
 * to know the wire version number, matching [DeviceCertCodec.issue]'s posture (and the
 * Rust reference's own `LinkPayload::new`). [CURRENT_WIRE_VERSION] itself stays
 * module-internal -- this is the one sanctioned way to reach it from outside `core`.
 */
fun newLinkPayload(devicePk: ByteArray, name: String, ticket: ByteArray): LinkPayload =
    LinkPayload(CURRENT_WIRE_VERSION, devicePk, name, ticket)

/**
 * Encodes/decodes the `azl` link payload: version(1) | device_pk(32) | name_len(1) |
 * name(name_len) | ticket_len(2) | ticket(ticket_len), as `"azl" + base32(payload)`. Never
 * signed — the whole point is that the new device holds no authority yet.
 *
 * The spec doesn't restate a bound on this `name_len`, but it is the same field (display name,
 * `1` length byte) as the certificate's, so this codec applies the same 0–63 limit for
 * consistency and to keep both wire formats' name fields bounded the same way.
 */
object LinkPayloadCodec {
    private const val HEADER_LEN = 1 + PUBLIC_KEY_LEN + 1 // version + devicePk + nameLen = 34

    /** Builds the literal `azl…` string. Encodes whatever `payload.version` holds so tests can
     *  build an invalid-version payload via `.copy(version = ...)` without a separate raw
     *  encoder; production callers should use [CURRENT_WIRE_VERSION]. */
    fun encode(payload: LinkPayload): String {
        require(payload.devicePk.size == PUBLIC_KEY_LEN) { "devicePk must be $PUBLIC_KEY_LEN bytes" }
        val nameBytes = payload.name.encodeToByteArray()
        require(nameBytes.size <= MAX_NAME_LEN) { "name must encode to at most $MAX_NAME_LEN UTF-8 bytes" }
        require(payload.ticket.size <= 0xFFFF) { "ticket must fit in a u16 length" }
        val out = ByteArray(HEADER_LEN + nameBytes.size + 2 + payload.ticket.size)
        out[0] = payload.version.toByte()
        payload.devicePk.copyInto(out, 1)
        out[33] = nameBytes.size.toByte()
        nameBytes.copyInto(out, 34)
        writeU16BE(out, 34 + nameBytes.size, payload.ticket.size)
        payload.ticket.copyInto(out, 36 + nameBytes.size)
        return LINK_PREFIX + Base32.encode(out)
    }

    /** Returns `null` on wrong prefix, bad base32, wrong version, `name_len` over 63, a
     *  truncated payload (not enough bytes for the name or the `ticket_len`/ticket that follow
     *  it), or an overlong one (trailing bytes past the declared ticket). */
    fun decode(s: String): LinkPayload? {
        val trimmed = s.trim()
        if (!trimmed.startsWith(LINK_PREFIX)) return null
        val bytes = Base32.decode(trimmed.substring(LINK_PREFIX.length)) ?: return null
        if (bytes.size < HEADER_LEN) return null
        val version = bytes[0].toInt() and 0xFF
        if (version != CURRENT_WIRE_VERSION) return null
        val devicePk = bytes.copyOfRange(1, 33)
        val nameLen = bytes[33].toInt() and 0xFF
        if (nameLen > MAX_NAME_LEN) return null
        val ticketLenOffset = 34 + nameLen
        if (bytes.size < ticketLenOffset + 2) return null
        val name = decodeStrictUtf8(bytes.copyOfRange(34, 34 + nameLen)) ?: return null
        val ticketLen = readU16BE(bytes, ticketLenOffset)
        val ticketOffset = ticketLenOffset + 2
        val expectedLen = ticketOffset + ticketLen
        if (bytes.size != expectedLen) return null
        val ticket = bytes.copyOfRange(ticketOffset, expectedLen)
        return LinkPayload(version, devicePk, name, ticket)
    }
}

// --- Verification words -------------------------------------------------

/**
 * The four words both devices display during a QR-link enrollment, before any grant is
 * possible: the first 44 bits of `SHA-256(lower_pk || higher_pk)` — the two device public keys
 * sorted bytewise ascending — read as four 11-bit big-endian indices into the BIP-39 wordlist.
 */
object VerificationWords {
    private const val WORD_COUNT = 4
    private const val BITS_PER_WORD = 11 // 4 * 11 = 44

    fun compute(devicePkA: ByteArray, devicePkB: ByteArray): List<String> {
        require(devicePkA.size == PUBLIC_KEY_LEN) { "devicePkA must be $PUBLIC_KEY_LEN bytes" }
        require(devicePkB.size == PUBLIC_KEY_LEN) { "devicePkB must be $PUBLIC_KEY_LEN bytes" }
        val (lower, higher) = if (devicePkA.compareUnsignedTo(devicePkB) <= 0) {
            devicePkA to devicePkB
        } else {
            devicePkB to devicePkA
        }
        val hash = Sha256.hash(lower + higher)

        // The first 44 bits: all 8 bits of hash[0..4] (40 bits) plus the top 4 bits of hash[5].
        val bits = ArrayList<Int>(WORD_COUNT * BITS_PER_WORD)
        for (byteIndex in 0 until 6) {
            val v = hash[byteIndex].toInt() and 0xFF
            val bitsToTake = if (byteIndex < 5) 8 else 4
            for (b in 7 downTo (8 - bitsToTake)) bits.add((v ushr b) and 1)
        }

        return (0 until WORD_COUNT).map { w ->
            var idx = 0
            for (i in 0 until BITS_PER_WORD) idx = (idx shl 1) or bits[w * BITS_PER_WORD + i]
            BIP39_WORDS[idx]
        }
    }
}

/**
 * A minimal, dependency-free base32 codec: RFC 4648 alphabet, lowercase, no padding. Copied in
 * (rather than shared) because `core` cannot depend on the `link` module (its `Invite.kt` has
 * the same private object) and `core` is the dependency leaf.
 */
private object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val DECODE_MAP = IntArray(128) { -1 }.also {
        for ((i, c) in ALPHABET.withIndex()) it[c.code] = i
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsInBuffer = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                sb.append(ALPHABET[(buffer ushr bitsInBuffer) and 0x1F])
            }
        }
        if (bitsInBuffer > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsInBuffer)) and 0x1F])
        }
        return sb.toString()
    }

    /** Null on any invalid character or non-zero padding bits (a corrupted/truncated encoding). */
    fun decode(s: String): ByteArray? {
        if (s.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>((s.length * 5) / 8 + 1)
        var buffer = 0
        var bitsInBuffer = 0
        for (c in s) {
            val code = c.code
            val value = if (code < DECODE_MAP.size) DECODE_MAP[code] else -1
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bitsInBuffer += 5
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8
                out.add(((buffer ushr bitsInBuffer) and 0xFF).toByte())
            }
        }
        if (bitsInBuffer > 0) {
            val mask = (1 shl bitsInBuffer) - 1
            if ((buffer and mask) != 0) return null // leftover bits must be zero padding
        }
        return out.toByteArray()
    }
}
