import dev.azula.core.Ed25519
import dev.azula.core.Sha256

/**
 * A deterministic stand-in for Ed25519 used only by tests — NOT a real signature scheme, and
 * never shipped. A "public key" is defined as `SHA-256(secret)`, and a "signature" embeds the
 * secret plus a MAC of `secret || data`, so [verify] can check the `(secret, publicKey)` pairing
 * without ever being handed the secret directly. This lets DeviceCert/EventLog codec and
 * verification tests exercise every success/failure path deterministically (fixed keys, no
 * randomness) without a real crypto dependency in `core` — production wires the same [Ed25519]
 * seam to iroh-kmp's real Ed25519 functions instead.
 */
internal class FakeEd25519 : Ed25519 {
    override fun sign(secret: ByteArray, data: ByteArray): ByteArray {
        require(secret.size == 32) { "fake secret must be 32 bytes" }
        return secret + Sha256.hash(secret + data)
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        val secret = signature.copyOfRange(0, 32)
        val mac = signature.copyOfRange(32, 64)
        if (!Sha256.hash(secret).contentEquals(publicKey)) return false
        return Sha256.hash(secret + data).contentEquals(mac)
    }
}

/** The [FakeEd25519]-paired public key for a given fake secret: `SHA-256(secret)`. */
internal fun fakePublicKey(secret: ByteArray): ByteArray = Sha256.hash(secret)

/** Decodes a lowercase hex string into bytes (test-only; production code never needs this). */
internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** A fixed, non-random 32-byte key: `[fill, fill+1, .., fill+31] mod 256`. Deterministic so
 *  encodings are reproducible across runs and languages (see task 2.4). */
internal fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }
