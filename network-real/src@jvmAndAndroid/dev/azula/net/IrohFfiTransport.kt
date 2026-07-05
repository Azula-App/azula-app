package dev.azula.net

import app.azula.iroh.IrohEndpoint
import app.azula.iroh.IrohStream
import app.azula.iroh.nodeIdFromTicket
import app.azula.iroh.ticketBytes as irohTicketBytes
import app.azula.iroh.ticketFromBytes as irohTicketFromBytes
import app.azula.iroh.verifySignature as irohVerifySignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Persists the iroh secret key so the node id (and "your code") stays stable. */
interface SecretKeyStore {
    fun load(): ByteArray?
    fun save(bytes: ByteArray)
}

/** Default file-backed store (also works on Android via the app's files dir). */
class FileSecretKeyStore(private val path: String) : SecretKeyStore {
    override fun load(): ByteArray? = runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
    override fun save(bytes: ByteArray) {
        runCatching {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
    }

    /** Removes the plaintext file. Only called by [MacKeychainSecretKeyStore] after a verified migration. */
    fun delete() {
        runCatching { File(path).delete() }
    }
}

/**
 * Desktop macOS: stores the key in the login Keychain via the `security` CLI,
 * invoked with `ProcessBuilder` (no shell, no new dependency). Falls back to
 * [fallback] — the old plaintext file — if the Keychain call fails for any
 * reason (non-macOS, `security` missing/sandboxed, etc.), so a failing
 * Keychain call never loses the identity.
 *
 * Migration: an existing plaintext file is read, written to the Keychain, and
 * read back to verify it matches *before* the file is deleted — mirroring the
 * iOS Keychain migration in `IrohTransport.ios.kt`.
 */
class MacKeychainSecretKeyStore(
    private val service: String,
    private val account: String,
    private val fallback: FileSecretKeyStore,
) : SecretKeyStore {

    override fun load(): ByteArray? {
        keychainLoad()?.let { return it }

        // Not in the Keychain yet: fall back to (and migrate) the legacy
        // plaintext file, if any.
        val legacy = fallback.load() ?: return null
        val migratedOk = runCatching {
            keychainSave(legacy) && keychainLoad()?.contentEquals(legacy) == true
        }.getOrDefault(false)
        if (migratedOk) {
            // Verified: the Keychain now holds the same bytes. Safe to drop
            // the plaintext copy.
            runCatching { fallback.delete() }
        }
        // Either way we return the correct bytes — there's never a window
        // where the key exists in neither place.
        return legacy
    }

    override fun save(bytes: ByteArray) {
        val savedToKeychain = runCatching { keychainSave(bytes) }.getOrDefault(false)
        if (savedToKeychain) {
            runCatching { fallback.delete() }
        } else {
            // Keychain unavailable for some reason — fall back to the old
            // plaintext-file behavior rather than losing the key.
            fallback.save(bytes)
        }
    }

    private fun keychainLoad(): ByteArray? {
        val (code, out) = runSecurity("find-generic-password", "-a", account, "-s", service, "-w") ?: return null
        if (code != 0) return null
        val trimmed = out.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()
    }

    private fun keychainSave(bytes: ByteArray): Boolean {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val result = runSecurity("add-generic-password", "-U", "-a", account, "-s", service, "-w", b64) ?: return false
        return result.first == 0
    }

    /** Runs `security <args>`, returning (exit code, stdout) or null if it couldn't run at all. */
    private fun runSecurity(vararg args: String): Pair<Int, String>? = runCatching {
        val process = ProcessBuilder(listOf("security") + args).start()
        // Read stdout to EOF before waiting, so a full pipe can't deadlock the child.
        val stdout = process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        val exited = process.waitFor(5, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.exitValue() to stdout
    }.getOrNull()
}

/** Injectable hooks so the Android app can supply an encrypted key store + lifecycle. */
object IrohConfig {
    @Volatile var keyStore: SecretKeyStore? = null
    @Volatile var activeTransport: IrohFfiTransport? = null
}

/**
 * Real iroh transport for JVM desktop and Android, over the `app.azula.iroh:iroh-kmp`
 * binding. Unlike the published JNA artifact, this Gobley/JNI binding's async calls
 * complete on Android, so [IrohEndpoint.bind] returns and the node comes online.
 */
class IrohFfiTransport(private val keyStore: SecretKeyStore) : IrohTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var endpoint: IrohEndpoint? = null
    private var alpns: List<String> = emptyList()

    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        this.alpns = alpns
        // bind reuses the persisted secret key (if any) and brings relays online
        // in the background; see the Rust crate's `IrohEndpoint::bind`. A failing
        // key store must not fail the bind — degrade to a fresh key, never to demo.
        val stored = runCatching { keyStore.load() }.getOrNull()
        val ep = IrohEndpoint.bind(alpns, stored)
        endpoint = ep
        nodeId = ep.nodeId()
        runCatching { keyStore.save(ep.secretKeyBytes()) }
        IrohConfig.activeTransport = this
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = endpoint?.myTicket() ?: ""

    override fun secretKeyBytes(): ByteArray? =
        runCatching { endpoint?.secretKeyBytes() }.getOrNull() ?: runCatching { keyStore.load() }.getOrNull()

    override suspend fun importSecretKey(bytes: ByteArray) {
        require(bytes.size == 32) { "secret key must be 32 bytes" }
        runCatching { keyStore.save(bytes) }
        // Re-bind with the restored identity: tear down the old endpoint and bind
        // again, which reads the freshly-saved key from the store.
        runCatching { endpoint?.shutdown() }
        endpoint = null
        bind(alpns)
    }

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        val ep = requireNotNull(endpoint) { "transport not bound" }
        return IrohP2pStream(ep.connect(ticket, alpn))
    }

