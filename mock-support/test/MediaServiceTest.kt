@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.mock.FakeTransport
import dev.azula.mock.InMemoryBlobStore
import dev.azula.model.Attachment
import dev.azula.model.ConversationKind
import dev.azula.model.Role
import dev.azula.model.TransferState
import dev.azula.net.Frame
import dev.azula.net.MAX_MEDIA_BYTES
import dev.azula.persist.AttachmentDto
import dev.azula.persist.ConversationDto
import dev.azula.persist.MessageDto
import dev.azula.state.AUTO_DOWNLOAD_THRESHOLD
import dev.azula.state.AzulaState
import dev.azula.state.ConversationStore
import dev.azula.state.PersistenceCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests [dev.azula.state.MediaService] adversarially against the design doc's
 * contract (`/Users/sal/.claude/plans/audit-the-code-to-pure-engelbart.md`,
 * Phase 4), not against whatever the implementation happens to do -- driven
 * entirely through [AzulaState]'s public surface (`conv`, `startFetch`,
 * `cancelFetch`, `sendMedia`), same spirit as [ConnectServiceFrameDispatchTest].
 *
 * Uses [FakeTransport] (extended here with [FakeTransport.blobs] /
 * [FakeTransport.injectIncoming] / [FakeTransport.mediaRequests]) rather than
 * [PeerPipe] -- a fetch test needs ONE transport that both delivers an inbound
 * [Frame.MediaOffer] over the CHAT loopback AND serves the `azula/media/0`
 * fetch itself, and [PeerPipe]/`OneShotIncomingTransport`'s `connect()` always
 * throws (inbound-only), so it can't play both roles.
 *
 * [MediaService.startFetch] launches its fetch job on a real background
 * dispatcher (`Dispatchers.Default`), not the test's virtual clock -- exactly
 * the "never advanceUntilIdle" situation [ConnectService]'s infinite `pollRtt`
 * loop already warns about elsewhere. Every wait below is against a channel
 * ([InMemoryMessageStore.saves], [InMemoryBlobStore.writes], or
 * [FakeTransport.mediaRequests]) or a bounded real-time poll -- never
 * `advanceUntilIdle()`.
 */
class MediaServiceTest {

    @Test
    fun smallImageOfferAutoDownloadsAndWalksPastOfferedToComplete() = runTest {
        val transport = FakeTransport()
        val blobStore = InMemoryBlobStore()
        val messageStore = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val blobId = "img-1"
        // Multi-chunk (> MEDIA_CHUNK = 256 KiB) so there's an observable in-flight
        // moment between chunks; paced so the middle chunk can't have landed yet
        // right after the first -- see the assertion below for why that matters.
        val bytes = ByteArray(600_000) { (it % 251).toByte() }
        transport.blobs[blobId] = bytes
        transport.mediaChunkDelayMs = 250

        transport.injectIncoming(
            "peer-ticket",
            Frame.MediaOffer(id = blobId, kind = "image", name = "cat.png", mime = "image/png", size = bytes.size.toLong(), fetchTicket = "peer-ticket"),
        )
        runCurrent()

        fun attachment(): Attachment? =
            azulaState.conv(convId).messages.flatMap { it.attachments }.firstOrNull { it.id == blobId }
        assertTrue(attachment() != null, "expected the offer to append an attachment immediately")
        assertEquals(TransferState.Offered, attachment()!!.state, "should be Offered before the auto-fetch's first chunk lands")

        // Await exactly two chunk writes: since chunk 3 (the last) requires its own
        // 250ms pacing delay that can only start counting down *after* chunk 2 has
        // fully landed, Complete is structurally impossible yet at this point --
        // this is why the assertion below can require Downloading rather than
        // merely tolerating it.
        blobStore.writes.receive()
        blobStore.writes.receive()
        val mid = pollForAttachment(azulaState, convId, blobId) { it.state !is TransferState.Offered }
        assertTrue(mid.state is TransferState.Downloading, "expected Downloading before the final chunk could possibly have landed, got ${mid.state}")

        val dto = awaitSaveMatching(messageStore, convId) { d ->
            d.messages.any { m -> m.attachments.any { it.id == blobId && it.state == "complete" } }
        }
        assertTrue(dto.messages.any { it.attachments.any { it.id == blobId } }, "expected the completed attachment to be persisted")

        assertEquals(TransferState.Complete, attachment()!!.state)
        assertEquals(bytes.size.toLong(), blobStore.sizeOnDisk(blobId))
        assertTrue(bytes.contentEquals(blobStore.bytesFor(blobId)), "downloaded bytes should match the served blob exactly")
    }

