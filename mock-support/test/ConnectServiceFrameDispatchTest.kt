@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

import dev.azula.mock.InMemoryBlobStore
import dev.azula.model.Role
import dev.azula.model.TransferState
import dev.azula.net.Alpns
import dev.azula.net.FileEncoding
import dev.azula.net.Frame
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers [dev.azula.state.ConnectService]'s incoming-frame dispatch (`applyFrame`
 * / `applyProfile`, private) through the *public* path: an inbound connection
 * delivered via [IrohTransport.incoming], driven entirely through [AzulaState]'s
 * accessors (`conv`, `conversation`, `thinking`, `surfaces`). This exercises the
 * real dispatch code, not a re-implementation of it.
 */
class ConnectServiceFrameDispatchTest {

    @Test
    fun chatFrameAppendsMessageToTheRightConversation() = runTest {
        val pipe = PeerPipe()
        val transport = OneShotIncomingTransport("peer-chat", Alpns.CHAT, pipe)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        // A stranger on the CHAT alpn is gated: present a (legacy, invite-less)
        // Hello and accept the resulting pending request before it's wired.
        pipe.sendFrame(Frame.Hello("peer-chat"))
        runCurrent()
        azulaState.acceptInvite(azulaState.pendingInvites.single())
        runCurrent()

        pipe.sendFrame(Frame.Chat("hello there"))
        runCurrent()

        val messages = azulaState.conv("peer-chat").messages
        assertTrue(
            messages.any { it.role == Role.THEM && it.text == "hello there" },
            "expected a THEM chat message, got $messages",
        )
    }

    @Test
    fun tokenStreamAssemblesOneAssistantMessageAndTogglesThinking() = runTest {
        val pipe = PeerPipe()
        // LLM alpn so the conversation's kind is LLM: that's what gates the
        // Thinking toggle and picks the AZULA role for streamed tokens.
        val transport = OneShotIncomingTransport("peer-llm", Alpns.LLM, pipe)
        // The stranger gate closes an unknown peer's LLM/TERM stream outright --
        // there's no accept path for either (see ConnectService's incoming loop).
        // Seed a saved peer entry so "peer-llm" counts as known and wires as before.
        val peerStore = InMemoryPeerStore().also { it.save(listOf("peer-llm")) }
        val azulaState = testAzulaState(backgroundScope, transport, peerStore = peerStore)
        azulaState.start()
        runCurrent()

        pipe.sendFrame(Frame.Thinking(true))
        runCurrent()
        assertTrue(azulaState.thinking, "thinking should flip true on Frame.Thinking(true)")

        pipe.sendFrame(Frame.Token("Hel", done = false))
        runCurrent()
        pipe.sendFrame(Frame.Token("lo", done = false))
        runCurrent()
        pipe.sendFrame(Frame.Token("", done = true))
        runCurrent()
        pipe.sendFrame(Frame.Thinking(false))
        runCurrent()

        assertFalse(azulaState.thinking, "thinking should flip false on Frame.Thinking(false)")
        val assembled = azulaState.conv("peer-llm").messages.filter { it.role == Role.AZULA }
        assertEquals(1, assembled.size, "the whole token stream should assemble into exactly one message")
        assertEquals("Hello", assembled.single().text)
    }

    @Test
    fun a2uiFrameCreatesLiveSurfaceAndAttachesToMessage() = runTest {
        val pipe = PeerPipe()
        val transport = OneShotIncomingTransport("peer-a2ui", Alpns.CHAT, pipe)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        pipe.sendFrame(Frame.Hello("peer-a2ui"))
        runCurrent()
        azulaState.acceptInvite(azulaState.pendingInvites.single())
        runCurrent()

        val createSurface = buildJsonObject {
            put("version", "v0.9.1")
            putJsonObject("createSurface") { put("surfaceId", "s1") }
        }
        pipe.sendFrame(Frame.A2ui(createSurface))
        runCurrent()

        assertNotNull(azulaState.surfaces["s1"], "CreateSurface should register a live surface")
        val messages = azulaState.conv("peer-a2ui").messages
        assertTrue(
            messages.any { it.role == Role.AZULA && it.a2ui?.surfaceId == "s1" },
            "expected an AZULA message carrying the surface, got $messages",
        )
    }

