package dev.azula.sync

import dev.azula.core.DeviceCertCodec
import dev.azula.core.EventKind
import dev.azula.core.LogEntry
import dev.azula.core.LogEntryCodec
import dev.azula.core.ZERO_PREV_HASH
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.P2pStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Protocol tests for [runSyncSession]/[sendLoop]/[recvLoop] against
 * azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
 * "Sync Runs Only Between Same-Root Certified Devices" -- the same session shape (and
 * the same test breakdown) as azula-cli's Rust `sync::run_session` test suite, so the
 * two protocols stay provably in lockstep (see
 * `.worktrees/azula-cli--multi-device-identity/src/sync.rs`).
 */
@OptIn(ExperimentalEncodingApi::class)
class SyncSessionTest {
    private val tempDir = createTempDirectory("sync-session-test").toFile()
    private val ed25519 = FakeEd25519()

    private val rootSecret = fixedKey(0x00)
    private val rootPk = fakePublicKey(rootSecret)
    private val foreignRootSecret = fixedKey(0x40)
    private val foreignRootPk = fakePublicKey(foreignRootSecret)
    private val deviceASecret = fixedKey(0x80)
    private val deviceAPk = fakePublicKey(deviceASecret)
    private val deviceBSecret = fixedKey(0xC0)
    private val deviceBPk = fakePublicKey(deviceBSecret)

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    // multi-device-identity task 4.6: every store in this file represents one
    // root's own log set, so a fixed rootPkHex supplier (this test class's own
    // rootPk) is enough -- each test already isolates via a distinct [name]
    // subdirectory, same as before this task's root-scoping existed.
    private fun newStore(name: String) = FileEventLogStore(java.io.File(tempDir, name), ed25519) { rootPk.toHexLower() }

    private fun cert(rootSecret: ByteArray, rootPk: ByteArray, devicePk: ByteArray, name: String) =
        DeviceCertCodec.issue(0, rootPk, devicePk, 1_700_000_000L, 0L, name, rootSecret, ed25519)

    /** Builds (but does not store) a single entry -- used only where the "broken chain"
     *  test needs to hand-construct a bogus `prev_hash`; every other test uses [appendChain]. */
    private fun entry(secret: ByteArray, pk: ByteArray, seq: Long, prevHash: ByteArray) =
        LogEntryCodec.sign(
            EventKind.MESSAGE_OUT.wireValue, pk, seq, seq, seq * 1000, prevHash,
            "{}".encodeToByteArray(), secret, ed25519,
        )

    private fun base64Of(entry: LogEntry): String = Base64.encode(LogEntryCodec.encode(entry))

    private suspend fun appendChain(store: EventLogStore, secret: ByteArray, pk: ByteArray, count: Int) {
        repeat(count) {
            store.appendOwn(pk, secret, EventKind.MESSAGE_OUT.wireValue, 1000L, "{}".encodeToByteArray())
        }
    }

    /**
     * A connected pair of [P2pStream]s over two unbounded in-memory channels (one per
     * direction) -- mirrors `network-api/test/FileTransferTest.kt`'s `Pipe`, minus the
     * raw-byte half (the sync protocol never uses `sendBytes`/`receiveBytes`). [aToB]/
     * [bToA] are exposed directly so a test can peek (`tryReceive`) at exactly what one
     * side wrote, e.g. to assert nothing follows a rejected hello.
     */
    private class Pipe {
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

    // --- Cert rejection paths (each must close before any vector exchange) -----------

    @Test
    fun helloRejectsACertForADifferentRootBeforeAnyVectorExchange() = runTest {
        val myCert = cert(rootSecret, rootPk, deviceAPk, "victim")
        val attackerCert = cert(foreignRootSecret, foreignRootPk, deviceBPk, "attacker")
        val pipe = Pipe()
        pipe.b.sendLine(FrameCodec.encode(Frame.SyncHello(cert = DeviceCertCodec.encode(attackerCert))))

        val outcome = runSyncSession(
            pipe.a, myCert, emptyList(), deviceBPk, newStore("wrong_root"), ed25519, 1_700_000_500L,
        )
        val rejected = outcome as? SyncSessionOutcome.HelloRejected ?: fail("expected HelloRejected, got $outcome")
        assertTrue(rejected.reason.contains("root"), rejected.reason)

        assertTrue(FrameCodec.decode(pipe.aToB.tryReceive().getOrThrow()) is Frame.SyncHello)
        assertTrue(pipe.aToB.tryReceive().isFailure, "nothing should follow the hello")
    }

