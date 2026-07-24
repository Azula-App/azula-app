import dev.azula.core.DeviceCert
import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.net.Alpns
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.persist.AppSettings
import dev.azula.sync.DeviceRevokeBody
import dev.azula.sync.EventLogStore
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.SyncSessionOutcome
import dev.azula.sync.encodeDeviceRevokeBody
import dev.azula.sync.hexToByteArrayOrNull
import dev.azula.sync.runSyncSession
import dev.azula.sync.toHexLower
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * multi-device-identity task 5.4 (sibling discovery/dial), driven end to end through
 * [testAzulaState]'s [dev.azula.state.AzulaState] surface -- same posture as
 * `AccountSyncWiringTest.kt`: a real [FileEventLogStore] for "device A" (this identity's
 * root-holding device, exercised through the real `ConnectService.reconnectSiblings`/
 * `dialSibling`/`runSyncWith` wiring), not a mocked-out stand-in for any of it. "Device B" is
 * played by hand on the other end of an in-memory pipe, running the real
 * [dev.azula.sync.runSyncSession] directly against its own [FileEventLogStore] -- mirroring
 * `sync-real`'s own `SyncSessionTest.twoFakeDevicesConvergeFromDivergentLogs`, just reached
 * from device A's side through the full app wiring instead of a bare function call.
 *
 * Both tests share one identity: device A is root-holding (`device_pk == root_pk`, the
 * migration invariant), so it never persists a `linkedIdentityBundleJson` -- meaning its
 * "bundle baseline" revocation set is always empty, and any revocation it enforces MUST come
 * from live-scanning its own event log. That's exactly the trap this task's kdoc calls out,
 * and exactly what [revocationLearnedOnlyViaTheLogIsEnforcedOnTheNextSyncSession] proves.
 */
class SiblingSyncTest {
    private val tempDir = createTempDirectory("sibling-sync-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private class SiblingEd25519 : Ed25519 {
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

    private val ed25519 = SiblingEd25519()
    private fun fakePublicKey(secret: ByteArray): ByteArray = Sha256.hash(secret)
    private fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }

    private val deviceASecret = fixedKey(0x01)
    private val deviceAPk = fakePublicKey(deviceASecret)
    private val deviceAHex = deviceAPk.toHexLower()

    private val deviceBSecret = fixedKey(0x70)
    private val deviceBPk = fakePublicKey(deviceBSecret)
    private val deviceBHex = deviceBPk.toHexLower()

    private fun newStoreA() = FileEventLogStore(File(tempDir, "a"), ed25519) { deviceAHex }
    private fun newStoreB() = FileEventLogStore(File(tempDir, "b-${System.nanoTime()}"), ed25519) { deviceAHex }

