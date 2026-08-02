import dev.azula.core.DeviceCertCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.ROLE_MAILBOX
import dev.azula.core.ROLE_SESSION
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.model.ConversationKind
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.persist.AppSettings
import dev.azula.persist.InvitationsBook
import dev.azula.persist.InvitationsStore
import dev.azula.sync.DeviceAddBody
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.encodeDeviceAddBody
import dev.azula.sync.toHexLower
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * cli-multi-session-relay phase 5.1/5.3: the session-cert accept gate
 * ([dev.azula.state.ConnectService.admitSessionCert], via
 * [dev.azula.state.AccountSyncService.checkSessionCert]) and the `relay_hint` frame it
 * sends on admission over the LLM ALPN. Driven end to end through [testAzulaState]'s
 * public [dev.azula.state.AzulaState] surface, against a real [FileEventLogStore] (so
 * [dev.azula.state.AccountSyncService.checkSessionCert]'s signature/expiry verification
 * is genuine, not a stub) -- same posture as `CertKnownPeerTest.kt`/`AccountSyncWiringTest.kt`.
 *
 * All crypto fixtures here are nested (not top-level) `private` declarations for the
 * same reason `CertKnownPeerTest`'s are: they must not collide with sibling test
 * classes' own same-purpose fixtures at the JVM binary-name level.
 */