    @Test
    fun oversizedVideoOfferStaysOfferedUntilExplicitFetch() = runTest {
        val transport = FakeTransport()
        val blobStore = InMemoryBlobStore()
        val messageStore = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val blobId = "vid-1"
        // The offer *declares* a size over AUTO_DOWNLOAD_THRESHOLD (so auto-fetch
        // must not trigger); the actual served bytes are deliberately much smaller
        // for test speed -- onMediaOffer only ever looks at the declared frame.size
        // to gate auto-download, never the real blob, so this doesn't compromise
        // what's under test here (offer/blob size *consistency* is covered by the
        // restart-reconciliation test instead).
        val bytes = ByteArray(5_000) { it.toByte() }
        transport.blobs[blobId] = bytes

        transport.injectIncoming(
            "peer-ticket",
            Frame.MediaOffer(
                id = blobId, kind = "video", name = "clip.mp4", mime = "video/mp4",
                size = AUTO_DOWNLOAD_THRESHOLD + 1, fetchTicket = "peer-ticket",
            ),
        )
        runCurrent()

        fun attachment(): Attachment? =
            azulaState.conv(convId).messages.flatMap { it.attachments }.firstOrNull { it.id == blobId }
        assertEquals(TransferState.Offered, attachment()?.state, "an over-threshold video must not auto-download")
        assertEquals(0L, blobStore.sizeOnDisk(blobId), "nothing should have been fetched yet")

        azulaState.startFetch(convId, blobId)
        awaitSaveMatching(messageStore, convId) { d -> d.messages.any { m -> m.attachments.any { it.id == blobId && it.state == "complete" } } }

        assertEquals(TransferState.Complete, attachment()!!.state)
        assertTrue(bytes.contentEquals(blobStore.bytesFor(blobId)))
    }

    @Test
    fun cancelMidFetchKeepsPartialBytesAndResumeSendsTheRightOffset() = runTest {
        val transport = FakeTransport()
        val blobStore = InMemoryBlobStore()
        val messageStore = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val blobId = "vid-2"
        val bytes = ByteArray(600_000) { (it % 199).toByte() } // 3 chunks: 256K, 256K, ~88K
        transport.blobs[blobId] = bytes
        transport.mediaChunkDelayMs = 300 // real pacing so cancel lands inside the gap before chunk 2

        transport.injectIncoming(
            "peer-ticket",
            Frame.MediaOffer(
                id = blobId, kind = "video", name = "clip.mp4", mime = "video/mp4",
                size = AUTO_DOWNLOAD_THRESHOLD + 1, fetchTicket = "peer-ticket",
            ),
        )
        runCurrent()

        azulaState.startFetch(convId, blobId)
        blobStore.writes.receive() // first chunk (256 KiB) landed
        azulaState.cancelFetch(blobId)

        // The fetch job's `finally` runs on a real background dispatcher, same as
        // MediaService.startFetch itself, so its Failed state assignment isn't
        // synchronized with anything the test's virtual clock can drive -- poll the
        // live (Compose) state directly rather than assume anything about
        // persistence-save ordering (messageStore.saves' relative ordering across
        // the offer's own save and this cancellation's save turned out to not be a
        // reliable sync point across platforms -- see the reply to "main" for why).
        val afterCancel = pollForAttachment(azulaState, convId, blobId) { it.state is TransferState.Failed }
        assertTrue(afterCancel.state is TransferState.Failed, "expected Failed after cancelling mid-fetch, got ${afterCancel.state}")

        val partialSize = blobStore.sizeOnDisk(blobId)
        assertEquals(262_144L, partialSize, "expected exactly one chunk's worth of bytes retained")

        val firstRequest = transport.mediaRequests.receive()
        assertEquals(0L, firstRequest.offset, "the first request should start from scratch")

        azulaState.startFetch(convId, blobId)
        val secondRequest = transport.mediaRequests.receive()
        assertEquals(partialSize, secondRequest.offset, "the retry must resume from exactly what's already on disk")

        val completed = pollForAttachment(azulaState, convId, blobId) { it.state is TransferState.Complete }
        assertEquals(TransferState.Complete, completed.state)
        assertEquals(bytes.size.toLong(), blobStore.sizeOnDisk(blobId))
        assertTrue(bytes.contentEquals(blobStore.bytesFor(blobId)), "resumed download should still match the full blob byte-for-byte")
    }

