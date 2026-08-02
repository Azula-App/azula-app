import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.persist.InvitationsBook
import dev.azula.persist.InvitationsStore
import dev.azula.sync.DeviceRevokeBody
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.encodeDeviceRevokeBody
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
 * multi-device-identity tasks 7.2 ("Extend known-peer checks... per the invitations
 * delta") and 7.3 (conversation keying by root pk, merging a legacy endpoint-id
 * conversation the moment its peer first presents a valid certificate). Driven end to
 * end through [testAzulaState]'s public [dev.azula.state.AzulaState] surface, against
 * a real [FileEventLogStore] (so [dev.azula.state.AccountSyncService.checkPresentedCert]'s
 * revocation scan is genuine, not a stub) -- same posture as `AccountSyncWiringTest.kt`/
 * `SiblingSyncTest.kt`.
 *
 * All crypto fixtures here are nested (not top-level) `private` declarations
 * specifically so they can't collide with `AccountSyncWiringTest`'s/`SiblingSyncTest`'s
 * own same-purpose fixtures at the JVM binary-name level -- see this change's working
 * notes on that exact trap.
 */
class CertKnownPeerTest {
    private val tempDir = createTempDirectory("cert-known-peer-test").toFile()

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

    // "Us" -- the accepting identity these tests exercise ConnectService's gate as.
    private val mySecret = fixedKey(0x10)
    private val myPk = fakePublicKey(mySecret)
    private val myHex = myPk.toHexLower()

    // "The contact" -- a root identity with (at least) two certified devices.
    private val contactRootSecret = fixedKey(0x40)
    private val contactRootPk = fakePublicKey(contactRootSecret)
    private val phoneSecret = fixedKey(0x50)
    private val phonePk = fakePublicKey(phoneSecret)
    private val phoneHex = phonePk.toHexLower()
    private val laptopSecret = fixedKey(0x60)
    private val laptopPk = fakePublicKey(laptopSecret)
    private val laptopHex = laptopPk.toHexLower()

    private fun cert(devicePk: ByteArray, name: String = "device"): String {
        val c = DeviceCertCodec.issue(0, contactRootPk, devicePk, 1_700_000_000L, 0L, name, contactRootSecret, ed25519)
        return DeviceCertCodec.encode(c)
    }

    private fun newStore() = FileEventLogStore(File(tempDir, "log-${System.nanoTime()}"), ed25519) { myHex }

    /** A [InvitationsStore] fake seeded with an arbitrary contacts list -- unlike
     *  [dev.azula.mock.MockInvitationsStore] (fixed to "mockterm"/"buildbot"), the
     *  contacts list here is whatever each test needs (empty, or a root pk hex). */
    private class SeededInvitationsStore(private val contacts: List<String>) : InvitationsStore {
        override fun load(): InvitationsBook = InvitationsBook(contacts = contacts)
        override fun save(book: InvitationsBook) = Unit
    }

    /** Mirrors `AccountSyncWiringTest`'s `IdentityFakeTransport`: a fixed,
     *  [TestEd25519]-consistent (devicePk, secret) identity delegating everything
     *  else to a real [FakeTransport] -- except [incoming], overridden here so a test
     *  can inject an arbitrary inbound connection via [emit] without racing
     *  [FakeTransport]'s own fixed "mockterm" demo connection. */
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

