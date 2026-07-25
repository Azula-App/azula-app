package dev.azula.sync

import dev.azula.core.DeviceCert
import dev.azula.core.DeviceCertCodec
import dev.azula.core.Ed25519
import dev.azula.core.LogEntry
import dev.azula.core.LogEntryCodec
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.P2pStream
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// `azula/sync/0` session: catch-up + live replication of per-device append-only logs
// between same-root certified sibling devices.
//
// See azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
// "Sync Runs Only Between Same-Root Certified Devices" requirement, which this file
// implements exactly -- and mirrors azula-cli's Rust `sync::run_session`
// (`.worktrees/azula-cli--multi-device-identity/src/sync.rs`) closely enough that the
// two interoperate over the wire:
//
// 1. Mutual `SyncHello{cert}`. Each side verifies the peer's cert -- signature valid,
//    chains to *its own* root key, not revoked, and the cert's `device_pk` equals the
//    connection's transport peer id -- closing the connection (no further writes) on
//    any failure. See [readAndVerifyHello].
// 2. Both sides send `SyncVector{vector}`: device public key hex to the highest
//    *contiguous* `seq` held for that device ([EventLogStore.vector]).
// 3. Each side streams `SyncEntries{entries}` for whatever the other side's vector
//    says it's missing -- per-device, ascending `seq`, at most [MAX_ENTRIES_PER_FRAME]
//    entries per frame. See [sendGap].
// 4. Both send `SyncAck{vector}`.
// 5. While the connection stays open, newly appended entries are pushed immediately as
//    further `SyncEntries` frames -- no new vector exchange, ever. Rather than push
//    incrementally, each wake recomputes the full current vector and pushes whatever's
//    newer than what was already sent, so a coalesced or missed [EventLogStore.changeVersion]
//    update can never lose an entry (only ever merge two pushes into one) -- the same
//    design choice the Rust reference makes. A caller can opt out of this live phase
//    entirely (`livePush = false`), ending the session -- and with it the connection --
//    once both acks have crossed: the bounded catch-up shape task 5.4's sibling
//    auto-dial wiring runs (see [runSyncSession]'s `livePush` kdoc).
//
// [runSyncSession] is the whole protocol, generic over any [P2pStream] -- an in-memory
// pipe in this file's tests (no real iroh socket needed), or a real one dialed/accepted
// on [dev.azula.net.Alpns.SYNC] by a caller outside this module's scope (sibling
// discovery/dial is multi-device-identity task 5.4, not implemented here).

/** Cap on entries per `SyncEntries` frame (spec: "at most 64 per frame"). */
internal const val MAX_ENTRIES_PER_FRAME = 64

/** Outcome of a sync session that ran to completion without a hard protocol error. */
sealed class SyncSessionOutcome {
    /**
     * The peer's `SyncHello` failed verification (or a malformed/absent frame arrived
     * instead) -- the connection was closed before any vector or entry exchange. Carries
     * a human-readable reason for logging; callers are responsible for actually closing
     * the transport connection.
     */
    data class HelloRejected(val reason: String) : SyncSessionOutcome()

    /** The catch-up + live phase ran until one side's connection closed. */
    data object Closed : SyncSessionOutcome()
}

