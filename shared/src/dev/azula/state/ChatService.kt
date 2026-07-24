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
import dev.azula.sync.toHexLower
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    private val accountSyncService: AccountSyncService,
) {
    /** True while the remote assistant is composing a reply. */
    var thinking by mutableStateOf(false)
        private set
    fun updateThinking(on: Boolean) { thinking = on }

    /**
     * Set by AzulaState (multi-device-identity task 7.4): when [send] has no live
     * stream to ship over, attempt to reach one of the contact's other known devices
     * instead -- dial order (most-recently-seen, then any mailbox-role device), first
     * success wins -- reusing the same wire `Chat.id` on every attempt so a receiver
     * that saw an earlier ambiguous delivery still dedupes it (spec: "Delivery to Any
     * Device Counts for the Identity"). Not a constructor dependency: ChatService must
     * not depend on ConnectService (which already depends on FrameDispatcher, which
     * depends on ChatService -- a direct dependency the other way would be circular),
     * so this follows the same post-construction-callback convention as
     * [dev.azula.state.ConnectService.onNavigate]/[FrameDispatcher.foreground]. Defaults
     * to a no-op: the `-mock` apps, most tests, and any conversation with no other known
     * devices are entirely unaffected -- exactly the pre-7.4 behavior of just logging
     * `message_out` and doing nothing further. */
    var dialKnownDeviceAndSend: (convId: String, frame: Frame.Chat) -> Unit = { _, _ -> }

    /** Append a chat message locally, persist, log it, then ship it on the live stream. */
    fun send(convId: String, txt: String) {
        val cs = convStore.conv(convId)
        cs.draft = ""
        cs.messages.add(Message(convStore.nextId("u"), Role.ME, text = txt))
        persistence.save(convId)
        // multi-device-identity task 4.5: message_out is recorded regardless of
        // delivery outcome (spec: "Delivery to Any Device Counts for the Identity"),
        // so this runs unconditionally -- whether or not a stream is even open below --
        // rather than being gated on the send succeeding. The same random id rides on
        // the wire Chat frame, so a retry against a sibling device (task 7.4) reuses it
        // and dedupes at fold time.
        val id = randomChatId()
        accountSyncService.appendMessageOut(convId, txt, id)
        val frame = Frame.Chat(txt, id)
        val stream = cs.stream
        if (stream != null) {
            scope.launch { runCatching { stream.sendLine(FrameCodec.encode(frame)) } }
        } else {
            // multi-device-identity task 7.4: no live stream -- try this contact's other
            // known devices before giving up to the ordinary reconnect behavior.
            dialKnownDeviceAndSend(convId, frame)
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

/** A fresh 16-random-byte lowercase-hex id for the wire `Chat.id` field (spec:
 *  "Chat Message Ids Deduplicate Retries" -- "16 random bytes, lowercase hex"). */
private fun randomChatId(): String = Random.nextBytes(16).toHexLower()
