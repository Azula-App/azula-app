package dev.azula.model

/** Who authored a message. */
enum class Role { ME, THEM, AZULA, SYSTEM }

/** What kind of conversation a row in the sidebar represents. */
enum class ConversationKind { LLM, PEER, TERMINAL }

/** A conversation shown in the sidebar / mobile home. */
data class Conversation(
    val id: String,
    val name: String,
    val kind: ConversationKind,
    val glyph: String,
    val sub: String,
    val preview: String,
)

/**
 * One entry in a thread. Either a [text] message (markdown) or an inline [widget].
 * Widget interaction state lives separately in [dev.azula.state.AzulaState.widgets],
 * keyed by [id], so a widget can be rendered immutably here and still react to taps.
 */
data class Message(
    val id: String,
    val role: Role,
    val text: String? = null,
    val widget: InlineWidget? = null,
)

/** Mutable per-widget interaction state (decide pick, poll vote, dice roll). */
data class WidgetState(
    val chosen: String? = null,
    val voted: Int? = null,
    val dicePhase: DicePhase = DicePhase.IDLE,
    val you: Int = 0,
    val them: Int = 0,
)

enum class DicePhase { IDLE, ROLLING, DONE }

/** A single line in the terminal view. */
data class TerminalLine(val kind: Kind, val text: String, val tint: Tint = Tint.NORMAL) {
    enum class Kind { CMD, OUT }
    enum class Tint { NORMAL, GREEN, ERROR }
}

/** Stages of the "connect a peer" holepunch flow. */
enum class ConnectStage { IDLE, DIALING, CONNECTED, FAILED }