    @Test
    fun offerOverMaxBytesIsRejectedWithoutFetching() = runTest {
        val transport = FakeTransport()
        val blobStore = InMemoryBlobStore()
        val messageStore = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val blobId = "huge-1"
        transport.injectIncoming(
            "peer-ticket",
            Frame.MediaOffer(
                id = blobId, kind = "video", name = "movie.mp4", mime = "video/mp4",
                size = MAX_MEDIA_BYTES + 1, fetchTicket = "peer-ticket",
            ),
        )
        runCurrent()

        val attachment = azulaState.conv(convId).messages.flatMap { it.attachments }.firstOrNull { it.id == blobId }
        assertTrue(attachment != null, "an over-cap offer should still append a (failed) placeholder")
        val state = attachment.state
        assertTrue(state is TransferState.Failed && state.reason == "too large", "expected Failed(\"too large\"), got $state")
        assertTrue(transport.mediaRequests.tryReceive().isFailure, "an over-cap offer must never trigger a fetch")
    }

    @Test
    fun restartReconciliationRecomputesStateFromDiskTruth() = runTest {
        val blobId = "blob-restart"
        val total = 1000L

        val seed = mapOf(
            "peerX" to ConversationDto(
                name = "peerX", kind = ConversationKind.PEER, glyph = "P",
                messages = listOf(
                    MessageDto(
                        id = "m0", role = Role.THEM,
                        attachments = listOf(
                            AttachmentDto(id = blobId, name = "vid.mp4", mime = "video/mp4", size = total, blobId = blobId, state = "offered"),
                        ),
                    ),
                ),
            ),
        )

        // Partial bytes on disk -> Downloading(partial, total).
        val partialBlobStore = InMemoryBlobStore(mapOf(blobId to ByteArray(400)))
        val convStore1 = ConversationStore()
        val coordinator1 = PersistenceCoordinator(convStore1, InMemoryMessageStore(seed), backgroundScope, partialBlobStore)
        coordinator1.restoreAll()
        val attachment1 = convStore1.conv("peerX").messages.first().attachments.first()
        assertEquals(TransferState.Downloading(400, total), attachment1.state)

        // Full bytes on disk -> Complete.
        val fullBlobStore = InMemoryBlobStore(mapOf(blobId to ByteArray(total.toInt())))
        val convStore2 = ConversationStore()
        val coordinator2 = PersistenceCoordinator(convStore2, InMemoryMessageStore(seed), backgroundScope, fullBlobStore)
        coordinator2.restoreAll()
        val attachment2 = convStore2.conv("peerX").messages.first().attachments.first()
        assertEquals(TransferState.Complete, attachment2.state)

        // Nothing on disk yet -> Offered.
        val emptyBlobStore = InMemoryBlobStore()
        val convStore3 = ConversationStore()
        val coordinator3 = PersistenceCoordinator(convStore3, InMemoryMessageStore(seed), backgroundScope, emptyBlobStore)
        coordinator3.restoreAll()
        val attachment3 = convStore3.conv("peerX").messages.first().attachments.first()
        assertEquals(TransferState.Offered, attachment3.state)
    }

