import dev.azula.core.DeviceCertCodec
import dev.azula.core.Ed25519
import dev.azula.core.LinkPayloadCodec
import dev.azula.core.Sha256
import dev.azula.core.newLinkPayload
import dev.azula.mock.FakeTransport
import dev.azula.net.Alpns
import dev.azula.net.Contact
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IdentityBundle
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.state.AzulaState
import dev.azula.state.ConnectService
import dev.azula.sync.hexToByteArrayOrNull
import dev.azula.sync.toHexLower
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * multi-device-identity task 6.1's new-device side ("Link to existing identity"),
 * driven end-to-end through [dev.azula.state.AzulaState]'s public surface against a real
 * transport -- same posture as `AccountSyncWiringTest.kt`: exercise the actual wiring
 * (`ConnectService.beginDeviceLink` -> the incoming-loop LINK routing ->
 * `DeviceLinkProtocol.runNewDeviceSession` -> `AccountSyncService.verifyGrantedCert` ->
 * `SettingsService.saveLinkedIdentity`), not a mocked-out stand-in for it.
 *
 * [LinkTestTransport] mirrors `AccountSyncWiringTest.kt`'s own `IdentityFakeTransport` (a
 * separate, top-level copy in this file -- same reasoning: bare [FakeTransport]'s `nodeId`
 * is a random `"self<hex>"` string, which isn't valid hex at all and fails
 * `hexToByteArrayOrNull()`, exactly what `ConnectService.beginDeviceLink` calls on it) plus
 * a mutable, nullable root secret so [dev.azula.net.IrohTransport.forgetRootSecret] is
 * actually observable. Kept top-level (not nested in the test class) so its `publicKeyFor`
 * override can call [fakePublicKey] without an enclosing-instance receiver, same as every
 * top-level helper in `AccountSyncWiringTest.kt`.
 *
 * The "granting device" (task 6.2) is played by hand: this test writes/reads its side of
 * an injected [P2pStream] pair directly ([FakeTransport.injectIncomingConnection] is the
 * same test hook `StrangerGateTest.kt`/others already use to synthesize an inbound
 * connection), rather than standing up a second real [ConnectService] -- task 6.2's own
 * wiring is covered separately by [dev.azula.state.DeviceLinkProtocol]'s pure tests
 * (`shared/test/DeviceLinkProtocolTest.kt`).
 */
class DeviceLinkFlowTest {

    private val ed25519 = LinkFakeEd25519()

    @Test
    fun linkedDeviceEndsWithCertAndBundleButNoRootSecret() = runTest {
        val secret = fixedKey(0x10)
        val devicePkHex = fakePublicKey(secret).toHexLower()
        val inner = FakeTransport()
        val transport = LinkTestTransport(secret, devicePkHex, inner)
        val azulaState = testAzulaState(backgroundScope, transport, ed25519 = ed25519)
        azulaState.start()
        runCurrent()
        assertNotNull(transport.rootSecretBytes(), "sanity: the race already adopted a root secret before linking begins")

        azulaState.beginDeviceLink("my-phone")
        runCurrent()

        assertEquals(ConnectService.DeviceLinkStage.WAITING_FOR_SCAN, azulaState.deviceLinkStage)
        val payload = LinkPayloadCodec.decode(azulaState.deviceLinkPayload!!)!!
        assertEquals(devicePkHex, payload.devicePk.toHexLower())

        // Simulate a root-holding device scanning the payload and dialing in.
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        val grantingDevicePkHex = fakePublicKey(fixedKey(0x50)).toHexLower()
        inner.injectIncomingConnection(IncomingConnection(remoteId = grantingDevicePkHex, alpn = Alpns.LINK, stream = newDeviceEnd))
        runCurrent()

        assertTrue(azulaState.deviceLinkWords.isNotEmpty(), "verification words must be shown before any grant is possible")
        assertEquals(ConnectService.DeviceLinkStage.VERIFYING, azulaState.deviceLinkStage)

        val hello = FrameCodec.decode(grantingDeviceEnd.receiveLine()!!) as Frame.LinkHello
        assertEquals(devicePkHex, hello.devicePk, "LinkHello must present this device's own node key")
        assertEquals("my-phone", hello.name)
        assertEquals(0, hello.roles, "the app never requests the mailbox role for itself")

        // Play the granting side by hand: issue and send back a real grant.
        val rootSecret = fixedKey(1)
        val rootPk = fakePublicKey(rootSecret)
        val newDevicePk = hello.devicePk.hexToByteArrayOrNull()!!
        val cert = DeviceCertCodec.issue(0, rootPk, newDevicePk, 1_700_000_000L, 0L, "my-phone", rootSecret, ed25519)
        val bundle = IdentityBundle(
            rootPk = rootPk.toHexLower(),
            certs = listOf(DeviceCertCodec.encode(cert)),
            revocations = emptyList(),
            contacts = listOf(Contact(rootPk = "some-contact-root", name = "Alice")),
            mailbox = null,
        )
        grantingDeviceEnd.sendLine(FrameCodec.encode(Frame.LinkGrant(DeviceCertCodec.encode(cert), bundle)))
        runCurrent()

        assertEquals(ConnectService.DeviceLinkStage.GRANTED, azulaState.deviceLinkStage)
        assertEquals(DeviceCertCodec.encode(cert), azulaState.linkedDeviceCert, "the granted cert must be persisted")
        assertNull(
            transport.rootSecretBytes(),
            "a QR-linked device must hold no root secret, even though bind() had already adopted one",
        )
    }

