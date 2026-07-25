package dev.azula.sync

import dev.azula.core.DeviceCert
import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.LOG_ENTRY_ORDER
import dev.azula.core.LogEntry

// azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
// "Derived State Is a Deterministic Fold": message history, contacts, device set,
// read state, and profile are all derived from the union of the identity's logs.
// This file is that fold -- pure functions over `core.LogEntry`, no store needed.

/** One message folded into a conversation's history, from either a `message_out`/
 *  `agent_out` ([outgoing] `true`) or a deduplicated `message_in`/`agent_in`
 *  ([outgoing] `false`) entry. [fromAgent] is `true` for the two cli-multi-session-relay
 *  kinds (`agent_in`/`agent_out`) -- callers use it to tell a session conversation's
 *  history apart from a peer conversation's (e.g. to auto-create a conversation a device
 *  only ever learned about via a relayed entry -- see `AccountSyncService.rebuildProjection`). */
data class FoldedMessage(
    val conversation: String,
    val text: String,
    val id: String?,
    val outgoing: Boolean,
    val authorDevicePk: ByteArray,
    val lamport: Long,
    val tsMs: Long,
    val fromAgent: Boolean = false,
) {
    // See core's LogEntry.equals kdoc: a data class would give the ByteArray field
    // reference equality, which is wrong for a value the determinism test compares
    // structurally across independently-folded (but logically identical) states.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FoldedMessage) return false
        return conversation == other.conversation &&
            text == other.text &&
            id == other.id &&
            outgoing == other.outgoing &&
            lamport == other.lamport &&
            tsMs == other.tsMs &&
            fromAgent == other.fromAgent &&
            authorDevicePk.contentEquals(other.authorDevicePk)
    }

    override fun hashCode(): Int {
        var result = conversation.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + outgoing.hashCode()
        result = 31 * result + lamport.hashCode()
        result = 31 * result + tsMs.hashCode()
        result = 31 * result + fromAgent.hashCode()
        result = 31 * result + authorDevicePk.contentHashCode()
        return result
    }
}

/** A resolved contact: still present (its highest-ordered `contact_add`/`contact_remove`
 *  entry was an add), with the display name from that same entry, if any. */
data class ContactInfo(val key: String, val name: String?)

/** The identity's derived profile fields -- each independently last-writer-wins;
 *  see [AccountSyncFold]'s `PROFILE_UPDATE` handling. */
data class ProfileInfo(val name: String? = null, val description: String? = null)

/**
 * The identity's entire derived state -- rebuildable at any time from the raw union
 * of its devices' logs alone (spec: "Any device SHALL be able to rebuild its entire
 * derived state from the raw logs alone").
 */
data class DerivedState(
    val conversations: Map<String, List<FoldedMessage>>,
    val contacts: Map<String, ContactInfo>,
    val devices: List<DeviceCert>,
    val readMarkers: Map<String, Long>,
    val profile: ProfileInfo,
)

/**
 * The deterministic fold from "Derived State Is a Deterministic Fold": entries are
 * sorted by [LOG_ENTRY_ORDER] ascending before folding, so [fold] returns identical
 * [DerivedState] regardless of the order [entries] is handed in -- the determinism
 * property the spec's "Same logs, same state" scenario requires.
 *
 * Unknown kinds (a newer sibling's extension this build doesn't recognize) are
 * skipped -- excluded from every part of [DerivedState] -- never rejected;
 * `EventLogStore` still stores and re-serves them verbatim (spec: "Unknown kind
 * passes through"). A *known* kind whose body fails to decode (a malformed or
 * corrupted entry) is skipped the same defensive way, rather than crashing the fold.
 *
 * [rootPk] is the identity's root public key: a `device_add`/`device_revoke` entry's
 * embedded certificate/revocation must chain to it -- and itself pass
 * [DeviceCertCodec.verify]/`DeviceRevocationCodec.verify` -- to affect
 * [DerivedState.devices]; a forged or foreign-root cert/revocation is silently
 * excluded, exactly like an unknown kind. This reading goes slightly beyond the
 * spec's literal "union of device_add certificates minus verified revocations" (which
 * only says *revocations* must be verified) by also verifying each `device_add`
 * certificate's own signature/expiry -- otherwise any device with log-write access
 * could inject an unverifiable cert into the device set, which would make the
 * "device set" meaningless as a security boundary.
 */
