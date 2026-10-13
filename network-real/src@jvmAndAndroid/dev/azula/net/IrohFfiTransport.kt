package dev.azula.net

import app.azula.iroh.IrohEndpoint
import app.azula.iroh.IrohStream
import app.azula.iroh.ed25519PublicFromSecret
import app.azula.iroh.ed25519Sign
import app.azula.iroh.ed25519Verify
import app.azula.iroh.endpointIdFromTicket
import app.azula.iroh.ticketBytes as irohTicketBytes
import app.azula.iroh.ticketFromBytes as irohTicketFromBytes
import app.azula.iroh.verifySignature as irohVerifySignature
import dev.azula.core.DeviceCert
import dev.azula.core.Ed25519
import dev.azula.core.RootIdentityMigration
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

/**
 * A single persisted 32-byte secret slot -- one named entry in the platform's key-at-rest
 * mechanism (one Keychain (service, account) pair, one plain file). The two-entry
 * [SecretKeyStore] the transport actually uses is built by pairing two of these -- one for
 * the endpoint key, one for the root secret -- under distinct names; see [LayeredSecretKeyStore].
 */
interface SingleSlotKeyStore {
    fun load(): ByteArray?
    fun save(bytes: ByteArray)

    /** Removes this slot's persisted value, if any (multi-device-identity task 6.1's
     *  QR-link "forget the raced-in root secret" path). Defaults to a no-op so every
     *  existing implementer stays source-compatible. */
    fun clear() {}
}

/**
 * Persists azula's two independently-present secrets under distinct entries, per the
 * `identity` capability's Per-Platform Key-at-Rest Storage requirement: [loadEndpointKey]/
 * [saveEndpointKey] for this device's iroh endpoint secret (present once bound -- the endpoint id
 * derives from it), [loadRootSecret]/[saveRootSecret] for the identity's root secret
 * (present once this device holds one; permanently absent on a QR-linked device). The two
 * entries are independent -- a device may hold a endpoint key and no root secret, so
 * [loadRootSecret] is nullable rather than falling back to the endpoint key.
 */
interface SecretKeyStore {
    fun loadEndpointKey(): ByteArray?
    fun saveEndpointKey(bytes: ByteArray)
    fun loadRootSecret(): ByteArray?
    fun saveRootSecret(bytes: ByteArray)

    /** Clears the root-secret entry only (never the endpoint key) -- see
     *  [IrohTransport.forgetRootSecret]. Defaults to a no-op so every existing
     *  implementer stays source-compatible. */
    fun clearRootSecret() {}
}

/**
 * Builds the two-entry [SecretKeyStore] contract out of a pair of [SingleSlotKeyStore]s --
 * one per entry -- so JVM desktop's per-item Keychain/file stores (each already a single
 * named entry) can supply both the endpoint and root entries without duplicating their own
 * load/save/migrate logic.
 */
class LayeredSecretKeyStore(
    internal val endpoint: SingleSlotKeyStore,
    internal val root: SingleSlotKeyStore,
) : SecretKeyStore {
    override fun loadEndpointKey(): ByteArray? = endpoint.load()
    override fun saveEndpointKey(bytes: ByteArray) = endpoint.save(bytes)
    override fun loadRootSecret(): ByteArray? = root.load()
    override fun saveRootSecret(bytes: ByteArray) = root.save(bytes)
    override fun clearRootSecret() = root.clear()
}

/** Default file-backed store (also works on Android via the app's files dir). */
class FileSecretKeyStore(private val path: String) : SingleSlotKeyStore {
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

    override fun clear() = delete()
}

