package dev.azula.terminal

/**
 * The narrow surface the terminal UI needs from app state — so the terminal
 * module depends on this contract, not the whole `AzulaState`. Implemented by the
 * app's state holder; injected/passed to the terminal composables.
 */
interface TerminalSession {
    /** The live emulator (grid + parser) for a conversation's terminal session. */
    fun terminalScreen(convId: String): TerminalEmulator

    /** Whether that conversation currently has a live PTY stream. */
    fun terminalOnline(convId: String): Boolean

    /** Send raw keystroke bytes to the PTY, verbatim (feeds the predictor first). */
    fun terminalRaw(convId: String, bytes: String)

    /** Send Ctrl-C to the PTY. */
    fun terminalInterrupt(convId: String)

    /** Paste text into the PTY, wrapping in bracketed-paste markers when the app has
     *  it enabled (`ESC[200~ … ESC[201~`). Never predicted. */
    fun terminalPaste(convId: String, text: String)

    /** Report the terminal viewport size so the PTY wraps to match. */
    fun terminalResize(convId: String, cols: Int, rows: Int)
}
