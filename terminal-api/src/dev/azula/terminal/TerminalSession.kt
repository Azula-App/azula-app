package dev.azula.terminal

/**
 * The narrow surface the terminal UI needs from app state — so the terminal
 * module depends on this contract, not the whole `AzulaState`. Implemented by the
 * app's state holder; injected/passed to the terminal composables.
 */
interface TerminalSession {
    /** The live screen buffer for a conversation's terminal session. */
    fun terminalScreen(convId: String): TermScreen

    /** Whether that conversation currently has a live PTY stream. */
    fun terminalOnline(convId: String): Boolean

    /** Send raw keystroke bytes to the PTY, verbatim. */
    fun terminalRaw(convId: String, bytes: String)

    /** Send Ctrl-C to the PTY. */
    fun terminalInterrupt(convId: String)

    /** Report the terminal viewport size so the PTY wraps to match. */
    fun terminalResize(convId: String, cols: Int, rows: Int)
}