    @Test
    fun helloRejectsARevokedDeviceBeforeAnyVectorExchange() = runTest {
        val myCert = cert(rootSecret, rootPk, deviceAPk, "victim")
        val siblingCert = cert(rootSecret, rootPk, deviceBPk, "sibling") // same root, but revoked below
        val pipe = Pipe()
        pipe.b.sendLine(FrameCodec.encode(Frame.SyncHello(cert = DeviceCertCodec.encode(siblingCert))))

        val outcome = runSyncSession(
            pipe.a, myCert, listOf(deviceBPk), deviceBPk, newStore("revoked_device"), ed25519, 1_700_000_500L,
        )
        val rejected = outcome as? SyncSessionOutcome.HelloRejected ?: fail("expected HelloRejected, got $outcome")
        assertTrue(rejected.reason.contains("revok"), rejected.reason)

        assertTrue(FrameCodec.decode(pipe.aToB.tryReceive().getOrThrow()) is Frame.SyncHello)
        assertTrue(pipe.aToB.tryReceive().isFailure)
    }

    @Test
    fun helloRejectsATransportNodeIdMismatchBeforeAnyVectorExchange() = runTest {
        val myCert = cert(rootSecret, rootPk, deviceAPk, "victim")
        val siblingCert = cert(rootSecret, rootPk, deviceBPk, "sibling")
        val pipe = Pipe()
        pipe.b.sendLine(FrameCodec.encode(Frame.SyncHello(cert = DeviceCertCodec.encode(siblingCert))))

        // Transport peer id does NOT match siblingCert.devicePk.
        val outcome = runSyncSession(
            pipe.a, myCert, emptyList(), foreignRootPk, newStore("node_id_mismatch"), ed25519, 1_700_000_500L,
        )
        val rejected = outcome as? SyncSessionOutcome.HelloRejected ?: fail("expected HelloRejected, got $outcome")
        assertTrue(rejected.reason.contains("transport") || rejected.reason.contains("node id"), rejected.reason)

        assertTrue(FrameCodec.decode(pipe.aToB.tryReceive().getOrThrow()) is Frame.SyncHello)
        assertTrue(pipe.aToB.tryReceive().isFailure)
    }

    @Test
    fun helloRejectsAMalformedCertBeforeAnyVectorExchange() = runTest {
        val myCert = cert(rootSecret, rootPk, deviceAPk, "victim")
        val pipe = Pipe()
        pipe.b.sendLine(FrameCodec.encode(Frame.SyncHello(cert = "not-a-real-cert")))

        val outcome = runSyncSession(
            pipe.a, myCert, emptyList(), deviceBPk, newStore("malformed_cert"), ed25519, 1_700_000_500L,
        )
        val rejected = outcome as? SyncSessionOutcome.HelloRejected ?: fail("expected HelloRejected, got $outcome")
        assertTrue(rejected.reason.contains("certificate") || rejected.reason.contains("malformed"), rejected.reason)

        assertTrue(FrameCodec.decode(pipe.aToB.tryReceive().getOrThrow()) is Frame.SyncHello)
        assertTrue(pipe.aToB.tryReceive().isFailure)
    }

    // --- Vector exchange / batching / live push (sendLoop in isolation) ---------------

    @Test
    fun sendLoopTransfersOnlyTheGap() = runTest {
        val store = newStore("gap_only")
        appendChain(store, deviceASecret, deviceAPk, 5)

        val myVector = store.vector()
        val peerVector = mapOf(deviceAPk.toHexLower() to 3L)

        val pipe = Pipe()
        val job = launch { sendLoop(pipe.a, store, myVector, peerVector) }

        val entriesFrame = FrameCodec.decode(pipe.b.receiveLine()!!)
        val entries = (entriesFrame as? Frame.SyncEntries)?.entries ?: fail("expected SyncEntries, got $entriesFrame")
        assertEquals(2, entries.size, "only the missing seq 4..=5 should be sent")
        val decoded = entries.map { LogEntryCodec.decode(Base64.decode(it))!! }
        assertEquals(4L, decoded[0].seq)
        assertEquals(5L, decoded[1].seq)

        val ackFrame = FrameCodec.decode(pipe.b.receiveLine()!!)
        assertTrue(ackFrame is Frame.SyncAck)

        job.cancel()
    }

    @Test
    fun sendLoopBatchesMoreThan64EntriesIntoMultipleAscendingFrames() = runTest {
        val store = newStore("batching")
        appendChain(store, deviceASecret, deviceAPk, 130)

        val myVector = store.vector()
        val pipe = Pipe()
        val job = launch { sendLoop(pipe.a, store, myVector, emptyMap()) }

        val allEntries = mutableListOf<LogEntry>()
        var frameCount = 0
        loop@ while (true) {
            when (val frame = FrameCodec.decode(pipe.b.receiveLine()!!)) {
                is Frame.SyncEntries -> {
                    assertTrue(frame.entries.size <= MAX_ENTRIES_PER_FRAME, "frame exceeded the cap: ${frame.entries.size}")
                    frameCount++
                    frame.entries.forEach { allEntries.add(LogEntryCodec.decode(Base64.decode(it))!!) }
                }
                is Frame.SyncAck -> break@loop
                else -> fail("unexpected frame: $frame")
            }
        }
        assertEquals(3, frameCount, "130 entries at <=64/frame should take 3 frames")
        assertEquals(130, allEntries.size)
        allEntries.forEachIndexed { i, e -> assertEquals((i + 1).toLong(), e.seq, "entries must arrive in ascending seq order") }

        job.cancel()
    }

    @Test
    fun livePushDeliversNewEntriesWithoutANewVectorExchange() = runTest {
        val store = newStore("live_push")
        val pipe = Pipe()
        val job = launch { sendLoop(pipe.a, store, store.vector(), emptyMap()) }

        val first = FrameCodec.decode(pipe.b.receiveLine()!!)
        assertTrue(first is Frame.SyncAck, "expected the initial ack, got $first")

        // Append while the session is "live" -- no new vector round trip should
        // happen; the very next frame must be the pushed entry.
        appendChain(store, deviceASecret, deviceAPk, 1)

        val second = FrameCodec.decode(pipe.b.receiveLine()!!)
        val entries = (second as? Frame.SyncEntries)?.entries
            ?: fail("expected a live-pushed SyncEntries frame with no new vector, got $second")
        assertEquals(1, entries.size)
        val e = LogEntryCodec.decode(Base64.decode(entries[0]))!!
        assertEquals(1L, e.seq)
        assertTrue(e.authorDevicePk.contentEquals(deviceAPk))

        job.cancel()
    }

    @Test
    fun bootstrapEmptyVectorReceivesEverything() = runTest {
        val store = newStore("bootstrap")
        appendChain(store, deviceASecret, deviceAPk, 3)
        appendChain(store, deviceBSecret, deviceBPk, 2)

        val pipe = Pipe()
        val job = launch { sendLoop(pipe.a, store, store.vector(), emptyMap()) }

        val allEntries = mutableListOf<LogEntry>()
        loop@ while (true) {
            when (val frame = FrameCodec.decode(pipe.b.receiveLine()!!)) {
                is Frame.SyncEntries -> frame.entries.forEach { allEntries.add(LogEntryCodec.decode(Base64.decode(it))!!) }
                is Frame.SyncAck -> break@loop
                else -> fail("unexpected frame: $frame")
            }
        }
        assertEquals(5, allEntries.size, "a brand new device's empty vector should receive every entry")

        job.cancel()
    }

    // --- recvLoop: broken chain rejection + unknown frame resilience ------------------

