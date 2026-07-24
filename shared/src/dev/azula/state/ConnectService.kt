package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.core.RecoveryPhrase
import dev.azula.core.AppScope
import dev.azula.core.DeviceCertCodec
import dev.azula.core.LinkPayloadCodec
import dev.azula.core.VerificationWords
import dev.azula.core.newLinkPayload
import dev.azula.epochSeconds
import dev.azula.link.AzulaLinks
import dev.azula.link.DeepLinkBus
import dev.azula.link.InviteCodec
import dev.azula.link.isInvite
import dev.azula.model.ConnectStage
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.model.Role
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IdentityBundle
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.net.PeerStore
import dev.azula.net.receiveFile
import dev.azula.state.DeviceLinkProtocol.GrantDecision
import dev.azula.state.DeviceLinkProtocol.LinkOutcome
import dev.azula.sync.hexToByteArrayOrNull
import dev.azula.sync.toHexLower
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The connect + transport core carved out of AzulaState: owns the (rebindable)
 * [transport], the outbound dial / holepunch flow, silent reconnect, the inbound
 * connection loop, and the per-conversation receive loop that reads frames off the
 * wire. Interpreting a received frame (fan-out to chat/a2ui/terminal, the
 * peer-profile handshake) is [FrameDispatcher]'s job -- this class just hands each
 * frame to it as it arrives; the dependency is one-directional, FrameDispatcher
 * never calls back into this class.
 *
 * One app-shell concern is provided by a callback the owner sets after
 * construction (avoids a circular graph dependency): [onNavigate] to open a
 * conversation. (The other, `foreground`, now lives on [FrameDispatcher] since
 * that's the only place it's read.)
 */
