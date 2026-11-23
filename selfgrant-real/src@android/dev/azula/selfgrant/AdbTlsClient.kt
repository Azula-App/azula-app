package dev.azula.selfgrant

import android.net.ssl.SSLSockets
import android.os.Build
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The TLS side of Wireless debugging: client certificate from our adb key, no
 * server verification (adbd presents a throwaway certificate; the trust anchor is
 * the pairing code and the loopback address, as in adb itself).
 */
internal class AdbTlsClient(private val key: AdbKeyPair) {
    private val context: SSLContext by lazy {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(key.certificate.inputStream()) as X509Certificate
        val keyManager = object : X509ExtendedKeyManager() {
            private val alias = "adb"
            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out java.security.Principal>?, socket: Socket?) =
                if (keyTypes.any { it == "RSA" }) alias else null
            override fun getCertificateChain(alias: String?) = if (alias == this.alias) arrayOf(cert) else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) key.keyPair.private else null
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun getServerAliases(keyType: String, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
        }
        val trustAll = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLSv1.3").apply { init(arrayOf<KeyManager>(keyManager), arrayOf(trustAll), SecureRandom()) }
    }

    private fun connect(port: Int): Socket =
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            soTimeout = IO_TIMEOUT_MS
        }

    private fun tls(socket: Socket, port: Int): SSLSocket =
        (context.socketFactory.createSocket(socket, LOOPBACK, port, true) as SSLSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            startHandshake()
        }

    /** Pairs with the `_adb-tls-pairing` service on [port], registering our key as [name]. */
    fun pair(port: Int, code: String, name: String) {
        check(Build.VERSION.SDK_INT >= 34) { "pairing needs Android 14+" }
        val plain = connect(port)
        try {
            val ssl = tls(plain, port)
            val keyMaterial = checkNotNull(
                SSLSockets.exportKeyingMaterial(ssl, PairingProtocol.EXPORT_LABEL, null, PairingProtocol.EXPORT_LENGTH),
            ) { "TLS keying material unavailable" }
            PairingProtocol.runClient(ssl.inputStream, ssl.outputStream, keyMaterial, code, key.adbPublicKey(name))
        } finally {
            runCatching { plain.close() }
        }
    }

    /** Connects to the `_adb-tls-connect` service on [port] and runs [command] in `shell:`. */
    fun shell(port: Int, command: String): String {
        val plain = connect(port)
        try {
            return Adb.shell(
                Adb.Streams(plain.getInputStream(), plain.getOutputStream()),
                { tls(plain, port).let { Adb.Streams(it.inputStream, it.outputStream) } },
                command,
            )
        } finally {
            runCatching { plain.close() }
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IO_TIMEOUT_MS = 20_000
    }
}
