@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.mock.FakeTransport
import dev.azula.model.Role
import dev.azula.persist.ConversationDto
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [dev.azula.state.ChatService.send] end-to-end through [AzulaState]'s
 * public surface: appends locally, persists (via an in-memory [MessageStore]),
 * and writes the frame onto the conversation's live stream -- readable back
 * through [FakeTransport]'s loopback (it greets, then echoes chat back).
 */
class ChatServiceTest {

    @Test
    fun sendAppendsLocallyPersistsAndLoopsBackOverTheStream() = runTest {
        val transport = FakeTransport()
        val store = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, store)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("any-ticket")
        runCurrent()
        val convId = azulaState.conversations.single().id

        azulaState.updateDraft(convId, "hello there")
        azulaState.send(convId)
        runCurrent()

        // Appended locally.
        val messages = azulaState.conv(convId).messages
        assertTrue(
            messages.any { it.role == Role.ME && it.text == "hello there" },
            "expected the local ME message, got $messages",
        )

        // Looped back over the wire: FakeTransport's loopback echoes chat, and
        // the echo arrives back through the real receive loop as a THEM message.
        assertTrue(
            messages.any { it.role == Role.THEM && it.text?.contains("hello there") == true },
            "expected the loopback echo to come back as a THEM message, got $messages",
        )

        // Persisted: PersistenceCoordinator.save runs on a real background
        // dispatcher (not the test's virtual clock), so await it via the fake
        // store's channel rather than racing it with runCurrent().
        val dto = awaitSaveContaining(store, convId, "hello there")
        assertTrue(dto.messages.any { it.text == "hello there" })
    }
}

private suspend fun awaitSaveContaining(
    store: InMemoryMessageStore,
    convId: String,
    text: String,
    maxAttempts: Int = 20,
): ConversationDto {
    repeat(maxAttempts) {
        val (id, dto) = store.saves.receive()
        if (id == convId && dto.messages.any { it.text == text }) return dto
    }
    throw AssertionError("no save of '$convId' contained a message with text '$text' within $maxAttempts saves")
}
