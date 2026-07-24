package dev.azula.sync

import dev.azula.core.Ed25519
import dev.azula.core.Sha256

/**
 * A deterministic stand-in for Ed25519, for this module's tests only -- NOT a real
 * signature scheme, never shipped. Same construction as `core/test/CryptoTestFixtures.kt`'s
 * `FakeEd25519` (duplicated rather than shared -- see that file's kdoc, and
 * `sync-api/test/SyncTestFixtures.kt`'s copy, for the rationale: each module's test
 * compilation is isolated). A "public key" is `SHA-256(secret)`; a "signature" embeds
 * the secret plus a MAC of `secret || data`, so [verify] can check the pairing
 * without ever being handed the secret directly.
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

/** A fixed, non-random 32-byte key: `[fill, fill+1, .., fill+31] mod 256`. Deterministic so
 *  every test using it is reproducible. */
internal fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }
