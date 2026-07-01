package dev.azula.terminal

import dev.azula.model.TerminalLine

/**
 * The narrow surface the terminal UI needs from app state — so the terminal
 * module depends on this contract, not the whole `AzulaState`. Implemented by the
 * app's state holder; injected/passed to the terminal composables.
 */
interface TerminalSession {
    /** Demo / UI-test build: renders the seeded offline terminal instead of a PTY. */
    val isDemo: Boolean

    /** The seeded demo terminal buffer (only read by the offline demo view). */
    val demoLines: List<TerminalLine>

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
