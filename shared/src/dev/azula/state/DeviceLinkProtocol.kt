package dev.azula.state

import dev.azula.core.DeviceCert
import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.LOG_ENTRY_ORDER
import dev.azula.core.LogEntry
import dev.azula.net.Contact
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IdentityBundle
import dev.azula.net.P2pStream
import dev.azula.sync.AccountSyncFold
import dev.azula.sync.decodeContactKeyBody
import dev.azula.sync.decodeDeviceRevokeBody

// azula-docs/openspec/changes/multi-device-identity/specs/device-linking/spec.md's
// "QR-Link Enrollment Grants a Certificate Without Root Authority" -- tasks 6.1 (new
// device) and 6.2 (root-holding device). The Rust reference (`azula-cli`'s `link.rs`,
// `main.rs::verify_granted_cert`) only ever plays the new-device role -- the CLI has no
// UI to confirm a grant -- so [runGrantingSession]/[buildIdentityBundle] below have no
// direct Rust counterpart; [runNewDeviceSession]/[verifyGrantedCert] mirror it closely.

/**
 * The `azula/link/0` protocol, factored exactly like the Rust reference into pure
 * functions over anything that can `sendLine`/`receiveLine` -- a real [P2pStream] in
 * production (see `ConnectService`, the only caller that ever hands this a real iroh
 * stream), a plain in-memory pair in tests (`DeviceLinkProtocolTest`). No sockets, no
 * iroh dependency here at all.
 *
 * Per the spec ("both devices SHALL display the same four verification words ... before
 * any grant is possible"), the words themselves are derived by the *caller*, from the two
 * device public keys -- both already known to each side before any frame is exchanged (the
 * new device's own key plus the transport-level remote id of the inbound connection it
 * just accepted; the granting device's own key plus the scanned `azl…` payload's embedded
 * `device_pk`) -- so neither function here computes or displays them; see
 * [dev.azula.core.VerificationWords.compute] and `ConnectService`'s callers.
 */
object DeviceLinkProtocol {

    /** What the new-device side of one `azula/link/0` session ends with. */
    sealed class LinkOutcome {
        data class Granted(val cert: String, val bundle: IdentityBundle) : LinkOutcome()
        data class Rejected(val reason: String) : LinkOutcome()
    }

    /**
     * New-device side (task 6.1): send [Frame.LinkHello] presenting [devicePkHex]/[name]/
     * [roles] (the requested roles bitfield -- bit 0 mailbox), then wait for the
     * root-holding device's [Frame.LinkGrant] or [Frame.LinkReject].
     *
     * Unlike the Rust reference (which `bail!`s -- an `Err`, unwound by the CLI process
     * exiting -- on a malformed reply or an early close), this always resolves to a
     * [LinkOutcome]: the app UI has to land on *some* screen, so both failure shapes fold
     * into [LinkOutcome.Rejected] with a descriptive reason rather than throwing. Callers
     * MUST persist nothing on [LinkOutcome.Rejected], exactly as on an explicit
     * [Frame.LinkReject].
     */
    suspend fun runNewDeviceSession(stream: P2pStream, devicePkHex: String, name: String, roles: Int): LinkOutcome {
        stream.sendLine(FrameCodec.encode(Frame.LinkHello(devicePkHex, name, roles)))
        val line = stream.receiveLine()
            ?: return LinkOutcome.Rejected("connection closed before granting or rejecting")
        return when (val frame = runCatching { FrameCodec.decode(line) }.getOrNull()) {
            is Frame.LinkGrant -> LinkOutcome.Granted(frame.cert, frame.bundle)
            is Frame.LinkReject -> LinkOutcome.Rejected(frame.reason)
            else -> LinkOutcome.Rejected("expected a grant or a rejection, got something else")
        }
    }

    /** What the confirmation UI decided, for [runGrantingSession] to act on. Only
     *  [Grant] can ever produce a [Frame.LinkGrant] -- see that function's kdoc. */
    sealed class GrantDecision {
        data class Grant(val cert: String, val bundle: IdentityBundle) : GrantDecision()
        data class Reject(val reason: String) : GrantDecision()
    }

    /** Outcome of the granting side's session, for callers/tests that want to distinguish
     *  "granted" from "declined" from "the peer never even sent a proper hello". */
    enum class GrantSessionResult { GRANTED, REJECTED, NO_HELLO }

    /**
     * Root-holding side (task 6.2): wait for the new device's [Frame.LinkHello], hand its
     * `(devicePk, name, roles)` to [decide] -- which the caller wires to "show the
     * verification words plus the requested roles, and wait for the user's explicit
     * confirm/cancel" -- then send the resulting [Frame.LinkGrant]/[Frame.LinkReject].
     *
     * Per the spec ("only explicit confirmation on the root-holding device causes the
     * `LinkGrant`; cancelling sends `LinkReject` and no certificate exists"), [decide] is
     * the *only* thing that can produce a grant: this function never grants on its own,
     * and a cancellation is exactly [GrantDecision.Reject]. If the peer never sends a
     * (decodable) `LinkHello` first, nothing is sent back at all and
     * [GrantSessionResult.NO_HELLO] is returned -- there is no request to decide on, so
     * nothing is shown to the user and no frame is worth sending.
     */
    suspend fun runGrantingSession(
        stream: P2pStream,
        decide: suspend (devicePkHex: String, name: String, roles: Int) -> GrantDecision,
    ): GrantSessionResult {
        val line = stream.receiveLine() ?: return GrantSessionResult.NO_HELLO
        val hello = runCatching { FrameCodec.decode(line) }.getOrNull() as? Frame.LinkHello
            ?: return GrantSessionResult.NO_HELLO
        return when (val decision = decide(hello.devicePk, hello.name, hello.roles)) {
            is GrantDecision.Grant -> {
                stream.sendLine(FrameCodec.encode(Frame.LinkGrant(decision.cert, decision.bundle)))
                GrantSessionResult.GRANTED
            }
            is GrantDecision.Reject -> {
                stream.sendLine(FrameCodec.encode(Frame.LinkReject(decision.reason)))
                GrantSessionResult.REJECTED
            }
        }
    }

