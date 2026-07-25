import dev.azula.a2ui.JsonPointer
import dev.azula.core.DeviceCert
import dev.azula.core.DeviceCertCodec
import dev.azula.core.Ed25519
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.persist.AppSettings
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.hexToByteArrayOrNull
import dev.azula.sync.toHexLower
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * cli-multi-session-relay phase 6.3: "A2UI Snapshot Replay on Reconnect", driven end to end
 * through [testAzulaState]'s real [dev.azula.state.AzulaState]/[dev.azula.state.ConnectService]/
 * [dev.azula.state.AccountSyncService] wiring -- same posture as `SiblingSyncTest.kt` (a real
 * [FileEventLogStore] for "device A", the phone under test; "device B", the relay, played by
 * hand on the other end of an in-memory pipe). Unlike `SiblingSyncTest`, device B's side here
 * is NOT [dev.azula.sync.runSyncSession] (that function never emits `sync_a2ui` -- only a real
 * relay does), so this test hand-drives B's protocol steps directly: hello, vector, the A2UI
 * replay frame(s) BEFORE B's own `SyncAck` (see `SyncSession.kt`'s `onSyncA2ui` kdoc for why
 * that ordering matters to a bounded, `livePush = false` catch-up), then the ack.
 */
class A2uiSnapshotReplayTest {
    private val tempDir = createTempDirectory("a2ui-snapshot-replay-test").toFile()

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

    private val deviceASecret = fixedKey(0x01)
    private val deviceAPk = fakePublicKey(deviceASecret)
    private val deviceAHex = deviceAPk.toHexLower()

    private val deviceBSecret = fixedKey(0x70)
    private val deviceBPk = fakePublicKey(deviceBSecret)
    private val deviceBHex = deviceBPk.toHexLower()

    // A session public key this phone has never connected to directly -- only ever
    // learned about via the relay's A2UI snapshot replay (spec: "the surface appears
    // in the session's conversation in its latest state", and task 6.3's "ensure the
    // conversation exists ... if not, ensureConv").
    private val sessionHex = "deadbeef00112233445566778899aabbccddeeff0011223344556677889900aa"