    @Test
    fun recvLoopRejectsBrokenChainAndDoesNotAdvanceCursor() = runTest {
        val store = newStore("broken_chain")
        val e1 = entry(deviceASecret, deviceAPk, 1, ZERO_PREV_HASH)
        assertTrue(store.insert(e1))

        val bogusHash = LogEntryCodec.chainHash(e1).copyOf()
        bogusHash[0] = (bogusHash[0].toInt() xor 0xFF).toByte()
        val e2Broken = entry(deviceASecret, deviceAPk, 2, bogusHash)
        val e2Valid = entry(deviceASecret, deviceAPk, 2, LogEntryCodec.chainHash(e1))

        val pipe = Pipe()
        pipe.b.sendLine(FrameCodec.encode(Frame.SyncEntries(entries = listOf(base64Of(e2Broken)))))
        pipe.b.sendLine(FrameCodec.encode(Frame.SyncEntries(entries = listOf(base64Of(e2Valid)))))
        pipe.b.close() // EOF after these two frames

        recvLoop(pipe.a, store)

        assertEquals(2L, store.vector()[deviceAPk.toHexLower()], "the valid e2 should still land after the broken one is rejected")
        val entries = store.readFrom(deviceAPk, 0)
        assertEquals(2, entries.size)
        assertEquals(e2Valid, entries[1], "the stored seq-2 entry must be the valid one, not the broken one")
    }

    @Test
    fun recvLoopIgnoresAnUnrecognizedFrameTypeAndKeepsProcessing() = runTest {
        val store = newStore("unknown_frame")
        val e1 = entry(deviceASecret, deviceAPk, 1, ZERO_PREV_HASH)

        val pipe = Pipe()
        // A newer sibling's frame kind this build doesn't recognize.
        pipe.b.sendLine("""{"type":"sync_resync_request","foo":"bar"}""")
        pipe.b.sendLine(FrameCodec.encode(Frame.SyncEntries(entries = listOf(base64Of(e1)))))
        pipe.b.close()

        recvLoop(pipe.a, store)

        assertEquals(1L, store.vector()[deviceAPk.toHexLower()])
    }

    // --- Two fake devices converge from divergent starting logs -----------------------

    @Test
    fun twoFakeDevicesConvergeFromDivergentLogs() = runTest {
        val certA = cert(rootSecret, rootPk, deviceAPk, "a")
        val certB = cert(rootSecret, rootPk, deviceBPk, "b")

        val storeA = newStore("converge_a")
        val storeB = newStore("converge_b")

        // Divergent starting logs: A authored 3 entries B has never seen; B authored 2
        // entries A has never seen. Neither has synced before.
        appendChain(storeA, deviceASecret, deviceAPk, 3)
        appendChain(storeB, deviceBSecret, deviceBPk, 2)

        val pipe = Pipe()
        val jobA = launch {
            runSyncSession(pipe.a, certA, emptyList(), deviceBPk, storeA, ed25519, 1_700_000_500L)
        }
        val jobB = launch {
            runSyncSession(pipe.b, certB, emptyList(), deviceAPk, storeB, ed25519, 1_700_000_500L)
        }

        // No delays anywhere in the protocol path -- once both sides are blocked purely
        // on each other's channels (the live-push phase), the scheduler is idle and both
        // stores have fully converged.
        advanceUntilIdle()

        val aHex = deviceAPk.toHexLower()
        val bHex = deviceBPk.toHexLower()
        assertEquals(3L, storeA.vector()[aHex])
        assertEquals(2L, storeA.vector()[bHex])
        assertEquals(3L, storeB.vector()[aHex])
        assertEquals(2L, storeB.vector()[bHex])

        // Full state equality, not just the vector -- every entry for every device must
        // be byte-identical on both sides.
        assertEquals(storeA.readFrom(deviceAPk, 0), storeB.readFrom(deviceAPk, 0))
        assertEquals(storeA.readFrom(deviceBPk, 0), storeB.readFrom(deviceBPk, 0))

        jobA.cancel()
        jobB.cancel()
    }
}
