package dev.azula.selfgrant

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ed25519Test {
    @Test
    fun baseAndGeneratedPointsAreOnTheCurve() {
        assertTrue(Ed25519.isOnCurve(Ed25519.BASE))
        assertTrue(Ed25519.isOnCurve(Spake2.M))
        assertTrue(Ed25519.isOnCurve(Spake2.N))
        // RFC 8032 base point encoding: y = 4/5, even x.
        assertEquals("5866666666666666666666666666666666666666666666666666666666666666", hex(Ed25519.encode(Ed25519.BASE)))
    }

    @Test
    fun groupOrderTimesBaseIsIdentity() {
        assertEquals(Ed25519.IDENTITY, Ed25519.mul(Ed25519.L, Ed25519.BASE))
        assertEquals(Ed25519.BASE, Ed25519.mul(Ed25519.L.add(BigInteger.ONE), Ed25519.BASE))
    }

    @Test
    fun rfc8032PublicKeyVector() {
        // RFC 8032 section 7.1, test 1: secret key -> public key.
        val seed = unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val h = java.security.MessageDigest.getInstance("SHA-512").digest(seed)
        val a = h.copyOf(32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = (a[31].toInt() and 127).toByte()
        a[31] = (a[31].toInt() or 64).toByte()
        val pub = Ed25519.encode(Ed25519.mul(Ed25519.fromLe(a), Ed25519.BASE))
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", hex(pub))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        var p = Ed25519.BASE
        repeat(20) {
            val d = Ed25519.decode(Ed25519.encode(p))
            assertEquals(p, d)
            p = Ed25519.add(p, Spake2.M)
        }
        // y = 2 is not on the curve (no square root), so decoding must fail.
        assertNull(Ed25519.decode(ByteArray(32).also { it[0] = 2 }))
        assertNull(Ed25519.decode(ByteArray(31)))
    }
}

class Spake2Test {
    @Test
    fun mAndNRegenerateFromBoringSslSeeds() {
        // The seeds and algorithm documented in BoringSSL's spake25519.cc.
        assertContentEquals(Spake2.N_ENCODED_BYTES, Spake2.generatePoint("edwards25519 point generation seed (N)"))
        assertContentEquals(Spake2.M_ENCODED_BYTES, Spake2.generatePoint("edwards25519 point generation seed (M)"))
    }

    private fun run(aliceName: String = "adb pair client", bobPw: String = "123456", alicePw: String = "123456"): Pair<ByteArray?, ByteArray?> {
        val a = Spake2(Spake2.Role.Alice, aliceName.toByteArray(), "adb pair server".toByteArray())
        val b = Spake2(Spake2.Role.Bob, "adb pair server".toByteArray(), "adb pair client".toByteArray())
        val am = a.generateMessage(alicePw.toByteArray())
        val bm = b.generateMessage(bobPw.toByteArray())
        assertEquals(32, am.size)
        return a.processMessage(bm) to b.processMessage(am)
    }

    @Test
    fun bothSidesDeriveTheSameKey() {
        repeat(5) {
            val (ka, kb) = run()
            assertNotNull(ka)
            assertEquals(64, ka.size)
            assertContentEquals(ka, kb)
        }
    }

    @Test
    fun wrongPasswordOrNameGivesDifferentKeys() {
        val (ka, kb) = run(bobPw = "654321")
        assertFalse(ka.contentEquals(kb))
        val (kc, kd) = run(aliceName = "someone else")
        assertFalse(kc.contentEquals(kd))
    }

    @Test
    fun rejectsPointsThatAreNotOnTheCurve() {
        val a = Spake2(Spake2.Role.Alice, "a".toByteArray(), "b".toByteArray())
        a.generateMessage("pw".toByteArray())
        // y = 2 has no x on edwards25519.
        val bad = ByteArray(32).also { it[0] = 2 }
        assertNull(a.processMessage(bad))
        assertNull(a.processMessage(ByteArray(31)))
    }

    @Test
    fun knownAnswerWithFixedSeeds() {
        // Regression vector produced by this port with fixed private seeds. It
        // is not authoritative (BoringSSL publishes none); wire compatibility
        // with the real adbd is checked on a device. It pins the encoding,
        // transcript hash and length prefixes against accidental change.
        val seedA = ByteArray(64) { (it + 1).toByte() }
        val seedB = ByteArray(64) { (0x80 + it).toByte() }
        val a = Spake2(Spake2.Role.Alice, PairingProtocol.CLIENT_NAME, PairingProtocol.SERVER_NAME, privateSeed = seedA)
        val b = Spake2(Spake2.Role.Bob, PairingProtocol.SERVER_NAME, PairingProtocol.CLIENT_NAME, privateSeed = seedB)
        val pw = "123456".toByteArray() + ByteArray(64) { it.toByte() }
        val am = a.generateMessage(pw)
        val bm = b.generateMessage(pw)
        val ka = a.processMessage(bm)!!
        assertContentEquals(ka, b.processMessage(am))
        assertEquals(KAT, "${hex(am)}|${hex(bm)}|${hex(ka)}")
    }

    companion object {
        const val KAT =
            "d5fde54906b45552c0b9f123d2d50e7daa0e0c087ccb901cd3a283a645af786b|" +
                "1935da473328b88cb70ca98c78da83eee6859ba66c3c1c35a6f2f92f85467845|" +
                "6f59bffd595e090d405781b2d5e142ff6ac71649c18d61e852a0a1258e27745aed301b7393962844a26eb42a455ff87af3b166800d2ff79584db7818d3800b49"
    }
}

class PairingProtocolTest {
    @Test
    fun headerLayoutIsBigEndian() {
        assertEquals("010000000010", hex(PairingProtocol.header(PairingProtocol.TYPE_SPAKE2, 16)))
        assertEquals("010100002000", hex(PairingProtocol.header(PairingProtocol.TYPE_PEER_INFO, 0x2000)))
    }

    @Test
    fun headerValidation() {
        fun read(b: ByteArray) = PairingProtocol.readHeader(DataInputStream(ByteArrayInputStream(b)))
        assertEquals(1.toByte() to 0x2010, read(PairingProtocol.header(1, 0x2010)))
        assertFailsWith<PairingProtocol.ProtocolException> { read(byteArrayOf(2, 0, 0, 0, 0, 5)) } // version
        assertFailsWith<PairingProtocol.ProtocolException> { read(byteArrayOf(1, 9, 0, 0, 0, 5)) } // type
        assertFailsWith<PairingProtocol.ProtocolException> { read(PairingProtocol.header(0, 0)) } // empty
        assertFailsWith<PairingProtocol.ProtocolException> { read(PairingProtocol.header(0, 99999)) } // too big
    }

    @Test
    fun passwordIsCodeThenKeyMaterial() {
        val km = ByteArray(64) { it.toByte() }
        val pw = PairingProtocol.password("123456", km)
        assertEquals(70, pw.size)
        assertEquals("123456", String(pw, 0, 6))
        assertContentEquals(km, pw.copyOfRange(6, 70))
    }

    @Test
    fun peerInfoIsFixedSizeWithTypeByte() {
        val p = PairingProtocol.peerInfo(0, "key".toByteArray())
        assertEquals(8192, p.size)
        assertEquals(0, p[0].toInt())
        assertEquals("key", String(p, 1, 3))
        assertFailsWith<IllegalArgumentException> { PairingProtocol.peerInfo(0, ByteArray(8192)) }
    }

    @Test
    fun hkdfKeyVector() {
        // RFC 5869 style check of our single-block HKDF against javax HMAC computed
        // independently: PRK = HMAC(0^32, ikm), OKM = HMAC(PRK, info || 01)[0..16).
        val ikm = ByteArray(64) { it.toByte() }
        val key = PairingProtocol.hkdfAesKey(ikm)
        assertEquals(16, key.size)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        mac.update("adb pairing_auth aes-128-gcm key".toByteArray())
        mac.update(1)
        assertContentEquals(mac.doFinal().copyOf(16), key)
        // RFC 5869 test case 3 (zero-length salt/info) validates the extract step shape.
        val prk3 = run {
            mac.init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256"))
            mac.doFinal(unhex("0b".repeat(22)))
        }
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", hex(prk3))
    }

    @Test
    fun gcmCountersAdvanceAndDetectTampering() {
        val k = ByteArray(16) { 7 }
        val a = PairingProtocol.Gcm(k)
        val b = PairingProtocol.Gcm(k)
        val c1 = a.seal("one".toByteArray())
        val c2 = a.seal("one".toByteArray())
        assertFalse(c1.contentEquals(c2)) // different nonces
        assertEquals("one", String(b.open(c1)!!))
        assertEquals("one", String(b.open(c2)!!))
        val d = PairingProtocol.Gcm(k)
        val bad = d.seal("x".toByteArray()).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(PairingProtocol.Gcm(k).open(bad))
    }

    private class Pipe {
        val aIn = PipedInputStream(1 shl 16)
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1 shl 16)
        val aOut = PipedOutputStream(bIn)
    }

    @Test
    fun clientPairsAgainstServerOverPipes() {
        val p = Pipe()
        val km = ByteArray(64) { (it * 3).toByte() }
        val key = AdbKeyPair.generate()
        val clientKey = key.adbPublicKey("azula@test")
        var serverSaw: ByteArray? = null
        val t = thread {
            serverSaw = PairingProtocol.runServer(p.bIn, p.bOut, km, "424242", "server-guid".toByteArray())
        }
        val reply = PairingProtocol.runClient(p.aIn, p.aOut, km, "424242", clientKey)
        t.join()
        assertContentEquals(clientKey, serverSaw!!.copyOfRange(1, 1 + clientKey.size))
        assertEquals("server-guid", String(reply, 1, 11))
    }

    @Test
    fun wrongCodeSurfacesAsWrongCodeException() {
        val p = Pipe()
        val km = ByteArray(64)
        val t = thread {
            try {
                PairingProtocol.runServer(p.bIn, p.bOut, km, "111111", ByteArray(0))
            } catch (_: PairingProtocol.WrongCodeException) {
                p.bOut.close()
            }
        }
        assertFailsWith<PairingProtocol.WrongCodeException> {
            PairingProtocol.runClient(p.aIn, p.aOut, km, "222222", "k".toByteArray())
        }
        t.join()
    }
}

