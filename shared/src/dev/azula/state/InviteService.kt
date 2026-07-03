package dev.azula.state

import androidx.compose.runtime.mutableStateListOf
import dev.azula.core.AppScope
import dev.azula.epochSeconds
import dev.azula.link.InviteCodec
import dev.azula.link.InvitePayload
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.notify.Notifier
import dev.azula.persist.InvitationsBook
import dev.azula.persist.InvitationsStore
import dev.azula.persist.IssuedInvite
import dev.azula.persist.PendingInvite
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * An inbound stranger connection awaiting the user's accept/decline decision.
 * [stream]/[alpn] live only in memory (see [InviteService.pending]) -- a restart
 * necessarily drops the connection along with them; the rest mirrors the
 * persisted [PendingInvite] shape so the row can still be redrawn (as
 * unreachable) after one.
 */
data class PendingConnectionRequest(
    val nodeId: String,
    val inviteId: String?,
    val peerCode: String,
    val unverified: Boolean,
    val stream: P2pStream,
    val alpn: String,
)

/**
 * Owns invitations: minting/revoking invites this device issued, verifying an
 * inbound stranger's `Hello.invite` against the issued store, and the pending
 * accept/decline inbox for strangers whose stream [ConnectService] is holding
 * open. See azula-docs/docs/invitations.md for the full trust model and the
 * verification checklist [verifyInbound] implements.
 */
@Inject
@SingleIn(AppScope::class)
class InviteService(
    private val invitationsStore: InvitationsStore?,
    private val transport: IrohTransport,
    private val notifier: Notifier?,
    private val scope: CoroutineScope,
) {
    /** Set by ConnectService: wire an accepted stranger's held stream into a live conversation. */
    var onAccept: (PendingConnectionRequest) -> Unit = {}

    /** Set by AzulaState: whether the app is currently in the foreground. */
    var foreground: () -> Boolean = { true }

    /** Invites this device has minted. */
    val issued = mutableStateListOf<IssuedInvite>()

    /** Inbound strangers awaiting accept/decline, oldest first. */
    val pending = mutableStateListOf<PendingConnectionRequest>()

    /** Node ids of peers we've already accepted an invite from (or otherwise know). */
    private val contacts = mutableSetOf<String>()

    fun load() {
        val book = invitationsStore?.load() ?: return
        issued.clear(); issued.addAll(book.issued)
        contacts.clear(); contacts.addAll(book.contacts)
        // book.pending isn't resumable -- its live stream is gone after a restart.
    }

    private fun persist() {
        invitationsStore?.save(
            InvitationsBook(
                issued = issued.toList(),
                pending = pending.map { PendingInvite(it.nodeId, it.inviteId, epochSeconds(), it.peerCode, it.unverified) },
                contacts = contacts.toList(),
            ),
        )
    }

    /** True if [nodeIdHex] is a peer we've already accepted (the "known peer,
     *  connect with no gate" branch of the verification model). */
    fun isKnown(nodeIdHex: String): Boolean = nodeIdHex in contacts

    /**
     * Mint a new invite embedding this device's own ticket. Null if the
     * transport isn't bound yet, or ticket-byte encoding is unsupported (e.g.
     * the mock/no-op transport, or a real transport before [IrohTransport.bind]).
     */
    suspend fun createInvite(
        expiresInSeconds: Long? = null,
        sign: Boolean = false,
        singleUse: Boolean = false,
        label: String? = null,
    ): String? {
        val ticketStr = runCatching { transport.myTicket() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val ticketBytes = transport.ticketBytes(ticketStr) ?: return null
        val now = epochSeconds()
        val inviteId = Random.nextBytes(8)
        var flags = 0
        if (sign) flags = flags or 0x01
        if (singleUse) flags = flags or 0x02
        val expiresAt = expiresInSeconds?.let { now + it } ?: 0L
        var payload = InvitePayload(1, flags, inviteId, now, expiresAt, ticketBytes, null)
        if (sign) {
            val sig = transport.signWithNodeKey(payload.signedInputBytes()) ?: return null
            payload = payload.copy(signature = sig)
        }
        issued.add(IssuedInvite(payload.fingerprint, now, expiresAt, flags, label, consumed = false))
        persist()
        return InviteCodec.encode(payload)
    }

    fun revoke(id: String) {
        issued.removeAll { it.id == id }
        persist()
    }

    /**
     * Verify a stranger's `Hello.invite` against the issued store. Returns the
     * matching [IssuedInvite] iff every check in azula-docs/docs/invitations.md's
     * verification list passes; null on any failure (wrong version, unknown or
     * revoked id, expired, bad/missing signature, already-consumed single-use) --
     * never throws.
     */
    fun verifyInbound(inviteEncoded: String?): IssuedInvite? {
        if (inviteEncoded.isNullOrBlank()) return null
        val payload = InviteCodec.decode(inviteEncoded) ?: return null
        if (payload.version != 1) return null
        val myNodeId = transport.nodeId ?: return null
        val ticketStr = transport.ticketFromBytes(payload.ticket) ?: return null
        if (transport.peerIdForTicket(ticketStr) != myNodeId) return null
        val issuedInvite = issued.firstOrNull { it.id == payload.fingerprint } ?: return null
        if (issuedInvite.expiresAt != 0L && epochSeconds() >= issuedInvite.expiresAt) return null
        if (payload.signed) {
            val sig = payload.signature ?: return null
            if (!transport.verifyNodeSignature(myNodeId, payload.signedInputBytes(), sig)) return null
        }
        if (payload.singleUse && issuedInvite.consumed) return null
        return issuedInvite
    }

    /**
     * Hold a validated (or, if [unverified], legacy-allowed) stranger's stream
     * pending an accept/decline decision. Posts one batched notification --
     * sentinel conversation id "invites" -- rather than one per request.
     */
    fun enqueuePending(nodeId: String, inviteId: String?, peerCode: String, stream: P2pStream, alpn: String, unverified: Boolean) {
        if (pending.any { it.nodeId == nodeId }) {
            scope.launch { runCatching { stream.close() } } // already have one from this peer
            return
        }
        pending.add(PendingConnectionRequest(nodeId, inviteId, peerCode, unverified, stream, alpn))
        persist()
        if (!foreground()) {
            notifier?.postMessage("invites", "Connection requests", "${pending.size} pending")
        }
    }

    fun accept(req: PendingConnectionRequest) {
        if (!pending.remove(req)) return
        contacts.add(req.nodeId)
        req.inviteId?.let { id ->
            val idx = issued.indexOfFirst { it.id == id }
            if (idx >= 0 && (issued[idx].flags and 0x02) != 0) issued[idx] = issued[idx].copy(consumed = true)
        }
        persist()
        onAccept(req)
    }

    fun decline(req: PendingConnectionRequest) {
        if (!pending.remove(req)) return
        persist()
        scope.launch { runCatching { req.stream.close() } }
    }

    fun acceptAll() = pending.toList().forEach { accept(it) }
    fun declineAll() = pending.toList().forEach { decline(it) }
}