object AccountSyncFold {
    fun fold(
        entries: List<LogEntry>,
        rootPk: ByteArray,
        ed25519: Ed25519,
        nowEpochSeconds: Long,
    ): DerivedState {
        val ordered = entries.sortedWith(LOG_ENTRY_ORDER)

        val conversations = LinkedHashMap<String, MutableList<FoldedMessage>>()
        // (conversation, id) dedup, id != null only -- shared by MESSAGE_IN and AGENT_IN
        // (cli-multi-session-relay's "Agent retry deduplicates": a session that delivers a
        // message directly (folded as MESSAGE_IN on the receiving device's own log) and then,
        // after a timeout, retries the *same id* via the relay (folded as AGENT_IN on the
        // relay's log) must fold to exactly one message in that conversation -- which only
        // works if both kinds dedup against the same (conversation, id) key space).
        val seenIncoming = HashSet<Pair<String, String>>()
        val contactState = LinkedHashMap<String, ContactState>()
        val certsByDevice = LinkedHashMap<String, DeviceCert>() // hex device_pk -> latest valid cert
        val revokedDeviceHex = HashSet<String>()
        val readMarkers = LinkedHashMap<String, Long>()
        var profileName: String? = null
        var profileDescription: String? = null

        for (entry in ordered) {
            when (entry.knownKind) {
                EventKind.MESSAGE_OUT -> {
                    val body = decodeMessageOutBody(entry.body) ?: continue
                    conversations.getOrPut(body.conversation) { mutableListOf() }.add(
                        FoldedMessage(
                            conversation = body.conversation,
                            text = body.text,
                            id = body.id,
                            outgoing = true,
                            authorDevicePk = entry.authorDevicePk,
                            lamport = entry.lamport,
                            tsMs = entry.tsMs,
                        ),
                    )
                }

                EventKind.MESSAGE_IN -> {
                    val body = decodeMessageInBody(entry.body) ?: continue
                    // "Chat Message Ids Deduplicate Retries": dedupe by (sender/conversation, id);
                    // entries without an id are never deduplicated.
                    if (body.id != null && !seenIncoming.add(body.conversation to body.id)) continue
                    conversations.getOrPut(body.conversation) { mutableListOf() }.add(
                        FoldedMessage(
                            conversation = body.conversation,
                            text = body.text,
                            id = body.id,
                            outgoing = false,
                            authorDevicePk = entry.authorDevicePk,
                            lamport = entry.lamport,
                            tsMs = entry.tsMs,
                        ),
                    )
                }

                EventKind.AGENT_IN -> {
                    val body = decodeAgentInBody(entry.body) ?: continue
                    // Same (conversation, id) dedup space as MESSAGE_IN -- see seenIncoming's kdoc.
                    if (body.id != null && !seenIncoming.add(body.conversation to body.id)) continue
                    conversations.getOrPut(body.conversation) { mutableListOf() }.add(
                        FoldedMessage(
                            conversation = body.conversation,
                            text = body.text,
                            id = body.id,
                            outgoing = false,
                            authorDevicePk = entry.authorDevicePk,
                            lamport = entry.lamport,
                            tsMs = entry.tsMs,
                            fromAgent = true,
                        ),
                    )
                }

                EventKind.AGENT_OUT -> {
                    val body = decodeAgentOutBody(entry.body) ?: continue
                    // No dedup -- matches MESSAGE_OUT's posture (an identity's own outbound
                    // entries are never retried into the log twice under the same id).
                    conversations.getOrPut(body.conversation) { mutableListOf() }.add(
                        FoldedMessage(
                            conversation = body.conversation,
                            text = body.text,
                            id = body.id,
                            outgoing = true,
                            authorDevicePk = entry.authorDevicePk,
                            lamport = entry.lamport,
                            tsMs = entry.tsMs,
                            fromAgent = true,
                        ),
                    )
                }

                EventKind.READ_MARKER -> {
                    val body = decodeReadMarkerBody(entry.body) ?: continue
                    readMarkers[body.conversation] = body.upToLamport
                }

                EventKind.CONTACT_ADD -> {
                    val body = decodeContactKeyBody(entry.body) ?: continue
                    val key = body.key ?: continue
                    contactState[key] = ContactState(present = true, name = body.name)
                }

                EventKind.CONTACT_REMOVE -> {
                    val body = decodeContactKeyBody(entry.body) ?: continue
                    val key = body.key ?: continue
                    contactState[key] = ContactState(present = false, name = contactState[key]?.name)
                }

                EventKind.DEVICE_ADD -> {
                    val body = decodeDeviceAddBody(entry.body) ?: continue
                    val cert = DeviceCertCodec.decode(body.cert) ?: continue
                    if (!cert.rootPk.contentEquals(rootPk)) continue
                    if (!DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds)) continue
                    certsByDevice[cert.devicePk.toHexLower()] = cert
                }

                EventKind.DEVICE_REVOKE -> {
                    val body = decodeDeviceRevokeBody(entry.body) ?: continue
                    val revocation = DeviceRevocationCodec.decode(body.revocation) ?: continue
                    if (!revocation.rootPk.contentEquals(rootPk)) continue
                    if (!DeviceRevocationCodec.verify(revocation, ed25519)) continue
                    revokedDeviceHex.add(revocation.revokedDevicePk.toHexLower())
                }

                EventKind.PROFILE_UPDATE -> {
                    val body = decodeProfileUpdateBody(entry.body) ?: continue
                    // Per-field last-writer-wins: a field absent from this entry's body
                    // leaves whatever an earlier entry set untouched (not a clear).
                    if (body.name != null) profileName = body.name
                    if (body.description != null) profileDescription = body.description
                }

                null -> {
                    // Unknown kind byte: stored and re-served by EventLogStore, but
                    // excluded here from every part of the derived state.
                }
            }
        }

        val devices = certsByDevice.filterKeys { it !in revokedDeviceHex }.values.toList()
        val contacts = contactState.filterValues { it.present }
            .mapValues { (key, state) -> ContactInfo(key, state.name) }

        return DerivedState(
            conversations = conversations,
            contacts = contacts,
            devices = devices,
            readMarkers = readMarkers,
            profile = ProfileInfo(profileName, profileDescription),
        )
    }

    private data class ContactState(val present: Boolean, val name: String?)
}