@Inject
@SingleIn(AppScope::class)
class ConnectService(
    private val transport: IrohTransport,
    private val scope: CoroutineScope,
    private val convStore: ConversationStore,
    private val persistence: PersistenceCoordinator,
    private val frameDispatcher: FrameDispatcher,
    private val mediaService: MediaService,
    private val peerStore: PeerStore?,
    private val inviteService: InviteService,
    private val accountSyncService: AccountSyncService,
    // Device linking (multi-device-identity tasks 6.1/6.2): persists a QR-linked device's
    // granted cert + bundle (task 6.1) and reads the root secret when granting one (task
    // 6.2, via `transport.rootSecretBytes()` already available below). Metro resolves this
    // the same way it resolves accountSyncService above -- SettingsService is already
    // `@Inject`-constructible from the graph's `settingsStore: SettingsStore?` binding, so
    // no AppGraph.Factory change is needed for this new parameter.
    private val settingsService: SettingsService,
) {
    /** Set by AzulaState: open a conversation's chat view. */
    var onNavigate: (String) -> Unit = {}

    /**
     * Transition escape hatch (see azula-docs/openspec/specs/invitations/design.md): route
     * invite-less strangers into the pending inbox flagged "unverified" instead
     * of closing the connection outright. Default on for one release.
     */
    var allowLegacyInbound: Boolean = true

    // ---- connect UI state (read by the connect screen) ----
    var nodeId by mutableStateOf<String?>(null)
        private set
    var myCode by mutableStateOf("")
        private set
    var connectStage by mutableStateOf(ConnectStage.IDLE)
        private set
    var connectStep by mutableStateOf(0)
        private set
    var connectInput by mutableStateOf("")
    val shortCode: String get() = nodeId?.let { convStore.mnemonicCode(it) } ?: "connecting…"

    /** True when the transport failed to bind — we stay offline (no auto-reconnect). */
    private var offline = false

    /** The live inbound-accept loop, so a re-bind can replace it (see [startIncomingLoop]). */
    private var incomingJob: Job? = null

    /** True once the first [bind] attempt has been made; gates re-bind-only handling
     *  in `onCameOnline` (the first bind's loop is started by [start] itself). */
    private var bound = false

    fun resetConnect() {
        if (connectStage != ConnectStage.DIALING) {
            connectStage = ConnectStage.IDLE
            connectStep = 0
        }
    }

    fun peerIdForTicket(ticket: String): String? = transport.peerIdForTicket(ticket)

    /** The peer node id for a saved [PeerStore] entry -- a bare ticket, or a full
     *  encoded invite string -- resolving the latter first (see [resolveTicket]). */
    fun peerIdForEntry(entry: String): String? = resolveTicket(entry)?.let { transport.peerIdForTicket(it) }

    /** The peer node id referenced by a pasted connect input (URL or bare ticket). */
    fun peerIdForInput(input: String): String? {
        val ticket = AzulaLinks.parse(input) ?: input.trim()
        if (ticket.isEmpty()) return null
        return transport.peerIdForTicket(ticket)
    }

    /** The peer's mnemonic code for a pasted connect input, if it parses. */
    fun peerCodeForInput(input: String): String? =
        peerIdForInput(input)?.let { convStore.mnemonicCode(it) }

    // ---- identity backup / restore ----

    /** This identity's root secret as a 24-word BIP-39 recovery phrase, or null if this
     *  device doesn't hold a root secret (not yet known -- e.g. offline before the first
     *  bind -- or, permanently, a QR-linked device; see the `identity` capability's
     *  Per-Platform Key-at-Rest Storage requirement, multi-device-identity). */
    fun exportRecoveryPhrase(): String? =
        transport.rootSecretBytes()?.takeIf { it.size == 32 }?.let { RecoveryPhrase.encode(it) }

    /**
     * Import a recovery phrase, enrolling this device into the identity it encodes
     * (multi-device-identity task 3.4's "restore becomes enrollment" -- see the identity
     * capability's `Restore Recovers the Identity Onto This Device`). Returns false if
     * [phrase] is malformed or its checksum doesn't verify -- nothing changes in that case.
     *
     * On a valid phrase:
     * 1. The decoded root secret is persisted ([IrohTransport.importRootSecret]) -- unlike
     *    the pre-multi-device-identity restore flow, this never touches this device's own
     *    node key or rebinds the transport just to adopt the secret: the device keeps (or,
     *    on a fresh install, already minted at its first [bind]) its own node keypair
     *    regardless of which identity it now holds root authority for.
     * 2. This device self-issues its own certificate under the newly-held root and appends
     *    a `device_add` log entry for it ([AccountSyncService.ensureSelfCertified]) --
     *    best-effort, same posture as every other local persistence call here.
     * 3. If this device previously held a genuinely *different* identity (its root differs
     *    from the new one -- see [isDifferentIdentityRestore], which the UI calls *before*
     *    this to gate a second confirmation dialog on exactly this case), that identity's
     *    saved peer tickets no longer mean anything under the new one, so they're cleared
     *    (task 3.5c), and the transport is asked to [IrohTransport.rebind] ("begin syncing"
     *    under the freshly-adopted identity, per the spec) -- a rebind failure here
     *    degrades to offline exactly like a failed initial [bind], and does NOT fail this
     *    method (spec: "A failed transport re-bind SHALL NOT fail the restore"). A
     *    same-identity restore (re-entering your own phrase) skips both: nothing about the
     *    held identity actually changed, so nothing observable should either (task 3.5c) --
     *    in particular, the transport is never disrupted for a no-op restore.
     *
     * Task 3.5b (the displaced identity's root secret -- archive or discard): **discarded**,
     * by [IrohTransport.importRootSecret] simply overwriting the single root-secret entry --
     * no separate archive slot exists. Reasoning: the displaced identity's *logs* already
     * survive untouched on disk (task 4.6's per-root-pk namespacing), so no message history
     * is lost -- only its root secret goes away, and only after the user explicitly
     * confirmed a warning that says exactly that ("this can't be undone without that
     * identity's own recovery phrase"). Archiving it instead would mean this device
     * permanently keeps standing root authority for an identity the user just deliberately
     * moved away from, doubling the impersonation blast radius of a stolen/compromised
     * device for no corresponding benefit -- the phrase itself (not this device) is the
     * spec'd durable backup ("Recovery Phrase Security Properties"). If the user still
     * needs the old identity, its own 24-word phrase (which they were holding, to have
     * enrolled this device onto it in the first place) recovers it exactly as it always
     * would on any other device.
     *
     * Every step is best-effort / wrapped so a local failure (a locked Keychain, a
     * transport that can't rebind, ...) never propagates: callers run this inside a
     * `rememberCoroutineScope().launch`, which has no CoroutineExceptionHandler, so
     * anything thrown here would kill the process. The phrase itself decoded fine, so this
     * always reports success once it has -- reporting false here would incorrectly claim
     * the *phrase* was invalid.
     */
    suspend fun importRecoveryPhrase(phrase: String): Boolean {
        val newRoot = RecoveryPhrase.decode(phrase) ?: return false
        val previousRoot = runCatching { transport.rootSecretBytes() }.getOrNull()
        val differentIdentity = previousRoot != null && !sameRootIdentity(previousRoot, newRoot)

        try {
            transport.importRootSecret(newRoot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Best-effort: nothing else to do without a persisted-error-reporting seam.
        }

        runCatching {
            val devicePk = transport.nodeId?.hexToByteArrayOrNull()
            val deviceSecret = transport.secretKeyBytes()
            val rootPk = transport.publicKeyFor(newRoot)
            if (devicePk != null && deviceSecret != null && rootPk != null) {
                accountSyncService.ensureSelfCertified(rootPk, newRoot, devicePk, deviceSecret)
            }
        }

        if (differentIdentity) {
            // 3.5c: the displaced identity's saved peer tickets are meaningless under the
            // newly-adopted one -- rescope by clearing them rather than leaving them to be
            // redialed as though they still applied. (A same-identity restore's tickets
            // stay valid and untouched -- see this function's kdoc.)
            runCatching { peerStore?.save(emptyList()) }
            // "begin syncing" under the new identity. Must not fail the restore -- see kdoc.
            try {
                transport.rebind()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                offline = true
            }
        }
        return true
    }

    /**
     * Whether committing [phrase] via [importRecoveryPhrase] would overwrite a *different*
     * already-held identity's root secret -- the genuinely destructive case task 3.5a's
     * second confirmation guards (identity capability: "If the device previously held a
     * different identity, that identity's root secret SHALL be overwritten only after the
     * new phrase validates"). Meant to be read by the restore UI *before* committing, to
     * decide whether to interject a second confirmation step.
     *
     * Null if [phrase] doesn't even decode -- an invalid phrase never reaches the
     * destructive question; the caller should show the ordinary inline error instead (by
     * calling [importRecoveryPhrase], which re-validates and returns false). False when
     * this device holds no root secret yet (a fresh install or a QR-linked device --
     * nothing held to discard) or when [phrase] decodes to the identity already held
     * (re-entering your own phrase) -- both are single-tap, non-destructive.
     */
    fun isDifferentIdentityRestore(phrase: String): Boolean? {
        val newRoot = RecoveryPhrase.decode(phrase) ?: return null
        val currentRoot = transport.rootSecretBytes() ?: return false
        return !sameRootIdentity(currentRoot, newRoot)
    }

    /**
     * Whether two already-decoded 32-byte root secrets represent the same identity.
     * Direct byte equality is exactly equivalent to comparing their derived Ed25519 public
     * keys (a deterministic function of the secret alone -- "root public key" is the
     * spec's identity concept, but there's no need to actually derive it here, and doing
     * so would require every caller's [IrohTransport] to implement [IrohTransport.publicKeyFor]
     * just to answer this yes/no question).
     */
    private fun sameRootIdentity(a: ByteArray, b: ByteArray): Boolean = a.contentEquals(b)

    /** Whether a persisted identity key already exists, probed WITHOUT binding
     *  (bind eagerly mints+persists a key). The setup gate reads this once, before
     *  [start], to tell a fresh install from an upgrade -- see the onboarding
     *  capability's gate decision (D1). */
    fun hasPersistedIdentity(): Boolean = transport.hasPersistedIdentity()

    // ---- lifecycle ----

    suspend fun start() {
        // multi-device-identity tasks 4.4/4.5 (revised by task 3.4): this device's signing
        // identity for the event log, re-derived on every call (not snapshotted) so it
        // reflects whatever's currently bound. Entries are always signed by the DEVICE's
        // own node secret, never the root secret ("Key Roles and Signing Boundaries" --
        // the log-entry wire format's signature is "by the device key"), and verified
        // against `entry.authorDevicePk`, which is this same node public key. Using
        // secretKeyBytes() (not rootSecretBytes()) is what makes this correct once a
        // phrase-enrolled device's own node key differs from the identity's root key
        // (task 3.4, importRecoveryPhrase below): signing with the root secret there would
        // produce a signature that doesn't verify against this device's own devicePk at
        // all. Before task 3.4, every device was root-holding by construction (device_pk
        // == root_pk), which is why this used to read rootSecretBytes() interchangeably.
        accountSyncService.deviceIdentity = {
            val secret = transport.secretKeyBytes()
            val devicePk = transport.nodeId?.hexToByteArrayOrNull()
            if (secret != null && devicePk != null) devicePk to secret else null
        }
        // Wire an accepted stranger's held stream into a live conversation. No
        // sourceTicket is set -- an inbound-accepted peer left no ticket to
        // redial (matches every other inbound-accepted path; see scheduleReconnect).
        // If the user chose to share a persona back on accept, send it once the
        // stream is live (only meaningful on CHAT -- the only alpn a stranger's
        // held stream is ever on, see the incoming loop). multi-device-identity
        // task 7.2/7.3: a certified stranger's accepted conversation is keyed by its
        // pinned root pk (InviteService.accept already recorded the contact that way)
        // rather than by node id -- transportNodeId stays the real connection id either
        // way, for any future cert this same stream presents.
        inviteService.onAccept = { req, profile ->
            val peerId = req.certifiedRootPk?.toHexLower() ?: req.nodeId
            val convId = wireStream(req.alpn, req.stream, peerId, transportNodeId = req.nodeId)
            if (convId != null && profile != null && req.alpn == Alpns.CHAT) {
                convStore.convOrNull(convId)?.stream?.let { s ->
                    scope.launch { runCatching { s.sendLine(FrameCodec.encode(profile)) } }
                }
            }
        }
        transport.onCameOnline = {
            nodeId = transport.nodeId
            // Only for a RE-bind (recovery-phrase restore, Android foreground
            // resume). The first bind's loop is started by this function below --
            // and the test fakes' `bind` never invokes this callback at all, so
            // starting it here alone would leave them with no accept loop.
            if (bound) startIncomingLoop()
            scope.launch { reconnectSaved() }
            // multi-device-identity task 5.4: sibling sync auto-dial, alongside the
            // existing peer reconnect above -- also fires on the very first bind (see
            // reconnectSiblings' kdoc), same as reconnectSaved.
            scope.launch { reconnectSiblings() }
        }
        try {
            // MEDIA and LINK are bind-only (accept targets for on-demand fetch dials / an
            // inbound device-linking dial), never part of the outbound ALL dial race -- see
            // Alpns.MEDIA/Alpns.LINK's docs. Every device binds LINK, not just ones that
            // end up choosing "link to an existing identity" during onboarding: it's the
            // only way a device that HASN'T decided yet can still accept the inbound dial
            // once it does (see [beginDeviceLink]); an already-decided root-holding device
            // simply never has anything pending on [pendingLinkAccept] to complete. SYNC is
            // also bind-only (task 5.4): siblings dial each other directly, never as part of
            // the stranger-facing ALL race.
            transport.bind(Alpns.ALL + Alpns.MEDIA + Alpns.LINK + Alpns.SYNC)
        } catch (e: Throwable) {
            // Binding unavailable (e.g. missing native lib) — stay offline.
            offline = true
        }
        bound = true
        nodeId = transport.nodeId
        myCode = runCatching { transport.myTicket() }.getOrDefault("")
        startIncomingLoop()
        // Register the deeplink handler AFTER bind so a buffered/cold-start token
        // dials an already-bound transport rather than racing the bind.
        DeepLinkBus.setHandler { token -> connectPeer(token) }
        // multi-device-identity task 3.2's TODO, closed: the upgrade-in-place self-cert
        // (network-real's RootIdentityMigration.selfCertify/adoptRootSecretIfNeeded) is
        // computed but discarded there (network-real cannot depend on sync-real -- see
        // its module.yaml); the event-log store exists now, so recompute the identical
        // cert here and durably log its device_add. Idempotent (ensureSelfCertified checks
        // first): safe on every start(), including every relaunch of an already-certified
        // device.
        ensureSelfCertifiedIfHoldingRoot()
        // task 5.4: the first-bind sibling dial belongs HERE, not in onCameOnline. bind()
        // fires onCameOnline (which also calls reconnectSiblings) *before* the self-cert
        // above exists, so that first attempt hits `myCertString() == null` in runSyncWith
        // and closes every sibling stream unsent. onCameOnline's copy is still correct for a
        // *re-bind* (Android foreground resume), where the cert is already present; this call
        // covers the first bind, once the cert is in hand. dialSibling is bounded and a
        // sibling that somehow got dialed twice just runs two short sessions, so the overlap
        // on a genuine online-during-first-bind is harmless.
        reconnectSiblings()
    }

    /**
     * See [start]'s call site. This path only ever runs for a device that IS the root
     * (the upgrade-in-place/fresh-install migration -- design.md's Migration Plan step 2):
     * nothing here mints a *distinct* node key and then separately adopts a different root
     * secret -- that only happens via [importRecoveryPhrase] (task 3.4's enrollment path,
     * which calls [AccountSyncService.ensureSelfCertified] directly with an
     * independently-derived `rootPk`). So `rootPk == devicePk` is correct here, not a
     * placeholder. No-op if this device holds no root secret yet (a QR-linked device, or a
     * fresh install whose very first bind failed outright before migration could run).
     */
    private suspend fun ensureSelfCertifiedIfHoldingRoot() {
        val rootSecret = transport.rootSecretBytes() ?: return
        val devicePk = transport.nodeId?.hexToByteArrayOrNull() ?: return
        val deviceSecret = transport.secretKeyBytes() ?: return
        runCatching { accountSyncService.ensureSelfCertified(devicePk, rootSecret, devicePk, deviceSecret) }
    }

    /**
     * (Re)start the inbound-accept loop.
     *
     * [IrohTransport.incoming] binds to whichever endpoint was current when it was
     * collected, so every new endpoint needs a fresh collection. A restore (or an
     * Android foreground re-bind) tears the old endpoint down, at which point its
     * `acceptNext` reports a clean close, the flow completes, and — before this
     * existed — nothing ever collected again: the app accepted no inbound
     * connection until it was relaunched. Cancelling the previous job first keeps
     * a single consumer on the accept queue, which is what the transport requires.
     */
    private fun startIncomingLoop() {
        incomingJob?.cancel()
        incomingJob = scope.launch {
            runCatching {
                transport.incoming().collect { inc ->
                    if (inc.alpn == Alpns.MEDIA) {
                        scope.launch { runCatching { mediaService.serveIncoming(inc.stream) } }
                        return@collect
                    }
                    if (inc.alpn == Alpns.SYNC) {
                        // A sibling dialed us directly (multi-device-identity task 5.4) --
                        // symmetric with the outbound path in reconnectSiblings/dialSibling:
                        // either side may initiate, and either accepts.
                        val peerDevicePk = inc.remoteId.hexToByteArrayOrNull()
                        if (peerDevicePk != null) {
                            scope.launch { runSyncWith(inc.stream, peerDevicePk) }
                        } else {
                            scope.launch { runCatching { inc.stream.close() } }
                        }
                        return@collect
                    }
                    if (inc.alpn == Alpns.LINK) {
                        // The new-device side of a QR-link enrollment (task 6.1): only
                        // meaningful while [beginDeviceLink] is actively waiting for the
                        // one dial its own displayed payload invites -- anything else
                        // (a stray dial, or a second one after the first already landed)
                        // is dropped, mirroring the Rust reference's one-shot LinkHandler.
                        val awaiting = pendingLinkAccept
                        if (awaiting != null && !awaiting.isCompleted) {
                            pendingLinkAccept = null
                            awaiting.complete(inc)
                        } else {
                            scope.launch { runCatching { inc.stream.close() } }
                        }
                        return@collect
                    }
                    if (convStore.conversationOrNull(inc.remoteId)?.enabled == false) {
                        scope.launch { runCatching { inc.stream.close() } }
                        return@collect
                    }
                    if (!isKnownPeer(inc.remoteId)) {
                        // dialAll races CHAT+LLM+TERM, so a stranger's dial opens all
                        // three. Only CHAT is invite-gateable (see
                        // azula-docs/openspec/specs/invitations/design.md) -- hold it pending a valid
                        // Hello.invite. LLM/TERM have no accept UX and no legitimate
                        // reason to be opened by a stranger, so just close them; in
                        // particular don't enqueue them as pending too, or whichever
                        // of the three arrives first (not necessarily CHAT) would
                        // become the wrong held "primary".
                        if (inc.alpn == Alpns.CHAT) {
                            scope.launch { handleCertOrStrangerConnection(inc) }
                        } else {
                            scope.launch { runCatching { inc.stream.close() } }
                        }
                        return@collect
                    }
                    wireStream(inc.alpn, inc.stream, inc.remoteId)
                }
            }
        }
    }

    // ---- outbound connect ----

    fun connectPeer(ticketRaw: String, share: Frame.Profile? = null) {
        val raw = AzulaLinks.parse(ticketRaw) ?: ticketRaw.trim()
        if (raw.isEmpty() || connectStage == ConnectStage.DIALING) return
        // An invite carries the ticket rather than being one -- decode it and
        // dial the embedded ticket, while sending the whole encoded invite in
        // every Hello (see ping/dialAll) so the issuer can verify us.
        val invite = raw.takeIf { isInvite(it) }
        val invitePayload = invite?.let { InviteCodec.decode(it) }
        if (invite != null && invitePayload == null) { connectStage = ConnectStage.FAILED; return }
        val ticket = if (invitePayload != null) transport.ticketFromBytes(invitePayload.ticket) else raw
        if (ticket.isNullOrEmpty()) { connectStage = ConnectStage.FAILED; return }
        connectStage = ConnectStage.DIALING
        connectStep = 0
        scope.launch {
            val anim = launch { while (connectStep < 2) { delay(650); connectStep++ } }
            val primary = dialAll(ticket, invite)
            anim.cancel()
            if (primary == null) {
                connectStage = ConnectStage.FAILED
                return@launch
            }
            connectStep = 3
            val peerId = transport.peerIdForTicket(ticket) ?: ("t" + ticket.hashCode().toString(16))
            val convId = wireStream(primary.first, primary.second, peerId, dialedTicket = ticket)
            if (share != null && primary.first == Alpns.CHAT) {
                runCatching { primary.second.sendLine(FrameCodec.encode(share)) }
            }
            // Save the raw pasted/scanned value -- an invite string, not just its
            // embedded ticket -- so reconnects keep re-presenting it until the
            // issuer has accepted (see resolveTicket, reconnectSaved).
            persistPeer(raw)
            convId?.let {
                convStore.convOrNull(it)?.sourceTicket = ticket
                convStore.convOrNull(it)?.sourceInvite = invite
            }
            connectStage = ConnectStage.CONNECTED
            delay(1200)
            if (convId != null) onNavigate(convId)
            connectStage = ConnectStage.IDLE
            connectInput = ""
            connectStep = 0
        }
    }

    /**
     * Open a NEW session over the same peer connection as [convId] — a fresh
     * terminal/LLM/peer conversation reusing the known ticket (no re-pairing).
     */
    fun newSessionFrom(convId: String) {
        val ticket = convStore.conv(convId).sourceTicket ?: return
        val kind = convStore.conversation(convId).kind
        val alpn = when (kind) {
            ConversationKind.TERMINAL -> Alpns.TERM
            ConversationKind.LLM -> Alpns.LLM
            ConversationKind.PEER -> Alpns.CHAT
        }
        scope.launch {
            val stream = runCatching { transport.connect(ticket, alpn) }.getOrNull() ?: return@launch
            val base = convStore.conversationOrNull(convId)?.name
                ?: convStore.mnemonicCode(transport.peerIdForTicket(ticket) ?: ticket)
            val n = convStore.conversations.count { convStore.convOrNull(it.id)?.sourceTicket == ticket && it.kind == kind } + 1
            val id = convStore.nextId("sess")
            convStore.convState[id] = ConversationState(emptyList()).also { it.sourceTicket = ticket }
            convStore.conversations.add(Conversation(id, "$base · session $n", kind, convStore.glyphFor(base, kind), sub = "live session", preview = ""))
            wireConv(id, stream, isTerminal = kind == ConversationKind.TERMINAL)
            onNavigate(id)
        }
    }

    /** Re-open a terminal whose session ended: dial the saved ticket + rewire. */
    fun reconnectTerminal(convId: String) {
        val ticket = convStore.convOrNull(convId)?.sourceTicket ?: return
        scope.launch {
            val stream = runCatching { transport.connect(ticket, Alpns.TERM) }.getOrNull() ?: return@launch
            wireConv(convId, stream, isTerminal = true)
        }
    }

    private suspend fun ping(stream: P2pStream, invite: String? = null) {
        stream.sendLine(FrameCodec.encode(Frame.Hello(transport.nodeId ?: "", invite)))
    }

    /**
     * Resolves a saved [peerStore] entry -- a bare ticket, or a full encoded
     * invite string (see [connectPeer]'s [persistPeer] call) -- to a ticket the
     * transport can dial. Null if it's an invite that no longer decodes.
     */
    private fun resolveTicket(saved: String): String? =
        if (!isInvite(saved)) saved else InviteCodec.decode(saved)?.ticket?.let { transport.ticketFromBytes(it) }

    /**
     * Race all ALPNs against [ticket] and return the highest-priority winner
     * (losers are closed), or null if nothing connected. [invite] -- the full
     * encoded invite string, if [ticket] was dialed from one -- rides along on
     * every Hello so a stranger-gated issuer can verify us.
     */
    private suspend fun dialAll(ticket: String, invite: String? = null): Pair<String, P2pStream>? {
        val results = runCatching {
            coroutineScope {
                Alpns.ALL.map { alpn ->
                    async {
                        runCatching { transport.connect(ticket, alpn) }.getOrNull()
                            ?.also { runCatching { ping(it, invite) } }?.let { alpn to it }
                    }
                }.awaitAll().filterNotNull()
            }
        }.getOrDefault(emptyList())
        if (results.isEmpty()) return null
        val primary = results.maxBy { convStore.kindPriority(kindForAlpn(it.first)) }
        results.filter { it !== primary }.forEach { (_, s) -> scope.launch { runCatching { s.close() } } }
        return primary
    }

    /** [entry] is the raw pasted/scanned value -- a bare ticket, or a full
     *  encoded invite string -- exactly as [resolveTicket] expects it back. */
    private fun persistPeer(entry: String) {
        peerStore?.let { store -> store.save((store.load() + entry).distinct().takeLast(10)) }
    }

    fun reconnectSaved() {
        if (offline) return
        val saved = peerStore?.load().orEmpty()
        if (saved.isEmpty()) return
        saved.forEach { entry ->
            val invite = entry.takeIf { isInvite(it) }
            val ticket = resolveTicket(entry) ?: return@forEach
            val peerId = transport.peerIdForTicket(ticket) ?: return@forEach
            if (convStore.conversationOrNull(peerId)?.enabled == false) return@forEach
            scope.launch {
                repeat(3) {
                    val primary = dialAll(ticket, invite)
                    if (primary != null) {
                        val id = wireStream(primary.first, primary.second, peerId, replace = false, dialedTicket = ticket)
                        id?.let {
                            convStore.convOrNull(it)?.sourceTicket = ticket
                            convStore.convOrNull(it)?.sourceInvite = invite
                        }
                        return@launch
                    }
                    delay(700)
                }
            }
        }
    }

    /**
     * Redial [convId]'s saved ticket with backoff until the conversation is back
     * online. Started when a live stream drops and when the user opens an offline
     * conversation. Self-terminates once the conversation comes back online
     * through any path (wireConv's `replace = false` guard dedupes races), is
     * disabled, or the attempts run out. Only conversations we dialed are
     * eligible — an inbound-accepted peer left no ticket to redial.
     *
     * A terminal is eligible only when it holds a re-attachable persistent
     * session ([ConversationState.termSessionId] set, [ConversationState.termExited]
     * false) — wireConv's TermAttach will resume it with its scrollback, so
     * auto-reconnect is exactly as safe as for any other conversation kind. A
     * terminal whose shell already exited, or that never attached one (a
     * pre-persistence "legacy" server, or one that hasn't wired yet), stays
     * manual-only: its ending is normal, and re-opening one is an explicit
     * action ([reconnectTerminal]).
     */
    fun scheduleReconnect(convId: String) {
        if (offline) return
        val cs = convStore.convOrNull(convId) ?: return
        if (convStore.conversationOrNull(convId)?.kind == ConversationKind.TERMINAL &&
            (cs.termSessionId == null || cs.termExited)
        ) return
        val ticket = cs.sourceTicket ?: return
        val invite = cs.sourceInvite
        if (cs.online || cs.reconnecting) return
        cs.reconnecting = true
        scope.launch {
            try {
                var backoffMs = 1000L
                repeat(8) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                    val now = convStore.convOrNull(convId) ?: return@launch
                    if (now.online) return@launch
                    if (convStore.conversationOrNull(convId)?.enabled == false) return@launch
                    val primary = withTimeoutOrNull(10_000) { dialAll(ticket, invite) }
                    if (primary != null) {
                        val id = wireStream(primary.first, primary.second, convId, replace = false, dialedTicket = ticket)
                        id?.let { convStore.convOrNull(it)?.sourceTicket = ticket }
                        return@launch
                    }
                }
            } finally {
                convStore.convOrNull(convId)?.reconnecting = false
            }
        }
    }

    /**
     * multi-device-identity task 7.4 ("Delivery to Any Device Counts for the Identity"):
     * called by [ChatService] (via [dev.azula.state.AzulaState]'s
     * `chatService.dialKnownDeviceAndSend` wiring) when [convId]'s conversation has no
     * live stream to send [frame] over. Tries this contact's other known devices
     * ([ConversationState.knownDevices], populated by [applyPresentedCertToConversation])
     * in priority order -- interactive (non-mailbox) devices most-recently-seen first,
     * then any mailbox-role device as a last-resort store-and-forward fallback -- and
     * ships [frame] (the SAME `Frame.Chat`, so the same wire `id`) over the first one
     * that accepts the connection, wiring it in as the conversation's live stream so
     * later sends and any redial go over it directly ("first successful delivery...
     * counts as delivered to the identity"). If none accept, this returns having done
     * nothing further -- `message_out` is already logged unconditionally by
     * `ChatService.send` before this ever runs, and the conversation's own ordinary
     * reconnect behavior ([scheduleReconnect], or simply the next device to dial back
     * in) is what eventually delivers it, per spec: "undelivered sends SHALL retry with
     * the existing reconnect behavior." This function itself makes one bounded pass,
     * not an indefinite retry loop of its own.
     */
    fun dialKnownDeviceForSend(convId: String, frame: Frame.Chat) {
        scope.launch {
            val cs = convStore.convOrNull(convId) ?: return@launch
            val candidates = cs.knownDevices.entries
                .filter { it.value.ticket != null }
                .sortedWith(compareBy({ it.value.isMailbox }, { -it.value.lastSeenMs }))
            for ((deviceHex, info) in candidates) {
                if (convStore.convOrNull(convId)?.stream != null) return@launch // a reconnect already won the race
                val ticket = info.ticket ?: continue
                val stream = runCatching { transport.connect(ticket, Alpns.CHAT) }.getOrNull() ?: continue
                runCatching { ping(stream) }
                runCatching { stream.sendLine(FrameCodec.encode(frame)) }
                wireConv(convId, stream, isTerminal = false, transportNodeId = deviceHex, dialedTicket = ticket)
                return@launch
            }
        }
    }

    // =========================================================================================
    // Sibling sync (`azula/sync/0`, multi-device-identity task 5.4) -- devices persist sibling
    // tickets from the identity bundle and from granting a device_add cert, auto-dial siblings
    // alongside the ordinary peer reconnect above, and re-sync on reconnect. All protocol logic
    // is in `sync-real`'s runSyncSession (pure, tested without sockets); everything here is thin
    // iroh/state wiring, exactly like the device-linking section below.
    //
    // Where sibling tickets come from: neither the identity bundle nor the sync wire protocol
    // (SyncHello/SyncVector/SyncEntries/SyncAck carry no address at all) gives a device a
    // general list of every sibling's dialable ticket -- only two concrete sources exist, and
    // both are wired at their point of use rather than here: (a) the mailbox hint inside a
    // just-granted identity bundle (registerMailboxSiblingTicket, called from beginDeviceLink's
    // Granted branch), and (b) the very ticket this device just dialed while granting a link to
    // a new device (confirmGrantLink, task 6.2). A sibling this device has no ticket for is
    // simply never auto-dialed by it -- it's still reachable if *it* dials or is dialed by
    // someone who does have a ticket for it, and it always accepts an inbound SYNC dial
    // regardless (see startIncomingLoop above). This is a real (spec-acknowledged) reachability
    // gap, not a bug: "Device Registry Persistence" only requires persisting certs/revocations/
    // the mailbox hint, not a general sibling address book.
    // =========================================================================================

    /**
     * This identity's root public key, from whichever side of enrollment this device took: a
     * QR-linked device's own granted certificate embeds it (task 6.1); a root-holding device's
     * root pk is its own node key by construction (device_pk == root_pk -- the migration/
     * self-certify invariant `ensureSelfCertifiedIfHoldingRoot` establishes). Null before either
     * is known (offline before the first bind/self-certify).
     */
    private fun currentRootPk(): ByteArray? {
        settingsService.linkedDeviceCert?.let { return DeviceCertCodec.decode(it)?.rootPk }
        return transport.nodeId?.takeIf { transport.rootSecretBytes() != null }?.hexToByteArrayOrNull()
    }

    /**
     * This device's own certificate, encoded, for presenting in a `SyncHello` -- from
     * [SettingsService.linkedDeviceCert] if this device was QR-linked (task 6.1), otherwise
     * recovered from this device's own log via [AccountSyncService.ownCertString] (a
     * root-holding device's self-cert is always logged as its own `device_add` entry -- see
     * [ensureSelfCertifiedIfHoldingRoot]). Null if neither is available yet.
     */
    private suspend fun myCertString(): String? {
        settingsService.linkedDeviceCert?.let { return it }
        val devicePk = transport.nodeId?.hexToByteArrayOrNull() ?: return null
        return accountSyncService.ownCertString(devicePk)
    }

    /** The raw `"azr…"` revocation strings from this device's persisted identity bundle, if
     *  any (empty for a root-holding device, which never holds one) -- the "bundle baseline"
     *  [AccountSyncService.currentRevokedDevicePks] merges with the live log scan. */
    private fun bundleRevocationStrings(): List<String> =
        settingsService.linkedIdentityBundleJson?.let { FrameCodec.decodeBundle(it) }?.revocations.orEmpty()

    /**
     * Runs one `azula/sync/0` session against [stream] (already dialed or accepted, on either
     * side) and closes it once the session ends -- which it deterministically does:
     * [AccountSyncService.runSiblingSync] runs the session as a bounded catch-up (see its
     * kdoc), so this returns, and the stream closes, once both sides' acks have crossed.
     * Delegates everything -- including the per-session revocation recompute that is this
     * task's whole point, see [AccountSyncService.currentRevokedDevicePks]'s kdoc -- to
     * [AccountSyncService.runSiblingSync]. If this device doesn't yet have its own certificate
     * to present ([myCertString] null -- offline before the first self-certify), the stream is
     * just closed: there is nothing valid to say in a `SyncHello` yet.
     */
    private suspend fun runSyncWith(stream: P2pStream, transportPeerDevicePk: ByteArray) {
        val certString = myCertString()
        if (certString == null) {
            runCatching { stream.close() }
            return
        }
        accountSyncService.runSiblingSync(stream, certString, bundleRevocationStrings(), transportPeerDevicePk)
        runCatching { stream.close() }
    }

    /**
     * Auto-dial this identity's other devices for sync, alongside [reconnectSaved]'s ordinary
     * peer redial (multi-device-identity task 5.4) -- called from the same [IrohTransport.onCameOnline]
     * trigger, so both the very first bind and every re-bind re-sync with siblings, not just
     * reconnect chat peers.
     *
     * Deliberately does NOT pre-filter [SettingsService.siblingTickets] against the current
     * fold's device set before dialing -- an earlier version of this method did, and it broke
     * exactly the case task 5.4 (and the account-sync spec's "New Device Bootstrap Replays the
     * Logs") most needs to handle: a freshly QR-linked device's own event log is completely
     * empty at its very first bind, so [AccountSyncService.currentDeviceSet] over it returns
     * nothing yet, and gating on "is this device already known to my own log" would make the
     * mailbox hint -- the ONE ticket such a device has -- permanently undialable. Safety
     * instead comes entirely from [AccountSyncService.runSiblingSync]/`runSyncSession`'s own
     * per-session hello verification (chains to root, not revoked as of *this* session, and the
     * transport peer id matches): a stale, foreign-root, or revoked ticket is simply refused at
     * the hello, wasting one dial attempt and nothing more. [revokeDevice]'s best-effort
     * [SettingsService.removeSiblingTicket] is what keeps that from accumulating indefinitely;
     * this function doesn't need to duplicate that check to stay correct.
     */
    fun reconnectSiblings() {
        if (offline) return
        val tickets = settingsService.siblingTickets
        if (tickets.isEmpty()) return
        val myDeviceHex = transport.nodeId
        for ((deviceHex, ticket) in tickets) {
            if (deviceHex == myDeviceHex) continue
            scope.launch { dialSibling(ticket) }
        }
    }

    /** Dials one sibling ticket on [Alpns.SYNC] and runs the session once connected; bounded
     *  retries mirror [reconnectSaved]'s own posture (no infinite background loop -- the next
     *  [reconnectSiblings] call, triggered by the next [IrohTransport.onCameOnline], tries
     *  again if this one never connects). */
    private suspend fun dialSibling(ticket: String) {
        repeat(3) {
            val stream = runCatching { transport.connect(ticket, Alpns.SYNC) }.getOrNull()
            if (stream != null) {
                val peerDevicePk = transport.peerIdForTicket(ticket)?.hexToByteArrayOrNull()
                if (peerDevicePk != null) runSyncWith(stream, peerDevicePk) else runCatching { stream.close() }
                return
            }
            delay(700)
        }
    }

    // ---- stranger gate (inbound peer-ALPN connections) ----

    /**
     * A connecting peer is known if its node id matches an existing
     * conversation (restored from persistence on a known peer, or created
     * earlier this session), a saved [peerStore] entry (a peer we've dialed
     * out to before), or is in [InviteService]'s accepted-contacts list. Known
     * peers connect exactly as before -- no gate (see
     * azula-docs/openspec/specs/invitations/design.md's verification model).
     *
     * This is deliberately node-id-only -- multi-device-identity task 7.2's
     * additional "known by a presented certificate's root" path lives in
     * [handleCertOrStrangerConnection] instead (reached only when this returns
     * false), since checking it here would require reading the connection's first
     * frame before this synchronous gate can even run.
     */
    private fun isKnownPeer(nodeId: String): Boolean =
        convStore.conversationOrNull(nodeId) != null ||
            inviteService.isKnown(nodeId) ||
            peerStore?.load().orEmpty().any { peerIdForEntry(it) == nodeId }

    /**
     * A CHAT connection from a peer not already known by node id ([isKnownPeer]
     * false). Reads the first frame (15 s timeout) and checks any presented
     * `Hello.cert` against this identity's known contacts/revocations
     * ([AccountSyncService.checkPresentedCert], multi-device-identity task 7.2)
     * *before* falling to the ordinary invite-gated path:
     *
     * - [CertCheck.KnownByRoot] (cert verifies, binds to this connection, isn't
     *   revoked, and its root is already a contact): admitted immediately, no
     *   invite needed, wired straight into that root's conversation -- "A contact's
     *   new device is known by root."
     * - [CertCheck.Absent] (no cert, or one that fails to verify/bind, or is
     *   revoked) or [CertCheck.CertifiedStranger] (verifies fine but its root isn't
     *   a contact yet): falls through to exactly the pre-existing
     *   invite/[allowLegacyInbound] pending-request flow -- a `CertifiedStranger`'s
     *   root rides along into the pending request so accepting it pins the root
     *   (spec: "Accepting a certified stranger pins the root"). A cert that fails
     *   verification is thus NEVER a hard rejection -- it's simply absent, so a
     *   legacy (no-cert) peer and a peer with a broken certificate are gated
     *   identically, and neither is ever worse off than today.
     */
    private suspend fun handleCertOrStrangerConnection(inc: IncomingConnection) {
        val line = withTimeoutOrNull(15_000) { inc.stream.receiveLine() }
        val hello = line?.let { runCatching { FrameCodec.decode(it) }.getOrNull() } as? Frame.Hello
        val connectionDevicePk = inc.remoteId.hexToByteArrayOrNull()
        val certCheck = if (connectionDevicePk != null) {
            accountSyncService.checkPresentedCert(hello?.cert, connectionDevicePk, inviteService.knownContactIds(), bundleRevocationStrings())
        } else {
            CertCheck.Absent
        }
        if (certCheck is CertCheck.KnownByRoot) {
            val convId = wireStream(inc.alpn, inc.stream, certCheck.cert.rootPk.toHexLower(), transportNodeId = inc.remoteId)
            // Bookkeeping only (task 7.4): an inbound connection leaves no ticket to
            // redial (same rule as ConversationState.sourceTicket everywhere else), so
            // this never adds a new dial candidate -- it just keeps lastSeenMs/isMailbox
            // current for this device, preserving any ticket already known for it from a
            // prior OUTBOUND dial (see applyPresentedCertToConversation's identical
            // "existingTicket" preservation).
            if (convId != null) {
                val devicePkHex = certCheck.cert.devicePk.toHexLower()
                val existingTicket = convStore.convOrNull(convId)?.knownDevices?.get(devicePkHex)?.ticket
                convStore.convOrNull(convId)?.knownDevices?.set(
                    devicePkHex,
                    KnownContactDevice(ticket = existingTicket, lastSeenMs = epochSeconds() * 1000, isMailbox = certCheck.cert.isMailbox),
                )
            }
            return
        }
        val certifiedRootPk = certCheck.certOrNull?.rootPk
        val issuedInvite = inviteService.verifyInbound(hello?.invite)
        val peerCode = convStore.mnemonicCode(inc.remoteId)
        when {
            issuedInvite != null ->
                inviteService.enqueuePending(inc.remoteId, issuedInvite.id, peerCode, inc.stream, inc.alpn, unverified = false, certifiedRootPk)
            allowLegacyInbound ->
                inviteService.enqueuePending(inc.remoteId, null, peerCode, inc.stream, inc.alpn, unverified = true, certifiedRootPk)
            else -> runCatching { inc.stream.close() }
        }
    }

    // ---- stream wiring + incoming frames ----

    fun kindForAlpn(alpn: String): ConversationKind = when (alpn) {
        Alpns.CHAT -> ConversationKind.PEER
        Alpns.LLM -> ConversationKind.LLM
        Alpns.TERM -> ConversationKind.TERMINAL
        else -> ConversationKind.PEER
    }

    /**
     * [transportNodeId] is the connection's actual transport-level peer id -- normally
     * identical to [peerId] (the key the conversation is created/looked-up under), but
     * distinct for multi-device-identity task 7.2's known-by-root fast path, where a
     * never-before-seen device is wired straight into an existing root-pk-keyed
     * conversation ([peerId] = the contact's root pk hex) while [transportNodeId] stays
     * that specific device's own node id -- exactly what a presented certificate's
     * "binds to this connection" check (task 7.2/7.3) needs to verify against.
     * [dialedTicket] is the ticket used to reach this stream, when this device dialed
     * out (null for an inbound-accepted connection) -- task 7.4's dial address book
     * needs it to know which device a given ticket redials.
     */
    private fun wireStream(
        alpn: String,
        stream: P2pStream,
        peerId: String,
        announcedName: String? = null,
        replace: Boolean = true,
        transportNodeId: String = peerId,
        dialedTicket: String? = null,
    ): String? {
        if (peerId.isBlank()) { scope.launch { runCatching { stream.close() } }; return null }
        val convId = convStore.ensureConv(peerId, kindForAlpn(alpn), announcedName)
        wireConv(convId, stream, isTerminal = alpn == Alpns.TERM, replace = replace, transportNodeId = transportNodeId, dialedTicket = dialedTicket)
        return convId
    }

    private fun wireConv(
        convId: String,
        stream: P2pStream,
        isTerminal: Boolean,
        replace: Boolean = true,
        transportNodeId: String = convId,
        dialedTicket: String? = null,
    ) {
        val cs = convStore.conv(convId)
        if (!replace && cs.online && cs.stream != null) {
            scope.launch { runCatching { stream.close() } }
            return
        }
        // A2UI surfaces are session-scoped (never restored from persistence): a new
        // stream means a new session, so drop this conversation's old surfaces. A
        // same-run peer re-creates its surface (same id — the card revives in
        // place); a restarted peer's stale card stays frozen for good.
        frameDispatcher.dropSurfaces(convId)
        cs.stream = stream
        if (isTerminal) {
            // Device-status/attribute replies (DA/DSR) the emulator generates go back
            // to the server on the current stream — server-directed, never predicted.
            cs.termScreen.onResponse = { resp ->
                convStore.convOrNull(convId)?.stream?.let { st ->
                    scope.launch { runCatching { st.sendLine(FrameCodec.encode(Frame.Input(resp))) } }
                }
            }
            scope.launch(terminalDispatcher) { cs.termScreen.clear() }
            // Opt into a persistent session (or re-attach the one we last held) as
            // the very first frame on the stream, before any keystroke can flow --
            // the server only honors TermAttach as the first frame (a 500ms window).
            // Old servers don't know this frame and ignore it (Frame::Unknown on the
            // Rust side), so this is always safe: no TermSession ever arrives, and
            // everything behaves exactly as the legacy ephemeral-shell path did.
            scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.TermAttach(cs.termSessionId))) } }
        }
        cs.online = true
        receiveLoop(convId, stream, transportNodeId, dialedTicket)
        pollRtt(cs, stream)
    }

    /**
     * Poll the live QUIC RTT off [stream] into [cs] while it's the conversation's
     * active stream. The `cs.stream === stream` identity guard makes the loop
     * self-terminate on rewire/close (receiveLoop's `finally` nulls the stream).
     */
    private fun pollRtt(cs: ConversationState, stream: P2pStream) {
        scope.launch {
            while (cs.stream === stream) {
                cs.rttMs = stream.rttMs()?.toInt()
                delay(2000)
            }
        }
    }

    /**
     * [transportNodeId] is this stream's actual transport-level peer id (see
     * [wireConv]'s kdoc) -- fixed for the stream's whole lifetime, used only for a
     * presented certificate's "binds to this connection" check. [convId] is a `var`
     * locally ([activeConvId]) because multi-device-identity task 7.3's merge can
     * re-key the conversation mid-stream: once a peer's `Hello` presents its first
     * valid certificate, every frame from that point on (on this same connection)
     * routes to the root-pk-keyed conversation instead of the legacy node-id one.
     */
    private fun receiveLoop(convId: String, stream: P2pStream, transportNodeId: String = convId, dialedTicket: String? = null) {
        scope.launch {
            var activeConvId = convId
            try {
                while (true) {
                    val line = stream.receiveLine() ?: break
                    if (line.isBlank()) continue
                    val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                    if (frame is Frame.Hello) {
                        if (frame.name.isNotBlank() && frame.name != activeConvId) {
                            convStore.ensureConv(activeConvId, convStore.conversationOrNull(activeConvId)?.kind ?: kindForAlpn(Alpns.LLM), frame.name)
                        }
                        activeConvId = applyPresentedCertToConversation(activeConvId, frame.cert, transportNodeId, dialedTicket, stream)
                        continue
                    }
                    if (frame is Frame.FileBegin) {
                        runCatching {
                            val received = receiveFile(stream, frame) { stream.receiveLine() }
                            mediaService.ingestLocalBlob(activeConvId, received.bytes, received.name, received.mime, received.caption, Role.THEM)
                            frameDispatcher.notify(activeConvId, received.caption ?: "sent ${received.name}")
                        }
                        continue
                    }
                    if (frame is Frame.FileChunk || frame is Frame.FileEnd) continue
                    if (frame is Frame.Profile) { frameDispatcher.applyProfile(activeConvId, frame); continue }
                    frameDispatcher.applyFrame(activeConvId, frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A dropped peer (abrupt close, idle timeout) surfaces as a thrown
                // IrohException from recv(), not a clean EOF — degrade to the
                // offline state below instead of killing the process.
            } finally {
                // Identity guard: if a reconnect already wired a fresh stream, this
                // loop's late-firing finally must not clobber it. convOrNull (not the
                // throwing conv()) because a conversation merge (above) can have
                // removed the entry this loop started under.
                val cs = convStore.convOrNull(activeConvId)
                if (cs != null && cs.stream === stream) {
                    cs.online = false
                    cs.stream = null
                    cs.rttMs = null
                    scheduleReconnect(activeConvId)
                }
            }
        }
    }

    /**
     * multi-device-identity tasks 7.3/7.4: called for every `Hello` a wired stream
     * receives. Checks [certStr] against this identity's known contacts/revocations
     * ([AccountSyncService.checkPresentedCert]); a cert that fails to verify, doesn't
     * bind to [transportNodeId] (this connection's actual peer id), or names a revoked
     * device is [CertCheck.Absent] and this is a complete no-op -- [currentConvId] is
     * returned unchanged, exactly preserving pre-multi-device-identity behavior for a
     * legacy peer or a broken certificate alike.
     *
     * On a verified cert, records/refreshes its device in the (possibly just-renamed)
     * conversation's [ConversationState.knownDevices] dial address book (task 7.4), and
     * -- if this conversation isn't already keyed by this root -- pins the root as a
     * known contact and re-keys the conversation via [mergeIntoRootKeyedConversation]
     * (task 7.3), re-homing the live [stream]/online state onto whichever
     * [ConversationState] ends up representing the result. Returns the conversation id
     * subsequent frames on this stream must be routed under.
     */
    private suspend fun applyPresentedCertToConversation(
        currentConvId: String,
        certStr: String?,
        transportNodeId: String,
        dialedTicket: String?,
        stream: P2pStream,
    ): String {
        if (certStr == null) return currentConvId
        if (convStore.conversationOrNull(currentConvId)?.kind != ConversationKind.PEER) return currentConvId
        val connectionDevicePk = transportNodeId.hexToByteArrayOrNull() ?: return currentConvId
        val check = accountSyncService.checkPresentedCert(certStr, connectionDevicePk, inviteService.knownContactIds(), bundleRevocationStrings())
        val cert = check.certOrNull ?: return currentConvId
        val rootHex = cert.rootPk.toHexLower()
        var convId = currentConvId
        if (rootHex != convId) {
            inviteService.pinContactRoot(rootHex)
            val beforeState = convStore.convOrNull(convId)
            convId = mergeIntoRootKeyedConversation(convId, rootHex)
            convStore.convOrNull(convId)?.let { merged ->
                merged.stream = stream
                merged.online = true
                // Only the "merged into a pre-existing root-keyed conversation" branch
                // hands back a genuinely different ConversationState with no poll loop
                // of its own yet -- the "renamed in place" branch reuses the exact
                // object wireConv already started one against, so restarting here
                // would just be a second redundant loop against the same (cs, stream).
                if (merged !== beforeState) pollRtt(merged, stream)
            }
        }
        val devicePkHex = cert.devicePk.toHexLower()
        val existingTicket = convStore.convOrNull(convId)?.knownDevices?.get(devicePkHex)?.ticket
        convStore.convOrNull(convId)?.knownDevices?.set(
            devicePkHex,
            KnownContactDevice(ticket = dialedTicket ?: existingTicket, lastSeenMs = epochSeconds() * 1000, isMailbox = cert.isMailbox),
        )
        return convId
    }

    /**
     * multi-device-identity task 7.3: [oldConvId]'s conversation just learned (via
     * [applyPresentedCertToConversation]) that its peer's identity is actually rooted
     * at [rootPkHex] -- re-key it from node id to root pk so any other certified device
     * of the same identity lands in the same place (spec: "The contact's conversation
     * SHALL be keyed by root public key"). If a root-keyed conversation already exists
     * (a sibling device of this identity was accepted first), [oldConvId]'s history is
     * merged into it (prepended, oldest-first, ahead of whatever the root-keyed one
     * already holds) and the node-id entry is dropped entirely -- "don't strand history
     * under the old key." Otherwise the existing entry is renamed in place (same
     * [ConversationState] instance, so its live stream/messages/etc. all carry over
     * unchanged). No-op (returns [oldConvId]) if they're already the same key (a
     * root-holding single-device peer, where node id and root pk coincide) or no
     * conversation exists at [oldConvId] to move.
     */
    private fun mergeIntoRootKeyedConversation(oldConvId: String, rootPkHex: String): String {
        if (oldConvId == rootPkHex) return oldConvId
        val oldConv = convStore.conversationOrNull(oldConvId) ?: return rootPkHex
        val oldState = convStore.convOrNull(oldConvId)
        val existingRootConv = convStore.conversationOrNull(rootPkHex)
        if (existingRootConv != null) {
            if (oldState != null) {
                val rootState = convStore.conv(rootPkHex)
                rootState.messages.addAll(0, oldState.messages)
                rootState.knownDevices.putAll(oldState.knownDevices)
            }
            convStore.conversations.removeAll { it.id == oldConvId }
            convStore.convState.remove(oldConvId)
        } else {
            val idx = convStore.conversations.indexOfFirst { it.id == oldConvId }
            if (idx >= 0) convStore.conversations[idx] = oldConv.copy(id = rootPkHex)
            if (oldState != null) {
                convStore.convState.remove(oldConvId)
                convStore.convState[rootPkHex] = oldState
            }
        }
        persistence.delete(oldConvId)
        persistence.save(rootPkHex)
        return rootPkHex
    }

    // =========================================================================================
    // Device linking (`azula/link/0`, device-linking spec) -- multi-device-identity tasks 6.1
    // (new-device side: "Link to existing identity" in onboarding/settings) and 6.2
    // (root-holding side: scan/paste a peer's azl… payload and grant it). All protocol logic
    // is in [DeviceLinkProtocol] (pure, tested without sockets) -- everything below is thin
    // iroh/state wiring around it, exactly like every other ALPN this class handles.
    // =========================================================================================

    /** One inbound [Alpns.LINK] dial, handed from [startIncomingLoop] to whichever
     *  [beginDeviceLink] call is currently waiting for it. Null when nothing is waiting --
     *  an inbound LINK dial that arrives with this null (or already completed) is simply
     *  dropped, mirroring the Rust reference's one-shot `LinkHandler`. */
    private var pendingLinkAccept: CompletableDeferred<IncomingConnection>? = null
    private var linkSessionJob: Job? = null

    enum class DeviceLinkStage { IDLE, WAITING_FOR_SCAN, VERIFYING, GRANTED, REJECTED, FAILED }

    // ---- new-device side (task 6.1) ----

    /** The `azl…` payload to show as a QR + copyable string while [deviceLinkStage] is
     *  [DeviceLinkStage.WAITING_FOR_SCAN]. */
    var deviceLinkPayload by mutableStateOf<String?>(null)
        private set
    var deviceLinkStage by mutableStateOf(DeviceLinkStage.IDLE)
        private set

    /** The four verification words, set the instant a root-holding device's dial is
     *  accepted (before any frame is exchanged -- per the spec, "before any grant is
     *  possible") from this device's own key plus the *transport-level* remote id of that
     *  connection, exactly like the Rust reference's `LinkHandler::accept`. */
    var deviceLinkWords by mutableStateOf<List<String>>(emptyList())
        private set
    var deviceLinkRejectReason by mutableStateOf<String?>(null)
        private set

    /**
     * Begin the new-device side of a QR-link enrollment: generate this device's link
     * payload (its own already-bound node key -- not a separate identity, unlike the CLI's
     * `azula link`, since the app has only ever had the one) and wait for a root-holding
     * device to scan it and dial in. No-op if a link attempt is already in progress.
     *
     * Critically, this device must NOT hold a root secret when this finishes -- per the
     * spec, "a QR-linked device SHALL NOT receive the root secret". [transport.bind]'s
     * upgrade-in-place migration auto-adopts one the moment this device's *own* first bind
     * found none yet, which can race ahead of the user ever reaching this screen (see
     * [IrohTransport.forgetRootSecret]'s kdoc) -- so this is called first, unconditionally,
     * regardless of whether that race actually happened.
     */
    fun beginDeviceLink(name: String) {
        if (deviceLinkStage != DeviceLinkStage.IDLE) return
        transport.forgetRootSecret()
        val myDevicePkHex = transport.nodeId
        val myDevicePk = myDevicePkHex?.hexToByteArrayOrNull()
        if (myDevicePk == null) {
            deviceLinkStage = DeviceLinkStage.FAILED
            return
        }
        deviceLinkRejectReason = null
        deviceLinkWords = emptyList()

        val deferred = CompletableDeferred<IncomingConnection>()
        pendingLinkAccept = deferred
        linkSessionJob = scope.launch {
            // transport.myTicket() is suspend, so building the payload must happen inside
            // this coroutine rather than beginDeviceLink's own (non-suspend) body.
            val ticketBytes = runCatching { transport.myTicket() }.getOrNull()?.let { transport.ticketBytes(it) }
            if (ticketBytes == null) {
                pendingLinkAccept = null
                deviceLinkStage = DeviceLinkStage.FAILED
                return@launch
            }
            deviceLinkPayload = LinkPayloadCodec.encode(newLinkPayload(myDevicePk, name, ticketBytes))
            deviceLinkStage = DeviceLinkStage.WAITING_FOR_SCAN

            val inc = deferred.await()
            val remotePk = inc.remoteId.hexToByteArrayOrNull()
            deviceLinkWords = if (remotePk != null) VerificationWords.compute(myDevicePk, remotePk) else emptyList()
            deviceLinkStage = DeviceLinkStage.VERIFYING
            when (val outcome = DeviceLinkProtocol.runNewDeviceSession(inc.stream, myDevicePkHex, name, roles = 0)) {
                is LinkOutcome.Granted -> {
                    val verified = accountSyncService.verifyGrantedCert(outcome.cert, myDevicePk, outcome.bundle)
                    if (verified != null) {
                        // Belt and suspenders: forget the root secret again right before
                        // persisting, so nothing that could have raced in between
                        // beginDeviceLink() and this point ever survives alongside a
                        // freshly-persisted linked identity.
                        transport.forgetRootSecret()
                        settingsService.saveLinkedIdentity(outcome.cert, FrameCodec.encodeBundle(outcome.bundle))
                        registerMailboxSiblingTicket(outcome.bundle)
                        deviceLinkStage = DeviceLinkStage.GRANTED
                    } else {
                        deviceLinkRejectReason = "the granted certificate did not check out"
                        deviceLinkStage = DeviceLinkStage.FAILED
                    }
                }
                is LinkOutcome.Rejected -> {
                    deviceLinkRejectReason = outcome.reason
                    deviceLinkStage = DeviceLinkStage.REJECTED
                }
            }
            runCatching { inc.stream.close() }
        }
    }

    /**
     * multi-device-identity task 5.4: if the identity bundle a grant delivered carries a
     * mailbox ticket, persist it as a sibling ticket keyed to whichever certified device in
     * that same bundle actually carries the mailbox role -- the one concrete address this
     * newly-linked device has for any sibling at all until it either dials, or is dialed by,
     * one directly (see this file's "Sibling sync" section kdoc). A no-op if there's no
     * mailbox hint, or no cert in the bundle claims the mailbox role (nothing to key the
     * ticket to).
     */
    private fun registerMailboxSiblingTicket(bundle: IdentityBundle) {
        val mailboxTicket = bundle.mailbox ?: return
        val mailboxDevicePk = bundle.certs.asSequence()
            .mapNotNull { DeviceCertCodec.decode(it) }
            .firstOrNull { it.isMailbox }
            ?.devicePk
            ?: return
        settingsService.saveSiblingTicket(mailboxDevicePk.toHexLower(), mailboxTicket)
    }

    /**
     * Cancel an in-progress new-device link attempt (the user backs out of the QR
     * screen): dropping [pendingLinkAccept] means a dial that arrives afterward is simply
     * closed by [startIncomingLoop], and cancelling [linkSessionJob] unblocks (with a
     * [kotlinx.coroutines.CancellationException], structured concurrency's normal
     * cooperative-cancellation path) whichever suspend call it's currently parked in --
     * `deferred.await()` while waiting for a dial, or [DeviceLinkProtocol.runNewDeviceSession]
     * while running one. Nothing was ever persisted, so there is nothing to undo.
     */
    fun cancelDeviceLink() {
        pendingLinkAccept = null
        linkSessionJob?.cancel()
        linkSessionJob = null
        deviceLinkStage = DeviceLinkStage.IDLE
        deviceLinkPayload = null
        deviceLinkWords = emptyList()
        deviceLinkRejectReason = null
    }

    // ---- root-holding side (task 6.2) ----

    enum class GrantStage { IDLE, DIALING, AWAITING_HELLO, CONFIRMING, GRANTED, REJECTED, FAILED }

    var grantStage by mutableStateOf(GrantStage.IDLE)
        private set
    var grantPeerName by mutableStateOf("")
        private set
    var grantPeerRoles by mutableStateOf(0)
        private set
    var grantWords by mutableStateOf<List<String>>(emptyList())
        private set
    var grantFailReason by mutableStateOf<String?>(null)
        private set

    private var grantJob: Job? = null
    private var grantDecisionDeferred: CompletableDeferred<GrantDecision>? = null
    private var grantNewDevicePk: ByteArray? = null

    /** The ticket dialed to reach the new device in [beginGrantDial] -- multi-device-identity
     *  task 5.4 persists this as a sibling ticket once [confirmGrantLink] actually grants,
     *  since it's the one concrete address this device ever has for the new one. */
    private var grantPeerTicket: String? = null

    /** Whether this device can currently play the granting side at all -- it must hold
     *  the identity's root secret to issue a certificate. The confirmation/scan UI must
     *  not be offered when this is false (device-linking spec: "a device without one
     *  cannot be the granting side"). */
    fun canGrantDeviceLinks(): Boolean = transport.rootSecretBytes() != null

    /**
     * Root-holding side: decode a scanned/pasted `azl…` [payload] and dial its embedded
     * ticket on [Alpns.LINK]. Fails immediately (no dial attempted) if this device holds
     * no root secret ([canGrantDeviceLinks]), the payload doesn't decode, or the ticket
     * can't be resolved. Once dialed, waits for the peer's `LinkHello` before showing
     * anything to confirm -- see [confirmGrantLink]/[cancelGrantLink].
     *
     * Multi-device-identity task 6.6: [payload] may be either the bare `azl…` string or a
     * wrapped deeplink (`https://azula.app/l/<payload>` / `azula://l?c=<payload>`) -- the QR
     * now renders the latter (a bare payload is inert text to a phone's camera), but the
     * paste box must keep accepting the former too, since copy-paste is still the only
     * working path (no in-app scanner). [AzulaLinks.parseDeviceLink] extracts the payload
     * from whichever form was pasted; falling back to the raw input if it isn't recognized
     * keeps this permissive for anything [LinkPayloadCodec.decode] itself would reject
     * anyway (e.g. a genuinely bare payload that doesn't start with a recognizable prefix
     * shouldn't be silently swallowed into a null ticket before the codec gets a chance to
     * report exactly what's wrong).
     */
    fun beginGrantDial(payload: String) {
        if (grantStage != GrantStage.IDLE) return
        val rootSecret = transport.rootSecretBytes()
        if (rootSecret == null) {
            grantFailReason = "this device holds no root secret and cannot grant a link"
            grantStage = GrantStage.FAILED
            return
        }
        val rawPayload = AzulaLinks.parseDeviceLink(payload) ?: payload.trim()
        val parsed = LinkPayloadCodec.decode(rawPayload)
        val ticket = parsed?.let { transport.ticketFromBytes(it.ticket) }
        val myDevicePk = transport.nodeId?.hexToByteArrayOrNull()
        if (parsed == null || ticket == null || myDevicePk == null) {
            grantFailReason = "that isn't a valid link code"
            grantStage = GrantStage.FAILED
            return
        }
        grantFailReason = null
        grantStage = GrantStage.DIALING
        grantPeerTicket = ticket
        grantJob = scope.launch {
            val stream = runCatching { transport.connect(ticket, Alpns.LINK) }.getOrNull()
            if (stream == null) {
                grantFailReason = "couldn't connect to that device"
                grantStage = GrantStage.FAILED
                return@launch
            }
            grantStage = GrantStage.AWAITING_HELLO
            val result = DeviceLinkProtocol.runGrantingSession(stream) { devicePkHex, name, roles ->
                grantPeerName = name
                grantPeerRoles = roles
                grantNewDevicePk = devicePkHex.hexToByteArrayOrNull()
                grantWords = grantNewDevicePk?.let { VerificationWords.compute(myDevicePk, it) }.orEmpty()
                grantStage = GrantStage.CONFIRMING
                val deferred = CompletableDeferred<GrantDecision>()
                grantDecisionDeferred = deferred
                deferred.await()
            }
            grantStage = when (result) {
                DeviceLinkProtocol.GrantSessionResult.GRANTED -> GrantStage.GRANTED
                DeviceLinkProtocol.GrantSessionResult.REJECTED -> GrantStage.REJECTED
                DeviceLinkProtocol.GrantSessionResult.NO_HELLO -> {
                    grantFailReason = "the other device never presented a link request"
                    GrantStage.FAILED
                }
            }
            runCatching { stream.close() }
        }
    }

    /**
     * Explicit user confirmation naming [grantPeerName] and [grantPeerRoles] -- the only
     * thing that can produce a [Frame.LinkGrant] (device-linking spec: "only explicit
     * confirmation on the root-holding device causes the LinkGrant"). Issues the
     * certificate and identity bundle via [AccountSyncService.issueDeviceLinkGrant] and
     * completes the waiting [DeviceLinkProtocol.runGrantingSession] with a grant; a
     * failure to issue (no event log configured) falls back to a rejection rather than
     * silently hanging. No-op if not currently at [GrantStage.CONFIRMING].
     */
    fun confirmGrantLink() {
        val deferred = grantDecisionDeferred ?: return
        if (grantStage != GrantStage.CONFIRMING) return
        val newDevicePk = grantNewDevicePk ?: return
        val name = grantPeerName
        val roles = grantPeerRoles
        val ticket = grantPeerTicket
        scope.launch {
            val rootSecret = transport.rootSecretBytes()
            val rootPk = rootSecret?.let { transport.publicKeyFor(it) }
            val decision = if (rootSecret != null && rootPk != null) {
                val issued = accountSyncService.issueDeviceLinkGrant(rootPk, rootSecret, newDevicePk, name, roles)
                if (issued != null) {
                    // multi-device-identity task 5.4: persist the ticket we just dialed to
                    // reach this new device, so a later reconnectSiblings() can auto-dial it
                    // for sync without needing it to dial us first.
                    if (ticket != null) settingsService.saveSiblingTicket(newDevicePk.toHexLower(), ticket)
                    GrantDecision.Grant(issued.first, issued.second)
                } else {
                    GrantDecision.Reject("failed to issue a certificate for this device")
                }
            } else {
                GrantDecision.Reject("this device holds no root secret and cannot grant a link")
            }
            deferred.complete(decision)
        }
    }

    /** Explicit cancellation: at [GrantStage.CONFIRMING], completes the waiting session
     *  with a rejection (so the new device receives `LinkReject` and the peer's socket
     *  closes cleanly); before that (still dialing/awaiting hello), just tears down the
     *  attempt locally. Either way, no certificate is ever issued. */
    fun cancelGrantLink() {
        val deferred = grantDecisionDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(GrantDecision.Reject("declined by user"))
            return
        }
        grantJob?.cancel()
        grantJob = null
        grantStage = GrantStage.IDLE
        grantPeerName = ""
        grantPeerRoles = 0
        grantWords = emptyList()
        grantFailReason = null
        grantNewDevicePk = null
        grantPeerTicket = null
    }

    /** Reset every device-linking field back to idle -- called by the UI when leaving
     *  either screen after a terminal outcome (granted/rejected/failed), so reopening it
     *  starts a fresh attempt rather than showing stale state. */
    fun resetDeviceLink() {
        deviceLinkStage = DeviceLinkStage.IDLE
        deviceLinkPayload = null
        deviceLinkWords = emptyList()
        deviceLinkRejectReason = null
    }

    fun resetGrantLink() {
        grantStage = GrantStage.IDLE
        grantPeerName = ""
        grantPeerRoles = 0
        grantWords = emptyList()
        grantFailReason = null
        grantNewDevicePk = null
        grantDecisionDeferred = null
        grantJob = null
        grantPeerTicket = null
    }

    // =========================================================================================
    // Device management (multi-device-identity task 6.3): list the device set from the fold,
    // rename display names locally (never touches the certificate), and revoke -- root holders
    // only, gated by the same requirement [canGrantDeviceLinks] checks (the root secret) --
    // appending a device_revoke entry.
    // =========================================================================================

    /** One row in the device management screen: [certName] is the certificate's own `name`
     *  field; [localName] (if set) is this device's local override and always wins in the UI --
     *  same "local rename overrides the shared name" convention as [ConversationState]'s own
     *  rename. */
    data class DeviceRow(
        val devicePkHex: String,
        val certName: String,
        val localName: String?,
        val isMailbox: Boolean,
        val isSelf: Boolean,
    )

    var devices by mutableStateOf<List<DeviceRow>>(emptyList())
        private set

    /** Reloads [devices] from the current fold -- call when the device management screen
     *  opens, and after every rename/revoke so the list reflects it immediately. Empty (not
     *  an error) before any identity/event log is known, same defensive posture as every other
     *  fold-derived read in this class. */
    suspend fun refreshDevices() {
        val rootPk = currentRootPk()
        if (rootPk == null) {
            devices = emptyList()
            return
        }
        val myHex = transport.nodeId
        val localNames = settingsService.deviceLocalNames
        devices = accountSyncService.currentDeviceSet(rootPk)
            .map { cert ->
                val hex = cert.devicePk.toHexLower()
                DeviceRow(hex, cert.name, localNames[hex], cert.isMailbox, hex == myHex)
            }
            .sortedWith(compareByDescending<DeviceRow> { it.isSelf }.thenBy { it.localName ?: it.certName })
    }

    /** Local-only rename (device-linking spec's "renaming is local-only" reading, task 6.3):
     *  persists a display override for [devicePkHex] without touching its certificate -- only
     *  the root can reissue that, and this never does. A blank/null [name] clears the override,
     *  falling back to the certificate's own name. */
    fun renameDeviceLocally(devicePkHex: String, name: String?) {
        settingsService.saveDeviceLocalName(devicePkHex, name)
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        devices = devices.map { if (it.devicePkHex == devicePkHex) it.copy(localName = trimmed) else it }
    }

    /** Whether this device can revoke another (task 6.3): identical requirement to
     *  [canGrantDeviceLinks] -- both need the root secret -- reused rather than duplicated. A
     *  QR-linked device holds no root secret and must not be offered a revoke control. */
    fun canRevokeDevices(): Boolean = canGrantDeviceLinks()

    /**
     * Revoke [devicePkHex]: issues a root-signed revocation statement and appends it to this
     * device's own log as a `device_revoke` entry (device-linking spec's "Revocation
     * Statements Invalidate Certificates"). Returns false, appending nothing, if this device
     * holds no root secret ([canRevokeDevices] false) or the hex doesn't decode -- the UI must
     * already have hidden the control in the former case; this is the defensive re-check. On
     * success, best-effort drops any persisted sibling ticket for the revoked device (task 5.4
     * hygiene, not required for correctness -- see [reconnectSiblings]'s kdoc) and reloads
     * [devices], so the revoked device disappears from the list immediately (its certificate
     * is now excluded by the fold).
     */
    suspend fun revokeDevice(devicePkHex: String): Boolean {
        val rootSecret = transport.rootSecretBytes() ?: return false
        val rootPk = currentRootPk() ?: return false
        val devicePk = devicePkHex.hexToByteArrayOrNull() ?: return false
        accountSyncService.revokeDevice(rootPk, rootSecret, devicePk, epochSeconds()) ?: return false
        settingsService.removeSiblingTicket(devicePkHex)
        refreshDevices()
        return true
    }
}
