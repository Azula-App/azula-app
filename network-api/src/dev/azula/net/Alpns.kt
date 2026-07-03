package dev.azula.net

/**
 * ALPN protocol identifiers negotiated on each iroh connection. The accepting
 * side routes an incoming connection to the right conversation by its ALPN.
 * These must match the Rust `azula-cli` server byte strings exactly.
 */
object Alpns {
    /** App ↔ app direct peer messaging. */
    const val CHAT = "azula/chat/0"

    /** App → server: push messages into a remote LLM session, stream tokens back. */
    const val LLM = "azula/llm/0"

    /** App → server: drive a remote shell session ("take over the session"). */
    const val TERM = "azula/term/0"

    /**
     * Receiver → sender: fetch the body of a media attachment offered via
     * `Frame.MediaOffer` on the CHAT stream. One connection per fetch — not
     * part of [ALL]'s dial race, since it's only ever dialed against a specific
     * offer's `fetchTicket`, never bound-and-raced like the others.
     */
    const val MEDIA = "azula/media/0"

    val ALL = listOf(CHAT, LLM, TERM)
}