    /** A one-shot inbound stream a test drives as "the peer" -- mirrors
     *  `mock-support/test/TestSupport.kt`'s `PeerPipe` (a different, non-`@jvm`
     *  compilation unit, so it can't be reused directly here). */
    private class InboundPipe {
        private val toClient = Channel<String>(Channel.UNLIMITED)
        val stream: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) = Unit
            override suspend fun receiveLine(): String = toClient.receive()
            override suspend fun sendBytes(bytes: ByteArray) = Unit
            override suspend fun receiveBytes(n: Int): ByteArray? = null
            override suspend fun close() = Unit
        }

        suspend fun sendFrame(frame: Frame) = toClient.send(FrameCodec.encode(frame))
    }

    // ---- task 7.2: known-peer checks and accept flows ----

    @Test
    fun certBearingPeerWhoseRootIsAContactIsKnownAndSkipsTheInviteGate() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(contactRootPk.toHexLower()))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(phoneHex, Alpns.CHAT, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(phoneHex, cert = cert(phonePk, "phone")))
        runCurrent()

        val rootConvId = contactRootPk.toHexLower()
        assertTrue(azulaState.conversations.any { it.id == rootConvId }, "a device whose valid cert's root is already a contact must be known -- wired straight into the root-keyed conversation")
        assertTrue(azulaState.pendingInvites.isEmpty(), "known-by-root bypasses the pending inbox entirely -- no invite needed")
    }

    @Test
    fun revokedDeviceDoesNotRideTheRootMatchAndFallsToTheStrangerPath() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(contactRootPk.toHexLower()))
        val store = newStore()
        // A verified revocation for the phone device, sitting in OUR OWN log -- exactly
        // where one learned via sibling sync would land (see
        // AccountSyncService.currentRevokedDevicePks's kdoc: this must be a fresh scan,
        // never a cached snapshot, and this test's whole point is that it IS one).
        val revocation = DeviceRevocationCodec.issue(contactRootPk, phonePk, 1_700_050_000L, contactRootSecret, ed25519)
        val body = encodeDeviceRevokeBody(DeviceRevokeBody(revocation = DeviceRevocationCodec.encode(revocation)))
        store.appendOwn(myPk, mySecret, EventKind.DEVICE_REVOKE.wireValue, 1_700_050_000_000L, body)

        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(phoneHex, Alpns.CHAT, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(phoneHex, cert = cert(phonePk, "phone")))
        runCurrent()

        val rootConvId = contactRootPk.toHexLower()
        assertFalse(azulaState.conversations.any { it.id == rootConvId }, "a revoked device must not ride the root match into the contact's conversation")
        val pending = azulaState.pendingInvites.singleOrNull()
        assertEquals(phoneHex, pending?.endpointId, "falls all the way through to the ordinary stranger path")
        assertTrue(pending?.unverified == true, "no invite was presented -- admitted only via allowLegacyInbound, same as any stranger")
    }

    @Test
    fun invalidCertFallsToTheStrangerPathInsteadOfBeingRejected() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val invitations = SeededInvitationsStore(listOf(contactRootPk.toHexLower()))
        val azulaState = testAzulaState(backgroundScope, transport, invitationsStore = invitations, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val goodCert = DeviceCertCodec.decode(cert(phonePk, "phone"))!!
        val badCert = goodCert.copy(signature = goodCert.signature.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(phoneHex, Alpns.CHAT, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(phoneHex, cert = DeviceCertCodec.encode(badCert)))
        runCurrent()

        val rootConvId = contactRootPk.toHexLower()
        assertFalse(azulaState.conversations.any { it.id == rootConvId }, "an invalid cert must grant nothing -- even though its (unverifiable) root would otherwise be a known contact")
        val pending = azulaState.pendingInvites.singleOrNull()
        assertEquals(phoneHex, pending?.endpointId, "a cert that fails verification is treated as absent, NOT a hard rejection -- it still falls to the (unverified) stranger path exactly like a legacy peer")
        assertTrue(pending?.unverified == true)
    }

    @Test
    fun legacyNoCertPeerIsUnaffectedByTheCertCheck() = runTest {
        val transport = IdentityTransport(mySecret, myHex)
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val pipe = InboundPipe()
        transport.emit(IncomingConnection(phoneHex, Alpns.CHAT, pipe.stream))
        runCurrent()
        pipe.sendFrame(Frame.Hello(phoneHex)) // no cert, no invite -- exactly a legacy peer
        runCurrent()

        val pending = azulaState.pendingInvites.singleOrNull()
        assertEquals(phoneHex, pending?.endpointId, "a legacy peer must be gated exactly as before this change")
        assertTrue(pending?.unverified == true)
    }

    // ---- task 7.3: conversation keying by root pk ----

    @Test
    fun endpointIdConversationMergesIntoRootKeyedOneWhenThePeerFirstPresentsAValidCertPreservingHistory() = runTest {
        val inner = FakeTransport()
        val transport = IdentityTransport(mySecret, myHex, inner)
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        // FakeTransport.peerIdForTicket only resolves a ticket of exactly this shape
        // back to the endpoint id embedded in it.
        val ticket = "azula-mock-$phoneHex"
        azulaState.connectPeer(ticket)
        runCurrent()
        val legacyConvId = azulaState.conversations.single().id
        assertEquals(phoneHex, legacyConvId, "starts out endpoint-id-keyed, exactly as before this change")

        azulaState.updateDraft(legacyConvId, "hi there")
        azulaState.send(legacyConvId)
        runCurrent() // the LoopbackStream echoes a reply back over the same stream

        val historyBeforeMerge = azulaState.conv(legacyConvId).messages.map { it.role to it.text }
        assertTrue(historyBeforeMerge.isNotEmpty(), "sanity: there is history to preserve")

        // The peer's client upgrades: its next Hello on this same connection carries a
        // valid certificate for the first time.
        inner.injectIncoming(ticket, Frame.Hello(phoneHex, cert = cert(phonePk, "phone")), alpn = Alpns.CHAT)
        runCurrent()

        val rootConvId = contactRootPk.toHexLower()
        assertFalse(azulaState.conversations.any { it.id == phoneHex }, "the old endpoint-id-keyed conversation must be gone, not left stranded")
        assertTrue(azulaState.conversations.any { it.id == rootConvId }, "history must now live under the root-pk key")
        assertEquals(historyBeforeMerge, azulaState.conv(rootConvId).messages.map { it.role to it.text }, "history is preserved across the merge")
    }

    @Test
    fun secondDevicesEndpointIdConversationMergesIntoAnAlreadyEstablishedRootKeyedConversation() = runTest {
        val inner = FakeTransport()
        val transport = IdentityTransport(mySecret, myHex, inner)
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = newStore(), ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        // Phone connects fresh, already presenting a valid certificate for a root we
        // don't know yet -- a "certified stranger" -- admitted via the ordinary
        // (allowLegacyInbound) pending flow; accepting it lands straight in a
        // root-pk-keyed conversation, no legacy phase for this device at all.
        val phonePipe = InboundPipe()
        transport.emit(IncomingConnection(phoneHex, Alpns.CHAT, phonePipe.stream))
        runCurrent()
        phonePipe.sendFrame(Frame.Hello(phoneHex, cert = cert(phonePk, "phone")))
        runCurrent()
        val req = azulaState.pendingInvites.single()
        azulaState.acceptInvite(req)
        runCurrent()

        val rootConvId = contactRootPk.toHexLower()
        assertTrue(azulaState.conversations.any { it.id == rootConvId }, "accepting a certified stranger must pin the root and key its conversation by it")

        // Laptop, meanwhile, already has an established LEGACY (endpoint-id-keyed)
        // conversation from before it ever presented a certificate.
        val laptopTicket = "azula-mock-$laptopHex"
        azulaState.connectPeer(laptopTicket)
        runCurrent()
        assertTrue(azulaState.conversations.any { it.id == laptopHex })
        azulaState.updateDraft(laptopHex, "laptop says hi")
        azulaState.send(laptopHex)
        runCurrent()
        val laptopHistoryBeforeMerge = azulaState.conv(laptopHex).messages.map { it.role to it.text }
        assertTrue(laptopHistoryBeforeMerge.isNotEmpty())

        // Laptop's client upgrades: its next Hello carries a valid certificate for the
        // SAME root as the phone -- must merge into the already-established root-keyed
        // conversation, not create a second one.
        inner.injectIncoming(laptopTicket, Frame.Hello(laptopHex, cert = cert(laptopPk, "laptop")), alpn = Alpns.CHAT)
        runCurrent()

        assertFalse(azulaState.conversations.any { it.id == laptopHex }, "laptop's legacy endpoint-id conversation must be gone")
        assertEquals(1, azulaState.conversations.count { it.id == rootConvId }, "still exactly one conversation for this contact -- not stranded as a second one")
        val merged = azulaState.conv(rootConvId).messages.map { it.role to it.text }
        laptopHistoryBeforeMerge.forEach { assertTrue(it in merged, "laptop's prior history must survive the merge, missing $it") }
    }
}