class SessionCertGateTest {
    private val tempDir = createTempDirectory("session-cert-gate-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private class TestEd25519 : Ed25519 {
        override fun sign(secret: ByteArray, data: ByteArray): ByteArray {
            require(secret.size == 32) { "fake secret must be 32 bytes" }
            return secret + Sha256.hash(secret + data)
        }

        override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != 64) return false
            val secret = signature.copyOfRange(0, 32)
            val mac = signature.copyOfRange(32, 64)
            if (!Sha256.hash(secret).contentEquals(publicKey)) return false
            return Sha256.hash(secret + data).contentEquals(mac)
        }
    }

    private val ed25519 = TestEd25519()
    private fun fakePublicKey(secret: ByteArray): ByteArray = Sha256.hash(secret)
    private fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }

    // "Us" -- the phone accepting sessions through the gate. device_pk == root_pk here
    // (the ordinary root-holding-phone case), same simplification every other test in
    // this style uses.
    private val mySecret = fixedKey(0x11)
    private val myPk = fakePublicKey(mySecret)
    private val myHex = myPk.toHexLower()

    // "The machine" -- a paired CLI machine identity (device-linking's machine root ==
    // its own bridge/endpoint key).
    private val machineRootSecret = fixedKey(0x70)
    private val machineRootPk = fakePublicKey(machineRootSecret)
    private val machineRootHex = machineRootPk.toHexLower()

    // A session process's own ephemeral key, certified by the machine root.
    private val sessionSecret = fixedKey(0x90)
    private val sessionPk = fakePublicKey(sessionSecret)
    private val sessionHex = sessionPk.toHexLower()

    private val now = 1_700_000_000L

    // DeviceCertCodec.verify checks expires_at against the REAL wall clock
    // (epochSeconds()), not a test-injected "now" -- so a fixed future offset from the
    // fixed [now] anchor above (itself already years in the past by the time this
    // runs) would read as already-expired. Default to expires_at=0 ("never", per
    // DeviceCertCodec's convention -- same as CertKnownPeerTest's certs) so a "valid"
    // cert in these tests is genuinely unexpired regardless of when the suite runs;
    // only the expiry-specific tests below override it to something in the past.
    private fun sessionCert(
        expiresAt: Long = 0L,
        roles: Int = ROLE_SESSION,
        devicePk: ByteArray = sessionPk,
    ): String {
        val c = DeviceCertCodec.issue(roles, machineRootPk, devicePk, now, expiresAt, "cli", machineRootSecret, ed25519)
        return DeviceCertCodec.encode(c)
    }

    private fun newStore() = FileEventLogStore(File(tempDir, "log-${System.nanoTime()}"), ed25519) { myHex }

    /** Seeds a mailbox-role sibling device_add on [store], authored by "us"
     *  ([myPk]/[mySecret]) exactly like [dev.azula.state.AccountSyncService.issueDeviceLinkGrant]
     *  does in production -- the entry a real device-link grant to a relay/mailbox
     *  sibling would have appended. Returns the mailbox device's own pk hex. */
    private suspend fun seedMailboxSibling(store: FileEventLogStore, mailboxDevicePk: ByteArray): String {
        val cert = DeviceCertCodec.issue(ROLE_MAILBOX, myPk, mailboxDevicePk, now, 0L, "relay", mySecret, ed25519)
        val body = encodeDeviceAddBody(DeviceAddBody(cert = DeviceCertCodec.encode(cert)))
        store.appendOwn(myPk, mySecret, EventKind.DEVICE_ADD.wireValue, now * 1000, body)
        return mailboxDevicePk.toHexLower()
    }

    /** A [InvitationsStore] fake seeded with an arbitrary contacts list -- same as
     *  `CertKnownPeerTest`'s. */
    private class SeededInvitationsStore(private val contacts: List<String>) : InvitationsStore {
        override fun load(): InvitationsBook = InvitationsBook(contacts = contacts)
        override fun save(book: InvitationsBook) = Unit
    }

    /** Mirrors `CertKnownPeerTest`'s `IdentityTransport`: a fixed, [TestEd25519]-consistent
     *  (devicePk, secret) identity delegating everything else to a real [FakeTransport]. */
    private class IdentityTransport(
        private val secret: ByteArray,
        private val fixedEndpointIdHex: String,
        private val delegate: FakeTransport = FakeTransport(),
    ) : IrohTransport by delegate {
        override val endpointId: String? get() = fixedEndpointIdHex
        override fun rootSecretBytes(): ByteArray = secret
        override fun secretKeyBytes(): ByteArray = secret
        override fun publicKeyFor(secret: ByteArray): ByteArray = Sha256.hash(secret)

        private val incomingConnections = Channel<IncomingConnection>(Channel.UNLIMITED)
        override fun incoming(): Flow<IncomingConnection> = incomingConnections.receiveAsFlow()
        suspend fun emit(conn: IncomingConnection) = incomingConnections.send(conn)
    }

    /** A one-shot inbound stream a test drives as "the session process", ALSO recording
     *  every frame the app sends back via [sendLine] (needed for the relay_hint tests
     *  below) -- combines `CertKnownPeerTest`'s `InboundPipe` with
     *  `TermAttachTest`'s `RecordingStream` capture, since this suite needs both. */
    private class InboundPipe {
        private val toClient = Channel<String>(Channel.UNLIMITED)
        val sentFrames = mutableListOf<Frame>()
        var closed = false
        val stream: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) {
                runCatching { FrameCodec.decode(line) }.getOrNull()?.let { sentFrames.add(it) }
            }
            override suspend fun receiveLine(): String = toClient.receive()
            override suspend fun sendBytes(bytes: ByteArray) = Unit
            override suspend fun receiveBytes(n: Int): ByteArray? = null
            override suspend fun close() { closed = true }
        }
        suspend fun sendFrame(frame: Frame) = toClient.send(FrameCodec.encode(frame))
    }

    // ---- 5.1: session-cert admission ----

    @Test
    fun validSessionCertAdmittedOnLlmAlpnKeyedBySessionPk() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert()))
        runCurrent()

        assertTrue(azulaState.conversations.any { it.id == sessionHex }, "a valid session cert must be admitted, keyed by its own device_pk")
        assertTrue(azulaState.pendingInvites.isEmpty(), "session admission needs no pending prompt")
        assertFalse(azulaState.conversations.any { it.id == machineRootHex }, "a session conversation must never be keyed by the machine's root")
    }

    @Test
    fun expiredSessionCertIsRefused() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert(expiresAt = now - 10)))
        runCurrent()

        assertTrue(azulaState.conversations.isEmpty(), "an expired session cert must not be admitted")
        assertTrue(pipe.closed, "LLM has no other accept path -- the connection must be closed")
    }

    @Test
    fun certMissingTheSessionFlagIsRefused() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, pipe.stream))
        runCurrent()
        // An ordinary (non-session) device cert for the same root/device -- roles=0.
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert(roles = 0)))
        runCurrent()

        assertTrue(azulaState.conversations.isEmpty(), "a cert without the session role flag must not be admitted via this gate")
        assertTrue(pipe.closed)
    }

    @Test
    fun unknownMachineRootIsRefused() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        // No invitations seeded -- the machine root is not a paired contact.
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert()))
        runCurrent()

        assertTrue(azulaState.conversations.isEmpty(), "a session cert for an unpaired machine must not be admitted")
        assertTrue(pipe.closed)
    }

    @Test
    fun transportBindingMismatchIsRefused() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        // The connection's transport peer id is a DIFFERENT device than the cert's own
        // device_pk -- a replay over the wrong connection.
        val otherSecret = fixedKey(0xA0)
        val otherHex = fakePublicKey(otherSecret).toHexLower()
        val pipe = InboundPipe()
        transport.emit(IncomingConnection(otherHex, Alpns.LLM, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(otherHex, cert = sessionCert())) // cert names sessionPk, not otherHex
        runCurrent()

        assertTrue(azulaState.conversations.isEmpty(), "device_pk must equal the transport peer id")
        assertTrue(pipe.closed)
    }

    @Test
    fun chatFallsThroughToTheInviteGateWhenNoSessionCertIsPresented() = runTest {
        // "CHAT fallthrough to invite path intact" -- a stranger with no cert at all on
        // CHAT still lands in the ordinary pending inbox, exactly as before this change.
        val transport = IdentityTransport(mySecret, myHex)
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.CHAT, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex)) // no cert, no invite -- a legacy stranger
        runCurrent()

        val pending = azulaState.pendingInvites.singleOrNull()
        assertEquals(sessionHex, pending?.endpointId, "a CHAT stranger with no session cert must still reach the invite gate")
        assertTrue(pending?.unverified == true)
        assertTrue(azulaState.conversations.isEmpty())
    }

    @Test
    fun expiredSessionCertOnChatFallsThroughToTheInviteGateRatherThanBeingHardRejected() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.CHAT, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert(expiresAt = now - 10)))
        runCurrent()

        assertTrue(azulaState.conversations.isEmpty(), "an expired session cert must not be admitted")
        val pending = azulaState.pendingInvites.singleOrNull()
        assertEquals(sessionHex, pending?.endpointId, "a session-cert miss on CHAT falls through to the ordinary invite gate, not a hard close")
    }

    @Test
    fun reconnectWithAnExpiredCertReVerifiesRatherThanTriviallyWiringAnAlreadyKnownConversation() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        // First connection: admitted with a valid (never-expiring) cert.
        val firstPipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, firstPipe.stream))
        runCurrent()
        firstPipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert()))
        runCurrent()
        assertTrue(azulaState.conversations.any { it.id == sessionHex }, "sanity: the first connection is admitted")

        // Second connection (a redial -- e.g. the process restarted holding a cert
        // that has since expired) on the SAME endpoint id: the session-identity spec
        // requires this to be RE-VALIDATED, never short-circuited through the
        // ordinary "conversation already exists" known-peer fast path the way an
        // accepted contact's reconnect is.
        val secondPipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, secondPipe.stream))
        runCurrent()
        secondPipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert(expiresAt = now - 10)))
        runCurrent()

        assertTrue(
            secondPipe.closed,
            "an expired cert on a reconnect must be refused (LLM has no other accept path), not trivially wired through as an already-known peer",
        )
    }

    @Test
    fun sessionAdmittedOverLlmIsNamedFromASubsequentProfileFrame() = runTest {
        // "Admitted sessions get their auto-created conversation titled from the
        // session's Profile frame (existing path)" -- verifying it for an LLM-kind
        // conversation, not just the CHAT/PEER path other tests already cover.
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert()))
        runCurrent()
        pipe.sendFrame(Frame.Profile(name = "Claude Code — my-project"))
        runCurrent()

        val conv = azulaState.conversation(sessionHex)
        assertEquals("Claude Code — my-project", conv.name, "an admitted session's conversation must be named from its Profile frame, same as any other conversation")
        assertEquals(ConversationKind.LLM, conv.kind)
    }

    // ---- 5.3: relay_hint ----

    @Test
    fun relayHintSentOnSessionAdmissionWhenAMailboxTicketIsKnown() = runTest {
        val store = newStore()
        val mailboxSecret = fixedKey(0xB0)
        val mailboxPk = fakePublicKey(mailboxSecret)
        val mailboxHex = seedMailboxSibling(store, mailboxPk)
        val settingsStore = InMemorySettingsStore(AppSettings(siblingTickets = mapOf(mailboxHex to "mailbox-ticket-xyz")))

        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(
            backgroundScope, transport,
            invitationsStore = invitations, eventLogStore = store, ed25519 = ed25519,
            settingsStore = settingsStore,
        )
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert()))
        runCurrent()

        assertTrue(azulaState.conversations.any { it.id == sessionHex }, "sanity: the session must actually be admitted")
        val hint = pipe.sentFrames.filterIsInstance<Frame.RelayHint>().singleOrNull()
        assertEquals("mailbox-ticket-xyz", hint?.ticket, "a known mailbox ticket must be sent as relay_hint on LLM admission")
    }

    @Test
    fun noRelayHintSentWhenNoMailboxTicketIsKnown() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(machineRootHex))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(sessionHex, Alpns.LLM, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(sessionHex, cert = sessionCert()))
        runCurrent()

        assertTrue(azulaState.conversations.any { it.id == sessionHex })
        assertTrue(pipe.sentFrames.none { it is Frame.RelayHint }, "no mailbox ticket known -- nothing should be sent")
    }
}
