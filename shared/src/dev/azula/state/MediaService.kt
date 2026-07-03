package dev.azula.state

import dev.azula.core.AppScope
import dev.azula.model.Attachment
import dev.azula.model.MediaKind
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.model.TransferState
import dev.azula.net.Alpns
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import dev.azula.net.MAX_MEDIA_BYTES
import dev.azula.net.MEDIA_CHUNK
import dev.azula.net.MediaCodec
import dev.azula.net.MediaFetchRequest
import dev.azula.net.MediaFetchResponse
import dev.azula.net.P2pStream
import dev.azula.net.receiveMediaBody
import dev.azula.net.sendMediaBody
import dev.azula.persist.BlobStore
import dev.azula.ui.scaleImageForAvatar
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Below this size (and always for images), an inbound offer auto-fetches instead
 *  of waiting for a tap. 20 MB, not MiB, to match the design doc's plain constant. */
const val AUTO_DOWNLOAD_THRESHOLD = 20L * 1000 * 1000

/** Cap on the base64-encoded thumbnail embedded in a [Frame.MediaOffer] (32 KiB, matching the design doc). */
private const val THUMB_B64_CAP = 32 * 1024

/** How often (at most) an in-progress fetch republishes [TransferState.Downloading], to bound recomposition. */
private val THROTTLE_WINDOW = 150.milliseconds

/**
 * Owns the media-attachment lifecycle carved out of AzulaState: writing a sent
 * attachment's bytes into the [BlobStore] and offering it to the peer, reacting to
 * an inbound [Frame.MediaOffer] by appending an "offered" placeholder (and maybe
 * auto-fetching it), and running/serving the `azula/media/0` fetch protocol (see
 * network-api's `MediaProtocol.kt` for the wire-level `sendMediaBody`/
 * `receiveMediaBody` chunk loops this builds on). Deliberately independent of
 * [ConnectService] / [FrameDispatcher] -- they call into this class (one-directional:
 * [ConnectService] routes `Alpns.MEDIA` dials to [serveIncoming], [FrameDispatcher]
 * routes [Frame.MediaOffer] to [onMediaOffer]) -- never the reverse, so there's no
 * DI cycle.
 */