    @Test
    fun grantNamingADifferentDeviceIsRejectedAndPersistsNothing() = runTest {
        val secret = fixedKey(0x20)
        val devicePkHex = fakePublicKey(secret).toHexLower()
        val inner = FakeTransport()
        val transport = LinkTestTransport(secret, devicePkHex, inner)
        val azulaState = testAzulaState(backgroundScope, transport, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.beginDeviceLink("my-tablet")
        runCurrent()

        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        inner.injectIncomingConnection(
            IncomingConnection(remoteId = fakePublicKey(fixedKey(0x60)).toHexLower(), alpn = Alpns.LINK, stream = newDeviceEnd),
        )
        runCurrent()
        val hello = FrameCodec.decode(grantingDeviceEnd.receiveLine()!!) as Frame.LinkHello

        // The grant names a DIFFERENT device key than the one that dialed in -- a buggy
        // (or malicious) granting device replying with someone else's certificate.
        val rootSecret = fixedKey(2)
        val rootPk = fakePublicKey(rootSecret)
        val someoneElsesPk = fakePublicKey(fixedKey(0x61))
        val cert = DeviceCertCodec.issue(0, rootPk, someoneElsesPk, 1_700_000_000L, 0L, "not-this-device", rootSecret, ed25519)
        val bundle = IdentityBundle(rootPk = rootPk.toHexLower(), certs = emptyList(), revocations = emptyList(), contacts = emptyList())
        grantingDeviceEnd.sendLine(FrameCodec.encode(Frame.LinkGrant(DeviceCertCodec.encode(cert), bundle)))
        runCurrent()

        assertEquals(devicePkHex, hello.devicePk)
        assertEquals(ConnectService.DeviceLinkStage.FAILED, azulaState.deviceLinkStage)
        assertEquals(null, azulaState.linkedDeviceCert, "a cert naming a different device must never be persisted")
    }

    @Test
    fun cancellingBeforeAnyDialArrivesLeavesNothingPersisted() = runTest {
        val secret = fixedKey(0x30)
        val devicePkHex = fakePublicKey(secret).toHexLower()
        val transport = LinkTestTransport(secret, devicePkHex)
        val azulaState = testAzulaState(backgroundScope, transport, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.beginDeviceLink("my-tablet")
        runCurrent()
        assertEquals(ConnectService.DeviceLinkStage.WAITING_FOR_SCAN, azulaState.deviceLinkStage)

        azulaState.cancelDeviceLink()
        runCurrent()

        assertEquals(ConnectService.DeviceLinkStage.IDLE, azulaState.deviceLinkStage)
        assertEquals(null, azulaState.linkedDeviceCert)
    }

    /**
     * multi-device-identity task 6.6: the root-holding side's paste box must accept both the
     * bare `azl…` payload (the pre-6.6 behavior) AND the deeplink the QR now renders it as
     * (`https://azula.app/l/<payload>` / `azula://l?c=<payload>`) -- [ConnectService.beginGrantDial]
     * now routes the input through `AzulaLinks.parseDeviceLink` before decoding. Only the
     * dial (DIALING -> AWAITING_HELLO) is exercised here -- reaching CONFIRMING/GRANTED needs a
     * `LinkHello` from the peer, which is [DeviceLinkProtocol]'s own concern (tested elsewhere)
     * and orthogonal to whether the *input string* was unwrapped correctly.
     *
     * Each form gets its own fresh [azulaGrantDialHarness] rather than cancelling and reusing
     * one mid-flight: [FakeTransport]'s LINK-alpn `LoopbackStream.receiveLine()` catches
     * everything with `runCatching`, which -- same as any bare `runCatching` around a
     * suspending call -- also swallows the `CancellationException` a `cancelGrantLink()` mid
     * `AWAITING_HELLO` delivers, so [DeviceLinkProtocol.runGrantingSession] observes a normal
     * `NO_HELLO` instead of a cancellation and re-flips `grantStage` to `FAILED` moments after
     * `cancelGrantLink()` set it back to `IDLE`. That's a pre-existing quirk of the shared test
     * double, not something this task touches -- a fresh harness per form sidesteps it.
     */
    @Test
    fun beginGrantDialAcceptsWebDeeplinkPayload() = runTest {
        val (azulaState, payload) = azulaGrantDialHarness(0x40, 0x70)
        azulaState.beginGrantDial("https://azula.app/l/$payload")
        runCurrent()
        assertEquals(
            ConnectService.GrantStage.AWAITING_HELLO,
            azulaState.grantStage,
            "a wrapped web deeplink must decode and dial exactly like the bare payload",
        )
    }

    @Test
    fun beginGrantDialAcceptsCustomSchemeDeeplinkPayload() = runTest {
        val (azulaState, payload) = azulaGrantDialHarness(0x41, 0x71)
        azulaState.beginGrantDial("azula://l?c=$payload")
        runCurrent()
        assertEquals(
            ConnectService.GrantStage.AWAITING_HELLO,
            azulaState.grantStage,
            "a wrapped custom-scheme deeplink must decode and dial exactly like the bare payload",
        )
    }

    @Test
    fun beginGrantDialAcceptsBareDeviceLinkPayload() = runTest {
        val (azulaState, payload) = azulaGrantDialHarness(0x42, 0x72)
        azulaState.beginGrantDial(payload) // the pre-6.6 bare form must keep working too
        runCurrent()
        assertEquals(ConnectService.GrantStage.AWAITING_HELLO, azulaState.grantStage)
    }

    /** Builds a root-holding [AzulaState] (via [LinkTestTransport], seeded from [transportKeyFill])
     *  ready to grant, plus a fresh `azl…` payload for a distinct new device (seeded from
     *  [newDeviceKeyFill]) -- shared setup for the three [beginGrantDial] deeplink-form tests
     *  above, each of which needs its own untouched harness (see that test's kdoc). */
    private suspend fun TestScope.azulaGrantDialHarness(transportKeyFill: Int, newDeviceKeyFill: Int): Pair<AzulaState, String> {
        val secret = fixedKey(transportKeyFill)
        val devicePkHex = fakePublicKey(secret).toHexLower()
        val transport = LinkTestTransport(secret, devicePkHex)
        val azulaState = testAzulaState(backgroundScope, transport, ed25519 = ed25519)
        azulaState.start()
        runCurrent()
        assertNotNull(transport.rootSecretBytes(), "sanity: this device holds a root secret and can grant")

        val newDevicePk = fakePublicKey(fixedKey(newDeviceKeyFill))
        val ticket = "azula-mock-ticket-for-new-device"
        val payload = LinkPayloadCodec.encode(newLinkPayload(newDevicePk, "their-phone", ticket.encodeToByteArray()))
        return azulaState to payload
    }
}

// Duplicated test-only Ed25519 fake -- see AccountSyncWiringTest.kt's identical class for
// why every file keeps its own rather than sharing one across compilation units.
private class LinkFakeEd25519 : Ed25519 {
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

private fun fakePublicKey(secret: ByteArray): ByteArray = Sha256.hash(secret)
private fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }

/**
 * A test-only [IrohTransport] whose `nodeId` is a real, [LinkFakeEd25519]-consistent hex device
 * key (unlike bare [FakeTransport]'s random `"self<hex>"` id) -- see this file's kdoc.
 * Delegates everything else (bind/connect/incoming/the LINK-alpn accept loop) to a real
 * [FakeTransport] instance, so `ConnectService`'s existing dial/accept/receive paths behave
 * exactly as every other test using [FakeTransport] expects.
 *
 * `root` starts equal to [secret] -- modeling the exact race this task's kdoc calls out:
 * `bind()`'s upgrade-in-place migration already adopted a root secret before the user ever
 * reached the "link to an existing identity" screen -- and [forgetRootSecret] is a real,
 * observable mutation (unlike bare [FakeTransport]'s `rootless` flag, which is fixed at
 * construction).
 */
private class LinkTestTransport(
    private val secret: ByteArray,
    private val fixedNodeIdHex: String,
    private val delegate: FakeTransport = FakeTransport(),
) : IrohTransport by delegate {
    override val nodeId: String? get() = fixedNodeIdHex
    private var root: ByteArray? = secret
    override fun rootSecretBytes(): ByteArray? = root
    override fun secretKeyBytes(): ByteArray = secret
    override fun importRootSecret(bytes: ByteArray) {
        root = bytes
    }

    override fun forgetRootSecret() {
        root = null
    }

    override fun publicKeyFor(secret: ByteArray): ByteArray = fakePublicKey(secret)
}

private class QueueStream(private val inbox: Channel<String>, private val outbox: Channel<String>) : P2pStream {
    override suspend fun sendLine(line: String) {
        outbox.send(line)
    }

    override suspend fun receiveLine(): String? = inbox.receiveCatching().getOrNull()
    override suspend fun sendBytes(bytes: ByteArray) = Unit
    override suspend fun receiveBytes(n: Int): ByteArray? = null
    override suspend fun close() {
        outbox.close()
    }
}

/** Two ends of one bidirectional in-memory pipe -- no real socket involved. */
private fun streamPair(): Pair<P2pStream, P2pStream> {
    val aToB = Channel<String>(Channel.UNLIMITED)
    val bToA = Channel<String>(Channel.UNLIMITED)
    return QueueStream(inbox = bToA, outbox = aToB) to QueueStream(inbox = aToB, outbox = bToA)
}
