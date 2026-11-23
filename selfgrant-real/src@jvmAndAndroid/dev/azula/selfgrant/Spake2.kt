package dev.azula.selfgrant

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * SPAKE2 over edwards25519 as implemented by BoringSSL (crypto/curve25519/
 * spake25519.cc, ISC license), which is what adbd's pairing uses
 * (system/core/adb/pairing_auth). Ported to Kotlin from that file's algorithm;
 * the constants M and N are regenerated from BoringSSL's documented seeds in
 * Spake2Test, so they are checked rather than trusted.
 *
 * This is NOT RFC 9382: different M/N, a 64-byte transcript hash with 8-byte
 * little-endian length prefixes, and the private scalar is a multiple of 8.
 *
 * Not constant-time (BigInteger). The secret is a 6-digit code that is only
 * ever offered to a service on this same phone, so timing side channels aren't
 * part of the threat model here.
 */
internal object Ed25519 {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    class Point(val x: BigInteger, val y: BigInteger) {
        override fun equals(other: Any?) = other is Point && x == other.x && y == other.y
        override fun hashCode() = x.hashCode() * 31 + y.hashCode()
    }

    val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    /** The standard base point: y = 4/5, even x. */
    val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        Point(recoverX(y, 0)!!, y)
    }

    /** Unified addition; complete on edwards25519 (a = -1, d non-square). */
    fun add(a: Point, b: Point): Point {
        val x1y2 = a.x.multiply(b.y)
        val x2y1 = b.x.multiply(a.y)
        val y1y2 = a.y.multiply(b.y)
        val x1x2 = a.x.multiply(b.x)
        val dxy = D.multiply(x1x2).mod(P).multiply(a.y.multiply(b.y).mod(P)).mod(P)
        val x3 = x1y2.add(x2y1).multiply(BigInteger.ONE.add(dxy).modInverse(P)).mod(P)
        val y3 = y1y2.add(x1x2).multiply(BigInteger.ONE.subtract(dxy).mod(P).modInverse(P)).mod(P)
        return Point(x3, y3)
    }

    fun negate(a: Point) = Point(a.x.negate().mod(P), a.y)

    fun mul(k: BigInteger, p: Point): Point {
        var result = IDENTITY
        var addend = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    fun isOnCurve(p: Point): Boolean {
        val x2 = p.x.multiply(p.x).mod(P)
        val y2 = p.y.multiply(p.y).mod(P)
        val lhs = y2.subtract(x2).mod(P)
        val rhs = BigInteger.ONE.add(D.multiply(x2).mod(P).multiply(y2)).mod(P)
        return lhs == rhs
    }

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val num = y2.subtract(BigInteger.ONE).mod(P)
        val den = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (den.signum() == 0) return null
        val x2 = num.multiply(den.modInverse(P)).mod(P)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).mod(P) != x2) return null
        if (x.signum() == 0 && sign == 1) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x).mod(P)
        return x
    }

    fun encode(p: Point): ByteArray {
        val out = toLe(p.y, 32)
        if (p.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Decodes a compressed point; null if it is not on the curve. */
    fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val sign = (bytes[31].toInt() ushr 7) and 1
        val c = bytes.copyOf()
        c[31] = (c[31].toInt() and 0x7f).toByte()
        val y = fromLe(c).mod(P)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y)
    }

    fun fromLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    fun toLe(n: BigInteger, size: Int): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(size)
        // be is big-endian, possibly with a leading sign byte.
        var i = 0
        var j = be.size - 1
        while (j >= 0 && i < size) out[i++] = be[j--]
        return out
    }
}

internal class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val random: SecureRandom = SecureRandom(),
    /** 64 bytes standing in for the random draw; for known-answer tests only. */
    private val privateSeed: ByteArray? = null,
) {
    enum class Role { Alice, Bob }

    private var privateScalar: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null

    /** The 32-byte message to send to the peer. */
    fun generateMessage(password: ByteArray): ByteArray {
        check(myMsg == null) { "message already generated" }
        val seed = privateSeed ?: ByteArray(64).also { random.nextBytes(it) }
        // Cofactor cleared on the peer's point by making our scalar a multiple of 8.
        val x = Ed25519.fromLe(seed).mod(Ed25519.L).shiftLeft(3)
        val pw = MessageDigest.getInstance("SHA-512").digest(password)
        val w = Ed25519.fromLe(pw).mod(Ed25519.L)
        val mask = Ed25519.mul(w, if (role == Role.Alice) M else N)
        val pStar = Ed25519.add(Ed25519.mul(x, Ed25519.BASE), mask)
        privateScalar = x
        passwordHash = pw
        return Ed25519.encode(pStar).also { myMsg = it }
    }

    /** The 64-byte shared key, or null if the peer's message is not a valid point. */
    fun processMessage(theirMsg: ByteArray): ByteArray? {
        val mine = checkNotNull(myMsg) { "generateMessage first" }
        val pw = passwordHash!!
        val qStar = Ed25519.decode(theirMsg) ?: return null
        val w = Ed25519.fromLe(pw).mod(Ed25519.L)
        val peersMask = Ed25519.mul(w, if (role == Role.Alice) N else M)
        val q = Ed25519.add(qStar, Ed25519.negate(peersMask))
        val shared = Ed25519.encode(Ed25519.mul(privateScalar!!, q))

        val sha = MessageDigest.getInstance("SHA-512")
        fun upd(data: ByteArray) {
            var l = data.size.toLong()
            val len = ByteArray(8) { val b = (l and 0xff).toByte(); l = l ushr 8; b }
            sha.update(len)
            sha.update(data)
        }
        if (role == Role.Alice) {
            upd(myName); upd(theirName); upd(mine); upd(theirMsg)
        } else {
            upd(theirName); upd(myName); upd(theirMsg); upd(mine)
        }
        upd(shared)
        upd(pw)
        return sha.digest()
    }

    companion object {
        /** BoringSSL's N and M, compressed (spake25519.cc header comment). */
        private val N_ENCODED = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")
        private val M_ENCODED = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        val N: Ed25519.Point = Ed25519.decode(N_ENCODED)!!
        val M: Ed25519.Point = Ed25519.decode(M_ENCODED)!!
        val N_ENCODED_BYTES get() = N_ENCODED.copyOf()
        val M_ENCODED_BYTES get() = M_ENCODED.copyOf()

        /**
         * BoringSSL's point generation: SHA-256 of the seed, then re-hash until
         * the result decodes to a curve point.
         */
        fun generatePoint(seed: String): ByteArray {
            val sha = MessageDigest.getInstance("SHA-256")
            var v = sha.digest(seed.toByteArray(Charsets.US_ASCII))
            while (Ed25519.decode(v) == null) v = sha.digest(v)
            return v
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
