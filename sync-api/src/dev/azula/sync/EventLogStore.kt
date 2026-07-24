package dev.azula.sync

import dev.azula.core.LogEntry
import kotlinx.coroutines.flow.StateFlow

// azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
// "Per-Device Append-Only Signed Logs": each device is the single writer of exactly
// one log, so appends never fork and no merge logic exists anywhere. The identity's
// state is a deterministic fold over the union of its devices' logs (AccountSyncFold.kt).
//
// `sync-real`'s `FileEventLogStore` is the concrete JSONL-file actual (one base64 entry
// per line, one file per device) and is semantically identical to azula-cli's Rust
// `sync::LogStore` (`.worktrees/azula-cli--multi-device-identity/src/sync.rs`), so the
// two interoperate over the wire: `appendOwn`'s seq/prev_hash/lamport rules and
// `insert`'s rejection-without-advancing-the-cursor contract match that module's
// `append_own`/`append` exactly.

/**
 * Per-device append-only log storage. A single instance holds every device's log for
 * one identity (the union [AccountSyncFold] folds over).
 */
interface EventLogStore {
    /**
     * Builds, signs, and appends the next entry in [authorDevicePk]'s own chain:
     * `seq` continues that device's existing chain in this store (1 if this store
     * holds none yet for that device), `prev_hash` is the SHA-256 of the previous
     * entry's full wire bytes (all-zero at `seq` 1), and `lamport` is one greater
     * than the highest lamport this store has accepted for *any* device at call
     * time -- the spec's append-side rule. Persists and returns the signed entry.
     */
    suspend fun appendOwn(
        authorDevicePk: ByteArray,
        authorSecret: ByteArray,
        kind: Int,
        tsMs: Long,
        body: ByteArray,
    ): LogEntry

    /**
     * Validates [entry] against whatever this store already holds for
     * `entry.authorDevicePk` -- signature, `seq` continuity, and the `prev_hash`
     * chain -- and, only on success, persists it and advances that device's
     * cursor. Returns `false` on any rejection, leaving the cursor and on-disk
     * state exactly as they were (spec: "does not advance its cursor for that
     * device"). Serves both a synced-in foreign entry and a reconstructed/
     * replayed one -- the spec draws no distinction, and neither does this method.
     */
    suspend fun insert(entry: LogEntry): Boolean

    /**
     * The per-device high-water vector: device public key (lowercase hex) to the
     * highest *contiguous* `seq` held for that device. A device this store has
     * never accepted an entry from is simply absent from the map (equivalent to 0).
     */
    suspend fun vector(): Map<String, Long>

    /** Every stored entry for [devicePk] with `seq > fromSeq`, ascending by `seq`
     *  (which the on-disk file already is, by construction -- only [insert]/
     *  [appendOwn] ever append to it, and both validate seq continuity first). */
    suspend fun readFrom(devicePk: ByteArray, fromSeq: Long): List<LogEntry>

    /**
     * A counter that increments by exactly 1 on every successful [appendOwn]/[insert] to
     * this store, for any device. A live sync session (`SyncSession.kt` in `sync-real`)
     * watches this rather than polling the store: on each observed change it recomputes
     * [vector] from scratch and pushes whatever's newer than what it already sent -- never
     * incrementally -- so a burst of appends that coalesces into a single observed change
     * (or a wakeup a slow collector missed) can never lose an entry, only merge two pushes
     * into one. Because [StateFlow] always replays its latest value to a new collector,
     * this is also safe for more than one concurrent watcher sharing the same store (e.g.
     * two live sibling sessions at once) -- unlike a single-waiter wakeup primitive.
     */
    val changeVersion: StateFlow<Long>
}

/**
 * Every entry this store holds, across every device, unordered (callers that need the
 * fold's deterministic order pass this straight to [AccountSyncFold.fold], which sorts
 * by [dev.azula.core.LOG_ENTRY_ORDER] itself). Built from [EventLogStore.vector] (the
 * set of devices this store has anything from) plus one [EventLogStore.readFrom] per
 * device starting at `0` -- the same "rebuild from raw logs alone" path the
 * "Projection rebuild" scenario describes, generic over any [EventLogStore] (a fresh
 * `FileEventLogStore` reloaded after its derived stores were deleted, in particular).
 */
suspend fun EventLogStore.allEntries(): List<LogEntry> =
    vector().keys.flatMap { hex -> hex.hexToByteArrayOrNull()?.let { readFrom(it, 0L) } ?: emptyList() }