    @Test
    fun sendMediaWritesTheBlobAppendsLocallyAndEmitsAnOffer() = runTest {
        val transport = FakeTransport()
        val blobStore = InMemoryBlobStore()
        val messageStore = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val bytes = ByteArray(2_000) { it.toByte() }
        // AzulaState.sendMedia fires the blob write + local append on a real
        // background dispatcher (it's fire-and-forget Unit, meant to be safe to
        // call from the UI thread even for large payloads) -- await it via the
        // BlobStore write + the first persistence save rather than assuming it's
        // already done when this call returns.
        azulaState.sendMedia(convId, bytes, name = "photo.png", mime = "image/png", caption = "look at this")

        blobStore.writes.receive()
        awaitSaveMatching(messageStore, convId) { d -> d.messages.any { m -> m.attachments.any { it.mime == "image/png" } } }

        val meMessage = azulaState.conv(convId).messages.last { it.attachments.isNotEmpty() }
        assertEquals(Role.ME, meMessage.role)
        val attachment = meMessage.attachments.single()
        assertEquals(TransferState.Complete, attachment.state, "the sender's own copy is complete immediately")
        assertTrue(bytes.contentEquals(blobStore.bytesFor(attachment.blobId!!)), "the blob should be written under the attachment's blobId")

        // sendMedia fills in fetchTicket and emits the Frame.MediaOffer asynchronously
        // (myTicket() is suspend) -- await the offer landing on the wire rather than
        // racing it. sentFrames is shared across every loopback this transport
        // creates, so it also carries the Frame.Hello pings connectPeer sent while
        // racing CHAT/LLM/TERM earlier -- skip past those to the actual offer.
        val sentOffer = awaitFrameMatching(transport) { it is Frame.MediaOffer }
        assertEquals(attachment.id, (sentOffer as Frame.MediaOffer).id)
        assertEquals(bytes.size.toLong(), sentOffer.size)

        awaitSaveMatching(messageStore, convId) { d -> d.messages.any { m -> m.attachments.any { it.id == attachment.id && it.fetchTicket != null } } }
    }

    /**
     * On-device runtime testing surfaced audio/file attachments (picked via
     * Android's SAF `GetContent` picker) showing a "blob missing" / "unknown
     * blob" Failed+retry chrome in a `-mock` PEER conversation, while photo
     * attachments (Photo Picker) looked fine. This test (and its sibling below)
     * disambiguates real send bug vs. mock-rendering artifact: it drives
     * [AzulaState.sendMedia] (the PEER offer/fetch path an "Audio" pick uses) with
     * an audio MIME and asserts the ME attachment reaches [TransferState.Complete]
     * with the real bytes readable back out of the [BlobStore] -- i.e. the SEND
     * path is sound regardless of media kind. (The actual bug turned out to be
     * `AudioAttachmentView` calling `state.blobPath` on this already-Complete
     * attachment: `InMemoryBlobStore.pathFor` is deliberately null (off-disk),
     * which the view mistook for a failed transfer -- see Chat.kt.)
     */
    @Test
    fun sendAudioViaPeerSendMediaReachesCompleteWithReadableBlob() = runTest {
        val transport = FakeTransport()
        val blobStore = InMemoryBlobStore()
        val messageStore = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val bytes = ByteArray(4_000) { (it % 233).toByte() }
        azulaState.sendMedia(convId, bytes, name = "voice-memo.m4a", mime = "audio/mp4", caption = null)

        blobStore.writes.receive()
        awaitSaveMatching(messageStore, convId) { d -> d.messages.any { m -> m.attachments.any { it.mime == "audio/mp4" } } }

        val meMessage = azulaState.conv(convId).messages.last { it.attachments.isNotEmpty() }
        assertEquals(Role.ME, meMessage.role)
        val attachment = meMessage.attachments.single()
        assertEquals(TransferState.Complete, attachment.state, "the sender's own audio copy is complete immediately")
        assertEquals(dev.azula.model.MediaKind.AUDIO, attachment.kind)
        assertTrue(bytes.contentEquals(blobStore.bytesFor(attachment.blobId!!)), "the audio blob's bytes should be readable back out of the BlobStore")

        // Mirrors AudioAttachmentView's own check: under the mock's InMemoryBlobStore
        // this is null by design (see InMemoryBlobStore.pathFor) -- a real device's
        // FileBlobStore returns non-null here, so this is the mock-only condition
        // the view must not mistake for a failed send.
        assertEquals(null, azulaState.blobPath(attachment.blobId!!), "expected the mock's off-disk store to have no path, by design")
    }