    /** Mirrors `SiblingSyncTest`'s `SiblingPipe`. */
    private class Pipe {
        val aToB = Channel<String>(Channel.UNLIMITED)
        val bToA = Channel<String>(Channel.UNLIMITED)
        val a: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) { aToB.send(line) }
            override suspend fun receiveLine(): String? = bToA.receiveCatching().getOrNull()
            override suspend fun sendBytes(bytes: ByteArray) = Unit
            override suspend fun receiveBytes(n: Int): ByteArray? = null
            override suspend fun close() { aToB.close() }
        }
        val b: P2pStream = object : P2pStream {
            override suspend fun sendLine(line: String) { bToA.send(line) }
            override suspend fun receiveLine(): String? = aToB.receiveCatching().getOrNull()
            override suspend fun sendBytes(bytes: ByteArray) = Unit
            override suspend fun receiveBytes(n: Int): ByteArray? = null
            override suspend fun close() { bToA.close() }
        }
    }

    /** Hand-drives the relay's ("device B") side of one `azula/sync/0` session: hello,
     *  vector, then [messages] as one `sync_a2ui` frame for [sessionHex] BEFORE this
     *  side's own `SyncAck` -- exactly the ordering a real relay must use so a bounded
     *  (`livePush = false`) catch-up on A's side still observes it (see this file's
     *  class kdoc). Drains whatever A sends afterward (its own catch-up gap, if any)
     *  until A's `SyncAck` arrives. */
    private suspend fun playRelaySide(stream: P2pStream, certB: DeviceCert, sessionHex: String, messages: List<JsonObject>) {
        val helloLine = stream.receiveLine()!!
        check(FrameCodec.decode(helloLine) is Frame.SyncHello) { "expected SyncHello, got $helloLine" }
        stream.sendLine(FrameCodec.encode(Frame.SyncHello(cert = DeviceCertCodec.encode(certB))))

        val vectorLine = stream.receiveLine()!!
        check(FrameCodec.decode(vectorLine) is Frame.SyncVector) { "expected SyncVector, got $vectorLine" }
        stream.sendLine(FrameCodec.encode(Frame.SyncVector(vector = emptyMap())))

        stream.sendLine(FrameCodec.encode(Frame.SyncA2ui(conversation = sessionHex, messages = messages)))
        stream.sendLine(FrameCodec.encode(Frame.SyncAck(vector = emptyMap())))

        while (true) {
            val line = stream.receiveLine() ?: break
            if (FrameCodec.decode(line) is Frame.SyncAck) break
        }
    }

    /** Mirrors `SiblingSyncTest`'s `SiblingDialTransport`, minus the [dev.azula.sync.runSyncSession]
     *  call on B's side -- [playRelaySide] replaces it. */
    private class RelayReplayTransport(
        private val secret: ByteArray,
        private val fixedNodeIdHex: String,
        private val relayTicket: String,
        private val relayNodeIdHex: String,
        private val play: suspend (P2pStream) -> Unit,
        private val dialScope: CoroutineScope,
        private val delegate: FakeTransport = FakeTransport(),
    ) : IrohTransport by delegate {
        override val nodeId: String? get() = fixedNodeIdHex
        override fun rootSecretBytes(): ByteArray = secret
        override fun secretKeyBytes(): ByteArray = secret
        override fun publicKeyFor(secret: ByteArray): ByteArray = Sha256.hash(secret)

        override suspend fun connect(ticket: String, alpn: String): P2pStream {
            if (ticket == relayTicket && alpn == Alpns.SYNC) {
                val pipe = Pipe()
                dialScope.launch { play(pipe.b) }
                return pipe.a
            }
            return delegate.connect(ticket, alpn)
        }

        override fun peerIdForTicket(ticket: String): String? =
            if (ticket == relayTicket) relayNodeIdHex else delegate.peerIdForTicket(ticket)
    }

    @Test
    fun replayedSnapshotProducesALiveSurfaceAndAutoCreatesTheConversation() = runTest {
        val certB = DeviceCertCodec.issue(0, deviceAPk, deviceBPk, 1_700_000_000L, 0L, "relay", deviceASecret, ed25519)
        val createMsg = buildJsonObject {
            put("createSurface", buildJsonObject { put("surfaceId", JsonPrimitive("s1")); put("catalogId", JsonPrimitive("cards")) })
        }
        val updateComponentsMsg = buildJsonObject {
            put(
                "updateComponents",
                buildJsonObject {
                    put("surfaceId", JsonPrimitive("s1"))
                    put("components", buildJsonArray { add(buildJsonObject { put("id", JsonPrimitive("root")); put("type", JsonPrimitive("text")) }) })
                },
            )
        }
        val updateDataModelMsg = buildJsonObject {
            put("updateDataModel", buildJsonObject { put("surfaceId", JsonPrimitive("s1")); put("path", JsonPrimitive("/count")); put("value", JsonPrimitive(42)) })
        }

        val store = FileEventLogStore(File(tempDir, "a"), ed25519) { deviceAHex }
        val transport = RelayReplayTransport(
            deviceASecret, deviceAHex, relayTicket = "ticket-relay", relayNodeIdHex = deviceBHex,
            play = { stream -> playRelaySide(stream, certB, sessionHex, listOf(createMsg, updateComponentsMsg, updateDataModelMsg)) },
            dialScope = backgroundScope,
        )
        val settingsStore = InMemorySettingsStore(AppSettings(siblingTickets = mapOf(deviceBHex to "ticket-relay")))
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = store, ed25519 = ed25519, settingsStore = settingsStore)

        azulaState.start()
        runCurrent()

        assertTrue(
            azulaState.conversations.any { it.id == sessionHex },
            "a device that only heard about this session via a relayed A2UI snapshot must auto-create its conversation",
        )
        val surface = azulaState.surfaces["s1"]
        assertNotNull(surface, "the replayed createSurface+updateComponents+updateDataModel sequence must produce a live surface")
        assertEquals("cards", surface.catalogId)
        assertNotNull(surface.component("root"), "updateComponents must have populated the surface's component tree")
        assertEquals(JsonPrimitive(42), JsonPointer.get(surface.dataModel, "/count"), "updateDataModel must have written into the surface's data model")

        val cardMessage = azulaState.conv(sessionHex).messages.lastOrNull { it.a2ui?.surfaceId == "s1" }
        assertNotNull(cardMessage, "the surface must also be represented in the conversation's message list, same as a live a2ui frame")
    }

    @Test
    fun replayingASurfaceAlreadyLiveLeavesTheLatestStateNotAMergeOfStaleAndFresh() = runTest {
        // Idempotent-safe replay (spec: "A2UI Snapshot Replay on Reconnect"): a full
        // createSurface+updateComponents+updateDataModel sequence replayed for an
        // already-live surface must leave the latest state, never a merge -- exactly
        // what a live createSurface already does (it replaces the surface outright).
        val certB = DeviceCertCodec.issue(0, deviceAPk, deviceBPk, 1_700_000_000L, 0L, "relay", deviceASecret, ed25519)
        val staleComponent = buildJsonObject { put("id", JsonPrimitive("root")); put("text", JsonPrimitive("stale")) }
        val freshComponent = buildJsonObject { put("id", JsonPrimitive("root")); put("text", JsonPrimitive("fresh")) }
        fun snapshot(component: JsonObject) = listOf(
            buildJsonObject { put("createSurface", buildJsonObject { put("surfaceId", JsonPrimitive("s1")) }) },
            buildJsonObject {
                put("updateComponents", buildJsonObject { put("surfaceId", JsonPrimitive("s1")); put("components", buildJsonArray { add(component) }) })
            },
        )

        val store = FileEventLogStore(File(tempDir, "a2"), ed25519) { deviceAHex }
        var callCount = 0
        val transport = RelayReplayTransport(
            deviceASecret, deviceAHex, relayTicket = "ticket-relay", relayNodeIdHex = deviceBHex,
            play = { stream ->
                val messages = if (callCount++ == 0) snapshot(staleComponent) else snapshot(freshComponent)
                playRelaySide(stream, certB, sessionHex, messages)
            },
            dialScope = backgroundScope,
        )
        val settingsStore = InMemorySettingsStore(AppSettings(siblingTickets = mapOf(deviceBHex to "ticket-relay")))
        val azulaState = testAzulaState(backgroundScope, transport, eventLogStore = store, ed25519 = ed25519, settingsStore = settingsStore)

        azulaState.start()
        runCurrent()
        assertEquals(JsonPrimitive("stale"), azulaState.surfaces.getValue("s1").component("root")?.get("text"))

        // A second reconnect (a second bounded catch-up round) replays a fresher snapshot.
        azulaState.reconnectSiblings()
        runCurrent()

        assertEquals(
            JsonPrimitive("fresh"),
            azulaState.surfaces.getValue("s1").component("root")?.get("text"),
            "the latest replay must win outright, not merge with the stale one",
        )
    }
}