/**
 * Runs one `azula/sync/0` session end to end, per this file's header doc.
 *
 * @param stream the already-negotiated (`Alpns.SYNC`) bidirectional stream to the peer.
 * @param myCert this device's own certificate, presented in our `SyncHello`; its
 *   [DeviceCert.rootPk] is also the root identity the peer's certificate must chain to
 *   (`Sync Runs Only Between Same-Root Certified Devices`).
 * @param revokedDevicePks device public keys already known-revoked for this root --
 *   the caller's job to have verified those revocation statements beforehand; this
 *   function only consults what's passed in (same contract as [DeviceCertCodec.verify]'s
 *   `revokedDevicePks` parameter).
 *
 *   **Callers must recompute this per session from the log store, not cache it.** The
 *   spec requires that a `device_revoke` learned via ordinary sync is enforced from the
 *   next connection onwards (`account-sync`: "Own devices enforce revocation after
 *   sync"). Passing a set snapshotted once from the identity bundle at startup satisfies
 *   every check in this function and still violates that requirement -- a revoked sibling
 *   keeps syncing until the process restarts. The Rust implementation shipped exactly
 *   that bug and fixed it with `LogStore::device_revocations()`, which scans the logs for
 *   verified `device_revoke` entries and is merged with the bundle baseline on every
 *   session; do the same here.
 * @param transportPeerDevicePk the connection's actual transport-layer peer device
 *   public key (e.g. decoded from [dev.azula.net.IncomingConnection.remoteId], or the
 *   ticket's node id for a dialed-out connection) -- the caller's job to obtain. Pins
 *   the presented certificate to the connection it actually arrived on.
 * @param store this device's [EventLogStore].
 * @param ed25519 real signature verification for [store]'s entries and the peer's cert.
 * @param nowEpochSeconds wall-clock time, for the peer cert's `expires_at` check --
 *   passed in rather than read internally so this function stays a pure, deterministic
 *   unit to test.
 * @param livePush `true` (the default, and the Rust reference's only behavior) keeps the
 *   session open after catch-up, pushing newly appended entries until one side's
 *   connection closes -- step 5 of this file's header doc. `false` runs a *bounded*
 *   catch-up instead: the session ends (returning [SyncSessionOutcome.Closed]) once this
 *   side has shipped its gap + `SyncAck` AND received the peer's `SyncAck` (everything
 *   after the peer's ack is live push by protocol order, so convergence is already
 *   complete at that point). This is what caller-driven wiring like
 *   `ConnectService.runSyncWith` (multi-device-identity task 5.4) needs to actually
 *   *return* -- its whole design is "dial, run one short catch-up session, close, and
 *   re-sync on the next reconnect"; an indefinite live session would leave every dialed
 *   sibling stream (and its coroutine) parked forever, piling up one per sibling per
 *   reconnect. Either mode is wire-compatible with a peer running the other: a bounded
 *   side's close is a clean EOF to a live peer's `recvLoop`, exactly like any dropped
 *   connection. The revocation-recompute contract above applies identically to both
 *   modes -- shorter sessions mean *more* frequent re-verification, never less.
 * @param onSyncA2ui cli-multi-session-relay's "A2UI Snapshot Replay on Reconnect":
 *   invoked once per inbound [Frame.SyncA2ui] frame, with its `conversation` and
 *   `messages` fields, as they arrive during either catch-up or live push -- handled
 *   inline in [recvLoop], the same place [Frame.SyncEntries] batches are applied to
 *   [store]. Defaults to a no-op, following this function's existing parameter-callback
 *   style; this module stays UI-free (a real callback is wired in by `shared`'s
 *   `AccountSyncService`, which can reach `ConversationStore`/`SurfaceStore`/
 *   `FrameDispatcher`, none of which this module may depend on). Kept `suspend` so a
 *   real implementation can safely touch coroutine-confined state (e.g. Compose
 *   snapshot state on the main dispatcher) without this module caring how.
 */
suspend fun runSyncSession(
    stream: P2pStream,
    myCert: DeviceCert,
    revokedDevicePks: List<ByteArray>,
    transportPeerDevicePk: ByteArray,
    store: EventLogStore,
    ed25519: Ed25519,
    nowEpochSeconds: Long,
    livePush: Boolean = true,
    onSyncA2ui: suspend (conversation: String, messages: List<JsonObject>) -> Unit = { _, _ -> },
): SyncSessionOutcome {
    // Step 1: mutual hello. Send ours, then verify theirs. Any verification failure
    // closes the connection with no further writes at all -- in particular, no
    // SyncVector -- matching the spec's "closing the connection otherwise", checked
    // before either side commits to anything.
    stream.sendLine(FrameCodec.encode(Frame.SyncHello(cert = DeviceCertCodec.encode(myCert))))
    val rejection = readAndVerifyHello(stream, myCert.rootPk, revokedDevicePks, transportPeerDevicePk, ed25519, nowEpochSeconds)
    if (rejection != null) return SyncSessionOutcome.HelloRejected(rejection)

    // Step 2: both sides send their per-device high-water vector. A malformed/missing
    // SyncVector here is a genuine protocol violation from an *already cert-verified*
    // peer -- unlike a hello failure, this is a hard error (propagated as a thrown
    // exception), not a normal "wrong peer" outcome.
    val myVector = store.vector()
    stream.sendLine(FrameCodec.encode(Frame.SyncVector(vector = myVector)))
    val peerVector = when (val frame = stream.receiveLine()?.let { FrameCodec.decode(it) }) {
        is Frame.SyncVector -> frame.vector
        null -> error("sync: connection closed before SyncVector")
        else -> error("sync: expected SyncVector after hello, got $frame")
    }

    // Steps 3-5: stream the gap + ack while concurrently draining and applying whatever
    // the peer streams to us, then (live mode) keep both directions open for live push.
    // Live mode ends when whichever direction ends first does (peer disconnected, or our
    // own write failed because the peer's side closed) -- mirrors the Rust reference's
    // `tokio::select!` over its own send/recv halves. Bounded mode instead awaits BOTH
    // halves: our gap + ack fully sent, and the peer's ack received -- awaiting only one
    // would race away the other half of the catch-up.
    return coroutineScope {
        val sendJob = async { sendLoop(stream, store, myVector, peerVector, livePush) }
        val recvJob = async { recvLoop(stream, store, untilAck = !livePush, onSyncA2ui = onSyncA2ui) }
        if (livePush) {
            select<Unit> {
                sendJob.onAwait {}
                recvJob.onAwait {}
            }
            sendJob.cancel()
            recvJob.cancel()
        } else {
            sendJob.await()
            recvJob.await()
        }
        SyncSessionOutcome.Closed
    }
}

/**
 * Read and verify the peer's `SyncHello`, per the spec's certificate checks: signature
 * valid, chains to [myRootPk], not revoked, and `device_pk` matches
 * [transportPeerDevicePk]. Returns `null` on success, or a human-readable rejection
 * reason otherwise -- never throws for a "wrong peer" outcome, since that's a normal
 * result here, not a bug (matches the Rust reference's `read_and_verify_hello`).
 */
internal suspend fun readAndVerifyHello(
    stream: P2pStream,
    myRootPk: ByteArray,
    revokedDevicePks: List<ByteArray>,
    transportPeerDevicePk: ByteArray,
    ed25519: Ed25519,
    nowEpochSeconds: Long,
): String? {
    val line = stream.receiveLine() ?: return "connection closed before sending SyncHello"
    val frame = try {
        FrameCodec.decode(line)
    } catch (e: Exception) {
        return "malformed frame while awaiting SyncHello: ${e.message}"
    }
    val certString = (frame as? Frame.SyncHello)?.cert
        ?: return "expected SyncHello as the first frame, got $frame"
    val cert = DeviceCertCodec.decode(certString)
        ?: return "malformed certificate"
    // Self-contained checks only (signature + expiry) -- root/revocation/binding are
    // checked separately below so each failure gets a distinguishable reason.
    if (!DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds, emptyList())) {
        return "certificate failed verification"
    }
    if (!cert.rootPk.contentEquals(myRootPk)) {
        return "certificate chains to a different root identity"
    }
    if (revokedDevicePks.any { it.contentEquals(cert.devicePk) }) {
        return "certificate's device key has been revoked"
    }
    if (!cert.devicePk.contentEquals(transportPeerDevicePk)) {
        return "certificate's device key does not match the connection's transport node id"
    }
    return null
}

/**
 * Ship the peer's gap (per device, ascending `seq`, batched to [MAX_ENTRIES_PER_FRAME]),
 * then a `SyncAck`, then -- in live mode -- push whatever the local store gains afterward,
 * with no new vector exchange, ever (spec: "Live push while connected"). Live mode runs
 * until a write fails (the peer's side of the connection closed) or is cancelled by
 * [runSyncSession] once [recvLoop] ends first; `livePush = false` instead returns right
 * after the `SyncAck` (see [runSyncSession]'s `livePush` kdoc).
 */