    /** A plain in-memory bidirectional [P2pStream] pair -- mirrors `sync-real`'s own
     *  `SyncSessionTest.Pipe` and `DeviceLinkFlowTest`'s `streamPair`. */
    private class SiblingPipe {
        val aToB = Channel<String>(Channel.UNLIMITED)
        val bToA = Channel<String>(Channel.UNLIMITED)
        val a: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) { aToB.send(line) }
            override suspend fun receiveLine(): String? = runCatching { bToA.receive() }.getOrNull()
            override suspend fun sendBytes(bytes: ByteArray) = Unit
            override suspend fun receiveBytes(n: Int): ByteArray? = null
            override suspend fun close() { aToB.close() }
        }
        val b: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) { bToA.send(line) }
            override suspend fun receiveLine(): String? = runCatching { aToB.receive() }.getOrNull()
            override suspend fun sendBytes(bytes: ByteArray) = Unit
            override suspend fun receiveBytes(n: Int): ByteArray? = null
            override suspend fun close() { bToA.close() }
        }
    }

    /**
     * Device A's test transport: a fixed, [ed25519]-consistent (devicePk, secret) identity
     * (like `AccountSyncWiringTest`'s `IdentityFakeTransport`), plus one extra wire: dialing
     * [siblingTicket] on [Alpns.SYNC] hands the real `ConnectService` one end of a fresh
     * [SiblingPipe] while playing device B's side of the *same* session on the other end,
     * via a real [runSyncSession] call against [storeB] -- launched on [dialScope] (the
     * test's own `backgroundScope`) so it stays governed by `runTest`'s virtual scheduler.
     * Wrapped in [runCatching] and recorded into [bResults]: when A's side rejects the
     * hello (the revocation trap test), A's `runSyncWith` closes its end of the pipe right
     * after, before ever sending a `SyncVector` -- which is a genuine protocol violation
     * from B's already-past-the-hello perspective, so B's own [runSyncSession] throws
     * rather than returning a outcome value (see `SyncSession.kt`'s Step 2: "a hard error,
     * not a normal outcome"). That's expected and fine here -- B is a deliberately naive
     * peer for this test, not the thing under test -- so it's caught rather than left to
     * fail the test via `backgroundScope`'s exception propagation; what this test actually
     * asserts on is [EventLogStore] state, which needs no clean shutdown to be meaningful.
     */
    private class SiblingDialTransport(
        private val secret: ByteArray,
        private val fixedNodeIdHex: String,
        private val siblingTicket: String,
        private val siblingNodeIdHex: String,
        private val storeB: EventLogStore,
        private val certB: DeviceCert,
        private val ed25519: Ed25519,
        private val dialScope: CoroutineScope,
        private val delegate: FakeTransport = FakeTransport(),
    ) : IrohTransport by delegate {
        override val nodeId: String? get() = fixedNodeIdHex
        override fun rootSecretBytes(): ByteArray = secret
        override fun secretKeyBytes(): ByteArray = secret
        override fun publicKeyFor(secret: ByteArray): ByteArray = Sha256.hash(secret)

        val bResults = mutableListOf<Result<SyncSessionOutcome>>()

        override suspend fun connect(ticket: String, alpn: String): P2pStream {
            if (ticket == siblingTicket && alpn == Alpns.SYNC) {
                val pipe = SiblingPipe()
                val aNodeIdBytes = fixedNodeIdHex.hexToByteArrayOrNull()!!
                dialScope.launch {
                    val result = runCatching {
                        runSyncSession(pipe.b, certB, emptyList(), aNodeIdBytes, storeB, ed25519, 1_700_000_500L)
                    }
                    bResults.add(result)
                }
                return pipe.a
            }
            return delegate.connect(ticket, alpn)
        }

        override fun peerIdForTicket(ticket: String): String? =
            if (ticket == siblingTicket) siblingNodeIdHex else delegate.peerIdForTicket(ticket)
    }

    @Test
    fun reconnectSiblingsDialsAPersistedTicketAndConvergesWithASibling() = runTest {
        val storeA = newStoreA()
        val storeB = newStoreB()
        val certB = DeviceCertCodec.issue(0, deviceAPk, deviceBPk, 1_700_000_000L, 0L, "device-b", deviceASecret, ed25519)

        val transport = SiblingDialTransport(
            deviceASecret, deviceAHex, siblingTicket = "ticket-b", siblingNodeIdHex = deviceBHex,
            storeB = storeB, certB = certB, ed25519 = ed25519, dialScope = backgroundScope,
        )
        val settingsStore = InMemorySettingsStore(AppSettings(siblingTickets = mapOf(deviceBHex to "ticket-b")))
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = storeA, ed25519 = ed25519, settingsStore = settingsStore)

        azulaState.start()
        // runCurrent, not advanceUntilIdle: every coroutine under test here lives on
        // [backgroundScope] (the injected app scope AND [SiblingDialTransport]'s dialScope),
        // and TestScope.advanceUntilIdle stops as soon as only background-scope tasks remain
        // -- it would return without running ANY of the sibling-dial work, leaving storeB
        // untouched. runCurrent drains everything queued at the current virtual time,
        // foreground and background alike -- the same drive idiom AccountSyncWiringTest and
        // the rest of this suite already use against the very same backgroundScope wiring.
        runCurrent()

        // Device A self-certifies on start(); reconnectSiblings() (fired off onCameOnline,
        // per the same trigger reconnectSaved uses) must have auto-dialed the persisted
        // ticket and actually synced -- device B's store should now hold A's self-cert.
        assertEquals(1L, storeB.vector()[deviceAHex], "device B should have received A's self-cert via the auto-dialed sync session")
        val deviceAddOnB = storeB.readFrom(deviceAPk, 0).filter { it.knownKind == EventKind.DEVICE_ADD }
        assertTrue(deviceAddOnB.isNotEmpty(), "A's self-cert device_add must have synced to B")
    }

    @Test
    fun revocationLearnedOnlyViaTheLogIsEnforcedOnTheNextSyncSession() = runTest {
        // The trap this task's kdoc calls out: a device_revoke entry that arrived via
        // ordinary sync (so it's sitting in A's own log) but was never part of any "bundle
        // baseline" -- A is root-holding here, so it has no linkedIdentityBundleJson at all,
        // meaning the ONLY way this revocation is enforced is if the sync wiring recomputes
        // the revoked set from the event log fresh, every session, rather than a snapshot
        // taken once at startup.
        val storeA = newStoreA()
        val storeB = newStoreB()
        val certB = DeviceCertCodec.issue(0, deviceAPk, deviceBPk, 1_700_000_000L, 0L, "device-b", deviceASecret, ed25519)

        val transport = SiblingDialTransport(
            deviceASecret, deviceAHex, siblingTicket = "ticket-b", siblingNodeIdHex = deviceBHex,
            storeB = storeB, certB = certB, ed25519 = ed25519, dialScope = backgroundScope,
        )
        val settingsStore = InMemorySettingsStore(AppSettings(siblingTickets = mapOf(deviceBHex to "ticket-b")))
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = storeA, ed25519 = ed25519, settingsStore = settingsStore)
        azulaState.start()
        runCurrent() // not advanceUntilIdle -- see the sibling test above for why
        assertTrue(storeB.vector().containsKey(deviceAHex), "sanity: an unrevoked sibling syncs fine first")

        // A now learns -- via *some* prior sync round this test doesn't otherwise model --
        // that device B has been revoked. Appended directly to A's own log, exactly where a
        // synced-in device_revoke entry would land; never touching any bundle.
        val revocation = DeviceRevocationCodec.issue(deviceAPk, deviceBPk, 1_700_050_000L, deviceASecret, ed25519)
        val body = encodeDeviceRevokeBody(DeviceRevokeBody(revocation = DeviceRevocationCodec.encode(revocation)))
        storeA.appendOwn(deviceAPk, deviceASecret, EventKind.DEVICE_REVOKE.wireValue, 1_700_050_000_000L, body)
        val storeBSeqForABeforeRound2 = storeB.vector()[deviceAHex]

        // Trigger another sibling sync round -- "the next session" the spec requires this to
        // be enforced from.
        azulaState.reconnectSiblings()
        runCurrent() // not advanceUntilIdle -- see the sibling test above for why

        // The decisive, crash-safe proof: if A's side had recomputed revocations from the
        // log (correct) rather than a startup-only bundle snapshot (the trap), it refuses
        // certB's hello before ever sending a SyncVector/SyncEntries -- so B's copy of A's
        // log cannot have advanced past what round 1 already gave it. If the trap were
        // present, this round would instead behave like the first (data would flow, or B's
        // vector for A would grow to cover the just-appended device_revoke entry too).
        assertEquals(
            storeBSeqForABeforeRound2,
            storeB.vector()[deviceAHex],
            "a revoked sibling must be refused before any further vector/entry exchange -- " +
                "device B must not receive anything new from A in the second round",
        )
        // Corroborating signal: B's own naive session (which never learns of the revocation
        // itself -- it has no reason to reject A) errors out trying to read a SyncVector
        // that never arrives, because A closed the connection right after rejecting the
        // hello -- consistent with a hello-stage rejection, not a normal converged session.
        val secondRoundResult = transport.bResults.last()
        assertTrue(secondRoundResult.isFailure, "device B's side should never reach a normal converged session in round 2, got $secondRoundResult")
    }
}
