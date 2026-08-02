package dev.azula.net

import kotlinx.coroutines.flow.Flow

/**
 * A single bidirectional, end-to-end-encrypted stream to a peer. Carries
 * newline-delimited JSON [Frame]s at the line level; framing/JSON is applied by
 * callers via [FrameCodec]. Also supports raw byte I/O for binary file transfers.
 */
interface P2pStream {
    suspend fun sendLine(line: String)

    /** Next line from the peer, or null when the stream is closed. */
    suspend fun receiveLine(): String?

    /**
     * Write exactly [bytes].size raw bytes to the stream with no framing.
     * Used for binary file-transfer bodies.
     */
    suspend fun sendBytes(bytes: ByteArray)

    /**
     * Read exactly [n] raw bytes from the stream, sharing the same internal
     * buffer as [receiveLine] so no bytes buffered from a prior line read are
     * lost. Returns null if EOF is reached before [n] bytes are available.
     */
    suspend fun receiveBytes(n: Int): ByteArray?

    /**
     * The live round-trip time to the peer in milliseconds, or null if not yet
     * available (no path established) or unsupported by this transport. Backed by
     * iroh's smoothed per-connection QUIC RTT — a cheap, non-blocking snapshot.
     * The mock and any non-iroh stream return null.
     */
    fun rttMs(): Long? = null

    suspend fun close()
}

/** An inbound connection a peer dialed to us, already negotiated on an ALPN. */
data class IncomingConnection(
    val remoteId: String,
    val alpn: String,
    val stream: P2pStream,
)

/**
 * Platform abstraction over an iroh endpoint. All platforms back this with the
 * `app.azula.iroh:iroh-kmp` binding (see azula-docs/openspec/specs/iroh-kmp/design.md). The
 * `-mock` apps back the UI with a FakeTransport (in the mock-support module)
 * instead, so no real networking is involved.
 */
interface IrohTransport {
    /** Hex endpoint id, available after [bind]. */
    val endpointId: String?

    /** Bind the endpoint, advertising the given ALPNs for incoming connections. */
    suspend fun bind(alpns: List<String>)

    /** A shareable ticket string encoding our address (our "code"). */
    suspend fun myTicket(): String

    /** The current 32-byte iroh secret key, for backup. Null if unavailable. */
    fun secretKeyBytes(): ByteArray?

    /**
     * Replace this device's identity with [bytes] (a 32-byte secret key): persist
     * it and re-bind the endpoint, so [endpointId] / [myTicket] reflect the restored
     * identity. Used by the recovery-phrase restore flow.
     */
    suspend fun importSecretKey(bytes: ByteArray)

    /**
     * The current 32-byte identity root secret, if this device holds one -- independent
     * of [secretKeyBytes]/the endpoint key (see the `identity` capability's Per-Platform
     * Key-at-Rest Storage requirement, multi-device-identity). Null both before any root
     * secret is known and permanently on a QR-linked device, which holds a endpoint key and
     * certificate but no root secret. Defaults to null so every existing [IrohTransport]
     * implementation stays source-compatible.
     */
    fun rootSecretBytes(): ByteArray? = null

    /**
     * Persists [bytes] (32 bytes) as this device's root secret, independent of the endpoint
     * key -- used by the recovery-phrase import flow (`RecoveryPhrase` /
     * `ConnectService.importRecoveryPhrase`). Unlike [importSecretKey] this never rebinds
     * the transport itself: under the multi-device-identity model the endpoint id is unaffected
     * by a root-secret change. The rest of enrollment (self-issued certificate, `device_add`
     * log entry, and a conditional [rebind] for a genuinely different identity) is
     * orchestrated by `ConnectService.importRecoveryPhrase` (multi-device-identity task
     * 3.4), calling this method and [publicKeyFor]/[rebind] in turn. Defaults to a no-op so
     * every existing [IrohTransport] implementation stays source-compatible.
     */
    fun importRootSecret(bytes: ByteArray) {}

    /**
     * Clears any persisted root secret, leaving [rootSecretBytes] permanently `null` until a
     * future [importRootSecret] -- multi-device-identity task 6.1's QR-link enrollment path
     * calls this before generating its link payload. Necessary because bind()'s
     * upgrade-in-place migration (see `IrohFfiTransport.maybeAdoptRootSecret`) auto-adopts the
     * endpoint secret as root the moment a device with no root secret yet completes its first
     * bind -- which happens unconditionally, before the user's onboarding choice is even made
     * (the setup gate decides *whether* to show setup pre-bind, but binding itself, and
     * therefore the auto-adopt, isn't gated on that choice). A device that goes on to choose
     * "link to an existing identity" must not keep that raced-in root secret: the QR-link flow
     * calls this first so the invariant ("a QR-linked device SHALL NOT receive the root
     * secret") holds regardless of the race's outcome. A no-op if no root secret is held.
     * Defaults to a no-op so every existing [IrohTransport] implementation stays
     * source-compatible; the real transports override it to actually clear the store.
     */
    fun forgetRootSecret() {}

