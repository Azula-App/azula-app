import dev.azula.core.DeviceCertCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.RecoveryPhrase
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.model.Role
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.IrohTransport
import dev.azula.sync.AccountSyncFold
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.allEntries
import dev.azula.sync.decodeDeviceAddBody
import dev.azula.sync.decodeMessageInBody
import dev.azula.sync.decodeMessageOutBody
import dev.azula.sync.decodeReadMarkerBody
import dev.azula.sync.toHexLower
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * multi-device-identity tasks 4.4/4.5, exercised end-to-end through [testAzulaState]'s
 * public [dev.azula.state.AzulaState] surface (same style as `ChatServiceTest.kt`), against
 * a *real* [FileEventLogStore] on a temp directory -- not a fake/mock event log -- so these
 * tests prove the actual wire-up: `ChatService.send`/`FrameDispatcher`'s `Frame.Chat`
 * handling/`AzulaState`'s conversation-open all reach a real [dev.azula.sync.EventLogStore],
 * and [AccountSyncFold] over what actually landed on disk reproduces the same message
 * history after the derived UI/`MessageStore` state is wiped (the "Projection rebuild"
 * scenario, task 4.4's centerpiece).
 *
 * [FakeTransport] itself is *not* used as the transport here: its `nodeId` is a random
 * `"self<hex>"` string, not real Ed25519 key material paired with anything, so there is no
 * consistent (devicePk, secret) identity to sign event-log entries with. [IdentityFakeTransport]
 * wraps a real [FakeTransport] (for its loopback/incoming behavior) but fixes `nodeId`/
 * `rootSecretBytes` to a [FakeEd25519]-paired pair, mirroring the `sync-api`/`sync-real` test
 * modules' own `fixedKey`/`fakePublicKey` convention (each module keeps its own tiny copy --
 * see those modules' `SyncTestFixtures.kt` kdoc for why).
 */
class AccountSyncWiringTest {
    private val tempDir = createTempDirectory("account-sync-wiring-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private val ed25519 = FakeEd25519()
    private val secret = ByteArray(32) { (it + 7).toByte() }
    private val devicePk = fakePublicKey(secret)
    private val devicePkHex = devicePk.toHexLower()

    // multi-device-identity task 4.6: device_pk == root_pk in this test's fixed
    // identity (same simplification AccountSyncService.deviceIdentity's kdoc
    // documents), so devicePkHex doubles as the root pk supplier here.
    private fun newStore(): FileEventLogStore =
        FileEventLogStore(File(tempDir, "log-${System.nanoTime()}"), ed25519) { devicePkHex }

    private fun newTransport(): IdentityFakeTransport = IdentityFakeTransport(secret, devicePkHex)

    @Test
    fun messageOutIsLoggedEvenWhenNothingIsThereToDeliverTo() = runTest {
        val store = newStore()
        val azulaState = testAzulaState(backgroundScope, newTransport(), eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id
        // Simulate "no device of the recipient is reachable": drop the live stream this
        // conversation would otherwise send over, so ChatService.send has nothing to
        // deliver to at all.
        azulaState.conv(convId).stream = null

        azulaState.updateDraft(convId, "are you there?")
        azulaState.send(convId)
        runCurrent()

        val entries = store.allEntries()
        val out = entries.filter { it.knownKind == EventKind.MESSAGE_OUT }
            .mapNotNull { decodeMessageOutBody(it.body) }
        assertTrue(
            out.any { it.conversation == convId && it.text == "are you there?" },
            "message_out must be logged regardless of delivery outcome, got $out",
        )
    }

    @Test
    fun legacyPeerConversationLogsBothDirections() = runTest {
        val store = newStore()
        val azulaState = testAzulaState(backgroundScope, newTransport(), eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        // FakeTransport's CHAT loopback has no certificate exchange at all -- exactly a
        // legacy (no-cert) peer, per the spec's "Legacy Peers Are Unaffected".
        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        azulaState.updateDraft(convId, "hello there")
        azulaState.send(convId)
        // One drain covers the whole chain: send appends message_out, ships the frame,
        // the loopback echoes it, and the echo arrives back through the real receive
        // loop and appends message_in -- see ChatServiceTest's identical single-drain
        // assertion for the same loopback.
        runCurrent()

        val entries = store.allEntries()
        val outgoing = entries.filter { it.knownKind == EventKind.MESSAGE_OUT }.mapNotNull { decodeMessageOutBody(it.body) }
        val incoming = entries.filter { it.knownKind == EventKind.MESSAGE_IN }.mapNotNull { decodeMessageInBody(it.body) }
        assertTrue(outgoing.any { it.conversation == convId && it.text == "hello there" }, "expected message_out, got $outgoing")
        assertTrue(
            incoming.any { it.conversation == convId && it.text.contains("hello there") },
            "expected the echoed reply logged as message_in, got $incoming",
        )
        // The legacy peer has no certified device key -- fromDevicePk falls back to the
        // peer's node id, the same value already used as the conversation key.
        assertTrue(incoming.all { it.fromDevicePk == convId })
    }

    @Test
    fun dedupesRetryDeliveredTwiceByConversationAndId() = runTest {
        val store = newStore()
        val inner = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, IdentityFakeTransport(secret, devicePkHex, inner), eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        // Two arrivals of the identical (conversation, id) -- an ambiguous-failure retry
        // that actually succeeded twice (spec: "Ambiguous failure then retry to another
        // device" -- here, simplified to the same device seeing the same id twice).
        inner.injectIncoming("any-ticket", Frame.Chat("hi", id = "retry-1"), Alpns.CHAT)
        runCurrent()
        inner.injectIncoming("any-ticket", Frame.Chat("hi", id = "retry-1"), Alpns.CHAT)
        runCurrent()

        val entries = store.allEntries()
        val rawIncoming = entries.filter { it.knownKind == EventKind.MESSAGE_IN }.mapNotNull { decodeMessageInBody(it.body) }
        assertEquals(2, rawIncoming.count { it.id == "retry-1" }, "both deliveries are logged by the receiving device")

        val derived = AccountSyncFold.fold(entries, devicePk, ed25519, nowEpochSeconds = 2_000_000_000L)
        assertEquals(
            1,
            derived.conversations.getValue(convId).count { it.id == "retry-1" },
            "but the fold shows the message exactly once",
        )
    }

    @Test
    fun readStateIsDerivedSolelyFromTheReadMarker() = runTest {
        val store = newStore()
        val azulaState = testAzulaState(backgroundScope, newTransport(), eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        azulaState.updateDraft(convId, "first")
        azulaState.send(convId)
        runCurrent()
        azulaState.updateDraft(convId, "second")
        azulaState.send(convId)
        runCurrent()

        // Opening the conversation is this codebase's only "the user read this" signal --
        // there is no other unread/seen mechanism anywhere to shadow (verified: grepping
        // the pre-existing codebase for "unread"/"read marker"/"seen" turned up nothing).
        azulaState.selectDesktop(convId)
        runCurrent()

        val entries = store.allEntries()
        val readMarkers = entries.filter { it.knownKind == EventKind.READ_MARKER }.mapNotNull { decodeReadMarkerBody(it.body) }
            .filter { it.conversation == convId }
        assertEquals(1, readMarkers.size, "exactly one read_marker entry for this conversation")

        val derived = AccountSyncFold.fold(entries, devicePk, ed25519, nowEpochSeconds = 2_000_000_000L)
        val highestMessageLamport = derived.conversations.getValue(convId).maxOf { it.lamport }
        assertEquals(
            highestMessageLamport,
            derived.readMarkers.getValue(convId),
            "read_marker covers exactly up through the highest lamport this device has seen",
        )
        // Unread count derives from messages ordered after the marker -- there are none.
        assertEquals(0, derived.conversations.getValue(convId).count { it.lamport > derived.readMarkers.getValue(convId) })
    }

    @Test
    fun rebuildFromTheLogReproducesTheSameMessageHistory() = runTest {
        val store = newStore()
        val inner = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, IdentityFakeTransport(secret, devicePkHex, inner), eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        azulaState.updateDraft(convId, "hey")
        azulaState.send(convId)
        runCurrent()
        azulaState.updateDraft(convId, "how's it going")
        azulaState.send(convId)
        runCurrent()

        val before = azulaState.conv(convId).messages.map { it.role to it.text }
        assertTrue(before.size >= 4, "expected at least 2 sent + 2 echoed messages, got $before")

        // "A device's derived stores are deleted but its logs remain": wipe the live
        // conversation state (what PersistenceCoordinator.save would have written to
        // MessageStore) without touching the event log at all.
        azulaState.conv(convId).messages.clear()
        assertTrue(azulaState.conv(convId).messages.isEmpty())

        azulaState.rebuildMessageHistoryFromLog()
        runCurrent()

        val after = azulaState.conv(convId).messages.map { it.role to it.text }
        assertEquals(before, after, "replaying the logs must reproduce the same derived message history")
    }

    @Test
    fun startSelfCertifiesAndAppendsExactlyOneDeviceAddEntry() = runTest {
        // multi-device-identity task 3.2's TODO, closed: the upgrade-in-place migration
        // path (device_pk == root_pk) must durably log a device_add for its self-cert,
        // not just prove one can be computed.
        val store = newStore()
        val azulaState = testAzulaState(backgroundScope, newTransport(), eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val entries = store.allEntries()
        val deviceAdds = entries.filter { it.knownKind == EventKind.DEVICE_ADD }
        assertEquals(1, deviceAdds.size, "the migration path must self-certify exactly once, got $deviceAdds")
        val cert = DeviceCertCodec.decode(decodeDeviceAddBody(deviceAdds.single().body)!!.cert)!!
        assertEquals(devicePkHex, cert.devicePk.toHexLower())
        assertEquals(devicePkHex, cert.rootPk.toHexLower(), "device_pk == root_pk on the migration path")
    }

    @Test
    fun importRecoveryPhraseSelfCertifiesUnderTheNewlyImportedRoot() = runTest {
        // multi-device-identity task 3.4: enrolling into a phrase-supplied identity keeps
        // this device's own node key as device_pk, but the certificate's root_pk must
        // reflect the *imported* root secret's own public key -- which differs from
        // device_pk whenever the phrase belongs to some other identity.
        val store = newStore()
        val transport = newTransport()
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = store, ed25519 = ed25519)
        azulaState.start()
        runCurrent()

        val newRootSecret = ByteArray(32) { (it * 3 + 5).toByte() }
        val phrase = RecoveryPhrase.encode(newRootSecret)
        val ok = azulaState.importRecoveryPhrase(phrase)
        runCurrent()
        assertTrue(ok, "a well-formed phrase must be accepted")

        val certs = store.allEntries().filter { it.knownKind == EventKind.DEVICE_ADD }
            .mapNotNull { DeviceCertCodec.decode(decodeDeviceAddBody(it.body)!!.cert) }
        val enrollmentCert = certs.find { !it.rootPk.contentEquals(it.devicePk) }
        assertNotNull(enrollmentCert, "expected a device_add whose root differs from this device's own key, got $certs")
        assertEquals(devicePkHex, enrollmentCert.devicePk.toHexLower(), "device_pk must stay this device's own node key")
        assertEquals(
            fakePublicKey(newRootSecret).toHexLower(),
            enrollmentCert.rootPk.toHexLower(),
            "root_pk must be the imported phrase's own derived public key",
        )
    }

    @Test
    fun preExistingHistorySurvivesTheOneTimeBackfill() = runTest {
        val store = newStore()
        val messageStore = InMemoryMessageStore(
            seed = mapOf(
                "legacy-peer" to dev.azula.persist.ConversationDto(
                    name = "legacy-peer",
                    kind = dev.azula.model.ConversationKind.PEER,
                    glyph = "L",
                    messages = listOf(
                        dev.azula.persist.MessageDto(id = "p0", role = Role.THEM, text = "hi from before the upgrade"),
                        dev.azula.persist.MessageDto(id = "u0", role = Role.ME, text = "hey yourself"),
                    ),
                ),
            ),
        )
        val azulaState = testAzulaState(
            backgroundScope,
            newTransport(),
            messageStore = messageStore,
            eventLogStore = store,
            ed25519 = ed25519,
        )
        azulaState.start()
        runCurrent()

        // Restored from the pre-existing MessageStore (not the log -- it was empty).
        val restored = azulaState.conv("legacy-peer").messages.map { it.role to it.text }
        assertEquals(listOf(Role.THEM to "hi from before the upgrade", Role.ME to "hey yourself"), restored)

        // And it's not just sitting outside the log forever: the one-time backfill folded
        // it in, so a rebuild from the log alone reproduces the same history.
        val entries = store.allEntries()
        assertTrue(entries.isNotEmpty(), "backfill should have appended log entries for the pre-existing history")
        val derived = AccountSyncFold.fold(entries, devicePk, ed25519, nowEpochSeconds = 2_000_000_000L)
        val derivedTexts = derived.conversations.getValue("legacy-peer").map { (if (it.outgoing) Role.ME else Role.THEM) to it.text }
        assertEquals(restored, derivedTexts)
    }
}

/** Same construction as `sync-api`/`sync-real`'s own test-only `FakeEd25519` (duplicated
 *  rather than shared -- see those modules' `SyncTestFixtures.kt` kdoc for why: each
 *  module's test compilation is isolated). A "public key" is `SHA-256(secret)`. */
private class FakeEd25519 : Ed25519 {
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

/**
 * A test-only [IrohTransport] whose `nodeId`/`rootSecretBytes` are a real, [FakeEd25519]-
 * consistent (devicePk, secret) pair -- unlike [FakeTransport]'s own random `"self<hex>"`
 * id, which pairs with nothing. Delegates everything else (bind/connect/incoming/the CHAT
 * loopback) to a real [FakeTransport] instance, so `ConnectService`'s existing dial/accept/
 * receive paths behave exactly as every other test using [FakeTransport] expects; only the
 * identity surface `AccountSyncService`'s signing depends on is overridden.
 *
 * [root] is mutable (starts equal to [secret], the migration invariant) so
 * `importRootSecret`/`rootSecretBytes` actually model multi-device-identity task 3.4's
 * enrollment path (a device adopting a *different* root while keeping its own node key) --
 * unlike the fixed, always-equal-to-[secret] version this class used to be, which only
 * ever exercised the migration/self-certify case (tasks 4.4/4.5). [publicKeyFor] is
 * overridden to the same [FakeEd25519]-paired derivation [fakePublicKey] provides, so
 * `ConnectService.importRecoveryPhrase`'s self-cert step can derive a real (fake) root
 * public key for an imported secret, not just this transport's own fixed one.
 */
private class IdentityFakeTransport(
    private val secret: ByteArray,
    private val fixedNodeIdHex: String,
    private val delegate: FakeTransport = FakeTransport(),
) : IrohTransport by delegate {
    override val nodeId: String? get() = fixedNodeIdHex
    private var root: ByteArray = secret
    override fun rootSecretBytes(): ByteArray = root
    override fun secretKeyBytes(): ByteArray = secret
    override fun importRootSecret(bytes: ByteArray) { root = bytes }
    override fun publicKeyFor(secret: ByteArray): ByteArray = fakePublicKey(secret)
}