class AdbWireTest {
    @Test
    fun messageEncodingMatchesTheAdbLayout() {
        val m = Adb.Message.text(Adb.A_OPEN, 1, 0, "shell:id")
        val b = m.encode()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4e45504f, bb.int) // "OPEN"
        assertEquals(1, bb.int)
        assertEquals(0, bb.int)
        assertEquals(9, bb.int) // "shell:id" + NUL
        assertEquals("shell:id\u0000".sumOf { it.code }, bb.int)
        assertEquals(0x4e45504f xor -1, bb.int)
        assertEquals("shell:id\u0000", String(b, 24, 9))
        assertEquals("4f50454e", hex(b.copyOf(4)))
    }

    @Test
    fun readRoundTripsAndRejectsBadMagic() {
        val m = Adb.Message(Adb.A_WRTE, 5, 6, "hello".toByteArray())
        val r = Adb.read(DataInputStream(ByteArrayInputStream(m.encode())))
        assertEquals(Adb.A_WRTE, r.command)
        assertEquals(5, r.arg0)
        assertEquals(6, r.arg1)
        assertEquals("hello", String(r.data))
        val bad = m.encode().also { it[20] = 0 }
        assertFailsWith<Adb.AdbException> { Adb.read(DataInputStream(ByteArrayInputStream(bad))) }
    }

    /** A fake adbd: CNXN -> STLS, STLS -> CNXN, OPEN shell -> OKAY + WRTE + CLSE. */
    @Test
    fun shellHandshakeOverAFakeDevice() {
        val toDevice = PipedOutputStream()
        val deviceIn = PipedInputStream(toDevice, 1 shl 16)
        val fromDevice = PipedOutputStream()
        val clientIn = PipedInputStream(fromDevice, 1 shl 16)
        var upgraded = false
        val seen = mutableListOf<String>()
        val device = thread {
            val din = DataInputStream(deviceIn)
            var m = Adb.read(din)
            seen += Adb.name(m.command)
            Adb.write(fromDevice, Adb.Message(Adb.A_STLS, Adb.STLS_VERSION, 0))
            m = Adb.read(din)
            seen += Adb.name(m.command)
            Adb.write(fromDevice, Adb.Message.text(Adb.A_CNXN, Adb.VERSION, Adb.MAX_DATA, "device::"))
            m = Adb.read(din)
            seen += "${Adb.name(m.command)}:" + String(m.data).trimEnd('\u0000')
            Adb.write(fromDevice, Adb.Message(Adb.A_OKAY, 77, m.arg0))
            Adb.write(fromDevice, Adb.Message(Adb.A_WRTE, 77, m.arg0, "uid=2000(shell)\n".toByteArray()))
            m = Adb.read(din); seen += Adb.name(m.command) // OKAY ack
            Adb.write(fromDevice, Adb.Message(Adb.A_CLSE, 77, 1))
            m = Adb.read(din); seen += Adb.name(m.command)
        }
        val out = Adb.shell(
            Adb.Streams(clientIn, toDevice),
            { upgraded = true; Adb.Streams(clientIn, toDevice) },
            "id",
        )
        device.join()
        assertTrue(upgraded)
        assertEquals("uid=2000(shell)\n", out)
        assertEquals(listOf("CNXN", "STLS", "OPEN:shell:id", "OKAY", "CLSE"), seen)
    }

    @Test
    fun refusesDevicesThatDoNotOfferTls() {
        val reply = ByteArrayOutputStream().also {
            it.write(Adb.Message(Adb.A_AUTH, 1, 0, ByteArray(20)).encode())
        }.toByteArray()
        assertFailsWith<Adb.AdbException> {
            Adb.shell(Adb.Streams(ByteArrayInputStream(reply), ByteArrayOutputStream()), { error("no") }, "id")
        }
    }
}

class AdbKeyTest {
    private val key = AdbKeyPair.generate()

    @Test
    fun adbPublicKeyStructRoundTrips() {
        val encoded = key.adbPublicKey("azula@pixel")
        val text = String(encoded)
        assertTrue(text.endsWith(" azula@pixel\u0000"))
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, raw.size)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.int)
        val n0inv = bb.int
        val n = ByteArray(256).also { bb.get(it) }
        val rr = ByteArray(256).also { bb.get(it) }
        assertEquals(65537, bb.int)
        val modulus = Ed25519.fromLe(n)
        assertEquals(key.publicKey.modulus, modulus)
        // n0inv = -1/n mod 2^32
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(BigInteger.ONE.negate().mod(r32), modulus.multiply(BigInteger(1, ByteBuffer.allocate(4).putInt(n0inv).array())).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), Ed25519.fromLe(rr))
    }

    @Test
    fun privateKeyPersistsAndReloads() {
        val again = AdbKeyPair.fromPrivateBytes(key.privateBytes())
        assertEquals(key.publicKey.modulus, again.publicKey.modulus)
        assertContentEquals(key.adbPublicKey("x"), again.adbPublicKey("x"))
    }

    @Test
    fun certificateParsesAndVerifiesAgainstTheKey() {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(key.certificate)) as X509Certificate
        assertEquals(3, cert.version)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(key.publicKey, cert.publicKey)
        cert.verify(key.publicKey) // self-signed: throws if the signature is wrong
        assertTrue(cert.subjectX500Principal.name.contains("CN=00"))
        cert.checkValidity()
        // A flipped bit in the signed body must not verify.
        val tampered = key.certificate.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        val parsed = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(tampered)) as X509Certificate
        }.getOrNull()
        if (parsed != null) assertFailsWith<java.security.GeneralSecurityException> { parsed.verify(key.publicKey) }
    }
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
