package dev.azula.net

/** Each platform supplies its concrete iroh-backed [IrohTransport]. */
expect fun createTransport(): IrohTransport
