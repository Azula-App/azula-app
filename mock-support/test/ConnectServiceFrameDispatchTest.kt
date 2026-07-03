@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.model.Role
import dev.azula.net.Alpns
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
        val azulaState = testAzulaState(backgroundScope, transport)
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

        pipe.sendFrame(Frame.Profile(name = "Bob", description = "a friend"))
        runCurrent()

        assertEquals("Bob", azulaState.conversation("peer-profile").name)
        assertEquals("a friend", azulaState.conv("peer-profile").peerDescription)
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
    override var nodeId: String? = "test-node"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = ""
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override suspend fun connect(ticket: String, alpn: String): P2pStream = error("not used: inbound-only test")
    override fun peerIdForTicket(ticket: String): String? = null
    override fun incoming(): Flow<IncomingConnection> = flow { emit(IncomingConnection(remoteId, alpn, pipe.clientStream)) }
    override suspend fun shutdown() = Unit
}
