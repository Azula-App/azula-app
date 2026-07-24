package dev.azula.mock

import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.MediaCodec
import dev.azula.net.MediaFetchRequest
import dev.azula.net.MediaFetchResponse
import dev.azula.net.P2pStream
import dev.azula.persist.InvitationsBook
import dev.azula.persist.InvitationsStore

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * A no-network transport for the mock / UI-test build (and a graceful fallback
 * when the real iroh binding can't load). Real UI, fake backend: CHAT/LLM dials
 * loop back a friendly chat echo; TERM dials return a fake echo shell; MEDIA
 * dials serve a fetch directly out of [blobs] (see [FakeMediaStream]) since
 * there's no second peer to play "the sender". On bind it also surfaces one
 * inbound TERM connection ("mockterm", an interactive echo shell), so a terminal
 * conversation appears in the mock build without needing a live peer.
 *
 * [demoTerminal] adds a *second* inbound terminal ("buildbot") replaying a
 * canned build transcript. It's off by default and only [buildMockState] turns
 * it on: it exists to make the `-mock` apps look populated, and the test suites
 * share this transport -- every test that reasons about "the terminal
 * conversation" would otherwise have to disambiguate a second one it never
 * asked for.
 *
 * [hasPersistedIdentity] backs [IrohTransport.hasPersistedIdentity] for the setup
 * gate (see azula-docs/openspec/changes/initial-setup-flow/design.md, decision D1):
 * tests pass `hasPersistedIdentity = false` to force the setup flow (a "fresh
 * install"), while the default `true` keeps `-mock`/demo apps skipping it, same as
 * every other [FakeTransport] caller today.
 *
 * [rootless] models a QR-linked device (see the `identity` capability's "QR-linked
 * device stores no root secret" / "Device keys have no phrase" scenarios): `true` makes
 * [rootSecretBytes] permanently null while [nodeId]/[secretKeyBytes] stay normal, so a
 * UI test can drive the reveal-recovery-phrase flow's rootless copy without a second,
 * bespoke transport. Default `false` keeps every existing caller root-holding, as before.
 */
class FakeTransport(
    private val demoTerminal: Boolean = false,
    hasPersistedIdentity: Boolean = true,
    private val rootless: Boolean = false,
) : IrohTransport {
    override var nodeId: String? = null
        private set

    override var onCameOnline: (() -> Unit)? = null

    // Backing value for the overridden hasPersistedIdentity() below, named
    // differently from the constructor parameter of the same name so the
    // property doesn't shadow (and read as recursing into) the method.
    private val persistedIdentity: Boolean = hasPersistedIdentity

    override fun hasPersistedIdentity(): Boolean = persistedIdentity

    /**
     * In-memory blob bodies keyed by id, served by `connect(ticket, Alpns.MEDIA)`
     * -- stands in for a real peer's `MediaService.serveIncoming` since this
     * transport is a solo loopback. Seed before triggering a fetch (demo or test).
     */
    val blobs: MutableMap<String, ByteArray> = mutableMapOf()

    /**
     * Real-time delay (ms) [FakeMediaStream] waits before serving each chunk
     * after the first, so a caller can interleave a cancel (or just observe
     * progress) between two body chunks landing. 0 (the default) serves
     * everything as fast as possible.
     */
    var mediaChunkDelayMs: Long = 0

    /** Every [MediaFetchRequest] a fetch has sent, in order -- lets a test assert
     *  a retry's resume offset without peeking at BlobStore internals. */
    val mediaRequests: Channel<MediaFetchRequest> = Channel(Channel.UNLIMITED)

    /** Every frame sent on a CHAT/LLM loopback stream (e.g. [MediaService][dev.azula.state.MediaService]'s
     *  outgoing [Frame.MediaOffer]) -- lets a caller assert something was sent
     *  without needing the loopback to echo it back to itself. */
    val sentFrames: Channel<Frame> = Channel(Channel.UNLIMITED)

    // Keyed by (ticket, alpn) rather than just ticket: connectPeer races CHAT/LLM/
    // TERM simultaneously (each gets its own stream instance), and PEER (CHAT)
    // always wins that race (see ConversationStore.kindPriority) -- but keying
    // only by ticket would risk capturing whichever alpn's connect() happened to
    // run last, not necessarily the one that ends up wired as the conversation's
    // live stream.
    private val loopbacks = mutableMapOf<Pair<String, String>, LoopbackStream>()

    // "self", not "mock": our own id goes through ConversationStore.mnemonicCode too
    // (the sidebar's YOUR CODE box), and that label is built from the id's first two
    // characters plus chars 2 and 5 -- so a "mock…" self id always rendered
    // "mo · onyx · <random>", i.e. the same first two segments as "mockterm"'s
    // conversation row, and collided with it outright ("mo · onyx · ivy") whenever the
    // random suffix's 2nd hex digit happened to be '5' or 'e' -- 1 run in 8. Home then
    // showed two identical labels, one of them not a conversation at all: ambiguous for
    // any by-text click (e2e/ios.yaml's tapOn, DesktopAppE2eTest's onNodeWithText, which
    // throws on multiple matches) and just confusing in a demo. "se · …" can't collide
    // with the demo peers' "mo · …"/"bu · …" -- the same first-two-chars rule the
    // incoming() doc calls out, which applies to our own id as much as to a peer's.
    override suspend fun bind(alpns: List<String>) {
        nodeId = "self" + Random.nextInt(0x10000, 0xfffff).toString(16)
        onCameOnline?.invoke()
    }

    override suspend fun myTicket(): String = "azula-mock-${nodeId ?: "offline"}"

    /** A stable fake 32-byte key so the mock build can render a recovery phrase. */
    override fun secretKeyBytes(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    override suspend fun importSecretKey(bytes: ByteArray) { /* mock: no-op */ }

    /**
     * Mirrors [secretKeyBytes]: the mock's throwaway identity is root-holding by default,
     * same as any single-device install that hasn't linked a second device (see
     * multi-device-identity's migration -- root and node coincide until a device is
     * linked). `-mock` apps persist nothing either way, so there's no real root/node
     * distinction to model here -- except when [rootless] opts into modeling a QR-linked
     * device instead (permanently no root secret, node key unaffected).
     */
    override fun rootSecretBytes(): ByteArray? = if (rootless || rootForgotten) null else secretKeyBytes()

    override fun importRootSecret(bytes: ByteArray) { /* mock: no-op, same as importSecretKey */ }

    /** Backing flag for [forgetRootSecret] (multi-device-identity task 6.1's QR-link
     *  flow): lets a test model "this device's bind() already raced in a root secret,
     *  then the link flow forgot it" without a second, bespoke transport -- mirrors
     *  [rootless] (permanently rootless from construction) but toggle-able at any point. */
    private var rootForgotten = false

    override fun forgetRootSecret() {
        rootForgotten = true
    }

    override suspend fun connect(ticket: String, alpn: String): P2pStream = when (alpn) {
        Alpns.TERM -> FakeTerminalStream()
        Alpns.MEDIA -> FakeMediaStream(
            blobLookup = { blobs[it] },
            onRequest = { mediaRequests.trySend(it) },
            chunkDelayMs = mediaChunkDelayMs,
        )
        else -> LoopbackStream(onSend = { sentFrames.trySend(it) }).also { loopbacks[ticket to alpn] = it }
    }

    /**
     * Pushes [frame] into [ticket]'s CHAT-alpn loopback stream, as if the peer had
     * sent it -- e.g. to demo/test an inbound [Frame.MediaOffer] so a `-mock` app
     * (or a test) can see a "received" image without a second peer. No-op if that
     * loopback doesn't exist yet (dial it first, e.g. via `AzulaState.connectPeer`).
     */
    suspend fun injectIncoming(ticket: String, frame: Frame, alpn: String = Alpns.CHAT) {
        loopbacks[ticket to alpn]?.injectIncoming(frame)
    }

    /**
     * Test-only channel backing [incoming]'s "arbitrary stranger" seam -- see
     * [injectIncomingConnection]. Unbounded and never closed by this class: a
     * test's own scope teardown just stops collecting it.
     */
    private val incomingConnections = Channel<IncomingConnection>(Channel.UNLIMITED)

    /**
     * Test-only hook: emits [connection] on the exact same inbound flow
     * [ConnectService][dev.azula.state.ConnectService]'s accept loop collects via
     * [incoming] -- as if a peer had just dialed in -- so a UI test can drive the
     * full stranger-gate path (`ConnectService.handleStrangerConnection` ->
     * `InviteService.enqueuePending` -> `state.pendingInvites` -> the Invites
     * screen / `InviteReviewSheet`) without a second real peer. Kept minimal and
     * clearly test-oriented (azula-docs/openspec/changes/invite-review-ui-test/): unlike
     * [injectIncoming] (which pushes a frame into an *existing* loopback this
     * transport dialed out to), this synthesizes the inbound connection itself,
     * so the caller supplies its own [P2pStream] (e.g. a fresh
     * `LoopbackStream`-shaped fake, or the shared test harness's `PeerPipe`) to
     * play "the stranger" -- sending a `Frame.Hello` (with or without a valid
     * invite) over it once `ConnectService` starts reading it.
     */
    suspend fun injectIncomingConnection(connection: IncomingConnection) {
        incomingConnections.send(connection)
    }

    /**
     * A stable fake peer id derived from the ticket, so mock conversations key
     * consistently. Special-cases [myTicket]'s own `"azula-mock-<nodeId>"` shape
     * so it resolves back to that exact [nodeId] -- the invariant a real
     * transport's ticket always has (its node id is embedded in it), and which
     * invite self-verification relies on (see InviteService.verifyInbound).
     */
    override fun peerIdForTicket(ticket: String): String =
        ticket.removePrefix("azula-mock-").takeIf { it != ticket && it.isNotBlank() }
            ?: ("mock" + (ticket.hashCode().toLong() and 0xffffffffL).toString(16))

    /** Surface the inbound terminal connection(s) so the mock build has a live terminal
     *  without a peer: always "mockterm" (an interactive echo shell, [MOCKTERM_GREETING]),
     *  plus "buildbot" (a canned, non-interactive build transcript, [BUILDBOT_GREETING])
     *  when [demoTerminal] is on. Their remoteIds must be pre-seeded as known contacts
     *  via [MockInvitationsStore] -- ConnectService's stranger gate (see
     *  azula-docs/openspec/specs/invitations/design.md) closes an unknown peer's TERM/LLM stream outright,
     *  with no accept path for either.
     *
     *  Their ids must stay distinct **in the first mnemonic segment**, not merely
     *  distinct overall. ConversationStore.mnemonicCode labels an un-announced peer's
     *  row "<first 2 chars> · word · word", and the sidebar truncates that label, so the
     *  row only ever shows about "mo · onyx · …". The obvious "mockbuild" collides
     *  outright (identical label to "mockterm"), and even "mockcargo" -- a genuinely
     *  different "mo · onyx · ember" -- still *renders* as "mo · onyx · …", i.e. two
     *  visually identical rows. "buildbot" gives "bu · kite · lyra", which reads apart at
     *  a glance. Keep any further demo peer distinct in those first two characters --
     *  and note our own node id ([bind]) is subject to the same rule, since the YOUR CODE
     *  box labels it through the very same mnemonicCode.
     *  Merged with [incomingConnections] so a test can additionally push arbitrary
     *  connections via [injectIncomingConnection] on top of the fixed demo one(s) -- all
     *  land on the same collector, exactly like a real transport fielding more than one
     *  inbound dial over its lifetime. */
    override fun incoming(): Flow<IncomingConnection> = merge(
        flow {
            delay(300)
            emit(IncomingConnection(remoteId = "mockterm", alpn = Alpns.TERM, stream = FakeTerminalStream()))
            if (demoTerminal) {
                emit(
                    IncomingConnection(
                        remoteId = "buildbot",
                        alpn = Alpns.TERM,
                        stream = FakeTerminalStream(greeting = BUILDBOT_GREETING, echo = false),
                    ),
                )
            }
        },
        incomingConnections.receiveAsFlow(),
    )

    override suspend fun shutdown() {}

    /** A deterministic fake 64-byte "signature" (not real crypto) so the mock invite flow round-trips. */
    override fun signWithNodeKey(data: ByteArray): ByteArray {
        val sum = data.sum()
        return ByteArray(64) { (sum + it).toByte() }
    }

    /** Mock always trusts -- there's no real key material to check. */
    override fun verifyNodeSignature(nodeIdHex: String, data: ByteArray, sig: ByteArray): Boolean = true

    /** Mock tickets are plain strings, so bytes/string round-trip via UTF-8. */
    override fun ticketBytes(ticket: String): ByteArray = ticket.encodeToByteArray()

    override fun ticketFromBytes(bytes: ByteArray): String = bytes.decodeToString()
}

/**
 * An [InvitationsStore] pre-seeded with [FakeTransport.incoming]'s own
 * auto-surfaced demo peers ("mockterm", "buildbot") as already-accepted
 * contacts, so ConnectService's stranger gate recognizes them as known and
 * wires them immediately instead of closing them as unrecognized strangers.
 * Deliberately NOT [PeerStore] entries: that would also make
 * ConnectService.reconnectSaved actively re-dial them as outbound peers
 * (FakeTransport.connect never fails), creating a second, spurious PEER
 * conversation alongside each real demo terminal. A contact is known without
 * being something to dial. Read-only: the mock build doesn't persist real
 * invitations, so [save] is a no-op.
 */
class MockInvitationsStore : InvitationsStore {
    override fun load(): InvitationsBook = InvitationsBook(contacts = listOf("mockterm", "buildbot"))
    override fun save(book: InvitationsBook) = Unit
}

/**
 * A fake echo shell: feeds [greeting], then -- when [echo] is true -- echoes
 * keystrokes back as terminal output and drops a fresh prompt after each
 * Enter. Enough to drive the terminal UI. Parameterized so [FakeTransport.incoming]
 * can surface two differently-flavored terminals off the same implementation:
 * "mockterm" ([MOCKTERM_GREETING], the interactive default) and "buildbot"
 * ([BUILDBOT_GREETING] with `echo = false`, a read-only transcript replay --
 * typing into it still reaches [sendLine], it just never produces output).
 */
private class FakeTerminalStream(
    private val greeting: String = MOCKTERM_GREETING,
    private val echo: Boolean = true,
) : P2pStream {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private var greeted = false

    // The greeting is held back until the first Frame.Resize arrives rather than
    // sent eagerly from init: the real client only resizes once TerminalView
    // actually measures its layout, well after the connection is made, so an
    // eager greeting used to be parsed by the emulator at its default 80-col
    // width and never reflowed on the later resize (resize() is top-anchored,
    // not a reflow) -- corrupting any width-sensitive replayed content. See
    // azula-docs/openspec/changes/mock-terminal-resize-greeting/, "Mock terminal feeds before resize."
    private fun greet() {
        if (greeted) return
        greeted = true
        inbox.trySend(FrameCodec.encode(Frame.Term(greeting)))
    }

    override suspend fun sendLine(line: String) {
        val frame = runCatching { FrameCodec.decode(line) }.getOrNull()
        if (frame is Frame.Resize) greet()
        if (echo && frame is Frame.Input) {
            // Echo the keystrokes (a real PTY echoes); on Enter, newline + a prompt.
            // Backspace arrives as DEL (TermSeq.BACKSPACE, 0x7f) and must be echoed the
            // way a real shell's readline does -- "\b \b": step left, overwrite the glyph
            // with a space, step left again. Echoing the DEL back verbatim (what this
            // used to do) leaves the character on screen, since DEL is a no-op on output.
            val echoed = if (frame.text.contains('\r') || frame.text.contains('\n')) {
                frame.text.replace("\r", "\r\n") + "› "
            } else {
                frame.text.replace("\u007f", "\b \b")
            }
            inbox.trySend(FrameCodec.encode(Frame.Term(echoed)))
        }
    }

    // receiveCatching(), not runCatching { receive() } -- see LoopbackStream.receiveLine below:
    // a broad Throwable catch would swallow the CancellationException a cancelled reader throws.
    override suspend fun receiveLine(): String? = inbox.receiveCatching().getOrNull()
    override suspend fun sendBytes(bytes: ByteArray) {}
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() { inbox.close() }
}

/**
 * Greeting for "mockterm", the interactive echo-shell terminal (see
 * [FakeTransport.incoming]). Written as though a couple of commands had
 * already run before the tab was opened, so it reads as a live shell instead
 * of a bare prompt -- ends on the same bare "› " the original greeting did,
 * so typing still lands right after it. Lines stay under ~72 columns: this
 * is fed at whatever width the UI measured for the first [Frame.Resize] (see
 * [FakeTerminalStream.greet]'s comment above).
 */
private const val MOCKTERM_GREETING =
    "mock shell — type away\r\n" +
        "[38;5;205m›azula[0m ~ git log --oneline -3\r\n" +
        "a3f9c1e resume interrupted media fetches\r\n" +
        "e281b04 add invite QR fallback for narrow screens\r\n" +
        "9c7d2aa fix holepunch retry backoff\r\n" +
        "[38;5;205m›azula[0m ~ pwd\r\n" +
        "/home/azula/dev/azula-cli\r\n" +
        "[38;5;205m›azula[0m ~ \r\n› "

/**
 * Greeting for "buildbot", a second demo terminal (see [FakeTransport.incoming])
 * that replays a canned `cargo build` -- using the same bold-green ANSI cargo
 * itself prints for "Compiling"/"Finished" -- followed by a `git status`, then
 * sits on a bare prompt. Always paired with `echo = false`, so it reads as a
 * finished transcript rather than something you can type into.
 */
private const val BUILDBOT_GREETING =
    "[1;32m   Compiling[0m azula-core v0.4.2\r\n" +
        "[1;32m   Compiling[0m azula-net v0.4.2\r\n" +
        "[1;32m   Compiling[0m azula-cli v0.4.2\r\n" +
        "[1;32m    Finished[0m dev [unoptimized + debuginfo] target(s) in 4.87s\r\n" +
        "[38;5;205m›azula[0m ~ git status\r\n" +
        "On branch main\r\n" +
        "Your branch is up to date with 'origin/main'.\r\n" +
        "\r\n" +
        "nothing to commit, working tree clean\r\n" +
        "[38;5;205m›azula[0m ~ "

/** Echoes sent chat back as if a peer replied — just enough to demo p2p chat solo.
 *  [onSend] fires for every decodable frame passed to [sendLine] (not just
 *  [Frame.Chat]), so a caller can observe e.g. an outgoing [Frame.MediaOffer]
 *  even though this loopback has no special handling for it. */
private class LoopbackStream(private val onSend: (Frame) -> Unit = {}) : P2pStream {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private var greeted = false

    // Raw byte buffer for binary loopback (not normally used in demo mode, but
    // required so LoopbackStream satisfies the P2pStream contract in tests).
    private val byteMutex = Mutex()
    private var byteBuffer = ByteArray(0)
    private val byteReady = Channel<Unit>(Channel.CONFLATED)

    override suspend fun sendLine(line: String) {
        if (!greeted) {
            greeted = true
            inbox.send(FrameCodec.encode(Frame.Chat("hey! we're linked over iroh 🎉 (mock loopback)")))
        }
        val frame = runCatching { FrameCodec.decode(line) }.getOrNull()
        if (frame != null) onSend(frame)
        if (frame is Frame.Chat) {
            inbox.send(FrameCodec.encode(Frame.Chat("echo · ${frame.text}")))
        }
    }

    // receiveCatching(), not runCatching { receive() }: runCatching's broad Throwable catch would
    // swallow the CancellationException receive() throws when a reader coroutine is cancelled
    // mid-await, returning a plain null instead of propagating the cancellation (the classic
    // "don't catch CancellationException" anti-pattern under structured concurrency).
    // receiveCatching only folds the channel-closed case into a null result -- cancellation still
    // propagates. Without this, a coroutine cancelled while awaiting a line reads as a clean EOF,
    // so a caller that treats null as "the peer said nothing" reports a failure that never happened.
    override suspend fun receiveLine(): String? = inbox.receiveCatching().getOrNull()

    override suspend fun sendBytes(bytes: ByteArray) {
        byteMutex.withLock { byteBuffer += bytes }
        byteReady.send(Unit)
    }

    override suspend fun receiveBytes(n: Int): ByteArray? {
        while (true) {
            val result = byteMutex.withLock {
                if (byteBuffer.size >= n) {
                    val slice = byteBuffer.copyOfRange(0, n)
                    byteBuffer = byteBuffer.copyOfRange(n, byteBuffer.size)
                    slice
                } else null
            }
            if (result != null) return result
            // Wait for more bytes to arrive.
            byteReady.receive()
        }
    }

    override suspend fun close() {
        inbox.close()
    }

    /** Pushes [frame] straight into the inbox, as if the peer had sent it. */
    suspend fun injectIncoming(frame: Frame) {
        inbox.send(FrameCodec.encode(frame))
    }
}

/**
 * Serves the `azula/media/0` fetch protocol directly against an in-memory blob
 * lookup, standing in for a real peer's `MediaService.serveIncoming` -- the
 * mock/test transport is a solo loopback, with no second [FakeTransport]
 * instance to play "the sender".
 *
 * [sendLine] handles exactly one [MediaFetchRequest] (decodes, validates,
 * queues the response line via [MediaCodec]); [receiveBytes] then serves the
 * body lazily out of the blob, one chunk per call, optionally paced by
 * [chunkDelayMs]. This deliberately does NOT reuse `sendMediaBody` — that
 * helper pushes a body eagerly onto a stream's `sendBytes`, which doesn't fit
 * here: the real caller ([dev.azula.state.MediaService.startFetch]) calls
 * `sendLine` then `receiveLine` *before* ever calling `receiveBytes`, so
 * whatever `sendLine` does must finish before any byte can be requested --
 * serving the body from `receiveBytes` instead is what lets pacing/cancellation
 * land at the same per-chunk granularity a real transfer would.
 */
private class FakeMediaStream(
    private val blobLookup: (String) -> ByteArray?,
    private val onRequest: (MediaFetchRequest) -> Unit,
    private val chunkDelayMs: Long,
) : P2pStream {
    private val toClient = Channel<String>(Channel.UNLIMITED)
    private var body: ByteArray? = null
    private var pos = 0
    private var chunksServed = 0

    override suspend fun sendLine(line: String) {
        val request = runCatching { MediaCodec.decodeRequest(line) }.getOrNull() ?: return
        onRequest(request)
        val bytes = blobLookup(request.id)
        when {
            bytes == null ->
                toClient.send(MediaCodec.encodeResponse(MediaFetchResponse.Error(request.id, "unknown blob")))
            request.offset > bytes.size.toLong() ->
                toClient.send(MediaCodec.encodeResponse(MediaFetchResponse.Error(request.id, "offset beyond blob size")))
            else -> {
                toClient.send(MediaCodec.encodeResponse(MediaFetchResponse.Ok(request.id, bytes.size.toLong(), request.offset)))
                body = bytes
                pos = request.offset.toInt()
            }
        }
    }

    override suspend fun receiveLine(): String? = toClient.receive()

    override suspend fun sendBytes(bytes: ByteArray) = Unit // this protocol never sends a body back to the requester

    override suspend fun receiveBytes(n: Int): ByteArray? {
        val bytes = body ?: return null
        if (pos >= bytes.size) return null
        if (chunksServed > 0 && chunkDelayMs > 0) delay(chunkDelayMs)
        chunksServed++
        val end = minOf(pos + n, bytes.size)
        return bytes.copyOfRange(pos, end).also { pos = end }
    }

    override suspend fun close() = Unit
}
