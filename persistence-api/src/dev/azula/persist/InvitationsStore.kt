package dev.azula.persist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An invite this device minted (see [dev.azula.link.InviteCodec]). [id] is the
 * invite's lowercase-hex fingerprint (`InvitePayload.fingerprint`) — the key the
 * accept-side gate looks up in this store (see azula-docs/openspec/specs/invitations/design.md's
 * verification checklist). [flags] mirrors the wire payload's flags byte (bit 0
 * signed, bit 1 single-use). [expiresAt]/[createdAt] are unix seconds; 0 for
 * [expiresAt] means never.
 */
@Serializable
data class IssuedInvite(
    val id: String,
    val createdAt: Long,
    val expiresAt: Long,
    val flags: Int,
    val label: String? = null,
    val consumed: Boolean = false,
)

/**
 * A stranger's connection held pending the user's accept/decline (see
 * `dev.azula.state.InviteService.pending`). The live stream itself is never
 * persisted — only enough to redraw the pending-request row after a restart
 * (the request itself won't survive one; the peer would need to reconnect).
 */
@Serializable
data class PendingInvite(
    val endpointId: String,
    val inviteId: String? = null,
    val receivedAt: Long,
    val peerCode: String,
    val unverified: Boolean = false,
)

/** Everything [InvitationsStore] persists: invites we issued, strangers awaiting
 *  a decision, and the endpoint ids of peers we've already accepted. */
@Serializable
data class InvitationsBook(
    val issued: List<IssuedInvite> = emptyList(),
    val pending: List<PendingInvite> = emptyList(),
    val contacts: List<String> = emptyList(),
)

/**
 * Persists invitations state. Mirrors the [ProfileStore] / [dev.azula.net.PeerStore]
 * expect/actual + injectable-config pattern: a single small JSON blob (the whole
 * [InvitationsBook]).
 */
interface InvitationsStore {
    fun load(): InvitationsBook
    fun save(book: InvitationsBook)
}

val InvitationsJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val invitationsBookSerializer = InvitationsBook.serializer()

fun encodeInvitationsBook(book: InvitationsBook): String = InvitationsJson.encodeToString(invitationsBookSerializer, book)

fun decodeInvitationsBook(text: String): InvitationsBook? =
    runCatching { InvitationsJson.decodeFromString(invitationsBookSerializer, text) }.getOrNull()
