@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

import dev.azula.mock.FakeTransport
import dev.azula.model.Attachment
import dev.azula.model.ConversationKind
import dev.azula.model.Role
import dev.azula.model.TransferState
import dev.azula.net.Alpns
import dev.azula.net.FileEncoding
import dev.azula.net.Frame
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import dev.azula.persist.BlobReader
import dev.azula.persist.BlobStore
import dev.azula.persist.BlobWriter
import dev.azula.persist.MediaExporter
import dev.azula.state.AzulaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2: auto-save every **received** media/file to user-visible device storage.
 * Asserts the [MediaExporter] seam fires exactly once, at the Complete transition,
 * for a received streamed fetch and for a received legacy (`FileBegin`) ingest --
 * and never for a send (Role.ME) or a failed fetch. Driven through [AzulaState]'s
 * public surface + inbound frames, same style as [MediaServiceTest] /
 * [ConnectServiceFrameDispatchTest].
 *
 * Uses [PathBlobStore] rather than mock-support's `InMemoryBlobStore` because the
 * exporter only runs when a real on-disk path exists (`blobStore.pathFor != null`);
 * `InMemoryBlobStore.pathFor` is deliberately null (off-disk), which is exactly the
 * "no path -> no export" case the impl guards on. The export coroutine is launched
 * on the state scope (the test's scheduler), so each test awaits the transfer's
 * completion on a real channel/poll and then `runCurrent()`s to flush the export.
 */
class MediaExportTest {

    @Test
    fun receivedStreamedFetchAutoExportsExactlyOnceAtComplete() = runTest {
        val transport = FakeTransport()
        val blobStore = PathBlobStore()
        val messageStore = InMemoryMessageStore()
        val exporter = RecordingExporter()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore, mediaExporter = exporter)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val blobId = "img-1"
        val bytes = ByteArray(4_000) { (it % 251).toByte() }
        transport.blobs[blobId] = bytes

        // Image offers auto-fetch immediately.
        transport.injectIncoming(
            "peer-ticket",
            Frame.MediaOffer(id = blobId, kind = "image", name = "cat.png", mime = "image/png", size = bytes.size.toLong(), fetchTicket = "peer-ticket"),
        )
        runCurrent()

        awaitCompleteSave(messageStore, convId, blobId)
        runCurrent() // flush the export coroutine launched on the state scope

        val call = exporter.exported.tryReceive().getOrNull()
        assertNotNull(call, "a completed received fetch must auto-export exactly once")
        assertEquals(blobStore.pathFor(blobId), call.path, "export should target the on-disk blob path")
        assertEquals("cat.png", call.name)
        assertEquals("image/png", call.mime)
        assertTrue(exporter.exported.tryReceive().isFailure, "export must fire exactly once, not repeatedly")
    }

    @Test
    fun receivedLegacyFileBeginAutoExports() = runTest {
        val pipe = PeerPipe()
        val transport = ExportInboundTransport("peer-legacy", Alpns.LLM, pipe)
        val peerStore = InMemoryPeerStore().also { it.save(listOf("peer-legacy")) }
        val blobStore = PathBlobStore()
        val exporter = RecordingExporter()
        val azulaState = testAzulaState(backgroundScope, transport, peerStore = peerStore, blobStore = blobStore, mediaExporter = exporter)
        azulaState.start()
        runCurrent()

        val bytes = ByteArray(256) { it.toByte() }
        pipe.sendFrame(Frame.FileBegin(id = "f1", name = "clip.bin", mime = "application/octet-stream", size = bytes.size.toLong(), encoding = FileEncoding.BASE64.wire, caption = "here"))
        runCurrent()
        pipe.sendFrame(Frame.FileChunk(id = "f1", seq = 0, data = Base64.encode(bytes)))
        runCurrent()
        pipe.sendFrame(Frame.FileEnd(id = "f1"))
        runCurrent()

        val attachment = azulaState.conv("peer-legacy").messages.last { it.attachments.isNotEmpty() }.attachments.single()
        assertEquals(Role.THEM, azulaState.conv("peer-legacy").messages.last { it.attachments.isNotEmpty() }.role)
        assertEquals(TransferState.Complete, attachment.state)

        val call = exporter.exported.tryReceive().getOrNull()
        assertNotNull(call, "a received legacy (THEM) ingest must auto-export")
        assertEquals(blobStore.pathFor(attachment.blobId!!), call.path)
        assertEquals("clip.bin", call.name)
        assertEquals("application/octet-stream", call.mime)
        assertTrue(exporter.exported.tryReceive().isFailure, "export must fire exactly once")
    }

    @Test
    fun sendingMediaDoesNotExport() = runTest {
        val transport = FakeTransport()
        val blobStore = PathBlobStore()
        val messageStore = InMemoryMessageStore()
        val exporter = RecordingExporter()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore, mediaExporter = exporter)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val bytes = ByteArray(2_000) { it.toByte() }
        azulaState.sendMedia(convId, bytes, name = "photo.png", mime = "image/png", caption = "mine")
        awaitCompleteSave(messageStore, convId, matchState = "complete", requireBlobId = false)
        runCurrent()

        assertTrue(exporter.exported.tryReceive().isFailure, "the sender's own outbound media must never be exported")
    }

    @Test
    fun failedFetchDoesNotExport() = runTest {
        val transport = FakeTransport()
        val blobStore = PathBlobStore()
        val messageStore = InMemoryMessageStore()
        val exporter = RecordingExporter()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore, mediaExporter = exporter)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        // No blob registered on the transport -> the media server answers "unknown
        // blob" -> the auto-fetch fails.
        val blobId = "missing-1"
        transport.injectIncoming(
            "peer-ticket",
            Frame.MediaOffer(id = blobId, kind = "image", name = "gone.png", mime = "image/png", size = 4_000, fetchTicket = "peer-ticket"),
        )
        runCurrent()

        val att = pollForAttachmentState(azulaState, convId, blobId) { it is TransferState.Failed }
        assertTrue(att.state is TransferState.Failed, "expected the fetch to fail, got ${att.state}")
        runCurrent()

        assertTrue(exporter.exported.tryReceive().isFailure, "a failed fetch must not export anything")
    }
}

