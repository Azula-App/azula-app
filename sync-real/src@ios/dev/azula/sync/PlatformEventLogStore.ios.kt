@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalEncodingApi::class)

package dev.azula.sync

import dev.azula.core.Ed25519
import dev.azula.core.LogEntry
import dev.azula.core.LogEntryCodec
import dev.azula.core.ZERO_PREV_HASH
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.*
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

actual fun platformEventLogStore(ed25519: Ed25519, rootPkHex: () -> String): EventLogStore? =
    NsFileManagerEventLogStore(ed25519, rootPkHex)

/**
 * The iOS actual [EventLogStore]: one file per device public key under
 * `NSDocumentDirectory/azula-eventlog/<root_pk_hex>/<hex-device-pk>.jsonl`, one
 * base64-encoded [LogEntry] per line -- the same on-disk shape as JVM/Android's
 * [FileEventLogStore] and azula-cli's Rust `sync::LogStore`. Mirrors
 * `persistence-real`'s `NsFileManagerBlobStore` for the NSFileManager plumbing (the
 * other iOS stores use NSUserDefaults, unsuited to an append-only log);
 * chain-validation caching mirrors [FileEventLogStore] exactly -- see that class's
 * kdoc for the rationale.
 *
 * The `<root_pk_hex>` subdirectory (multi-device-identity task 4.6) is what keeps
 * two identities that have ever shared this device from ever sharing a log set --
 * see [FileEventLogStore]'s kdoc for the bug this fixes and
 * `platformEventLogStore`'s for why [rootPkHex] is a lazy supplier rather than a
 * plain `String`.
 */
class NsFileManagerEventLogStore(private val ed25519: Ed25519, private val rootPkHex: () -> String) : EventLogStore {
    private val fileManager = NSFileManager.defaultManager
    private val mutex = Mutex()

    private var chains: MutableMap<String, LogEntry>? = null
    private var maxLamport: Long = 0L

    private val _changeVersion = MutableStateFlow(0L)
    override val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    private val root: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: NSTemporaryDirectory()
        val path = "$docs/azula-eventlog"
        fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    private val dir: String by lazy {
        val path = "$root/${rootPkHex()}"
        relocateFlatLayoutIfNeeded(path)
        fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    /**
     * One-time migration (multi-device-identity task 4.6): before this task, [root]
     * itself held `<device_pk>.jsonl` files directly, with no root-identity
     * namespacing. `FileEventLogStore`'s JVM/Android equivalent migration explains
     * why this is a *safe* migration to make automatically: this build's own 4.4
     * backfill is the only possible source of a flat file, since this whole change
     * is still unreleased, so any flat file found here was necessarily written
     * under whatever root now constructs this store. A no-op once [namespacedPath]
     * exists (already migrated, or a fresh store that never had a flat layout to
     * begin with).
     */
    private fun relocateFlatLayoutIfNeeded(namespacedPath: String) {
        if (fileManager.fileExistsAtPath(namespacedPath)) return
        val flatNames = fileManager.contentsOfDirectoryAtPath(root, error = null)
            ?.filterIsInstance<String>()
            ?.filter { it.endsWith(".jsonl") }
            .orEmpty()
        if (flatNames.isEmpty()) return
        fileManager.createDirectoryAtPath(namespacedPath, withIntermediateDirectories = true, attributes = null, error = null)
        for (name in flatNames) {
            fileManager.moveItemAtPath("$root/$name", toPath = "$namespacedPath/$name", error = null)
        }
    }

    private fun path(hex: String) = "$dir/$hex.jsonl"

    private fun readAllBytesOrNull(path: String): ByteArray? {
        if (!fileManager.fileExistsAtPath(path)) return null
        val data = fileManager.contentsAtPath(path) ?: return null
        return data.toByteArray()
    }

    private fun readLines(path: String): List<String> {
        val bytes = readAllBytesOrNull(path) ?: return emptyList()
        return bytes.decodeToString().split("\n").filter { it.isNotBlank() }
    }

    private fun appendLine(path: String, line: String) {
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createFileAtPath(path, contents = null, attributes = null)
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
            ?: error("NsFileManagerEventLogStore: unable to open $path for writing")
        handle.seekToEndOfFile()
        handle.writeData(("$line\n").encodeToByteArray().toNSData())
        handle.closeFile()
    }

    /** Must be called while holding [mutex]. Populates [chains]/[maxLamport] from disk
     *  exactly once; a no-op on every call after the first. */
    private fun ensureLoadedLocked() {
        if (chains != null) return
        val map = LinkedHashMap<String, LogEntry>()
        var maxLp = 0L
        val names = fileManager.contentsOfDirectoryAtPath(dir, error = null)
            ?.filterIsInstance<String>()
            ?.filter { it.endsWith(".jsonl") }
            ?.sorted()
            ?: emptyList()
        for (name in names) {
            val hex = name.removeSuffix(".jsonl")
            var previous: LogEntry? = null
            for (line in readLines("$dir/$name")) {
                val entry = LogEntryCodec.decode(Base64.decode(line))
                    ?: error("NsFileManagerEventLogStore: corrupt entry in $dir/$name")
                check(LogEntryCodec.verify(entry, previous, ed25519)) {
                    "NsFileManagerEventLogStore: corrupt chain in $dir/$name"
                }
                previous = entry
                maxLp = maxOf(maxLp, entry.lamport)
            }
            if (previous != null) map[hex] = previous
        }
        chains = map
        maxLamport = maxLp
    }

    /** Must be called while holding [mutex], after [ensureLoadedLocked]. */
    private fun persistLocked(entry: LogEntry) {
        val hex = entry.authorDevicePk.toHexLower()
        appendLine(path(hex), Base64.encode(LogEntryCodec.encode(entry)))
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

    override suspend fun readFrom(devicePk: ByteArray, fromSeq: Long): List<LogEntry> = mutex.withLock {
        ensureLoadedLocked()
        readLines(path(devicePk.toHexLower()))
            .mapNotNull { LogEntryCodec.decode(Base64.decode(it)) }
            .filter { it.seq > fromSeq }
    }
}

private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) {
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
    return out
}

private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}
