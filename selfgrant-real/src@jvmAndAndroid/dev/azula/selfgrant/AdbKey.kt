package dev.azula.selfgrant

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The app's own adb identity: a 2048-bit RSA key pair, its adb public-key
 * encoding (what `adb_keys` and pairing PeerInfo carry), and the self-signed
 * certificate the TLS client presents. adbd checks the certificate's public key
 * against the registered adb key; nothing else in the certificate matters.
 */
internal class AdbKeyPair(val keyPair: KeyPair) {
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    /** `<base64 of the 524-byte RSAPublicKey struct> <name>\0`, as adb writes it. */
    fun adbPublicKey(name: String): ByteArray = encodeAdbPublicKey(publicKey, name)

    val certificate: ByteArray by lazy { selfSignedCertificate(keyPair) }

    /** PKCS#8 bytes, to persist (encrypted by the caller). */
    fun privateBytes(): ByteArray = keyPair.private.encoded

    companion object {
        fun generate(): AdbKeyPair {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            return AdbKeyPair(g.generateKeyPair())
        }

        fun fromPrivateBytes(pkcs8: ByteArray): AdbKeyPair {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey
            val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
            return AdbKeyPair(KeyPair(pub, priv))
        }
    }
}

private const val MODULUS_BYTES = 2048 / 8
private const val MODULUS_WORDS = MODULUS_BYTES / 4

/** AOSP libcrypto_utils android_pubkey.c: modulus_size_words, n0inv, n, rr, exponent (little-endian). */
internal fun encodeAdbPublicKey(key: RSAPublicKey, name: String): ByteArray {
    val n = key.modulus
    require(n.bitLength() <= 2048) { "adb keys are RSA-2048" }
    val r32 = BigInteger.ONE.shiftLeft(32)
    val n0inv = n.mod(r32).modInverse(r32).negate()
    val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.TWO, n)

    val buf = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(MODULUS_WORDS)
    buf.putInt(n0inv.toInt())
    buf.put(Ed25519.toLe(n, MODULUS_BYTES))
    buf.put(Ed25519.toLe(rr, MODULUS_BYTES))
    buf.putInt(key.publicExponent.toInt())
    val b64 = Base64.getEncoder().encode(buf.array())
    return b64 + " $name\u0000".toByteArray(Charsets.UTF_8)
}

// ---- minimal DER, enough for a self-signed X.509 v3 certificate ----

private fun der(tag: Int, body: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(tag)
    val n = body.size
    when {
        n < 0x80 -> out.write(n)
        n < 0x100 -> { out.write(0x81); out.write(n) }
        n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
        else -> error("DER body too large")
    }
    out.write(body)
    return out.toByteArray()
}

private fun seq(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

private val SHA256_WITH_RSA = seq(
    der(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)),
    der(0x05, ByteArray(0)),
)

private fun name(cn: String) =
    seq(der(0x31, seq(der(0x06, byteArrayOf(0x55, 0x04, 0x03)), der(0x0c, cn.toByteArray(Charsets.UTF_8)))))

/**
 * A self-signed certificate for [keyPair], CN=00 (what adb itself uses), signed
 * with SHA256withRSA. Valid 1970-01-01 to 2100-01-01.
 */
internal fun selfSignedCertificate(keyPair: KeyPair): ByteArray {
    val tbs = seq(
        der(0xa0, der(0x02, byteArrayOf(2))), // version v3
        der(0x02, byteArrayOf(1)), // serial
        SHA256_WITH_RSA,
        name("00"),
        seq(der(0x17, "700101000000Z".toByteArray()), der(0x18, "21000101000000Z".toByteArray())),
        name("00"),
        keyPair.public.encoded, // SubjectPublicKeyInfo
    )
    val sig = Signature.getInstance("SHA256withRSA").run {
        initSign(keyPair.private)
        update(tbs)
        sign()
    }
    return seq(tbs, SHA256_WITH_RSA, der(0x03, byteArrayOf(0) + sig))
}