/** Records every [export] call; [exported] lets a test observe them deterministically. */
private class RecordingExporter : MediaExporter {
    data class Call(val path: String, val name: String, val mime: String)
    val exported: Channel<Call> = Channel(Channel.UNLIMITED)
    override suspend fun export(path: String, name: String, mime: String): String? {
        exported.trySend(Call(path, name, mime))
        return "Test/$name"
    }
}

/** Like `InMemoryBlobStore`, but [pathFor] returns a non-null pseudo-path once an id
 *  has bytes -- so the export seam (which only runs when a real path exists) fires. */
private class PathBlobStore : BlobStore {
    private val data = LinkedHashMap<String, ByteArray>()
    override fun exists(id: String): Boolean = data.containsKey(id)
    override fun sizeOnDisk(id: String): Long = (data[id]?.size ?: 0).toLong()
    override fun openWriter(id: String): BlobWriter = object : BlobWriter {
        override fun write(chunk: ByteArray) { data[id] = (data[id] ?: ByteArray(0)) + chunk }
        override fun close() = Unit
    }
    override fun openReader(id: String, offset: Long): BlobReader {
        val bytes = data[id] ?: ByteArray(0)
        var pos = offset.toInt()
        return object : BlobReader {
            override fun read(into: ByteArray): Int {
                if (pos >= bytes.size) return -1
                val end = minOf(pos + into.size, bytes.size)
                bytes.copyInto(into, 0, pos, end)
                val n = end - pos; pos = end; return n
            }
            override fun close() = Unit
        }
    }
    override fun pathFor(id: String): String? = if (data.containsKey(id)) "/blobs/$id" else null
    override fun delete(id: String) { data.remove(id) }
}

/** One inbound connection then done -- mirrors ConnectServiceFrameDispatchTest's
 *  OneShotIncomingTransport (which is file-private there), reused here for the
 *  legacy FileBegin path. */
private class ExportInboundTransport(
    private val remoteId: String,
    private val alpn: String,
    private val pipe: PeerPipe,
) : IrohTransport {
    override var nodeId: String? = "test-node"
    override var onCameOnline: (() -> Unit)? = null
    override suspend fun bind(alpns: List<String>) = Unit
    override suspend fun myTicket(): String = ""
    override fun secretKeyBytes(): ByteArray? = null
    override suspend fun importSecretKey(bytes: ByteArray) = Unit
    override suspend fun connect(ticket: String, alpn: String): P2pStream = error("not used: inbound-only test")
    override fun peerIdForTicket(ticket: String): String = ticket
    override fun incoming(): Flow<IncomingConnection> = flow { emit(IncomingConnection(remoteId, alpn, pipe.clientStream)) }
    override suspend fun shutdown() = Unit
}

/** Awaits a persistence save of [convId] carrying an attachment in [matchState]. */
private suspend fun awaitCompleteSave(
    store: InMemoryMessageStore,
    convId: String,
    blobId: String? = null,
    matchState: String = "complete",
    requireBlobId: Boolean = true,
    maxAttempts: Int = 50,
) {
    repeat(maxAttempts) {
        val (id, dto) = store.saves.receive()
        if (id == convId && dto.messages.any { m ->
                m.attachments.any { it.state == matchState && (!requireBlobId || blobId == null || it.id == blobId) }
            }
        ) return
    }
    throw AssertionError("no save of '$convId' reached state '$matchState' within $maxAttempts saves")
}

private suspend fun pollForAttachmentState(
    azulaState: AzulaState,
    convId: String,
    attachmentId: String,
    maxAttempts: Int = 400,
    intervalMs: Long = 25,
    predicate: (TransferState) -> Boolean,
): Attachment {
    repeat(maxAttempts) {
        val a = azulaState.conv(convId).messages.flatMap { it.attachments }.firstOrNull { it.id == attachmentId }
        if (a != null && predicate(a.state)) return a
        withContext(Dispatchers.Default) { delay(intervalMs) }
    }
    throw AssertionError("attachment '$attachmentId' never satisfied the predicate")
}