@Inject
@SingleIn(AppScope::class)
class MediaService(
    private val transport: IrohTransport,
    private val convStore: ConversationStore,
    private val persistence: PersistenceCoordinator,
    private val blobStore: BlobStore?,
    private val scope: CoroutineScope,
) {
    /**
     * In-flight fetch jobs keyed by attachment id, so a second [startFetch] for the
     * same id is a no-op and [cancelFetch] can find the right job. Backed by a
     * [MutableStateFlow] (rather than a plain mutable map) so add/remove is safe
     * under concurrent cancel/complete -- this runs on [scope], which may be
     * multi-threaded (e.g. Android's process-scoped `Dispatchers.Default` scope).
     */
    private val activeFetches = MutableStateFlow<Map<String, Job>>(emptyMap())

    // ---- sending ----

    /**
     * Send a media attachment: enforce [MAX_MEDIA_BYTES], write [bytes] into the
     * [BlobStore] under a fresh id, append a locally-complete ME message, persist,
     * and best-effort offer it to the peer on the live stream. Returns false (no
     * message appended, nothing written) if [bytes] exceeds the cap or no
     * [BlobStore] is bound -- the `-mock` apps get media loopback via a fake
     * transport instead (see Phase 4), rather than a legacy inline fallback here.
     *
     * This performs the blob write synchronously on the calling thread (matching
     * [ChatService.send]/[ChatService.sendFile]'s synchronous style); callers
     * sending large payloads should invoke it off the UI thread.
     */
    @OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
    fun sendMedia(convId: String, bytes: ByteArray, name: String, mime: String, caption: String? = null): Boolean {
        // Rejections still append a local Failed placeholder: AzulaState.sendMedia is
        // fire-and-forget, so without a message the user would see nothing at all.
        if (bytes.size.toLong() > MAX_MEDIA_BYTES) {
            appendSendFailure(convId, name, mime, bytes.size.toLong(), caption,
                "too large — max ${MAX_MEDIA_BYTES / (1024 * 1024)} MiB")
            return false
        }
        val store = blobStore ?: run {
            appendSendFailure(convId, name, mime, bytes.size.toLong(), caption, "media storage unavailable")
            return false
        }
        val cs = convStore.conv(convId)
        val id = Uuid.random().toString()
        val kind = MediaKind.fromMime(mime)

        store.openWriter(id).use { writer ->
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + MEDIA_CHUNK, bytes.size)
                writer.write(bytes.copyOfRange(offset, end))
                offset = end
            }
        }

        val thumbBytes = if (kind == MediaKind.IMAGE) scaleImageForAvatar(bytes)?.bytes else null

        // fetchTicket is filled in just below, asynchronously: IrohTransport.myTicket()
        // is suspend, and sendMedia (matching ChatService.send/sendFile) is not.
        val attachment = Attachment(
            id = id,
            name = name,
            mime = mime,
            size = bytes.size.toLong(),
            bytes = null,
            caption = caption,
            kind = kind,
            thumbBytes = thumbBytes,
            blobId = id,
            fetchTicket = null,
            state = TransferState.Complete,
        )
        cs.messages.add(Message(convStore.nextId("m"), Role.ME, text = caption, attachments = listOf(attachment)))
        persistence.save(convId)

        scope.launch {
            val ticket = runCatching { transport.myTicket() }.getOrNull() ?: return@launch
            updateAttachment(convId, id) { it.copy(fetchTicket = ticket) }
            persistence.save(convId)
            val stream = cs.stream ?: return@launch
            val thumbB64 = thumbBytes?.let { Base64.encode(it) }?.takeIf { it.length <= THUMB_B64_CAP }
            val offer = Frame.MediaOffer(
                id = id,
                kind = kind.wire,
                name = name,
                mime = mime,
                size = bytes.size.toLong(),
                caption = caption,
                thumbB64 = thumbB64,
                fetchTicket = ticket,
            )
            runCatching { stream.sendLine(FrameCodec.encode(offer)) }
        }
        return true
    }

    // ---- receiving an offer ----

    /**
     * React to an inbound [Frame.MediaOffer]: reject (append a
     * [TransferState.Failed] placeholder) if it exceeds [MAX_MEDIA_BYTES],
     * otherwise append a THEM message in [TransferState.Offered] and auto-fetch
     * images or anything at or under [AUTO_DOWNLOAD_THRESHOLD]. Persisting the
     * resulting message is the caller's job -- [FrameDispatcher.applyFrame]'s
     * trailing `persistence.save` covers this the same way it covers every other
     * frame case, so this method doesn't save itself.
     */
    fun onMediaOffer(convId: String, frame: Frame.MediaOffer) {
        if (findAttachment(convId, frame.id) != null) return // duplicate offer (e.g. a reconnect) -- ignore
        val cs = convStore.conv(convId)
        val kind = MediaKind.fromWire(frame.kind)

        if (frame.size > MAX_MEDIA_BYTES) {
            val attachment = offerAttachment(frame, kind, TransferState.Failed("too large"), thumbBytes = null)
            cs.messages.add(Message(convStore.nextId("r"), Role.THEM, text = frame.caption, attachments = listOf(attachment)))
            return
        }

        val thumbBytes = decodeThumb(frame.thumbB64)
        val attachment = offerAttachment(frame, kind, TransferState.Offered, thumbBytes)
        cs.messages.add(Message(convStore.nextId("r"), Role.THEM, text = frame.caption, attachments = listOf(attachment)))

        if (kind == MediaKind.IMAGE || frame.size <= AUTO_DOWNLOAD_THRESHOLD) {
            startFetch(convId, frame.id)
        }
    }

    /** A ME message whose only attachment is a [TransferState.Failed] placeholder --
     *  how a rejected send stays visible to the user (nothing is written or offered). */
    @OptIn(ExperimentalUuidApi::class)
    private fun appendSendFailure(convId: String, name: String, mime: String, size: Long, caption: String?, reason: String) {
        val cs = convStore.conv(convId)
        val attachment = Attachment(
            id = Uuid.random().toString(),
            name = name,
            mime = mime,
            size = size,
            bytes = null,
            caption = caption,
            kind = MediaKind.fromMime(mime),
            state = TransferState.Failed(reason),
        )
        cs.messages.add(Message(convStore.nextId("m"), Role.ME, text = caption, attachments = listOf(attachment)))
        persistence.save(convId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeThumb(b64: String?): ByteArray? = b64?.let { runCatching { Base64.decode(it) }.getOrNull() }

    private fun offerAttachment(frame: Frame.MediaOffer, kind: MediaKind, state: TransferState, thumbBytes: ByteArray?) = Attachment(
        id = frame.id,
        name = frame.name,
        mime = frame.mime,
        size = frame.size,
        bytes = null,
        caption = frame.caption,
        kind = kind,
        width = frame.width,
        height = frame.height,
        durationMs = frame.durationMs,
        thumbBytes = thumbBytes,
        blobId = frame.id,
        fetchTicket = frame.fetchTicket,
        state = state,
    )

    // ---- fetching ----

    private fun findAttachment(convId: String, attachmentId: String): Attachment? =
        convStore.convOrNull(convId)?.messages?.flatMap { it.attachments }?.firstOrNull { it.id == attachmentId }

    /** Replace the message carrying [attachmentId] with a copy whose matching
     *  attachment has been run through [transform] -- the same "replace the
     *  Message in place" pattern [FrameDispatcher] uses for token streaming. */
    private fun updateAttachment(convId: String, attachmentId: String, transform: (Attachment) -> Attachment) {
        val cs = convStore.convOrNull(convId) ?: return
        val msgIdx = cs.messages.indexOfLast { msg -> msg.attachments.any { it.id == attachmentId } }
        if (msgIdx < 0) return
        val msg = cs.messages[msgIdx]
        cs.messages[msgIdx] = msg.copy(attachments = msg.attachments.map { if (it.id == attachmentId) transform(it) else it })
    }

    /**
     * Fetch (or resume) attachment [attachmentId]'s body: dial its `fetchTicket` on
     * [Alpns.MEDIA], request from the current on-disk size ([BlobStore.sizeOnDisk]
     * doubles as the resume offset), and stream chunks to disk with throttled
     * [TransferState.Downloading] progress. No-op if a fetch for this id is already
     * running, no [BlobStore] is bound, or the attachment can't be found or has no
     * ticket. A failed or cancelled fetch leaves whatever bytes already landed on
     * disk -- calling this again resumes from there.
     */
    fun startFetch(convId: String, attachmentId: String) {
        val store = blobStore ?: return
        val attachment = findAttachment(convId, attachmentId) ?: return
        val ticket = attachment.fetchTicket ?: return

        // Atomically claim the id: bail while a live fetch exists; a dead entry (a
        // just-cancelled job whose cleanup hasn't finished) is replaced, and the new
        // job joins it before dialing so the old cleanup can't clobber new state or
        // deregister the new job.
        var job: Job
        while (true) {
            val cur = activeFetches.value
            val existing = cur[attachmentId]
            if (existing != null && existing.isActive) return
            job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                existing?.join()
                runFetch(convId, attachmentId, ticket, store)
            }
            if (activeFetches.compareAndSet(cur, cur + (attachmentId to job))) break
            job.cancel()
        }
        job.start()
    }

    private suspend fun runFetch(convId: String, attachmentId: String, ticket: String, store: BlobStore) {
        val self = kotlin.coroutines.coroutineContext[Job]
        var stream: P2pStream? = null
        try {
            val opened = transport.connect(ticket, Alpns.MEDIA)
            stream = opened
            val startOffset = store.sizeOnDisk(attachmentId)
            opened.sendLine(MediaCodec.encodeRequest(MediaFetchRequest(id = attachmentId, offset = startOffset)))
            val line = opened.receiveLine() ?: error("media fetch: peer closed before responding")
            when (val response = MediaCodec.decodeResponse(line)) {
                is MediaFetchResponse.Error -> updateAttachment(convId, attachmentId) {
                    it.copy(state = TransferState.Failed(response.reason))
                }
                is MediaFetchResponse.Ok -> {
                    var bytesDone = response.offset
                    var lastEmit = TimeSource.Monotonic.markNow()
                    val ok = store.openWriter(attachmentId).use { writer ->
                        receiveMediaBody(opened, total = response.size, offset = response.offset) { chunk ->
                            writer.write(chunk)
                            bytesDone += chunk.size
                            if (lastEmit.elapsedNow() >= THROTTLE_WINDOW) {
                                updateAttachment(convId, attachmentId) { it.copy(state = TransferState.Downloading(bytesDone, response.size)) }
                                lastEmit = TimeSource.Monotonic.markNow()
                            }
                        }
                    }
                    updateAttachment(convId, attachmentId) {
                        it.copy(state = if (ok) TransferState.Complete else TransferState.Failed("connection closed"))
                    }
                }
            }
        } catch (e: CancellationException) {
            updateAttachment(convId, attachmentId) { it.copy(state = TransferState.Failed("cancelled")) }
            throw e
        } catch (e: Throwable) {
            updateAttachment(convId, attachmentId) { it.copy(state = TransferState.Failed(e.message ?: "fetch failed")) }
        } finally {
            runCatching { stream?.close() }
            persistence.save(convId)
            // Deregister only ourselves -- a successor fetch may already occupy
            // this id's slot (see startFetch's dead-entry replacement).
            activeFetches.update { if (it[attachmentId] === self) it - attachmentId else it }
        }
    }

    /** Cancel an in-flight fetch, if any; bytes already on disk stay put (retry
     *  resumes from there). The job deregisters itself once its cleanup finishes. */
    fun cancelFetch(attachmentId: String) {
        activeFetches.value[attachmentId]?.cancel()
    }

    /** Cancel every in-flight fetch among [convId]'s attachments -- called when a
     *  conversation is deleted or disabled. */
    fun cancelAllForConversation(convId: String) {
        val ids = convStore.convOrNull(convId)?.messages.orEmpty().flatMap { it.attachments }.map { it.id }
        ids.forEach { cancelFetch(it) }
    }

    // ---- reading a completed blob (for the chat UI) ----

    /**
     * Read up to [maxBytes] of blob [id]'s full body into memory -- null if it's
     * missing, unreadable, or larger than [maxBytes] (so the UI doesn't accidentally
     * load a large video/audio file just to decode it as an image). Only meant for
     * content genuinely small enough to hold in memory (images, thumbnails); video
     * and audio play from [blobPath] instead.
     */
    fun blobBytes(id: String, maxBytes: Long = 32L * 1024 * 1024): ByteArray? {
        val store = blobStore ?: return null
        if (!store.exists(id)) return null
        val size = store.sizeOnDisk(id)
        if (size <= 0 || size > maxBytes) return null
        val out = ByteArray(size.toInt())
        var done = 0
        store.openReader(id).use { reader ->
            while (done < out.size) {
                val chunk = ByteArray(minOf(MEDIA_CHUNK, out.size - done))
                val n = reader.read(chunk)
                if (n <= 0) return null // truncated on disk -- shouldn't happen for a Complete attachment
                chunk.copyInto(out, destinationOffset = done, startIndex = 0, endIndex = n)
                done += n
            }
        }
        return out
    }

    /** A platform-native filesystem path to blob [id]'s bytes, for a native
     *  player/viewer (video/audio) or an "open externally" affordance. Null if the
     *  blob is incomplete, absent, or there's no [BlobStore] bound. */
    fun blobPath(id: String): String? = blobStore?.pathFor(id)

    // ---- serving a fetch (the sender side of the ALPN) ----

    /**
     * Handle one inbound `azula/media/0` connection: read the [MediaFetchRequest]
     * line, validate the blob exists (and the requested offset is sane), respond
     * with a [MediaFetchResponse], then stream the body from the [BlobStore].
     * Stateless per request -- always closes [stream] when done.
     */
    suspend fun serveIncoming(stream: P2pStream) = withContext(Dispatchers.Default) {
        try {
            val line = stream.receiveLine() ?: return@withContext
            val request = runCatching { MediaCodec.decodeRequest(line) }.getOrNull() ?: return@withContext
            val store = blobStore
            if (store == null || !store.exists(request.id)) {
                stream.sendLine(MediaCodec.encodeResponse(MediaFetchResponse.Error(request.id, "unknown blob")))
                return@withContext
            }
            val size = store.sizeOnDisk(request.id)
            if (request.offset > size) {
                stream.sendLine(MediaCodec.encodeResponse(MediaFetchResponse.Error(request.id, "offset beyond blob size")))
                return@withContext
            }
            stream.sendLine(MediaCodec.encodeResponse(MediaFetchResponse.Ok(request.id, size, request.offset)))
            store.openReader(request.id, request.offset).use { reader ->
                sendMediaBody(stream, total = size, offset = request.offset) { maxLen ->
                    val buf = ByteArray(maxLen)
                    val n = reader.read(buf)
                    if (n <= 0) null else if (n == maxLen) buf else buf.copyOf(n)
                }
            }
        } finally {
            runCatching { stream.close() }
        }
    }
}