    override fun peerIdForTicket(ticket: String): String? =
        runCatching { nodeIdFromTicket(ticket) }.getOrNull()

    override fun incoming(): Flow<IncomingConnection> = channelFlow {
        val ep = endpoint ?: return@channelFlow
        var backoffMs = 100L
        while (isActive) {
            // acceptNext already completes the handshake and accepts a bi stream.
            // Only a clean close (a null result) should end the loop — a failed
            // inbound handshake surfaces as an exception and must not kill it, or
            // one bad peer permanently stops us from accepting anyone else. Back
            // off on repeated failures so a persistently-failing endpoint can't
            // hot-loop.
            val result = runCatching { ep.acceptNext() }
            if (result.isFailure) {
                println("IrohFfiTransport.incoming: acceptNext failed, retrying in ${backoffMs}ms: ${result.exceptionOrNull()}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(1000L)
                continue
            }
            val conn = result.getOrNull() ?: break
            backoffMs = 100L
            send(IncomingConnection(conn.remoteId, conn.alpn, IrohP2pStream(conn.stream)))
        }
    }

    override suspend fun shutdown() {
        runCatching { endpoint?.shutdown() }
        endpoint = null
    }

    override fun signWithNodeKey(data: ByteArray): ByteArray? =
        runCatching { endpoint?.sign(data) }.getOrNull()

    override fun verifyNodeSignature(nodeIdHex: String, data: ByteArray, sig: ByteArray): Boolean =
        runCatching { irohVerifySignature(nodeIdHex, data, sig) }.getOrDefault(false)

    override fun ticketBytes(ticket: String): ByteArray? =
        runCatching { irohTicketBytes(ticket) }.getOrNull()

    override fun ticketFromBytes(bytes: ByteArray): String? =
        runCatching { irohTicketFromBytes(bytes) }.getOrNull()

    /** Android: called when the app is backgrounded — close cleanly to free sockets. */
    fun pause() {
        scope.launch { runCatching { endpoint?.shutdown() }; endpoint = null }
    }

    /** Android: called when the app returns — re-bind with the persisted identity. */
    fun resume() {
        if (endpoint != null || alpns.isEmpty()) return
        scope.launch { runCatching { bind(alpns) } }
    }
}

/** Wraps a bidirectional iroh stream as a newline-framed [P2pStream]. */
private class IrohP2pStream(private val stream: IrohStream) : P2pStream {
    private val lines = LineBuffer { stream.recv(65536u) }

    override suspend fun sendLine(line: String) {
        stream.sendBytes((line + "\n").encodeToByteArray())
    }

    override suspend fun receiveLine(): String? = lines.readLine()

    override suspend fun sendBytes(bytes: ByteArray) {
        stream.sendBytes(bytes)
    }

    override suspend fun receiveBytes(n: Int): ByteArray? = lines.readBytes(n)

    override fun rttMs(): Long? = runCatching { stream.rttMs()?.toLong() }.getOrNull()

    override suspend fun close() {
        runCatching { stream.finish() }
    }
}

actual fun createTransport(): IrohTransport =
    IrohFfiTransport(IrohConfig.keyStore ?: defaultDesktopKeyStore())

private fun defaultKeyPath(): String {
    val home = System.getProperty("user.home") ?: "."
    return home + File.separator + ".azula" + File.separator + "endpoint.key"
}

/**
 * Default store when nothing is injected via [IrohConfig.keyStore] — i.e. JVM
 * desktop (Android always injects `AndroidSecretKeyStore` before this runs).
 * macOS gets the Keychain-backed store; other desktop OSes keep the old
 * plaintext file, noted as a known gap (no equivalent dependency-free OS
 * keystore CLI on Linux/Windows).
 */
private fun defaultDesktopKeyStore(): SecretKeyStore {
    val fileStore = FileSecretKeyStore(defaultKeyPath())
    val isMac = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
    return if (isMac) {
        MacKeychainSecretKeyStore(service = "app.azula.identity", account = "endpoint_key", fallback = fileStore)
    } else {
        fileStore
    }
}
