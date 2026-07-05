package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.core.AppScope
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.net.FileEncoding
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.OutgoingFile
import dev.azula.net.sendFile
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The chat feature carved out of AzulaState: sending text/file messages on a live
 * stream. Sends go over the conversation's own [dev.azula.net.P2pStream] (not the
 * transport directly), so this service is independent of transport rebinding.
 */
@Inject
@SingleIn(AppScope::class)
class ChatService(
    private val convStore: ConversationStore,
    private val persistence: PersistenceCoordinator,
    private val scope: CoroutineScope,
    // Ingests the local ME attachment through the BlobStore (see
    // MediaService.ingestLocalBlob) so a legacy file send gets a blobId-backed
    // attachment locally, same as an inbound legacy receive -- the FileBegin/
    // Chunk/End wire frames below are unaffected. One-directional dependency:
    // MediaService never depends on ChatService.
    private val mediaService: MediaService,
) {
    /** True while the remote assistant is composing a reply. */
    var thinking by mutableStateOf(false)
        private set
    fun updateThinking(on: Boolean) { thinking = on }

    /** Append a chat message locally, persist, then ship it on the live stream. */
    fun send(convId: String, txt: String) {
        val cs = convStore.conv(convId)
        cs.draft = ""
        cs.messages.add(Message(convStore.nextId("u"), Role.ME, text = txt))
        persistence.save(convId)
        cs.stream?.let { stream ->
            scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Chat(txt))) } }
        }
    }

    fun sendFile(
        convId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        encoding: FileEncoding,
        caption: String?,
    ) {
        val cs = convStore.conv(convId)
        mediaService.ingestLocalBlob(convId, bytes, name, mime, caption, Role.ME)
        val stream = cs.stream ?: return
        scope.launch {
            runCatching { sendFile(stream, OutgoingFile(name = name, mime = mime, bytes = bytes, caption = caption), encoding) }
        }
    }
}
