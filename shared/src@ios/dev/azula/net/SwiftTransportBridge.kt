package dev.azula.net

/**
 * The contract Swift implements over the `IrohLib` Swift package (which
 * Kotlin/Native cannot call directly). Methods are callback-style on purpose:
 * Swift implements simple closures, and [IosIrohTransport] adapts them to the
 * common suspend/Flow [IrohTransport] interface. `iosApp.swift` registers a
 * concrete implementation into [IrohSwiftRegistry] before Compose starts.
 */
interface SwiftP2pStream {
    fun sendLine(line: String, onDone: (error: String?) -> Unit)
    fun receiveLine(onLine: (line: String?) -> Unit) // null line = stream closed
    fun close()
}

interface SwiftIrohTransport {
    fun bind(alpns: List<String>, onReady: (nodeId: String?, error: String?) -> Unit)
    fun myTicket(onResult: (ticket: String?, error: String?) -> Unit)
    fun connect(ticket: String, alpn: String, onStream: (stream: SwiftP2pStream?, error: String?) -> Unit)
    fun onIncoming(cb: (remoteId: String, alpn: String, stream: SwiftP2pStream) -> Unit)
    fun shutdown()
}

object IrohSwiftRegistry {
    var impl: SwiftIrohTransport? = null
}
