package dev.azula.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * iOS transport: adapts the injected callback-style [SwiftIrohTransport] (backed
 * by `IrohLib`) to the common suspend/Flow interface. Falls back to a demo
 * transport when Swift hasn't registered an implementation.
 */
class IosIrohTransport(private val swift: SwiftIrohTransport) : IrohTransport {
    override var nodeId: String? = null
        private set

    override suspend fun bind(alpns: List<String>) = suspendCancellableCoroutine { cont ->
        swift.bind(alpns) { id, error ->
            if (error != null) cont.resumeWith(Result.failure(RuntimeException(error)))
            else { nodeId = id; cont.resumeWith(Result.success(Unit)) }
        }
    }

    override suspend fun myTicket(): String = suspendCancellableCoroutine { cont ->
        swift.myTicket { ticket, error ->
            if (error != null) cont.resumeWith(Result.failure(RuntimeException(error)))
            else cont.resumeWith(Result.success(ticket ?: ""))
        }
    }

    override suspend fun connect(ticket: String, alpn: String): P2pStream = suspendCancellableCoroutine<P2pStream> { cont ->
        swift.connect(ticket, alpn) { stream, error ->
            if (error != null || stream == null) cont.resumeWith(Result.failure(RuntimeException(error ?: "no stream")))
            else cont.resumeWith(Result.success(IosP2pStream(stream)))
        }
    }

    override fun incoming(): Flow<IncomingConnection> = callbackFlow {
        swift.onIncoming { remoteId, alpn, stream ->
            trySend(IncomingConnection(remoteId, alpn, IosP2pStream(stream)))
        }
        awaitClose { }
    }

    override suspend fun shutdown() = swift.shutdown()
}

private class IosP2pStream(private val swift: SwiftP2pStream) : P2pStream {
    override suspend fun sendLine(line: String) = suspendCancellableCoroutine { cont ->
        swift.sendLine(line) { error ->
            if (error != null) cont.resumeWith(Result.failure(RuntimeException(error)))
            else cont.resumeWith(Result.success(Unit))
        }
    }

    override suspend fun receiveLine(): String? = suspendCancellableCoroutine { cont ->
        swift.receiveLine { line -> cont.resumeWith(Result.success(line)) }
    }

    override suspend fun close() = swift.close()
}

actual fun createTransport(): IrohTransport =
    IrohSwiftRegistry.impl?.let { IosIrohTransport(it) } ?: FakeTransport()
