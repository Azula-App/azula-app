package dev.azula.net

import app.azula.iroh.IrohEndpoint
import app.azula.iroh.IrohStream
import app.azula.iroh.nodeIdFromTicket
import app.azula.iroh.ticketBytes as irohTicketBytes
import app.azula.iroh.ticketFromBytes as irohTicketFromBytes
import app.azula.iroh.verifySignature as irohVerifySignature
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * iOS transport over the `app.azula.iroh:iroh-kmp` Kotlin/Native binding — the
 * same SDK the JVM/Android targets use, so the implementation mirrors
 * [IrohFfiTransport]. This replaces the older injected Swift `IrohLib` bridge.
 */
class IosIrohTransport : IrohTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var endpoint: IrohEndpoint? = null
    private var alpns: List<String> = emptyList()

    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        this.alpns = alpns
        // Reuse the persisted secret key so the node id (and "your code") stays
        // stable across launches, mirroring AndroidSecretKeyStore.
        val ep = IrohEndpoint.bind(alpns, loadSecretKey())
        endpoint = ep
        nodeId = ep.nodeId()
        runCatching { saveSecretKey(ep.secretKeyBytes()) }
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = endpoint?.myTicket() ?: ""

    override fun secretKeyBytes(): ByteArray? =
        runCatching { endpoint?.secretKeyBytes() }.getOrNull() ?: loadSecretKey()

    /**
     * Pre-bind probe for the setup gate (see [IrohTransport.hasPersistedIdentity]):
     * calls the same [loadSecretKey] the Keychain/legacy-NSUserDefaults read [bind]
     * uses, without binding, so checking for a persisted identity can't itself mint
     * one. [loadSecretKey] already wraps its Keychain/NSUserDefaults access in
     * `runCatching` internally, but this adds an outer guard too so any unexpected
     * failure reads as "no identity" (shows setup) rather than crashing the gate.
     */
    override fun hasPersistedIdentity(): Boolean =
        runCatching { loadSecretKey() != null }.getOrDefault(false)

    override suspend fun importSecretKey(bytes: ByteArray) {
        require(bytes.size == 32) { "secret key must be 32 bytes" }
        runCatching { saveSecretKey(bytes) }
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
            // Only a clean close (a null result) should end the loop — a failed
            // inbound handshake surfaces as an exception and must not kill it, or
            // one bad peer permanently stops us from accepting anyone else. Back
            // off on repeated failures so a persistently-failing endpoint can't
            // hot-loop.
            val result = runCatching { ep.acceptNext() }
            if (result.isFailure) {
                println("IosIrohTransport.incoming: acceptNext failed, retrying in ${backoffMs}ms: ${result.exceptionOrNull()}")
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

actual fun createTransport(): IrohTransport = IosIrohTransport()

// --- Secret-key persistence (Keychain, migrated from legacy NSUserDefaults) -
//
// Originally this stored the raw key as a hex string in NSUserDefaults, which
// is equivalent to a plaintext plist on disk. It now lives in the Keychain
// (kSecClassGenericPassword), which is encrypted at rest. `kSecAttrAccessible
// AfterFirstUnlock` (rather than the default WhenUnlocked) keeps it readable
// while the app runs in the background, since the transport can be active
// there. Migration never drops the key: the legacy copy is only removed after
// a verified read-back from the Keychain, and if the Keychain write/read ever
// fails, save/load fall back to the legacy NSUserDefaults path instead of
// losing the identity.

private const val KEYCHAIN_SERVICE = "app.azula.identity"
private const val KEYCHAIN_ACCOUNT = "endpoint_key"
private const val SECRET_KEY_PREF = "azula_iroh_secret_key"

private fun loadSecretKey(): ByteArray? {
    runCatching { keychainLoad(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT) }.getOrNull()?.let { return it }

    // Not (yet) in the Keychain: fall back to the legacy NSUserDefaults value,
    // if any, and migrate it in place.
    val legacy = NSUserDefaults.standardUserDefaults.stringForKey(SECRET_KEY_PREF)?.hexToBytesOrNull()
        ?: return null

    val migratedOk = runCatching {
        keychainSave(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT, legacy) &&
            keychainLoad(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT)?.contentEquals(legacy) == true
    }.getOrDefault(false)
    if (migratedOk) {
        // Verified: the Keychain now holds the same bytes. Safe to drop the
        // plaintext copy.
        runCatching { NSUserDefaults.standardUserDefaults.removeObjectForKey(SECRET_KEY_PREF) }
    }
    // Either the Keychain write verified (and the legacy copy is now gone) or
    // it didn't (and the legacy copy is untouched) — the bytes we return are
    // correct either way, so there's never a window with the key nowhere.
    return legacy
}

private fun saveSecretKey(bytes: ByteArray) {
    val savedToKeychain = runCatching { keychainSave(KEYCHAIN_SERVICE, KEYCHAIN_ACCOUNT, bytes) }.getOrDefault(false)
    if (savedToKeychain) {
        runCatching { NSUserDefaults.standardUserDefaults.removeObjectForKey(SECRET_KEY_PREF) }
    } else {
        // Keychain unavailable for some reason — fall back to the old
        // behavior rather than losing the key.
        NSUserDefaults.standardUserDefaults.setObject(bytes.toHex(), SECRET_KEY_PREF)
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}

// --- Keychain plumbing (kSecClassGenericPassword) ---------------------------

@OptIn(ExperimentalForeignApi::class)
private fun keychainQuery(service: String, account: String): CFMutableDictionaryRef? {
    val query = CFDictionaryCreateMutable(null, 0, null, null)
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    CFDictionaryAddValue(query, kSecAttrService, CFBridgingRetain(service))
    CFDictionaryAddValue(query, kSecAttrAccount, CFBridgingRetain(account))
    return query
}

@OptIn(ExperimentalForeignApi::class)
private fun keychainLoad(service: String, account: String): ByteArray? {
    val query = keychainQuery(service, account)
    CFDictionaryAddValue(query, kSecReturnData, CFBridgingRetain(true))
    CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
    return memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        if (status != errSecSuccess) return@memScoped null
        (CFBridgingRelease(result.value) as? NSData)?.toByteArrayOrNull()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun keychainSave(service: String, account: String, bytes: ByteArray): Boolean {
    val data = bytes.toNSData()
    val existsStatus = SecItemCopyMatching(keychainQuery(service, account), null)
    return if (existsStatus == errSecSuccess) {
        val update = CFDictionaryCreateMutable(null, 0, null, null)
        CFDictionaryAddValue(update, kSecValueData, CFBridgingRetain(data))
        SecItemUpdate(keychainQuery(service, account), update) == errSecSuccess
    } else {
        val add = keychainQuery(service, account)
        CFDictionaryAddValue(add, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        SecItemAdd(add, null) == errSecSuccess
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArrayOrNull(): ByteArray? {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}
