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
 * Thrown by [MacKeychainSecretKeyStore.load] when the Keychain may hold the identity
 * but is unreadable (login keychain locked, or the `security` call errored/timed out)
 * and no legacy plaintext copy exists. [IrohFfiTransport.bind] propagates it rather
 * than degrading to a fresh key, so a transient Keychain outage fails loudly instead
 * of silently minting a new identity and changing the node id.
 */
class KeychainUnavailableException(detail: String) : IllegalStateException(
    "Identity Keychain unavailable ($detail); refusing to mint a new identity. " +
        "Unlock the login keychain and relaunch.",
)

/**
 * Desktop macOS: stores the key in the login Keychain via the `security` CLI,
 * invoked with `ProcessBuilder` (no shell, no new dependency).
 *
 * A genuine "no such item" miss (errSecItemNotFound, exit 44) migrates/falls back to
 * [fallback] — the old plaintext file — or, if there's none, returns null (a fresh
 * install; bind then mints a first identity). But a Keychain *access* failure (locked,
 * `security` error, or timeout) is NOT a miss: with no plaintext copy it throws
 * [KeychainUnavailableException] instead of reporting "no key", so bind never mints a
 * new identity over a Keychain that is merely temporarily unreadable.
 *
 * Migration: an existing plaintext file is read, written to the Keychain, and
 * read back to verify it matches *before* the file is deleted — mirroring the
 * iOS Keychain migration in `IrohTransport.ios.kt`.
 *
 * [runSecurity] is injectable so the miss/unavailable paths are testable without a
 * real locked Keychain; it defaults to the real `security` CLI.
 */
class MacKeychainSecretKeyStore(
    private val service: String,
    private val account: String,
    private val fallback: FileSecretKeyStore,
    private val runSecurity: (List<String>) -> Pair<Int, String>? = ::runSecurityCli,
) : SecretKeyStore {

    private sealed interface Lookup {
        data class Found(val bytes: ByteArray) : Lookup
        data object Miss : Lookup
        data class Unavailable(val detail: String) : Lookup
    }

    override fun load(): ByteArray? {
        when (val r = keychainFind()) {
            is Lookup.Found -> return r.bytes
            Lookup.Miss -> {
                // Genuinely no Keychain entry: migrate a legacy plaintext file if any,
                // else there truly is no key (fresh install) — null mints a first one.
                val legacy = fallback.load() ?: return null
                val migratedOk = runCatching {
                    keychainSave(legacy) && (keychainFind() as? Lookup.Found)?.bytes?.contentEquals(legacy) == true
                }.getOrDefault(false)
                if (migratedOk) {
                    // Verified: the Keychain now holds the same bytes. Safe to drop
                    // the plaintext copy.
                    runCatching { fallback.delete() }
                }
                // Either way we return the correct bytes — never a window with no copy.
                return legacy
            }
            is Lookup.Unavailable -> {
                // The Keychain is present-but-unreadable (locked / security error / timeout).
                // Never report "no key" here: bind() would mint a NEW identity and orphan
                // the real one. Prefer a still-present legacy plaintext file (don't migrate
                // or delete while the Keychain is flaky); otherwise fail loudly.
                fallback.load()?.let { return it }
                throw KeychainUnavailableException(r.detail)
            }
        }
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

    private fun keychainFind(): Lookup {
        val res = runSecurity(listOf("find-generic-password", "-a", account, "-s", service, "-w"))
            ?: return Lookup.Unavailable("security find-generic-password did not run or timed out")
        val (code, out) = res
        return when (code) {
            0 -> {
                val bytes = out.trim().takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                bytes?.let { Lookup.Found(it) }
                    ?: Lookup.Unavailable("security returned exit 0 with no decodable value")
            }
            ERR_SEC_ITEM_NOT_FOUND -> Lookup.Miss
            else -> Lookup.Unavailable("security find-generic-password exited $code")
        }
    }

    private fun keychainSave(bytes: ByteArray): Boolean {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val result = runSecurity(listOf("add-generic-password", "-U", "-a", account, "-s", service, "-w", b64)) ?: return false
        return result.first == 0
    }

    private companion object {
        // errSecItemNotFound — the `security` CLI's exit code for "no such keychain item".
        private const val ERR_SEC_ITEM_NOT_FOUND = 44
    }
}

/** Runs `security <args>` with a 5s timeout, returning (exit code, stdout) or null if it couldn't run at all. */
private fun runSecurityCli(args: List<String>): Pair<Int, String>? = runCatching {
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

/** Injectable hooks so the Android app can supply an encrypted key store + lifecycle. */
object IrohConfig {
    @Volatile var keyStore: SecretKeyStore? = null
    @Volatile var activeTransport: IrohFfiTransport? = null
}

/**
 * Real iroh transport for JVM desktop and Android, over the `app.azula.iroh:iroh-kmp`
 * binding. Unlike the published `computer.iroh:iroh` artifact, this Gobley binding's
 * async calls complete on Android, so [IrohEndpoint.bind] returns and the node comes
 * online.
 *
 * Gobley's bindings still call through **JNA** on jvm + android (this comment used to
 * claim JNI, which sent a release-build FFI failure hunt down the wrong path). That
 * matters because JNA resolves fields and symbols from native code by their literal
 * names, so R8 will break the whole FFI layer unless the keep rules the SDK ships in
 * its AAR are applied — see `iroh-kmp/docs/gobley-consumer-proguard-gap.md`.
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
        // in the background; see the Rust crate's `IrohEndpoint::bind`. A benign
        // key-store hiccup degrades to a fresh key (never to demo) — but a
        // [KeychainUnavailableException] (the identity may exist but the Keychain
        // is locked/unreadable) propagates, so we fail loudly rather than silently
        // minting a new identity and changing the node id.
        val stored = try {
            keyStore.load()
        } catch (e: KeychainUnavailableException) {
            throw e
        } catch (e: Throwable) {
            null
        }
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

    /**
     * Pre-bind probe of [keyStore] for the setup gate (see [IrohTransport.hasPersistedIdentity]).
     * [SecretKeyStore.load] can throw [KeychainUnavailableException] (macOS Keychain locked) —
     * treated as "no identity" here, so a locked Keychain shows setup once rather than crashing;
     * harmless per the design's risk note since "create" falls back to the same
     * keystore-recovery behavior as today, and restore is still available.
     */
    override fun hasPersistedIdentity(): Boolean =
        runCatching { keyStore.load() != null }.getOrDefault(false)

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
