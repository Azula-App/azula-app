package dev.azula.state

import dev.azula.core.AppScope
import dev.azula.core.DeviceCert
import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocation
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.epochSeconds
import dev.azula.model.ConversationKind
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.net.IdentityBundle
import dev.azula.net.P2pStream
import dev.azula.sync.AccountSyncFold
import dev.azula.sync.DeviceAddBody
import dev.azula.sync.DeviceRevokeBody
import dev.azula.sync.EventLogStore
import dev.azula.sync.FoldedMessage
import dev.azula.sync.MessageInBody
import dev.azula.sync.MessageOutBody
import dev.azula.sync.ReadMarkerBody
import dev.azula.sync.SyncSessionOutcome
import dev.azula.sync.allEntries
import dev.azula.sync.decodeAgentInBody
import dev.azula.sync.decodeDeviceAddBody
import dev.azula.sync.decodeDeviceRevokeBody
import dev.azula.sync.decodeMessageInBody
import dev.azula.sync.decodeMessageOutBody
import dev.azula.sync.encodeDeviceAddBody
import dev.azula.sync.encodeDeviceRevokeBody
import dev.azula.sync.encodeMessageInBody
import dev.azula.sync.encodeMessageOutBody
import dev.azula.sync.encodeReadMarkerBody
import dev.azula.sync.hexToByteArrayOrNull
import dev.azula.sync.runSyncSession
import dev.azula.sync.toHexLower
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
// "Derived State Is a Deterministic Fold", "Read State Syncs as Log Entries", and
// "Legacy Peers Are Unaffected", tasks 4.4/4.5: this is where ConnectService/ChatService/
// FrameDispatcher's existing send/receive/read paths append `message_out`/`message_in`/
// `read_marker` entries, and where MessageStore's persisted history is projected back out
// of the fold (with a rebuild path for when the derived stores are gone but the logs
// remain -- see [rebuildProjection]).
//
// `conversation` keys used throughout this file are exactly the existing per-peer
// conversation id ([dev.azula.state.ConversationStore.ensureConv]'s `peerId`): a legacy
// node id, or -- since multi-device-identity task 7.3 -- a certified contact's root pk
// hex (see [dev.azula.state.ConnectService]'s `mergeIntoRootKeyedConversation`). Either
// way this file only ever sees it as an opaque string key; nothing here needs to know
// which kind it is.

/**
 * multi-device-identity task 7.2: the outcome of checking a peer-presented `Hello.cert`
 * against this identity's known contacts and revocations -- mirrors the Rust
 * reference's `CertCheck`/`check_cert` (`azula-cli/src/accept_gate.rs`). Every failure
 * mode (missing field, malformed payload, bad/expired signature, wrong connection
 * binding, or a revoked device key) collapses to [Absent] -- per spec, "a certificate
 * that fails verification SHALL be treated exactly as if the field were absent." See
 * [AccountSyncService.checkPresentedCert].
 */
sealed class CertCheck {
    /** Verifies, binds to this connection, isn't revoked, and its root is already a
     *  known contact -- known by root match, no invite needed (spec: "A contact's new
     *  device is known by root"). */
    data class KnownByRoot(val cert: DeviceCert) : CertCheck()

    /** Verifies, binds, and isn't revoked, but its root is not (yet) a known contact --
     *  falls through to the ordinary invite path; if that admits the connection, its
     *  root should be recorded as a new contact (spec: "Accepting a certified stranger
     *  pins the root"). */
    data class CertifiedStranger(val cert: DeviceCert) : CertCheck()

    /** No cert was presented, it failed to decode/verify/bind to this connection, or
     *  its device key has been revoked -- every one of these is treated exactly as if
     *  the field were absent. */
    object Absent : CertCheck()
}

/** The verified certificate carried by a non-[CertCheck.Absent] outcome, or null. */
val CertCheck.certOrNull: DeviceCert?
    get() = when (this) {
        is CertCheck.KnownByRoot -> cert
        is CertCheck.CertifiedStranger -> cert
        CertCheck.Absent -> null
    }

/**
 * The identity/account-sync core carved out of [ConnectService]/[ChatService]/
 * [FrameDispatcher]: appends `message_out`/`message_in`/`read_marker` entries to the
 * injected [eventLogStore], and derives/materializes [dev.azula.persist.MessageStore]'s
 * plain-text conversation history back out of the fold ([rebuildProjection],
 * [backfillLegacyHistory]).
 *
 * [eventLogStore] and [ed25519] are both nullable: `null` on the `-mock` apps and in
 * most tests (no real per-device log exists there, same nullable-optional-capability
 * pattern as [dev.azula.persist.MessageStore]/[dev.azula.persist.BlobStore] elsewhere) --
 * every method here degrades to a silent no-op rather than throwing when either is
 * absent, exactly like [PersistenceCoordinator.save] does with no
 * [dev.azula.persist.MessageStore] bound.
 */
@Inject
@SingleIn(AppScope::class)
class AccountSyncService(
    private val eventLogStore: EventLogStore?,
    private val ed25519: Ed25519?,
    private val convStore: ConversationStore,
    private val persistence: PersistenceCoordinator,
    private val scope: CoroutineScope,
) {
    /**
     * Set by [ConnectService] once the transport is bound (mirrors the [onNavigate]/
     * `onAccept`/`foreground` post-construction callback convention elsewhere in this
     * package, avoiding a circular graph dependency): this device's signing identity for
     * authoring log entries, as `(devicePk, secret)`. A function -- re-read on every call,
     * not a snapshot -- so it reflects a root secret adopted at bind time or imported
     * later (`ConnectService.importRecoveryPhrase`) without needing to be re-wired.
     *
     * `device_pk == root_pk` today: no device-linking flow (task 6.x) or `device_add`
     * persistence (task 3.4) exists yet, so every device is root-holding and
     * self-certified (design.md's Migration Plan step 2) -- `devicePk` doubles as the
     * fold's `rootPk` in [rebuildProjection] for exactly that reason. A future linked,
     * non-root-holding device needs its *own* root pk here distinct from its device pk;
     * that seam is deliberately left as a single obvious spot (this property, plus
     * [rebuildProjection]'s `rootPk` line) rather than hidden inside call sites.
     *
     * The same `device_pk == root_pk` simplification is why [eventLogStore]'s
     * on-disk directory can be namespaced from `transport.nodeId` alone today
     * (multi-device-identity task 4.6 -- see `platformEventLogStore`'s kdoc in
     * `sync-real`): the composition root (`AppEntry.kt`/`AzulaApplication.kt`) builds
     * that store with a lazy root-pk supplier reading the same transport this class's
     * `deviceIdentity` reads, ahead of when either is actually known (both only
     * resolve once [ConnectService.start] has bound the transport). A future
     * non-root-holding device needs that supplier to read this class's own future
     * root-pk seam instead of `transport.nodeId` directly -- the same seam this
     * kdoc already calls out for [rebuildProjection].
     */
    var deviceIdentity: (() -> Pair<ByteArray, ByteArray>?)? = null

    private fun identityOrNull(): Triple<EventLogStore, Ed25519, Pair<ByteArray, ByteArray>>? {
        val store = eventLogStore ?: return null
        val crypto = ed25519 ?: return null
        val identity = deviceIdentity?.invoke() ?: return null
        return Triple(store, crypto, identity)
    }

    private fun nowMs(): Long = epochSeconds() * 1000

    /**
     * Append `message_out` for a locally-sent chat message -- called regardless of
     * whether the send actually reaches any device of the recipient (spec: "Outbound
     * sends SHALL be recorded as `message_out` on the sending device's log regardless of
     * delivery outcome"). Fire-and-forget: failures (no identity yet, a transient disk
     * error) are swallowed, matching [PersistenceCoordinator.save]'s posture -- message
     * history in the live UI/[dev.azula.persist.MessageStore] is never gated on this
     * succeeding.
     */
    fun appendMessageOut(conversation: String, text: String, id: String?) {
        val (store, _, identity) = identityOrNull() ?: return
        val (devicePk, secret) = identity
        val body = encodeMessageOutBody(MessageOutBody(conversation, text, id))
        scope.launch { runCatching { store.appendOwn(devicePk, secret, EventKind.MESSAGE_OUT.wireValue, nowMs(), body) } }
    }

    /**
     * Append `message_in` for a received chat message. [fromDevicePk] is the sender's
     * device public key hex when known (a certified contact); for a legacy (no-cert)
     * peer there is no such key, so callers pass the peer's node id instead -- the same
     * value already used as [conversation] for a legacy contact (spec: "Legacy Peers Are
     * Unaffected" -- "An identity's own devices SHALL still log ... `message_in`/
     * `message_out` for conversations with legacy peers"). [id] is the wire `Chat.id` the
     * sender attached, if any (spec: "Chat Message Ids Deduplicate Retries") -- passed
     * straight through so a genuine retry (the same id delivered twice, to this device or
     * a sibling) folds to one message.
     */
    fun appendMessageIn(conversation: String, fromDevicePk: String, text: String, id: String?) {
        val (store, _, identity) = identityOrNull() ?: return
        val (devicePk, secret) = identity
        val body = encodeMessageInBody(MessageInBody(conversation, fromDevicePk, text, id))
        scope.launch { runCatching { store.appendOwn(devicePk, secret, EventKind.MESSAGE_IN.wireValue, nowMs(), body) } }
    }

    /**
     * Append a `read_marker` marking [conversation] read up through the highest lamport
     * this device currently knows about for it -- computed from a fresh scan of the whole
     * log rather than a locally-cached "last lamport we appended", so a sibling's messages
     * (once sync, task 5.4, actually runs) are correctly covered too, not just this
     * device's own sends/receives. Per spec, this is the *only* read-state channel: no
     * other "unread"/"seen" bookkeeping exists anywhere in this codebase (verified -- there
     * was none to replace), and none should be added outside this entry kind.
     * No-op if this conversation has no known messages yet (nothing to mark read).
     */
    fun markRead(conversation: String) {
        val (store, _, identity) = identityOrNull() ?: return
        val (devicePk, secret) = identity
        scope.launch {
            val entries = runCatching { store.allEntries() }.getOrDefault(emptyList())
            val upTo = entries.asSequence().mapNotNull { entry ->
                when (entry.knownKind) {
                    EventKind.MESSAGE_OUT -> decodeMessageOutBody(entry.body)?.takeIf { it.conversation == conversation }?.let { entry.lamport }
                    EventKind.MESSAGE_IN -> decodeMessageInBody(entry.body)?.takeIf { it.conversation == conversation }?.let { entry.lamport }
                    else -> null
                }
            }.maxOrNull() ?: return@launch
            val body = encodeReadMarkerBody(ReadMarkerBody(conversation, upTo))
            runCatching { store.appendOwn(devicePk, secret, EventKind.READ_MARKER.wireValue, nowMs(), body) }
        }
    }

    /**
     * Self-certifies [devicePk] (this device's own signing identity -- its node public
     * key) as a member of the identity whose root public key is [rootPk], and appends the
     * resulting certificate as a `device_add` entry authored by [devicePk]/[deviceSecret]
     * -- log entries are always signed by the *device* key, never the root key ("Key Roles
     * and Signing Boundaries"). No-op if this device's own log already carries a
     * `device_add` entry **for this same [rootPk]** -- checked by scanning [devicePk]'s own
     * entries and decoding each candidate's embedded certificate, not merely "does this
     * device have any `device_add` at all": a device that migrated in place under its own
     * key as root, then later enrolls into a *different* identity via a phrase, still has
     * its old self-cert sitting in the same log (see the caveat below) -- a coarser check
     * would mistake that unrelated cert for "already done" and silently skip logging the
     * new identity's. (Backfilled message history from [backfillLegacyHistory] is likewise
     * never mistaken for a certificate -- it isn't a `device_add` kind at all.) Safe to call
     * on every bind/every restore: idempotent per root, no duplicate self-certs for the
     * same identity.
     *
     * Two callers, two shapes of the same operation:
     * - `ConnectService.start()` (closing multi-device-identity task 3.2's TODO): the
     *   upgrade-in-place/fresh-install migration path, where [rootPk] == [devicePk] and
     *   [rootSecret] == [deviceSecret] by construction (this device IS the root).
     *   `network-real`'s `RootIdentityMigration.selfCertify`/`adoptRootSecretIfNeeded`
     *   computes the identical certificate deterministically but cannot persist/log it
     *   itself (`network-real` cannot depend on `sync-real` -- see its module.yaml), so it
     *   discards its copy; this call recomputes and durably logs the same bytes.
     * - `ConnectService.importRecoveryPhrase` (task 3.4's enrollment path): [rootPk] is
     *   independently derived from the imported phrase's decoded secret and may differ
     *   from [devicePk] entirely -- a phrase-enrolled device keeps its own node key (see
     *   the identity capability's "Restore Recovers the Identity Onto This Device").
     *
     * **Known caveat, not fixed by this task**: [eventLogStore]'s concrete on-disk
     * implementation (`sync-real`'s `FileEventLogStore`/`NsFileManagerEventLogStore`)
     * resolves its root-pk-namespaced directory (task 4.6) lazily *once* and caches it for
     * the instance's lifetime -- it does not notice mid-process if the identity this device
     * holds root for changes (a different-identity restore, still within the same running
     * app instance). So a different-identity enrollment's `device_add` (and any log entries
     * appended afterward, in the same process, before a relaunch) still lands in the
     * *previous* identity's on-disk directory rather than a fresh one -- this method's
     * per-root idempotency check above is what keeps that survivable (both identities' certs
     * coexist correctly in the shared log rather than one clobbering the other), but full
     * separation onto the new identity's own directory only takes effect after the app is
     * relaunched (`platformEventLogStore`'s lazy supplier re-evaluates against whatever root
     * is held at that point). Tracked as a follow-up; out of scope for task 3.4/3.5.
     *
     * No-op (returns `null`) if the event log or ed25519 aren't configured (the `-mock`
     * apps, most tests) -- same defensive posture as every other method here.
     */
    suspend fun ensureSelfCertified(
        rootPk: ByteArray,
        rootSecret: ByteArray,
        devicePk: ByteArray,
        deviceSecret: ByteArray,
        name: String = "",
    ): DeviceCert? {
        val store = eventLogStore ?: return null
        val crypto = ed25519 ?: return null
        // Idempotency must be scoped to THIS root, not "any device_add at all": a device
        // that migrated in place (self-certified under its own key as root) and later
        // enrolls into a *different* identity via a phrase still has its old self-cert
        // sitting in its own log -- if this store isn't (yet) re-pointed at a fresh
        // per-root directory (see this method's kdoc), a coarser "do I have any device_add"
        // check would wrongly treat the old identity's cert as already covering the new
        // one and skip logging the new identity's self-cert entirely.
        val alreadyCertifiedForThisRoot = runCatching { store.readFrom(devicePk, 0L) }.getOrDefault(emptyList())
            .any { entry ->
                entry.knownKind == EventKind.DEVICE_ADD &&
                    decodeDeviceAddBody(entry.body)
                        ?.let { DeviceCertCodec.decode(it.cert) }
                        ?.rootPk?.contentEquals(rootPk) == true
            }
        if (alreadyCertifiedForThisRoot) return null
        val cert = DeviceCertCodec.issue(
            roles = 0,
            rootPk = rootPk,
            devicePk = devicePk,
            issuedAt = epochSeconds(),
            expiresAt = 0,
            name = name,
            rootSecret = rootSecret,
            ed25519 = crypto,
        )
        val body = encodeDeviceAddBody(DeviceAddBody(cert = DeviceCertCodec.encode(cert)))
        runCatching { store.appendOwn(devicePk, deviceSecret, EventKind.DEVICE_ADD.wireValue, nowMs(), body) }
        return cert
    }

    /**
     * Root-holding side of a QR-link enrollment (multi-device-identity task 6.2, called
     * only after the user has explicitly confirmed the verification words and roles --
     * see `ConnectService.dialForGrant`/`confirmGrant`): issues a certificate for
     * [newDevicePk] under [rootPk], appends a `device_add` log entry for it authored by
     * *this* device (the granting device is the sole writer of its own chain -- the new
     * device has no log entries of its own yet, and isn't the one recording this), and
     * assembles the [IdentityBundle] the grant delivers alongside it
     * ([DeviceLinkProtocol.buildIdentityBundle]).
     *
     * `null` if this device can't currently do any of that -- no event log/ed25519
     * configured (the `-mock` apps, most tests), same defensive posture as every other
     * method here. Note this deliberately takes [rootPk]/[rootSecret] as explicit
     * parameters rather than reading [deviceIdentity] for them: unlike every other method
     * here, the *root* identity issuing this grant is not necessarily this device's own
     * `deviceIdentity` (a phrase-enrolled granting device's root differs from its device
     * pk) -- only the log-authoring identity (`myDevicePk`/`mySecret`, from
     * [identityOrNull] as usual) is.
     *
     * Returns the certificate already `"azd…"`-encoded (rather than a [DeviceCert]) so
     * `ConnectService` -- which only ever needs the wire string for [Frame.LinkGrant] --
     * doesn't need to import `core`'s certificate types just to call this.
     */
    suspend fun issueDeviceLinkGrant(
        rootPk: ByteArray,
        rootSecret: ByteArray,
        newDevicePk: ByteArray,
        newDeviceName: String,
        roles: Int,
    ): Pair<String, IdentityBundle>? {
        val (store, crypto, identity) = identityOrNull() ?: return null
        val (myDevicePk, mySecret) = identity
        val cert = DeviceCertCodec.issue(
            roles = roles,
            rootPk = rootPk,
            devicePk = newDevicePk,
            issuedAt = epochSeconds(),
            expiresAt = 0,
            name = newDeviceName,
            rootSecret = rootSecret,
            ed25519 = crypto,
        )
        val body = encodeDeviceAddBody(DeviceAddBody(cert = DeviceCertCodec.encode(cert)))
        runCatching { store.appendOwn(myDevicePk, mySecret, EventKind.DEVICE_ADD.wireValue, nowMs(), body) }
        val entries = runCatching { store.allEntries() }.getOrDefault(emptyList())
        val bundle = DeviceLinkProtocol.buildIdentityBundle(rootPk, rootPk.toHexLower(), entries, cert, crypto, epochSeconds())
        return DeviceCertCodec.encode(cert) to bundle
    }

    /**
     * New-device side of a QR-link enrollment (task 6.1): verify a freshly granted
     * certificate before persisting it ([DeviceLinkProtocol.verifyGrantedCert]) -- `null`
     * on any failure (malformed cert, wrong device, already revoked), or if no
     * [Ed25519] is configured (the `-mock` apps, most tests). This device's event log
     * isn't consulted at all: a QR-linked device holds no log of its own yet at this
     * point, only the grant's own accompanying [bundle].
     */
    fun verifyGrantedCert(certStr: String, expectedDevicePk: ByteArray, bundle: IdentityBundle): DeviceCert? {
        val crypto = ed25519 ?: return null
        return DeviceLinkProtocol.verifyGrantedCert(certStr, expectedDevicePk, bundle, crypto, epochSeconds())
    }

    /**
     * The identity's current device set, straight from [AccountSyncFold] over this store's
     * full logs (multi-device-identity tasks 5.4/6.3) -- already excludes any device whose
     * `device_add` fails to verify or is covered by a verified `device_revoke`, per the
     * fold's own rules. Recomputed fresh on every call (never cached) so it always reflects
     * whatever the log currently holds, including a revocation learned only moments ago via
     * sync -- the same "recompute per session/call, don't snapshot" posture
     * [currentRevokedDevicePks]/`sync-real`'s `runSyncSession` kdoc calls out. Empty (not an
     * error) if no event log/ed25519 is configured (the `-mock` apps, most tests).
     */
    suspend fun currentDeviceSet(rootPk: ByteArray): List<DeviceCert> {
        val store = eventLogStore ?: return emptyList()
        val crypto = ed25519 ?: return emptyList()
        val entries = runCatching { store.allEntries() }.getOrDefault(emptyList())
        return AccountSyncFold.fold(entries, rootPk, crypto, epochSeconds()).devices
    }

    /**
     * This device's own certificate, encoded -- the log-store counterpart of
     * `SettingsService.linkedDeviceCert` for a root-holding device (which never sets that
     * field): a root-holding device always logs its own self-certificate as a `device_add`
     * entry on its own chain ([ensureSelfCertified]), so it can be recovered from there by
     * any caller that needs to *present* it (multi-device-identity task 5.4's `SyncHello`)
     * without needing a live Ed25519 signature (the raw already-signed wire string is
     * re-served verbatim). Returns the first `device_add` on [devicePk]'s own chain whose
     * embedded cert actually names [devicePk] -- null if none exists yet (offline before the
     * first self-certify) or no event log is configured.
     */
    suspend fun ownCertString(devicePk: ByteArray): String? {
        val store = eventLogStore ?: return null
        return runCatching { store.readFrom(devicePk, 0L) }.getOrDefault(emptyList())
            .firstNotNullOfOrNull { entry ->
                if (entry.knownKind != EventKind.DEVICE_ADD) return@firstNotNullOfOrNull null
                val body = decodeDeviceAddBody(entry.body) ?: return@firstNotNullOfOrNull null
                val cert = DeviceCertCodec.decode(body.cert) ?: return@firstNotNullOfOrNull null
                if (!cert.devicePk.contentEquals(devicePk)) return@firstNotNullOfOrNull null
                body.cert
            }
    }

    /**
     * Merges [bundleRevocations] (raw `"azr…"` strings from the identity bundle a QR-linked
     * device was granted -- empty for a root-holding device, which never holds a bundle at
     * all) with every verified `device_revoke` entry this store's own logs have learned via
     * sync, and returns the union as raw revoked device public keys.
     *
     * **This is the trap `sync-real`'s `runSyncSession` kdoc calls out, and the whole reason
     * this method exists rather than a call site just reading the bundle once and caching
     * it**: the spec requires a `device_revoke` a sibling synced in to be enforced "on next
     * sync" (account-sync: "Own devices enforce revocation after sync"). A caller that
     * captured the bundle's revocation set once at startup and reused it for every session
     * would satisfy every check inside `runSyncSession` and still let a revoked sibling keep
     * syncing forever -- the Rust reference (`LogStore::device_revocations`) shipped exactly
     * that bug before it was fixed the same way this method fixes it here: called fresh, from
     * the log, on every session (see [runSiblingSync]).
     */
    suspend fun currentRevokedDevicePks(rootPk: ByteArray, bundleRevocations: List<String>): List<ByteArray> {
        val crypto = ed25519 ?: return emptyList()
        val fromBundle = bundleRevocations.mapNotNull { s ->
            val r = DeviceRevocationCodec.decode(s) ?: return@mapNotNull null
            if (!r.rootPk.contentEquals(rootPk) || !DeviceRevocationCodec.verify(r, crypto)) null else r.revokedDevicePk
        }
        val fromLog = eventLogStore?.let { store ->
            runCatching { store.allEntries() }.getOrDefault(emptyList())
                .asSequence()
                .filter { it.knownKind == EventKind.DEVICE_REVOKE }
                .mapNotNull { decodeDeviceRevokeBody(it.body)?.revocation }
                .mapNotNull { DeviceRevocationCodec.decode(it) }
                .filter { it.rootPk.contentEquals(rootPk) && DeviceRevocationCodec.verify(it, crypto) }
                .map { it.revokedDevicePk }
                .toList()
        }.orEmpty()
        val seenHex = LinkedHashSet<String>()
        val out = mutableListOf<ByteArray>()
        for (pk in fromBundle + fromLog) if (seenHex.add(pk.toHexLower())) out.add(pk)
        return out
    }

    /**
     * Runs one `azula/sync/0` session against [stream] (already dialed/accepted, on either
     * side -- see `ConnectService.runSyncWith`) -- multi-device-identity task 5.4.
     * Recomputes the revoked-device set fresh via [currentRevokedDevicePks] on every call
     * (never cached across sessions -- that is precisely this task's trap; see that method's
     * kdoc), then delegates the actual protocol to `sync-real`'s [runSyncSession] -- as a
     * *bounded* catch-up (`livePush = false`): the session ends, and the caller closes the
     * stream, once both sides' acks have crossed. That's what makes this method (and so
     * `runSyncWith`/`dialSibling`) actually return -- the wiring's contract is "one short
     * session per (re)connect, re-sync on the next one", not a parked-forever live stream
     * per sibling per reconnect; it's also what makes the per-session revocation recompute
     * above bite on every reconnect rather than only on the first. `-mock`/no-log posture:
     * returns `null` (and does nothing) if no event log/ed25519/signing identity is
     * configured (the `-mock` apps, most tests) or [myCertString] (the parameter) doesn't
     * decode.
     *
     * cli-multi-session-relay additions, both threaded straight through to
     * `sync-real`'s [runSyncSession]/its own `recvLoop`, and both fire only once the
     * bounded session above has fully closed (this method's `livePush = false` posture,
     * unchanged):
     * - [onSyncA2ui]: the "A2UI Snapshot Replay on Reconnect" callback -- see
     *   [runSyncSession]'s own kdoc for why it lives there rather than here.
     * - [onNewAgentMessage]: fired once per `agent_in` entry this session actually
     *   accepted (live-pushed or caught up), so a caller with access to
     *   `FrameDispatcher.notify` (this class can't depend on that -- see this class's own
     *   kdoc) can fire the identical notification path `Frame.Chat` uses. "New" is
     *   computed from the store's own per-device vector before/after this session ran --
     *   never wall time -- so a rebuild/bootstrap re-reading already-accepted entries
     *   never re-notifies (see [notifyNewlyAcceptedAgentMessages]'s kdoc). Also triggers
     *   [rebuildProjection] when anything changed, so a relayed agent message (or an
     *   ordinary synced peer message) actually becomes visible in the UI.
     */
    suspend fun runSiblingSync(
        stream: P2pStream,
        myCertString: String,
        bundleRevocations: List<String>,
        transportPeerDevicePk: ByteArray,
        onSyncA2ui: suspend (conversation: String, messages: List<JsonObject>) -> Unit = { _, _ -> },
        onNewAgentMessage: (conversation: String, text: String) -> Unit = { _, _ -> },
    ): SyncSessionOutcome? {
        val (store, crypto, _) = identityOrNull() ?: return null
        val myCert = DeviceCertCodec.decode(myCertString) ?: return null
        val revoked = currentRevokedDevicePks(myCert.rootPk, bundleRevocations)
        val beforeVector = runCatching { store.vector() }.getOrDefault(emptyMap())
        val outcome = runSyncSession(
            stream, myCert, revoked, transportPeerDevicePk, store, crypto, epochSeconds(),
            livePush = false, onSyncA2ui = onSyncA2ui,
        )
        val afterVector = runCatching { store.vector() }.getOrDefault(emptyMap())
        if (afterVector != beforeVector) {
            rebuildProjection()
            notifyNewlyAcceptedAgentMessages(store, beforeVector, afterVector, onNewAgentMessage)
        }
        return outcome
    }

    /**
     * cli-multi-session-relay task 6.2: fires [onNewAgentMessage] once per `agent_in`
     * entry this sync session actually caused [store] to accept -- i.e. any entry at a
     * `seq` past [beforeVector]'s snapshot for that device, up through [afterVector].
     * Keying "new" off this accepted-entry delta (never wall time, never "every agent_in
     * currently in the fold") is what keeps a later rebuild/bootstrap from re-notifying:
     * an entry a prior session already advanced a device's cursor past is simply outside
     * every subsequent call's `(beforeVector, afterVector]` window, no matter how many
     * times sync reruns. `message_in`'s own notification gap (see [runSiblingSync]'s
     * kdoc) is deliberately left alone here -- this only ever inspects
     * [EventKind.AGENT_IN] entries.
     */
    private suspend fun notifyNewlyAcceptedAgentMessages(
        store: EventLogStore,
        beforeVector: Map<String, Long>,
        afterVector: Map<String, Long>,
        onNewAgentMessage: (conversation: String, text: String) -> Unit,
    ) {
        for ((deviceHex, afterSeq) in afterVector) {
            val beforeSeq = beforeVector[deviceHex] ?: 0L
            if (afterSeq <= beforeSeq) continue
            val devicePk = deviceHex.hexToByteArrayOrNull() ?: continue
            val newEntries = runCatching { store.readFrom(devicePk, beforeSeq) }.getOrDefault(emptyList())
            for (entry in newEntries) {
                if (entry.knownKind != EventKind.AGENT_IN) continue
                val body = decodeAgentInBody(entry.body) ?: continue
                onNewAgentMessage(body.conversation, body.text)
            }
        }
    }

    /**
     * multi-device-identity task 7.2: verify a peer-presented `Hello.cert` against this
     * identity's known contacts and revocations -- mirrors the Rust reference's
     * `CertCheck`/`check_cert` (`azula-cli/src/accept_gate.rs`). [connectionDevicePk] is
     * the *transport*-level peer id of the exact connection the cert arrived on --
     * required so a cert can't be replayed over a connection it wasn't issued for
     * ("binds to this connection"). [knownRootPkHexes] is the accepted-contacts set
     * (root pk hex, or node id hex for a legacy contact -- see
     * [InviteService.knownContactIds]) and [bundleRevocations] is this device's bundle
     * baseline (see [currentRevokedDevicePks]'s kdoc for why that must be merged with a
     * fresh log scan on every call, never cached).
     *
     * Every failure mode -- no cert, malformed payload, bad/expired signature, wrong
     * connection binding, or a revoked device key -- collapses to [CertCheck.Absent]:
     * per spec, "a certificate that fails verification SHALL be treated exactly as if
     * the field were absent." [CertCheck.KnownByRoot] only when the (verified, bound,
     * unrevoked) cert's root is already in [knownRootPkHexes]; [CertCheck.CertifiedStranger]
     * for a genuine, unrevoked cert whose root isn't (yet) a known contact.
     */
    suspend fun checkPresentedCert(
        certStr: String?,
        connectionDevicePk: ByteArray,
        knownRootPkHexes: Set<String>,
        bundleRevocations: List<String>,
    ): CertCheck {
        val crypto = ed25519 ?: return CertCheck.Absent
        if (certStr == null) return CertCheck.Absent
        val cert = DeviceCertCodec.decode(certStr) ?: return CertCheck.Absent
        if (!cert.devicePk.contentEquals(connectionDevicePk)) return CertCheck.Absent
        if (!DeviceCertCodec.verify(cert, crypto, epochSeconds())) return CertCheck.Absent
        val revoked = currentRevokedDevicePks(cert.rootPk, bundleRevocations)
        if (revoked.any { it.contentEquals(cert.devicePk) }) return CertCheck.Absent
        return if (cert.rootPk.toHexLower() in knownRootPkHexes) CertCheck.KnownByRoot(cert) else CertCheck.CertifiedStranger(cert)
    }

    /**
     * cli-multi-session-relay ("Session Certificates Admit Strangers Without an Invite" /
     * "Phone Auto-Accepts Certified Sessions as Flat Conversations"): verifies [certStr]
     * as an admissible SESSION certificate. Unlike [checkPresentedCert] (which classifies
     * an ordinary IDENTITY device cert as [CertCheck.KnownByRoot]/[CertCheck.CertifiedStranger]/
     * [CertCheck.Absent]), this returns the verified [DeviceCert] only when EVERY one of
     * the spec's five checks holds, and `null` on any single failure -- per spec, "a
     * failure of any SHALL fall through to the ordinary invite verification path, never
     * to an error that blocks the invite path":
     *
     * 1. [certStr] decodes and its signature verifies against its own embedded `root_pk`.
     * 2. It is unexpired (checked together with 1 by [DeviceCertCodec.verify]).
     * 3. It carries the session role flag ([DeviceCert.isSession]).
     * 4. Its `root_pk` is already a paired machine contact -- [knownRootPkHexes], the
     *    same known-contact id set an identity cert's root is checked against
     *    ([InviteService.knownContactIds]); a machine's root pk and its legacy bridge
     *    node id are the same 32 bytes, so no separate lookup exists. A self-certified
     *    session cert (`device_pk == root_pk`) only passes this if that exact key is
     *    itself already a paired contact -- the scan-per-session case, admitted via the
     *    ordinary invite redeemed from the phone, never through this gate.
     * 5. Its `device_pk` equals [connectionDevicePk], the transport-level peer id of the
     *    exact connection it arrived on (never a replay over a different connection).
     *
     * Deliberately does NOT consult device revocations the way [checkPresentedCert] does:
     * v1 has no per-session revocation mechanism (design.md D1 -- "expiry bounds
     * exposure ... revoking the machine pairing kills all its sessions at once"), and a
     * session's `device_pk` is never added via `device_add`, so it could never
     * legitimately appear in that set anyway. `null` if no [Ed25519] is configured (the
     * `-mock` apps, most tests) -- same defensive posture as every other method here.
     */
    fun checkSessionCert(
        certStr: String?,
        connectionDevicePk: ByteArray,
        knownRootPkHexes: Set<String>,
    ): DeviceCert? {
        val crypto = ed25519 ?: return null
        if (certStr == null) return null
        val cert = DeviceCertCodec.decode(certStr) ?: return null
        if (!cert.devicePk.contentEquals(connectionDevicePk)) return null
        if (!DeviceCertCodec.verify(cert, crypto, epochSeconds())) return null
        if (!cert.isSession) return null
        if (cert.rootPk.toHexLower() !in knownRootPkHexes) return null
        return cert
    }

    /**
     * Revoke [devicePkToRevoke] (multi-device-identity task 6.3): issues a root-signed
     * [DeviceRevocation] and appends it to *this* device's own log as a `device_revoke`
     * entry -- signed by this device's own key, like every log entry ("Key Roles and
     * Signing Boundaries": entries are always signed by the device key, never the root
     * key), while the revocation statement it carries is separately signed by [rootSecret].
     * `null` if no event log/ed25519/signing identity is configured (the `-mock` apps, most
     * tests) -- `ConnectService.revokeDevice` is the one real caller, and it already checked
     * [rootSecret] is non-null (root holders only) before calling this.
     */
    suspend fun revokeDevice(rootPk: ByteArray, rootSecret: ByteArray, devicePkToRevoke: ByteArray, revokedAt: Long): DeviceRevocation? {
        val (store, crypto, identity) = identityOrNull() ?: return null
        val (myDevicePk, mySecret) = identity
        val revocation = DeviceRevocationCodec.issue(rootPk, devicePkToRevoke, revokedAt, rootSecret, crypto)
        val body = encodeDeviceRevokeBody(DeviceRevokeBody(revocation = DeviceRevocationCodec.encode(revocation)))
        runCatching { store.appendOwn(myDevicePk, mySecret, EventKind.DEVICE_REVOKE.wireValue, nowMs(), body) }
        return revocation
    }

    /**
     * Rebuilds every known conversation's plain-text message history from the union of
     * this identity's logs (spec's "Projection rebuild" scenario: "a device's derived
     * stores are deleted but its logs remain ... replaying the logs reproduces the same
     * derived state"). For each conversation [convStore] already has, replaces the
     * subsequence of plain-text ME/THEM messages (no attachments, no A2UI -- see
     * [isPlainLogMessage]) with the fold-derived list, in fold order, and persists the
     * result; any attachment/A2UI messages are left exactly where they were.
     *
     * That scoping is deliberate, not an oversight: the log's `message_out`/`message_in`
     * bodies only ever carry `{conversation, text, id?}` (design.md's "Media bodies inside
     * the log" non-goal), so a rebuild driven purely by the logs cannot -- even in
     * principle -- reconstruct an attachment or A2UI card. Conversations made of plain
     * chat text (the common case, and what this task's rebuild-equivalence test covers)
     * round-trip exactly; a mixed conversation keeps its non-text messages untouched by
     * this call, same as they always have been.
     *
     * Idempotent for an unchanged log, so "delete the derived stores, replay the logs" is
     * exactly: clear a conversation's messages (or its whole persisted blob) and call this.
     *
     * cli-multi-session-relay addition: a conversation [convStore] has never heard of is
     * normally skipped (a peer conversation is always `ensureConv`'d at the transport/
     * invite layer before any `message_in`/`message_out` for it is ever logged, so a
     * missing entry there is never this device's own). A relayed `agent_in`/`agent_out`
     * breaks that assumption -- the session's public key can arrive purely via sibling
     * sync, with this device never having connected to that session directly (spec: "a
     * device receiving a relayed agent_in for a new conversation SHALL surface it"). So a
     * conversation whose folded history is at least partly [FoldedMessage.fromAgent] is
     * auto-created here (LLM kind, hex fallback name) instead of skipped; a purely
     * peer-chat conversation's visibility is unchanged -- still gated on the transport/
     * invite path, not this rebuild.
     */
    suspend fun rebuildProjection() {
        val store = eventLogStore ?: return
        val crypto = ed25519 ?: return
        val (rootPk, _) = deviceIdentity?.invoke() ?: return
        val entries = store.allEntries()
        val derived = AccountSyncFold.fold(entries, rootPk, crypto, epochSeconds())
        for ((conversation, folded) in derived.conversations) {
            var cs = convStore.convOrNull(conversation)
            if (cs == null) {
                if (folded.none { it.fromAgent }) continue
                convStore.ensureConv(conversation, ConversationKind.LLM, conversation)
                cs = convStore.convOrNull(conversation) ?: continue
            }
            val nonLogMessages = cs.messages.filterNot { it.isPlainLogMessage() }
            val logMessages = folded.map { it.toMessage() }
            cs.messages.clear()
            cs.messages.addAll(logMessages)
            cs.messages.addAll(nonLogMessages)
            persistence.save(conversation)
        }
    }

    /**
     * One-time migration for an existing (pre-multi-device-identity) install: its message
     * history lives only in [dev.azula.persist.MessageStore]/[ConversationStore] and the
     * event log ships empty, so without this call that history would sit forever outside
     * the log the spec says derives it ("Message history ... SHALL be derived
     * deterministically from the union of the identity's logs"). Converts each existing
     * conversation's plain-text ME/THEM messages (see [isPlainLogMessage] -- attachments/
     * A2UI are out of the log's scope, same as [rebuildProjection]) into `message_out`/
     * `message_in` entries, in their existing order, with no `id` (these predate the wire
     * `Chat.id` this change adds, so -- correctly -- they can never be deduplicated, same
     * as a genuine legacy message never would be).
     *
     * Callers (see [AzulaState.start]) are responsible for calling this at most once --
     * gated on [dev.azula.state.SettingsService]'s persisted `eventLogBackfilled` flag,
     * which they should only set once this returns `true` -- since it has no idempotency
     * guard of its own: a second call would duplicate every message it already backfilled.
     *
     * @return `true` if backfilling actually ran (an event log and a signing identity were
     *   both available), `false` if it was a no-op (no event log configured -- the `-mock`
     *   apps, most tests). A caller should only persist "backfill done" on `true`: marking
     *   it done after a no-op would permanently skip backfilling if a real log later became
     *   available, which never happens in this change's wiring but would be a silent bug
     *   waiting for whatever change relaxes that.
     */
    suspend fun backfillLegacyHistory(): Boolean {
        val store = eventLogStore ?: return false
        // ed25519 isn't used directly below (EventLogStore.appendOwn needs no Ed25519 --
        // it already has its own baked in), but required to be present anyway:
        // eventLogStore and ed25519 are always wired together (see this class's kdoc), so
        // treating either's absence as "the whole feature isn't configured" is the same
        // defensive posture every other method here takes via [identityOrNull].
        if (ed25519 == null) return false
        val (devicePk, secret) = deviceIdentity?.invoke() ?: return false
        var ts = nowMs()
        for (conversation in convStore.conversations) {
            val cs = convStore.convOrNull(conversation.id) ?: continue
            for (message in cs.messages) {
                if (!message.isPlainLogMessage()) continue
                val text = message.text ?: continue
                val (kind, body) = if (message.role == Role.ME) {
                    EventKind.MESSAGE_OUT.wireValue to encodeMessageOutBody(MessageOutBody(conversation.id, text, id = null))
                } else {
                    EventKind.MESSAGE_IN.wireValue to encodeMessageInBody(MessageInBody(conversation.id, conversation.id, text, id = null))
                }
                runCatching { store.appendOwn(devicePk, secret, kind, ts, body) }
                ts += 1
            }
        }
        return true
    }
}

/** A message the log's fold can fully represent and reproduce: plain text, no
 *  attachments, no A2UI card, authored either by us or the peer. Everything else
 *  (file transfers, A2UI surfaces, terminal output) is outside the log's scope --
 *  see [AccountSyncService.rebuildProjection]'s kdoc. */
private fun Message.isPlainLogMessage(): Boolean =
    text != null && attachments.isEmpty() && a2ui == null && (role == Role.ME || role == Role.THEM)

/** Maps a folded log entry back to the UI's [Message] shape. Entries backfilled from
 *  pre-existing history (or any sent before the wire `Chat.id` existed) have no [id];
 *  a stable synthetic one keyed on the entry's (globally monotonic) lamport keeps the
 *  UI's LazyColumn key stable across rebuilds without ever colliding with a real one
 *  (real ids are 32 lowercase-hex characters; this is never that shape). */
private fun FoldedMessage.toMessage(): Message = Message(
    id = id ?: "log$lamport",
    role = if (outgoing) Role.ME else Role.THEM,
    text = text,
)
