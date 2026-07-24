package dev.azula.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [IrohKmpEd25519] wires `core`'s [dev.azula.core.Ed25519] seam to iroh-kmp's real Ed25519
 * free functions (multi-device-identity task 1.1's `crypto.rs`), now that iroh-kmp 0.1.2 is
 * resolvable (task 1.3). This is the first place in `azula-app` to call those functions --
 * mirrors `crypto.rs`'s own RFC 8032 test vectors, closing the "Kotlin cannot perform real
 * Ed25519 verification" gap noted in this change's design.md.
 */
class IrohKmpEd25519Test {
    // RFC 8032 §7.1 TEST 1: 32-byte seed, 0-byte message. Same vector `crypto.rs`'s
    // `rfc8032_test1` asserts against.
    private val secret = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val public = hexToBytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val signature = hexToBytes(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    @Test
    fun signsRfc8032TestVector1() {
        assertEquals(signature.toList(), IrohKmpEd25519.sign(secret, ByteArray(0)).toList())
    }

    @Test
    fun verifiesRfc8032TestVector1() {
        assertTrue(IrohKmpEd25519.verify(public, ByteArray(0), signature))
    }

    @Test
    fun rejectsATamperedSignature() {
        val tampered = signature.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(IrohKmpEd25519.verify(public, ByteArray(0), tampered))
    }

    @Test
    fun rejectsTheWrongPublicKey() {
        val otherPublic = ByteArray(32) { 0x42 }
        assertFalse(IrohKmpEd25519.verify(otherPublic, ByteArray(0), signature))
    }

    @Test
    fun signAndVerifyRoundTripOnArbitraryData() {
        val data = "hello multi-device-identity".encodeToByteArray()
        val sig = IrohKmpEd25519.sign(secret, data)

        assertEquals(64, sig.size)
        assertTrue(IrohKmpEd25519.verify(public, data, sig))
        assertFalse(IrohKmpEd25519.verify(public, "different data".encodeToByteArray(), sig))
    }
}

private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
