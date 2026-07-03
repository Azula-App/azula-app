package dev.azula.link

private const val INVITE_PREFIX = "azi"
private const val CURRENT_VERSION = 1

// Binary layout offsets/sizes (all integers big-endian) — see
// azula-docs/docs/invitations.md for the full spec and shared test vectors.
private const val INVITE_ID_LEN = 8
private const val HEADER_LEN = 20 // version(1) + flags(1) + inviteId(8) + issuedAt(4) + expiresAt(4) + ticketLen(2)
private const val SIGNATURE_LEN = 64

/**
 * A decoded azula invite: the ticket plus identity (an invite id), a validity
 * window, and optional single-use/signed semantics. See
 * azula-docs/docs/invitations.md for the trust model — the ticket lets a
 * redeemer dial, but the issuer only ever trusts invites it finds in its own
 * issued-invite store.
 */
data class InvitePayload(
    val version: Int,
    val flags: Int,
    val inviteId: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    val ticket: ByteArray,
    val signature: ByteArray?,
) {
    /** flags bit 0: [signature] is present and should verify against the issuer's node key. */
    val signed: Boolean get() = (flags and 0x01) != 0

    /** flags bit 1: the invite is consumed after the first successful accept. */
    val singleUse: Boolean get() = (flags and 0x02) != 0

    /** Lowercase-hex render of [inviteId] — the display fingerprint. */
    val fingerprint: String get() = inviteId.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * Bytes `[0, 20+ticket.size)` — the header plus ticket, with no signature.
     * This is what the issuer signs and what a verifier checks [signature]
     * against; [InviteCodec] itself never signs or verifies.
     */
    fun signedInputBytes(): ByteArray {
        val out = ByteArray(HEADER_LEN + ticket.size)
        writeHeader(out, this)
        ticket.copyInto(out, HEADER_LEN)
        return out
    }
}

/**
 * Encodes/decodes the azula invite payload: a fixed binary header followed by
 * the opaque issuer ticket and an optional trailing Ed25519 signature,
 * rendered as `"azi" + base32(payload)` (RFC 4648, no padding, lowercase — see
 * [Base32]). Pure layout/parsing only: signing and verifying the signature is
 * the caller's job, via [InvitePayload.signedInputBytes]. See
 * azula-docs/docs/invitations.md for the wire layout and the shared V1/V2 test
 * vectors this codec must match byte-for-byte.
 */
object InviteCodec {
    fun encode(payload: InvitePayload): String {
        val signedInput = payload.signedInputBytes()
        val sig = payload.signature
        val bytes = if (sig != null) signedInput + sig else signedInput
        return INVITE_PREFIX + Base32.encode(bytes)
    }

    /** Returns null on any spec rejection: wrong prefix, bad base32, wrong version, truncation,
     *  a ticket_len that overruns the payload, or a signed flag with no (or a short) signature. */
    fun decode(s: String): InvitePayload? {
        val trimmed = s.trim()
        if (!trimmed.startsWith(INVITE_PREFIX)) return null
        val bytes = Base32.decode(trimmed.substring(INVITE_PREFIX.length)) ?: return null
        if (bytes.size < HEADER_LEN) return null

        val version = bytes[0].toInt() and 0xFF
        if (version != CURRENT_VERSION) return null
        val flags = bytes[1].toInt() and 0xFF
        val inviteId = bytes.copyOfRange(2, 2 + INVITE_ID_LEN)
        val issuedAt = readU32BE(bytes, 10)
        val expiresAt = readU32BE(bytes, 14)
        val ticketLen = readU16BE(bytes, 18)
        val ticketEnd = HEADER_LEN + ticketLen
        if (ticketEnd > bytes.size) return null
        val ticket = bytes.copyOfRange(HEADER_LEN, ticketEnd)

        val signature = if ((flags and 0x01) != 0) {
            val sigEnd = ticketEnd + SIGNATURE_LEN
            if (sigEnd > bytes.size) return null
            bytes.copyOfRange(ticketEnd, sigEnd)
        } else {
            null
        }

        return InvitePayload(version, flags, inviteId, issuedAt, expiresAt, ticket, signature)
    }
}

private fun writeHeader(out: ByteArray, p: InvitePayload) {
    out[0] = p.version.toByte()
    out[1] = p.flags.toByte()
    p.inviteId.copyInto(out, 2)
    writeU32BE(out, 10, p.issuedAt)
    writeU32BE(out, 14, p.expiresAt)
    writeU16BE(out, 18, p.ticket.size)
}

/** True if [s] looks like a bare encoded invite (does not decode/validate the payload). */
fun isInvite(s: String): Boolean = s.trim().startsWith(INVITE_PREFIX)

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

private fun writeU16BE(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value ushr 8).toByte()
    out[offset + 1] = value.toByte()
}

private fun readU16BE(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

/**
 * A minimal, dependency-free base32 codec: RFC 4648 alphabet, lowercase, no
 * padding — the same alphabet family iroh tickets use. Mirrors how [Qr] hand-
 * rolls its own encoding rather than pulling in a dependency.
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
