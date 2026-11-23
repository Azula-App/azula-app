package dev.azula.selfgrant

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The adb wireless-pairing wire protocol (AOSP system/core/adb/pairing_auth and
 * pairing_connection), over an already-established TLS channel:
 *
 *   1. both sides export 64 bytes of TLS keying material (label "adb-label\0")
 *   2. SPAKE2 with password = code || keyMaterial; names "adb pair client" /
 *      "adb pair server", each including a trailing NUL (sizeof in the C++)
 *   3. exchange SPAKE2 messages            packet type 0
 *   4. key = HKDF-SHA256(spake2 key, info "adb pairing_auth aes-128-gcm key", 16 B)
 *   5. exchange PeerInfo (8192 B) under AES-128-GCM, nonce = LE counter, no AAD
 *      packet type 1; the adb public key goes in PeerInfo type 0
 *
 * Packet header: version u8 (1), type u8, payload length u32 big-endian.
 * Reference for the client side: Shizuku's AdbPairingClient (Apache-2.0).
 */
internal object PairingProtocol {
    const val VERSION: Byte = 1
    const val TYPE_SPAKE2: Byte = 0
    const val TYPE_PEER_INFO: Byte = 1
    const val HEADER_SIZE = 6
    const val MAX_PEER_INFO = 8192
    const val MAX_PAYLOAD = MAX_PEER_INFO * 2
    const val PEER_INFO_RSA_PUB_KEY: Byte = 0
    const val EXPORT_LABEL = "adb-label\u0000"
    const val EXPORT_LENGTH = 64

    val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
    val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

    class WrongCodeException : Exception("the pairing code was not accepted")
    class ProtocolException(message: String) : Exception(message)

    fun header(type: Byte, payload: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION).put(type).putInt(payload).array()

    /** Reads and validates one header; returns (type, payload length). */
    fun readHeader(input: DataInputStream): Pair<Byte, Int> {
        val b = ByteBuffer.wrap(ByteArray(HEADER_SIZE).also { input.readFully(it) }).order(ByteOrder.BIG_ENDIAN)
        val version = b.get()
        val type = b.get()
        val payload = b.int
        if (version != VERSION) throw ProtocolException("pairing version $version")
        if (type != TYPE_SPAKE2 && type != TYPE_PEER_INFO) throw ProtocolException("pairing packet type $type")
        if (payload <= 0 || payload > MAX_PAYLOAD) throw ProtocolException("pairing payload size $payload")
        return type to payload
    }

    /** The SPAKE2 password: the six code digits followed by the TLS exporter output. */
    fun password(code: String, keyMaterial: ByteArray): ByteArray = code.toByteArray(Charsets.US_ASCII) + keyMaterial

    fun hkdfAesKey(spake2Key: ByteArray): ByteArray {
        // HKDF-SHA256, empty salt (== a hash-length run of zeros), single expand block.
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
        val prk = extract.doFinal(spake2Key)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        expand.update(HKDF_INFO)
        expand.update(1.toByte())
        return expand.doFinal().copyOf(16)
    }

    /** AES-128-GCM with a per-direction 64-bit little-endian counter as the nonce. */
    class Gcm(key: ByteArray) {
        private val key = SecretKeySpec(key, "AES")
        private var enc = 0L
        private var dec = 0L

        private fun nonce(n: Long) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()

        fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(enc++)))
            return c.doFinal(plain)
        }

        /** Null if authentication fails. */
        fun open(sealed: ByteArray): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(dec++)))
            c.doFinal(sealed)
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    fun peerInfo(type: Byte, data: ByteArray): ByteArray {
        require(data.size <= MAX_PEER_INFO - 1) { "peer info too large" }
        return ByteArray(MAX_PEER_INFO).also { it[0] = type; data.copyInto(it, 1) }
    }

    /**
     * Runs the client (Alice) side of pairing over an established channel.
     * [peerInfoData] is the adb public key text to register. Returns the
     * server's decrypted PeerInfo block.
     */
    fun runClient(
        input: InputStream,
        output: OutputStream,
        keyMaterial: ByteArray,
        code: String,
        peerInfoData: ByteArray,
    ): ByteArray {
        val din = DataInputStream(input)
        val dout = DataOutputStream(output)
        val spake = Spake2(Spake2.Role.Alice, CLIENT_NAME, SERVER_NAME)
        val ours = spake.generateMessage(password(code, keyMaterial))
        dout.write(header(TYPE_SPAKE2, ours.size)); dout.write(ours); dout.flush()

        val (t1, n1) = readHeader(din)
        if (t1 != TYPE_SPAKE2) throw ProtocolException("expected SPAKE2 message, got type $t1")
        val theirs = ByteArray(n1).also { din.readFully(it) }
        val key = spake.processMessage(theirs) ?: throw ProtocolException("invalid SPAKE2 point")
        val gcm = Gcm(hkdfAesKey(key))

        val sealed = gcm.seal(peerInfo(PEER_INFO_RSA_PUB_KEY, peerInfoData))
        dout.write(header(TYPE_PEER_INFO, sealed.size)); dout.write(sealed); dout.flush()

        // adbd drops the connection instead of replying when it can't authenticate
        // our PeerInfo, which after a successful SPAKE2 exchange means a wrong code.
        val body = try {
            val (t2, n2) = readHeader(din)
            if (t2 != TYPE_PEER_INFO) throw ProtocolException("expected peer info, got type $t2")
            ByteArray(n2).also { din.readFully(it) }
        } catch (_: java.io.IOException) {
            throw WrongCodeException()
        }
        val plain = gcm.open(body) ?: throw WrongCodeException()
        if (plain.size != MAX_PEER_INFO) throw ProtocolException("peer info size ${plain.size}")
        return plain
    }

    /** Server (Bob) side; used by tests to exercise the client against a fake adbd. */
    fun runServer(
        input: InputStream,
        output: OutputStream,
        keyMaterial: ByteArray,
        code: String,
        ourPeerInfo: ByteArray,
    ): ByteArray {
        val din = DataInputStream(input)
        val dout = DataOutputStream(output)
        val spake = Spake2(Spake2.Role.Bob, SERVER_NAME, CLIENT_NAME)
        val ours = spake.generateMessage(password(code, keyMaterial))
        val (t1, n1) = readHeader(din)
        if (t1 != TYPE_SPAKE2) throw ProtocolException("expected SPAKE2 message")
        val theirs = ByteArray(n1).also { din.readFully(it) }
        dout.write(header(TYPE_SPAKE2, ours.size)); dout.write(ours); dout.flush()
        val key = spake.processMessage(theirs) ?: throw ProtocolException("invalid SPAKE2 point")
        val gcm = Gcm(hkdfAesKey(key))
        val (t2, n2) = readHeader(din)
        if (t2 != TYPE_PEER_INFO) throw ProtocolException("expected peer info")
        val body = ByteArray(n2).also { din.readFully(it) }
        val peer = gcm.open(body) ?: throw WrongCodeException()
        val sealed = gcm.seal(peerInfo(PEER_INFO_RSA_PUB_KEY, ourPeerInfo))
        dout.write(header(TYPE_PEER_INFO, sealed.size)); dout.write(sealed); dout.flush()
        return peer
    }
}