    /**
     * New-device side (task 6.1): verify a freshly granted certificate before persisting
     * it -- mirrors the Rust reference's `verify_granted_cert` (`main.rs`), which exists
     * precisely because an earlier version of that code persisted whatever arrived. Checks,
     * all of which must pass:
     *  - the certificate decodes at all ([DeviceCertCodec.decode]);
     *  - its signature verifies against its own embedded root key and it isn't expired
     *    ([DeviceCertCodec.verify]);
     *  - its `device_pk` names *this* device's own freshly generated key ([expectedDevicePk])
     *    -- a grant should always name us, never a different device;
     *  - it isn't already revoked per the accompanying [bundle]'s own (verified) revocation
     *    set.
     *
     * Returns `null` on any failure -- callers MUST persist nothing in that case.
     */
    fun verifyGrantedCert(
        certStr: String,
        expectedDevicePk: ByteArray,
        bundle: IdentityBundle,
        ed25519: Ed25519,
        nowEpochSeconds: Long,
    ): DeviceCert? {
        val cert = DeviceCertCodec.decode(certStr) ?: return null
        val revokedDevicePks = verifiedRevocationDevicePks(bundle, ed25519)
        if (!DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds, revokedDevicePks)) return null
        if (!cert.devicePk.contentEquals(expectedDevicePk)) return null
        return cert
    }

    /** Every `azr…` revocation in [bundle] that decodes and verifies, as raw revoked device
     *  public keys -- the same "decode, keep only what verifies" posture as the Rust
     *  reference's `mailbox_role::verified_revocations_from_bundle`. */
    private fun verifiedRevocationDevicePks(bundle: IdentityBundle, ed25519: Ed25519): List<ByteArray> =
        bundle.revocations.mapNotNull { s ->
            val revocation = DeviceRevocationCodec.decode(s) ?: return@mapNotNull null
            if (!DeviceRevocationCodec.verify(revocation, ed25519)) return@mapNotNull null
            revocation.revokedDevicePk
        }

    /**
     * Root-holding side (task 6.2): the [IdentityBundle] a grant delivers alongside the new
     * certificate -- root public key, every other currently-valid certificate this device
     * knows of (via [AccountSyncFold], so an already-revoked device's cert is excluded the
     * same way any other verifier would exclude it -- the accompanying [IdentityBundle.revocations]
     * is what lets a learner reject that device again if it ever resurfaces), the verified
     * revocation set, and the contacts snapshot. [newCert] (the just-issued certificate for
     * the linking device) is folded into `certs` even though [entries] doesn't contain its
     * own `device_add` entry yet -- the caller appends that in the same beat (see
     * `AccountSyncService.issueDeviceLinkGrant`), so this function doesn't need to re-read
     * the store after appending.
     *
     * `mailbox` is always `null` here: no store of "which sibling currently serves the
     * mailbox role" exists in the app yet -- that's the mailbox capability's own follow-up,
     * not this task. A device that later learns one keeps it via a later sync round, per
     * "Device Registry Persistence"'s "reconstructable from the identity bundle plus log
     * sync" -- losing this hint is exactly as recoverable as losing everything else this
     * function computes.
     */
    fun buildIdentityBundle(
        rootPk: ByteArray,
        rootPkHex: String,
        entries: List<LogEntry>,
        newCert: DeviceCert,
        ed25519: Ed25519,
        nowEpochSeconds: Long,
    ): IdentityBundle {
        val derived = AccountSyncFold.fold(entries, rootPk, ed25519, nowEpochSeconds)
        val certs = (derived.devices.map { DeviceCertCodec.encode(it) } + DeviceCertCodec.encode(newCert)).distinct()
        val revocations = verifiedRevocationStrings(entries, rootPk, ed25519)
        val contacts = contactsSnapshot(entries)
        return IdentityBundle(rootPk = rootPkHex, certs = certs, revocations = revocations, contacts = contacts, mailbox = null)
    }

    /** Every `device_revoke` entry's embedded revocation, re-encoded, that decodes, chains
     *  to [rootPk], and verifies -- deduplicated. */
    private fun verifiedRevocationStrings(entries: List<LogEntry>, rootPk: ByteArray, ed25519: Ed25519): List<String> =
        entries.asSequence()
            .filter { it.knownKind == EventKind.DEVICE_REVOKE }
            .mapNotNull { decodeDeviceRevokeBody(it.body)?.revocation }
            .filter { s ->
                val r = DeviceRevocationCodec.decode(s) ?: return@filter false
                r.rootPk.contentEquals(rootPk) && DeviceRevocationCodec.verify(r, ed25519)
            }
            .distinct()
            .toList()

    /**
     * The identity's current contact set, folded from `contact_add`/`contact_remove`
     * entries in [LOG_ENTRY_ORDER] (last-writer-wins per key; an add after a remove
     * restores it). A small local fold rather than reusing
     * [AccountSyncFold.DerivedState.contacts], which collapses each contact to a single
     * opaque `key` string and so can't tell a certified contact's `root_pk` apart from a
     * legacy one's `node_id` -- exactly the distinction [Contact] (and the wire bundle)
     * needs to preserve (`Contact`'s own kdoc: "exactly one of the two, never both").
     */
    private fun contactsSnapshot(entries: List<LogEntry>): List<Contact> {
        val state = LinkedHashMap<String, ContactFoldState>()
        for (entry in entries.sortedWith(LOG_ENTRY_ORDER)) {
            when (entry.knownKind) {
                EventKind.CONTACT_ADD -> {
                    val body = decodeContactKeyBody(entry.body) ?: continue
                    val key = body.key ?: continue
                    state[key] = ContactFoldState(present = true, rootPk = body.rootPk, nodeId = body.nodeId, name = body.name)
                }

                EventKind.CONTACT_REMOVE -> {
                    val body = decodeContactKeyBody(entry.body) ?: continue
                    val key = body.key ?: continue
                    val prev = state[key]
                    state[key] = ContactFoldState(present = false, rootPk = prev?.rootPk, nodeId = prev?.nodeId, name = prev?.name)
                }

                else -> Unit
            }
        }
        return state.values.filter { it.present }.map { Contact(rootPk = it.rootPk, nodeId = it.nodeId, name = it.name) }
    }

    private data class ContactFoldState(val present: Boolean, val rootPk: String?, val nodeId: String?, val name: String?)
}