    /**
     * The other half of the disambiguation above: an audio file picked via the
     * generic "File" menu entry (also SAF `GetContent`, and also possibly
     * MIME-sniffed to an audio type) never goes through [AzulaState.sendMedia] at
     * all -- [dev.azula.ui.Chat.AttachMenu]'s File entry always calls
     * [AzulaState.sendFile] regardless of conversation kind (see ChatService.sendFile
     * -> MediaService.ingestLocalBlob). Confirms that path, too, lands the ME
     * attachment at Complete with real, readable bytes -- so neither of the two
     * real send paths an on-device audio/file pick can take is broken.
     */
    @Test
    fun sendAudioViaChatServiceSendFileIngestsLocallyAsComplete() = runTest {
        val transport = FakeTransport()
        val blobStore = InMemoryBlobStore()
        val messageStore = InMemoryMessageStore()
        val azulaState = testAzulaState(backgroundScope, transport, messageStore, blobStore)
        azulaState.start()
        runCurrent()

        azulaState.connectPeer("peer-ticket")
        runCurrent()
        val convId = azulaState.conversations.single { it.kind == ConversationKind.PEER }.id

        val bytes = ByteArray(4_000) { (it % 211).toByte() }
        azulaState.sendFile(convId, bytes, name = "voice-memo.m4a", mime = "audio/mp4")

        awaitSaveMatching(messageStore, convId) { d -> d.messages.any { m -> m.attachments.any { it.mime == "audio/mp4" } } }

        val meMessage = azulaState.conv(convId).messages.last { it.attachments.isNotEmpty() }
        assertEquals(Role.ME, meMessage.role)
        val attachment = meMessage.attachments.single()
        assertEquals(TransferState.Complete, attachment.state, "sendFile's local ingestion completes immediately")
        assertEquals(dev.azula.model.MediaKind.AUDIO, attachment.kind)
        assertTrue(attachment.blobId != null, "ingestLocalBlob should have written a real blobId-backed attachment")
        assertTrue(bytes.contentEquals(blobStore.bytesFor(attachment.blobId!!)), "the ingested audio blob's bytes should be readable back out of the BlobStore")
    }
}

private suspend fun awaitSaveMatching(
    store: InMemoryMessageStore,
    convId: String,
    maxAttempts: Int = 50,
    predicate: (ConversationDto) -> Boolean,
): ConversationDto {
    repeat(maxAttempts) {
        val (id, dto) = store.saves.receive()
        if (id == convId && predicate(dto)) return dto
    }
    throw AssertionError("no save of '$convId' matched the predicate within $maxAttempts saves")
}

/** Drains [FakeTransport.sentFrames] until [predicate] matches, skipping anything
 *  else queued ahead of it (e.g. connectPeer's Frame.Hello handshake pings). */
private suspend fun awaitFrameMatching(
    transport: FakeTransport,
    maxAttempts: Int = 50,
    predicate: (Frame) -> Boolean,
): Frame {
    repeat(maxAttempts) {
        val frame = transport.sentFrames.receive()
        if (predicate(frame)) return frame
    }
    throw AssertionError("no sent frame matched the predicate within $maxAttempts frames")
}

/**
 * Bounded real-time poll for [MediaService]'s live (Compose) attachment state --
 * used only where there's no save/write channel to await instead. Real, not
 * virtual, `delay` (via `withContext(Dispatchers.Default)`), since the fetch job
 * this observes runs on a real background dispatcher.
 */
private suspend fun pollForAttachment(
    azulaState: AzulaState,
    convId: String,
    attachmentId: String,
    // Generous ceiling (10s): returns as soon as the predicate matches, but a
    // loaded machine (parallel suites + the iOS simulator) can starve the fetch
    // job well past the second a quiet run needs.
    maxAttempts: Int = 400,
    intervalMs: Long = 25,
    predicate: (Attachment) -> Boolean,
): Attachment {
    repeat(maxAttempts) {
        val a = azulaState.conv(convId).messages.flatMap { it.attachments }.firstOrNull { it.id == attachmentId }
        if (a != null && predicate(a)) return a
        withContext(Dispatchers.Default) { delay(intervalMs) }
    }
    throw AssertionError("attachment '$attachmentId' never satisfied the predicate within ${maxAttempts * intervalMs}ms")
}