    @Test
    fun profileFrameUpdatesPeerNameAndDescription() = runTest {
        val pipe = PeerPipe()
        val transport = OneShotIncomingTransport("peer-profile", Alpns.CHAT, pipe)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        pipe.sendFrame(Frame.Hello("peer-profile"))
        runCurrent()
        azulaState.acceptInvite(azulaState.pendingInvites.single())
        runCurrent()

        pipe.sendFrame(Frame.Profile(name = "Bob", description = "a friend"))
        runCurrent()

        assertEquals("Bob", azulaState.conversation("peer-profile").name)
        assertEquals("a friend", azulaState.conv("peer-profile").peerDescription)
    }

    /**
     * Covers Phase 0's R2: `receiveLoop`'s `Frame.FileBegin` branch (the legacy
     * inline file protocol LLM/MCP peers use) now routes through
     * [dev.azula.state.MediaService.ingestLocalBlob], so an inbound legacy file
     * gets a real `blobId` in the [dev.azula.persist.BlobStore] instead of
     * persisting as inline base64.
     */
    @Test
    fun legacyFileBeginIngestsIntoTheBlobStoreWithARealBlobId() = runTest {
        val pipe = PeerPipe()
        val transport = OneShotIncomingTransport("peer-legacy-file", Alpns.LLM, pipe)
        // Stranger gate: LLM has no accept path, so a known peer must be seeded
        // (same as tokenStreamAssemblesOneAssistantMessageAndTogglesThinking).
        val peerStore = InMemoryPeerStore().also { it.save(listOf("peer-legacy-file")) }
        val blobStore = InMemoryBlobStore()
        val azulaState = testAzulaState(backgroundScope, transport, peerStore = peerStore, blobStore = blobStore)
        azulaState.start()
        runCurrent()

        val bytes = ByteArray(256) { it.toByte() }
        pipe.sendFrame(
            Frame.FileBegin(
                id = "f1", name = "clip.bin", mime = "application/octet-stream",
                size = bytes.size.toLong(), encoding = FileEncoding.BASE64.wire, caption = "here you go",
            ),
        )
        runCurrent()
        pipe.sendFrame(Frame.FileChunk(id = "f1", seq = 0, data = Base64.encode(bytes)))
        runCurrent()
        pipe.sendFrame(Frame.FileEnd(id = "f1"))
        runCurrent()

        val message = azulaState.conv("peer-legacy-file").messages.last { it.attachments.isNotEmpty() }
        assertEquals(Role.THEM, message.role)
        val attachment = message.attachments.single()
        assertEquals(TransferState.Complete, attachment.state)
        assertTrue(attachment.blobId != null, "a legacy inbound file should ingest through the BlobStore, not persist inline bytes")
        assertTrue(bytes.contentEquals(blobStore.bytesFor(attachment.blobId!!)), "the blob's bytes should round-trip exactly")
    }
}

/**
 * Emits exactly one inbound connection (a peer dialing us) on [incoming], then
 * completes -- enough to wire [pipe] into a conversation via the real
 * [ConnectService.start] -> `wireStream` -> `wireConv` -> `receiveLoop` path.
 * Outbound [connect] isn't used by these tests.
 */
private class OneShotIncomingTransport(
    private val remoteId: String,
    private val alpn: String,
    private val pipe: PeerPipe,
) : IrohTransport {
    override var endpointId: String? = "test-endpoint"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = ""
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override suspend fun connect(ticket: String, alpn: String): P2pStream = error("not used: inbound-only test")
    // Identity, so a peerStore entry equal to the target remoteId (see the
    // tokenStream test's seeded InMemoryPeerStore) resolves back to it --
    // needed to make LLM/TERM's stranger, which the gate never lets through
    // any accept path, a "known" peer instead.
    override fun peerIdForTicket(ticket: String): String = ticket
    override fun incoming(): Flow<IncomingConnection> = flow { emit(IncomingConnection(remoteId, alpn, pipe.clientStream)) }
    override suspend fun shutdown() = Unit
}
