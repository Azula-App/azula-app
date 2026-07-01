package dev.azula.state

import dev.azula.core.AppScope
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.persist.ConversationDto
import dev.azula.persist.MessageStore
import dev.azula.persist.toDto
import dev.azula.persist.toMessage
import dev.azula.ui.decodeImageBitmap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Restores and persists conversation history via the injected [MessageStore],
 * carved out of AzulaState. Saves run on a single-threaded dispatcher so rapid
 * successive saves can't race. Operates on the shared [ConversationStore].
 */
@Inject
@SingleIn(AppScope::class)
class PersistenceCoordinator(
    private val convStore: ConversationStore,
    private val messageStore: MessageStore?,
    private val scope: CoroutineScope,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Restore every saved conversation (identity + history) from disk. Returns the
     * highest numeric id suffix seen so the caller can advance its id counter past
     * ids minted in a previous session (a duplicate id crashes the LazyColumn), or
     * -1 if nothing was restored.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun restoreAll(): Int {
        val store = messageStore ?: return -1
        var maxSuffix = -1
        for (id in runCatching { store.list() }.getOrDefault(emptyList())) {
            val dto = runCatching { store.load(id) }.getOrNull() ?: continue
            val cs = convStore.convState.getOrPut(id) { ConversationState(emptyList()) }
            cs.messages.clear()
            // Restored A2UI surfaces are intentionally NOT registered live so they
            // render frozen (see Chat's `frozen` predicate).
            cs.messages.addAll(dto.messages.map { it.toMessage() })
            cs.peerAvatarB64 = dto.avatarB64
            cs.peerAvatarMime = dto.avatarMime
            cs.peerDescription = dto.peerDescription
            cs.peerAvatarBitmap = dto.avatarB64?.let { b64 -> runCatching { decodeImageBitmap(Base64.decode(b64)) }.getOrNull() }
            if (convStore.conversationOrNull(id) == null) {
                convStore.conversations.add(Conversation(id, dto.name, dto.kind, dto.glyph, dto.sub, dto.preview, dto.enabled))
            }
            for (m in dto.messages) {
                val n = m.id.takeLastWhile { it.isDigit() }.toIntOrNull() ?: continue
                if (n > maxSuffix) maxSuffix = n
            }
        }
        return maxSuffix
    }

    /** Serialize and save a conversation's identity + full history off the main
     *  thread. No-op when no store is bound (e.g. the mock build passes null). */
    fun save(convId: String) {
        val store = messageStore ?: return
        val cs = convStore.convOrNull(convId) ?: return
        val meta = convStore.conversationOrNull(convId)
        val dto = ConversationDto(
            name = meta?.name ?: convId,
            kind = meta?.kind ?: ConversationKind.PEER,
            glyph = meta?.glyph ?: "•",
            sub = meta?.sub ?: "",
            preview = meta?.preview ?: "",
            messages = cs.messages.map { it.toDto() },
            enabled = meta?.enabled ?: true,
            avatarB64 = cs.peerAvatarB64,
            avatarMime = cs.peerAvatarMime,
            peerDescription = cs.peerDescription,
        )
        scope.launch(saveDispatcher) { runCatching { store.save(convId, dto) } }
    }

    /** Remove a conversation's persisted blob (no-op when no store is bound). */
    fun delete(convId: String) {
        scope.launch(saveDispatcher) { runCatching { messageStore?.delete(convId) } }
    }
}