/**
 * Thrown by [MacKeychainSecretKeyStore.load] when the Keychain may hold the identity
 * but is unreadable (login keychain locked, or the `security` call errored/timed out)
 * and no legacy plaintext copy exists. [IrohFfiTransport.bind] propagates it rather
 * than degrading to a fresh key, so a transient Keychain outage fails loudly instead
 * of silently minting a new identity and changing the endpoint id.
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
) : SingleSlotKeyStore {

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

    /** Deletes both the Keychain entry and any legacy plaintext fallback file -- best-effort;
     *  a Keychain delete failure (e.g. no entry present) is not itself an error here since the
     *  end state ("no secret readable") is what [clear] promises, and [Lookup.Miss] already
     *  means that. */
    override fun clear() {
        runSecurity(listOf("delete-generic-password", "-a", account, "-s", service))
        fallback.delete()
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
 * async calls complete on Android, so [IrohEndpoint.bind] returns and the endpoint comes
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

    override var endpointId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    override suspend fun bind(alpns: List<String>) {
        this.alpns = alpns
        // bind reuses the persisted secret key (if any) and brings relays online
        // in the background; see the Rust crate's `IrohEndpoint::bind`. A benign
        // key-store hiccup degrades to a fresh key (never to demo) — but a
        // [KeychainUnavailableException] (the identity may exist but the Keychain
        // is locked/unreadable) propagates, so we fail loudly rather than silently
        // minting a new identity and changing the endpoint id.
        val stored = try {
            keyStore.loadEndpointKey()
        } catch (e: KeychainUnavailableException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        val ep = IrohEndpoint.bind(alpns, stored)
        endpoint = ep
        endpointId = ep.id()
        runCatching { keyStore.saveEndpointKey(ep.secretKeyBytes()) }
        runCatching { maybeAdoptRootSecret(ep) }
        IrohConfig.activeTransport = this
        onCameOnline?.invoke()
    }

    /**
     * multi-device-identity design.md Migration Plan step 2: if this device has no root
     * secret yet, its endpoint secret becomes the root secret unchanged and a self-certificate
     * (`device_pk == root_pk`) is issued. This covers both an existing single-device
     * identity's first post-upgrade launch AND a genuinely fresh install -- there's no
     * separate "link to an existing identity" flow yet (task 6), so every device is
     * root-holding today. The endpoint key itself is never touched here, so the endpoint id,
     * recovery phrase, and existing contacts are all unaffected -- see
     * [adoptRootSecretIfNeeded]'s kdoc for the TODO this leaves for task 3.4.
     */
    private fun maybeAdoptRootSecret(ep: IrohEndpoint) {
        val endpointSecret = ep.secretKeyBytes()
        val devicePk = runCatching { ed25519PublicFromSecret(endpointSecret) }.getOrNull() ?: return
        adoptRootSecretIfNeeded(keyStore, endpointSecret, devicePk, System.currentTimeMillis() / 1000)
    }

    override suspend fun myTicket(): String = endpoint?.myTicket() ?: ""

    override fun secretKeyBytes(): ByteArray? =
        runCatching { endpoint?.secretKeyBytes() }.getOrNull() ?: runCatching { keyStore.loadEndpointKey() }.getOrNull()

    /**
     * Pre-bind probe of [keyStore] for the setup gate (see [IrohTransport.hasPersistedIdentity]).
     * [SecretKeyStore.loadEndpointKey] can throw [KeychainUnavailableException] (macOS Keychain
     * locked) — treated as "no identity" here, so a locked Keychain shows setup once rather
     * than crashing; harmless per the design's risk note since "create" falls back to the
     * same keystore-recovery behavior as today, and restore is still available.
     */
    override fun hasPersistedIdentity(): Boolean =
        runCatching { keyStore.loadEndpointKey() != null }.getOrDefault(false)

    override suspend fun importSecretKey(bytes: ByteArray) {
        require(bytes.size == 32) { "secret key must be 32 bytes" }
        runCatching { keyStore.saveEndpointKey(bytes) }
        // Re-bind with the restored identity: tear down the old endpoint and bind
        // again, which reads the freshly-saved key from the store.
        runCatching { endpoint?.shutdown() }
        endpoint = null
        bind(alpns)
    }

    /** The current 32-byte identity root secret, if this device holds one -- independent
     *  of the endpoint key (see [SecretKeyStore]). Null on a QR-linked device, or before any
     *  root secret is known. */
    override fun rootSecretBytes(): ByteArray? = runCatching { keyStore.loadRootSecret() }.getOrNull()

    /**
     * Persists [bytes] as this device's root secret, independent of the endpoint key -- used by
     * the recovery-phrase import flow (see [dev.azula.core.RecoveryPhrase] /
     * `ConnectService.importRecoveryPhrase`). Unlike [importSecretKey] this never rebinds
     * the transport itself: the endpoint id is unaffected by a root-secret change under the
     * multi-device-identity model. The rest of enrollment (self-issued certificate,
     * `device_add` log entry, second-confirmation-on-overwrite, and a conditional
     * [rebind] for a genuinely different identity) is orchestrated by
     * `ConnectService.importRecoveryPhrase` (multi-device-identity task 3.4), calling this
     * method and [publicKeyFor]/[rebind] in turn.
     */
    override fun importRootSecret(bytes: ByteArray) {
        require(bytes.size == 32) { "root secret must be 32 bytes" }
        runCatching { keyStore.saveRootSecret(bytes) }
    }

    /** See [IrohTransport.forgetRootSecret] -- clears [keyStore]'s root entry only, never
     *  touching the endpoint key/id. */
    override fun forgetRootSecret() {
        runCatching { keyStore.clearRootSecret() }
    }

    override fun publicKeyFor(secret: ByteArray): ByteArray? =
        runCatching { ed25519PublicFromSecret(secret) }.getOrNull()

    override suspend fun rebind() {
        if (alpns.isEmpty()) return // never bound; nothing to re-bind
        runCatching { endpoint?.shutdown() }
        endpoint = null
        bind(alpns)
    }

    override suspend fun connect(ticket: String, alpn: String): P2pStream {
        val ep = requireNotNull(endpoint) { "transport not bound" }
        return IrohP2pStream(ep.connect(ticket, alpn))
    }

    override suspend fun connectById(endpointId: String, alpn: String): P2pStream {
        val ep = requireNotNull(endpoint) { "transport not bound" }
        return IrohP2pStream(ep.connectById(endpointId, alpn).openBi())
    }

    override fun peerIdForTicket(ticket: String): String? =
        runCatching { endpointIdFromTicket(ticket) }.getOrNull()

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

    override fun signWithEndpointKey(data: ByteArray): ByteArray? =
        runCatching { endpoint?.sign(data) }.getOrNull()

    override fun verifyEndpointSignature(endpointIdHex: String, data: ByteArray, sig: ByteArray): Boolean =
        runCatching { irohVerifySignature(endpointIdHex, data, sig) }.getOrDefault(false)

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

/**
 * The effective home directory for azula desktop's on-disk state: the
 * `AZULA_DATA_DIR` override when set, otherwise the real `user.home` exactly
 * as before. The override lets a developer stand up a second, fully isolated
 * desktop instance -- files *and* keys, see [defaultDesktopKeyStore] -- against
 * the real network without mutating `~/.azula` or the macOS Keychain identity
 * of their primary install; documented in
 * `azula-docs/openspec/specs/testing/design.md`. Unset (the default for every
 * real user) resolves identically to before this override existed -- that's
 * the load-bearing safety property, not just a convenience default.
 *
 * `env` is overridable so tests can exercise both branches deterministically:
 * the JVM has no supported way to set an env var for the running process, so
 * production code always calls this with the default (`System::getenv`) and
 * tests inject a fake lookup instead of mutating real process state.
 */
internal fun azulaHomeDir(env: (String) -> String? = System::getenv): String =
    env("AZULA_DATA_DIR")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") ?: ".")

private fun defaultKeyPath(fileName: String, env: (String) -> String? = System::getenv): String =
    azulaHomeDir(env) + File.separator + ".azula" + File.separator + fileName

/**
 * Default store when nothing is injected via [IrohConfig.keyStore] — i.e. JVM
 * desktop (Android always injects `AndroidSecretKeyStore` before this runs).
 * macOS gets the Keychain-backed store; other desktop OSes keep the old
 * plaintext file, noted as a known gap (no equivalent dependency-free OS
 * keystore CLI on Linux/Windows). The endpoint entry keeps its original name/account
 * (`endpoint.key` / `endpoint_key`) so an existing install's endpoint key -- and
 * therefore its endpoint id -- is read back unchanged; the root entry (`root.key` /
 * `root_secret`) is new and starts absent, which is exactly what triggers the
 * migration in [IrohFfiTransport.maybeAdoptRootSecret] on first launch.
 *
 * When `AZULA_DATA_DIR` overrides the desktop home (see [azulaHomeDir]), key
 * storage *always* falls back to file-based storage under the scratch dir --
 * even on macOS -- instead of touching the real Keychain. This is deliberate,
 * not a shortcut: an isolated/test instance must never be able to read or
 * write the primary install's real `app.azula.identity` Keychain entries, and
 * routing it through the actual Keychain instead would (a) prompt for
 * Keychain unlock/access on every isolated run, and (b) leave orphaned
 * Keychain entries behind that deleting the scratch dir can't clean up. A
 * scratch instance must be fully contained: `rm -rf $AZULA_DATA_DIR` has to
 * remove *everything* it touched, which file-based fallback guarantees and
 * a namespaced Keychain service would not.
 *
 * `env`/`isMac` are overridable for the same reason as [azulaHomeDir]: tests
 * exercise the override branch deterministically on any OS without mutating
 * real process state.
 */
internal fun defaultDesktopKeyStore(
    env: (String) -> String? = System::getenv,
    isMac: Boolean = System.getProperty("os.name")?.lowercase()?.contains("mac") == true,
): SecretKeyStore {
    val overridden = env("AZULA_DATA_DIR")?.isNotBlank() == true
    return if (isMac && !overridden) {
        LayeredSecretKeyStore(
            endpoint = MacKeychainSecretKeyStore(
                service = "app.azula.identity",
                account = "endpoint_key",
                fallback = FileSecretKeyStore(defaultKeyPath("endpoint.key", env)),
            ),
            root = MacKeychainSecretKeyStore(
                service = "app.azula.identity",
                account = "root_secret",
                fallback = FileSecretKeyStore(defaultKeyPath("root.key", env)),
            ),
        )
    } else {
        LayeredSecretKeyStore(
            endpoint = FileSecretKeyStore(defaultKeyPath("endpoint.key", env)),
            root = FileSecretKeyStore(defaultKeyPath("root.key", env)),
        )
    }
}

/**
 * [Ed25519] wired to iroh-kmp's real Ed25519 free functions (`crypto.rs`, multi-device-identity
 * task 1.1) -- the first real (non-fake) implementation `core`'s codecs and verification can
 * run against, now that iroh-kmp 0.1.2 is resolvable (task 1.3).
 */
object IrohKmpEd25519 : Ed25519 {
    override fun sign(secret: ByteArray, data: ByteArray): ByteArray = ed25519Sign(secret, data)

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        runCatching { ed25519Verify(publicKey, data, signature) }.getOrDefault(false)
}

actual fun platformEd25519(): Ed25519 = IrohKmpEd25519

/**
 * multi-device-identity design.md Migration Plan step 2, factored out of
 * [IrohFfiTransport.maybeAdoptRootSecret] so it's testable without a real bound
 * [IrohEndpoint]: if [keyStore] has no root secret yet, [endpointSecret] becomes the root
 * secret (persisted as-is) and a `device_pk == root_pk` self-certificate is computed via
 * [RootIdentityMigration.selfCertify] and returned. A no-op (returns null) if a root
 * secret is already present -- migration runs at most once per device.
 *
 * multi-device-identity task 3.4, closed: `network-real` still cannot depend on
 * `sync-real` (see this module's `module.yaml`), so this function's caller keeps
 * discarding the returned certificate -- but it's no longer the only computation of it.
 * `ConnectService.start()` (in `shared`, which *can* see the event log) independently
 * recomputes the identical certificate via `AccountSyncService.ensureSelfCertified` and
 * durably logs its `device_add` there. Safe because self-certification is deterministic
 * (same endpoint secret, same devicePk, same signature) -- this copy and that one always
 * agree, and `ensureSelfCertified` is idempotent, so redoing the computation here costs
 * nothing.
 */
fun adoptRootSecretIfNeeded(
    keyStore: SecretKeyStore,
    endpointSecret: ByteArray,
    devicePk: ByteArray,
    nowEpochSeconds: Long,
    ed25519: Ed25519 = IrohKmpEd25519,
): DeviceCert? {
    if (runCatching { keyStore.loadRootSecret() }.getOrNull() != null) return null
    val cert = runCatching { RootIdentityMigration.selfCertify(endpointSecret, devicePk, nowEpochSeconds, ed25519) }.getOrNull()
    runCatching { keyStore.saveRootSecret(endpointSecret) }
    return cert
}