    /**
     * Derives the Ed25519 public key for a raw 32-byte [secret] this device doesn't
     * necessarily hold as its own bound key -- used by the recovery-phrase enrollment path
     * (multi-device-identity task 3.4) to compute the `root_pk` a self-issued certificate
     * embeds, and to namespace the per-identity event-log directory (task 4.6) by the
     * identity's actual root public key. Distinct from [endpointId] (this device's OWN bound
     * key's public half): the upgrade-in-place migration path's root secret always equals
     * this device's endpoint secret, so the two coincide there, but a phrase-enrolled device
     * keeps its own endpoint keypair while adopting a *different* root secret (see `Restore
     * Recovers the Identity Onto This Device`), so the two must be computed independently
     * in general. Null if unsupported or [secret] isn't a valid 32-byte Ed25519 secret.
     * Defaults to null so every existing [IrohTransport] implementation (mocks, `-mock`
     * apps' `FakeTransport`, most tests) stays source-compatible.
     */
    fun publicKeyFor(secret: ByteArray): ByteArray? = null

    /**
     * Re-binds the transport using whatever endpoint key is already persisted (no key change)
     * -- a teardown-then-rebuild of the endpoint, mirroring [importSecretKey]'s existing
     * safe sequence minus the key swap. Used by the recovery-phrase enrollment path
     * (`ConnectService.importRecoveryPhrase`, task 3.4) to "begin syncing" after adopting a
     * *different* identity's root secret: re-announcing presence and refreshing
     * connections is appropriate there (the device just switched identities), unlike a
     * same-identity restore, which must change nothing observable and therefore never
     * calls this. A failure here (network unavailable, endpoint error) propagates as an
     * exception -- callers degrade to offline exactly as [endpointId]'s initial-bind try/catch
     * does; this method itself makes no attempt to swallow it. Defaults to a no-op so every
     * existing [IrohTransport] implementation stays source-compatible and never throws.
     */
    suspend fun rebind() {}

    /** Dial a peer by ticket on the given ALPN, opening a fresh stream. */
    suspend fun connect(ticket: String, alpn: String): P2pStream

    /**
     * The peer's hex endpoint id decoded from a ticket, without dialing — so an
     * outbound conversation can be keyed/named after the peer (inbound ones use
     * [IncomingConnection.remoteId]). Returns null if the ticket can't be parsed.
     */
    fun peerIdForTicket(ticket: String): String?

    /** Connections peers dial to us. */
    fun incoming(): Flow<IncomingConnection>

    suspend fun shutdown()

    /**
     * Sign [data] with this device's iroh endpoint key (Ed25519), for the
     * `signed` invite flag (see azula-docs/openspec/specs/invitations/design.md). Null if the
     * endpoint isn't bound or signing is unsupported. Defaults to null so
     * every existing [IrohTransport] implementation stays source-compatible.
     */
    fun signWithEndpointKey(data: ByteArray): ByteArray? = null

    /**
     * Verify [sig] over [data] against the endpoint key encoded in [endpointIdHex].
     * False (not an exception) on any failure — bad hex, wrong signature, or
     * unsupported. Defaults to false.
     */
    fun verifyEndpointSignature(endpointIdHex: String, data: ByteArray, sig: ByteArray): Boolean = false

    /** The raw postcard bytes of [ticket], for embedding in an invite payload. Null on failure. */
    fun ticketBytes(ticket: String): ByteArray? = null

    /** The inverse of [ticketBytes]: rebuilds a dialable ticket string from raw bytes. Null on failure. */
    fun ticketFromBytes(bytes: ByteArray): String? = null

    /**
     * Invoked at the end of every successful [bind] — the initial bind and any
     * re-bind (e.g. Android foreground-resume). The app sets this to re-announce
     * presence to saved peers/bridges so a bridge flushes any queued mail right
     * away. Defaults to null.
     */
    var onCameOnline: (() -> Unit)?

    /**
     * Whether a previously persisted identity key exists, read WITHOUT binding
     * (bind would eagerly mint+persist a key and destroy this signal). The setup
     * gate reads this once, before start(), to tell a fresh install (no key) from
     * an upgrade of an existing install (key present) — see the onboarding
     * capability's gate decision. Defaults to true so unknown/test transports skip
     * setup; the real transports override with a pre-bind key-store probe.
     */
    fun hasPersistedIdentity(): Boolean = true
}