internal suspend fun sendLoop(
    stream: P2pStream,
    store: EventLogStore,
    myVector: Map<String, Long>,
    peerVector: Map<String, Long>,
    livePush: Boolean = true,
) {
    for ((deviceHex, mySeq) in myVector) {
        val peerSeq = peerVector[deviceHex] ?: 0L
        if (peerSeq >= mySeq) continue
        sendGap(stream, store, deviceHex, peerSeq)
    }

    stream.sendLine(FrameCodec.encode(Frame.SyncAck(vector = myVector)))
    if (!livePush) return

    // Live push: whenever the store changes, ship whatever's newer than what we've
    // already pushed. No new SyncVector/SyncAck -- just SyncEntries. `changeVersion`
    // always replays its current value to this collector, so if the store changed
    // during the catch-up send above, the very first `first{}` call below returns
    // immediately rather than missing it.
    var pushed = myVector
    var lastVersion = store.changeVersion.value
    while (true) {
        lastVersion = store.changeVersion.first { it != lastVersion }
        val current = store.vector()
        for ((deviceHex, seq) in current) {
            val sent = pushed[deviceHex] ?: 0L
            if (seq <= sent) continue
            sendGap(stream, store, deviceHex, sent)
        }
        pushed = current
    }
}

/**
 * Send every entry after [fromSeq] for [deviceHex], batched to [MAX_ENTRIES_PER_FRAME]
 * entries per `SyncEntries` frame, in ascending `seq` order (which [EventLogStore.readFrom]
 * already returns).
 */
internal suspend fun sendGap(stream: P2pStream, store: EventLogStore, deviceHex: String, fromSeq: Long) {
    val devicePk = deviceHex.hexToByteArrayOrNull()
        ?: error("sync: unparseable device key $deviceHex in our own vector")
    val entries = store.readFrom(devicePk, fromSeq)
    for (chunk in entries.chunked(MAX_ENTRIES_PER_FRAME)) {
        val batch = chunk.map { it.toSyncBase64() }
        stream.sendLine(FrameCodec.encode(Frame.SyncEntries(entries = batch)))
    }
}

/**
 * Drain inbound frames and apply `SyncEntries` to [store], validating each entry through
 * [EventLogStore.insert]'s chain rules; a rejected entry is skipped without disturbing
 * that device's cursor (spec: receivers "reject ... and does not advance its cursor").
 * A malformed line (bad JSON, an unrecognized frame `type`, or an undecodable base64
 * entry) is skipped rather than tearing down an otherwise-healthy live session --
 * matches this codebase's established frame-tolerance idiom (see
 * `ConnectService.receiveLoop`) and the Rust reference's `Frame::Unknown` catch-all.
 * Runs until a clean EOF (the peer's side of the connection closed) or is cancelled by
 * [runSyncSession] once [sendLoop] ends first; with `untilAck = true` it additionally
 * returns once the peer's `SyncAck` arrives -- by protocol order everything before the
 * ack is the peer's catch-up gap and everything after is live push, so this is exactly
 * the bounded-catch-up cutoff [runSyncSession]'s `livePush = false` mode needs.
 *
 * A [Frame.SyncA2ui] is handed straight to [onSyncA2ui] rather than touching [store] --
 * A2UI snapshots are deliberately never written to the hash-chained log (design.md D6) --
 * and otherwise ignored the same tolerant way as any other non-`SyncEntries` frame.
 */
internal suspend fun recvLoop(
    stream: P2pStream,
    store: EventLogStore,
    untilAck: Boolean = false,
    onSyncA2ui: suspend (conversation: String, messages: List<JsonObject>) -> Unit = { _, _ -> },
) {
    while (true) {
        val line = stream.receiveLine() ?: return
        val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
        if (untilAck && frame is Frame.SyncAck) return
        if (frame is Frame.SyncA2ui) {
            onSyncA2ui(frame.conversation, frame.messages)
            continue
        }
        if (frame !is Frame.SyncEntries) continue // SyncAck / stray hello|vector / unrecognized: ignored
        for (b64 in frame.entries) {
            val entry = b64.decodeSyncBase64() ?: continue
            store.insert(entry)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun LogEntry.toSyncBase64(): String = Base64.encode(LogEntryCodec.encode(this))

@OptIn(ExperimentalEncodingApi::class)
private fun String.decodeSyncBase64(): LogEntry? =
    runCatching { Base64.decode(this) }.getOrNull()?.let { LogEntryCodec.decode(it) }
