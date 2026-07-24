package dev.azula.sync

import dev.azula.core.Ed25519
import dev.azula.core.LogEntry
import dev.azula.core.LogEntryCodec
import dev.azula.core.ZERO_PREV_HASH
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The concrete per-device append-only [EventLogStore]: one file per device public key
 * under `<baseDir>/eventlog/<root_pk_hex>/<hex-device-pk>.jsonl`, one base64-encoded
 * [LogEntry] per line -- matching azula-cli's Rust `sync::LogStore`
 * (`.worktrees/azula-cli--multi-device-identity/src/sync.rs`) exactly, so the two
 * would interoperate if pointed at the same directory.
 *
 * The root-pk subdirectory (multi-device-identity task 4.6) is what keeps two
 * identities that have ever shared this device from ever sharing a log set: before
 * it existed, a device's log lived at the flat `eventlog/<device_pk>.jsonl` with no
 * identity binding at all, so entries it authored while enrolled in one identity
 * stayed in its log after enrolling into a *different* one -- still signed by its
 * own device key, which the new identity's root has now certified, so they'd
 * verify, chain correctly, and fold the old identity's private history into the
 * new one. See [rootPkHex] and [relocateFlatLayoutIfNeeded] for the corresponding
 * one-time migration of a pre-existing flat layout.
 *
 * Chain-validation state -- each device's last-accepted entry (all [appendOwn]/
 * [insert] need to validate the *next* one), plus the store-wide max-lamport
 * tracker [appendOwn] needs -- is rebuilt from disk lazily on first use and cached
 * in memory afterward. The on-disk file is always the source of truth; the cache
 * only exists so every append/insert doesn't have to re-read and re-validate the
 * whole file. A single [Mutex] guards both (mirroring the Rust store's single
 * `tokio::sync::Mutex<StoreState>`), so the two never drift apart.
 *
 * Shared by JVM (base dir `~/.azula`, see `PlatformEventLogStore.jvm.kt`) and
 * Android (base dir `context.filesDir`, via [AndroidEventLogStore] -- Android's
 * `platformEventLogStore()` itself returns `null`, same as
 * `persistence-real`'s `platformMessageStore()`, since a real per-app directory
 * needs a `Context` this module never receives).
 *
 * @param rootPkHex the identity's root public key (lowercase hex), evaluated
 *   lazily -- see `platformEventLogStore`'s kdoc for why this is a supplier
 *   rather than a plain `String`: at construction time (composition-root/AppGraph
 *   creation) the transport hasn't bound yet, so the root pk isn't known; nothing
 *   calls this store's methods until after it is, so the supplier is only ever
 *   invoked once that's true. Required, not defaulted -- a call site with no
 *   answer yet is a wiring bug, not a case to paper over with a placeholder
 *   directory.
 */
class FileEventLogStore(baseDir: File, private val ed25519: Ed25519, private val rootPkHex: () -> String) : EventLogStore {
    private val root = File(baseDir, "eventlog")
    private val dir: File by lazy { File(root, rootPkHex()) }
    private val mutex = Mutex()

    /** `null` until first loaded; hex device_pk -> last accepted entry for that device. */
    private var chains: MutableMap<String, LogEntry>? = null
    private var maxLamport: Long = 0L

    private val _changeVersion = MutableStateFlow(0L)
    override val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    /**
     * One-time migration (multi-device-identity task 4.6): before this task, [root]
     * itself held `<device_pk>.jsonl` files directly, with no root-identity
     * namespacing. This build's own 4.4 backfill may have written some there during
     * development -- since this whole change is still unreleased, that's the only
     * way a flat file can exist at all, which means it was necessarily written
     * under whatever root now constructs this store. If [dir] (this root's
     * namespaced subdirectory) doesn't exist yet and [root] holds flat `.jsonl`
     * files, they're moved under [dir] rather than left orphaned or silently
     * merged with a different root's log set. A no-op once [dir] exists (already
     * migrated, or a fresh store that never had a flat layout to begin with).
     */
    private fun relocateFlatLayoutIfNeeded() {
        if (dir.exists()) return
        val flatFiles = root.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") }.orEmpty()
        if (flatFiles.isEmpty()) return
        dir.mkdirs()
        for (file in flatFiles) {
            file.renameTo(File(dir, file.name))
        }
    }

    /** Must be called while holding [mutex]. Populates [chains]/[maxLamport] from disk
     *  exactly once; a no-op on every call after the first. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun ensureLoadedLocked() {
        if (chains != null) return
        relocateFlatLayoutIfNeeded()
        dir.mkdirs()
        val map = LinkedHashMap<String, LogEntry>()
        var maxLp = 0L
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") } ?: emptyList()
        for (file in files.sortedBy { it.name }) {
            val hex = file.name.removeSuffix(".jsonl")
            var previous: LogEntry? = null
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val entry = LogEntryCodec.decode(Base64.decode(line))
                    ?: error("FileEventLogStore: corrupt entry in ${file.path}")
                check(LogEntryCodec.verify(entry, previous, ed25519)) {
                    "FileEventLogStore: corrupt chain in ${file.path}"
                }
                previous = entry
                maxLp = maxOf(maxLp, entry.lamport)
            }
            if (previous != null) map[hex] = previous
        }
        chains = map
        maxLamport = maxLp
    }

    private fun deviceFile(hex: String) = File(dir, "$hex.jsonl")

    /** Must be called while holding [mutex], after [ensureLoadedLocked]. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun persistLocked(entry: LogEntry) {
        val hex = entry.authorDevicePk.toHexLower()
        val file = deviceFile(hex)
        file.parentFile?.mkdirs()
        file.appendText(Base64.encode(LogEntryCodec.encode(entry)) + "\n")
        chains!![hex] = entry
        maxLamport = maxOf(maxLamport, entry.lamport)
        _changeVersion.update { it + 1 }
    }

    override suspend fun appendOwn(
        authorDevicePk: ByteArray,
        authorSecret: ByteArray,
        kind: Int,
        tsMs: Long,
        body: ByteArray,
    ): LogEntry = mutex.withLock {
        ensureLoadedLocked()
        val previous = chains!![authorDevicePk.toHexLower()]
        val seq = (previous?.seq ?: 0L) + 1
        val prevHash = previous?.let { LogEntryCodec.chainHash(it) } ?: ZERO_PREV_HASH
        val lamport = maxLamport + 1
        val entry = LogEntryCodec.sign(
            kind, authorDevicePk, seq, lamport, tsMs, prevHash, body, authorSecret, ed25519,
        )
        persistLocked(entry)
        entry
    }

    override suspend fun insert(entry: LogEntry): Boolean = mutex.withLock {
        ensureLoadedLocked()
        val previous = chains!![entry.authorDevicePk.toHexLower()]
        if (!LogEntryCodec.verify(entry, previous, ed25519)) return@withLock false
        persistLocked(entry)
        true
    }

    override suspend fun vector(): Map<String, Long> = mutex.withLock {
        ensureLoadedLocked()
        chains!!.mapValues { it.value.seq }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun readFrom(devicePk: ByteArray, fromSeq: Long): List<LogEntry> = mutex.withLock {
        ensureLoadedLocked()
        val file = deviceFile(devicePk.toHexLower())
        if (!file.exists()) return@withLock emptyList()
        file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { LogEntryCodec.decode(Base64.decode(it)) }
            .filter { it.seq > fromSeq }
    }
}
