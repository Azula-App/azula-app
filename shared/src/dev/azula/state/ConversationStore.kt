package dev.azula.state

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import dev.azula.core.AppScope
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Owns the app's conversation state — the sidebar list and each conversation's
 * per-thread [ConversationState] — plus the helpers every feature shares to look
 * up, name, and create conversations. Carved out of AzulaState so the connect /
 * chat / terminal services can build on one shared store; AzulaState delegates to
 * it (and seeds it in demo mode), keeping the UI-facing surface stable.
 */
@Inject
@SingleIn(AppScope::class)
class ConversationStore {
    /** The conversations shown in the sidebar / mobile home. */
    val conversations = mutableStateListOf<Conversation>()

    /** Per-conversation mutable state (thread, draft, live stream), keyed by id. */
    val convState = mutableStateMapOf<String, ConversationState>()

    fun conv(id: String): ConversationState = convState.getValue(id)
    fun conversation(id: String): Conversation = conversations.first { it.id == id }
    fun convOrNull(id: String): ConversationState? = convState[id]
    fun conversationOrNull(id: String): Conversation? = conversations.firstOrNull { it.id == id }

    /**
     * Get — or create — the conversation for [peerId] (one per peer). [kind] is
     * derived from the ALPN; [announcedName] (a server's Hello, e.g. "Blackjack")
     * becomes the title, otherwise the peer's mnemonic code is. A higher-priority
     * service (CHAT > LLM > TERM) upgrades the kind of an existing conversation.
     */
    fun ensureConv(peerId: String, kind: ConversationKind, announcedName: String? = null): String {
        val id = peerId
        val code = mnemonicCode(peerId)
        val existing = conversationOrNull(id)
        if (existing != null) {
            val newKind = if (kindPriority(kind) > kindPriority(existing.kind)) kind else existing.kind
            val newName = announcedName ?: existing.name
            if (newKind != existing.kind || newName != existing.name) {
                val idx = conversations.indexOfFirst { it.id == id }
                if (idx >= 0) conversations[idx] = existing.copy(
                    name = newName,
                    kind = newKind,
                    glyph = glyphFor(newName, newKind),
                    preview = if (newName != code) code else "",
                )
            }
            return id
        }
        convState[id] = ConversationState(emptyList())
        val name = announcedName ?: code
        conversations.add(
            Conversation(id, name, kind, glyphFor(name, kind), sub = code, preview = if (name != code) code else "")
        )
        return id
    }

    fun kindPriority(k: ConversationKind): Int = when (k) {
        ConversationKind.PEER -> 3
        ConversationKind.LLM -> 2
        ConversationKind.TERMINAL -> 1
    }

    fun glyphFor(name: String, kind: ConversationKind): String = when {
        name.equals("blackjack", ignoreCase = true) -> "♠"
        kind == ConversationKind.TERMINAL -> "›_"
        kind == ConversationKind.LLM -> "✦"
        else -> name.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
    }

    /** A human-friendly label derived from any node id (our own or a peer's). */
    fun mnemonicCode(id: String): String {
        val h = id.filter { it.isLetterOrDigit() }
        if (h.length < 4) return "··· offline"
        val words = MNEMONICS
        fun pick(i: Int) = words[(h[i % h.length].code) % words.size]
        return "${h.take(2)} · ${pick(2)} · ${pick(5)}"
    }

    private companion object {
        val MNEMONICS = listOf(
            "fox", "ember", "lyra", "onyx", "moth", "ivy", "rune", "echo",
            "wren", "kite", "sol", "vex", "nyx", "zephyr", "cobalt", "flint",
        )
    }
}
