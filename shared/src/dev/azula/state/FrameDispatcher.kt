package dev.azula.state

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.azula.a2ui.A2uiCodec
import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.CreateSurface
import dev.azula.a2ui.DeleteSurface
import dev.azula.core.AppScope
import dev.azula.model.ConversationKind
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.notify.Notifier
import dev.azula.ui.decodeImageBitmap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The inbound-frame reaction core carved out of [ConnectService]: everything that
 * interprets a received [Frame] and mutates conversation / surface / persona state
 * -- `applyFrame`'s dispatch, the peer-profile handshake (`applyProfile` + the
 * "share my profile?" prompts), and the notification gating for both. [ConnectService]
 * still owns the transport/stream lifecycle and hands frames to this class as they
 * arrive off the wire (see `ConnectService.receiveLoop`); this class never needs to
 * call back into [ConnectService], keeping the dependency one-directional.
 */
@Inject
@SingleIn(AppScope::class)
class FrameDispatcher(
    private val scope: CoroutineScope,
    private val convStore: ConversationStore,
    private val surfaceStore: SurfaceStore,
    private val persistence: PersistenceCoordinator,
    private val chatService: ChatService,
    private val mediaService: MediaService,
    private val notifier: Notifier?,
) {
    /** Set by AzulaState: whether the app is currently in the foreground. */
    var foreground: () -> Boolean = { true }

    /** Inbound connections awaiting a "share my profile?" decision (prompt each time). */
    val pendingShares = mutableStateListOf<PendingShareRequest>()

    /**
     * Forget the live surfaces referenced by [convId]'s messages. Surfaces are
     * session-scoped, so this is called when a new stream is wired: cards stay
     * rendered from their message snapshots but freeze (Chat's
     * `surfaceId !in surfaces` check) until the peer re-creates them.
     */
    fun dropSurfaces(convId: String) {
        convStore.convOrNull(convId)?.messages
            ?.mapNotNull { it.a2ui?.surfaceId }
            ?.forEach { surfaceStore.surfaces.remove(it) }
    }

    fun applyFrame(convId: String, frame: Frame) {
        val cs = convStore.conv(convId)
        when (frame) {
            is Frame.Hello -> Unit
            is Frame.Chat -> {
                cs.messages.add(Message(convStore.nextId("p"), Role.THEM, text = frame.text))
                notify(convId, frame.text)
            }
            is Frame.Term -> scope.launch(terminalDispatcher) { cs.termScreen.feed(frame.line) }
            is Frame.Thinking -> if (convStore.conversationOrNull(convId)?.kind == ConversationKind.LLM) chatService.updateThinking(frame.on)
            is Frame.A2ui -> {
                val msg = A2uiCodec.parse(frame.message.toString()) ?: return
                when (msg) {
                    is CreateSurface -> {
                        val surface = A2uiSurface(msg.surfaceId, msg.catalogId)
                        surfaceStore.surfaces[msg.surfaceId] = surface
                        val existing = cs.messages.indexOfLast { it.a2ui?.surfaceId == msg.surfaceId }
                        if (existing >= 0) {
                            cs.messages[existing] = cs.messages[existing].copy(a2ui = surface)
                        } else {
                            cs.messages.add(Message(convStore.nextId("p"), Role.AZULA, a2ui = surface))
                            notify(convId, "sent an interactive card")
                        }
                    }
                    is DeleteSurface -> surfaceStore.surfaces.remove(msg.surfaceId)
                    else -> surfaceStore.surfaces[msg.surfaceId]?.apply(msg)
                }
            }
            is Frame.A2uiAction -> Unit
            is Frame.Token -> {
                val role = if (convStore.conversationOrNull(convId)?.kind == ConversationKind.LLM) Role.AZULA else Role.THEM
                if (cs.streamingMsgId == null && !frame.done) {
                    val id = convStore.nextId("s")
                    cs.streamingMsgId = id
                    cs.messages.add(Message(id, role, text = frame.delta))
                } else {
                    val id = cs.streamingMsgId
                    if (id != null) {
                        val idx = cs.messages.indexOfLast { it.id == id }
                        if (idx >= 0) {
                            val cur = cs.messages[idx]
                            cs.messages[idx] = cur.copy(text = (cur.text ?: "") + frame.delta)
                        }
                    }
                }
                if (frame.done) {
                    val finished = cs.streamingMsgId?.let { id -> cs.messages.lastOrNull { it.id == id } }
                    cs.streamingMsgId = null
                    finished?.text?.takeIf { it.isNotBlank() }?.let { notify(convId, it) }
                }
            }
            is Frame.Input, is Frame.Resize -> Unit
            is Frame.FileBegin, is Frame.FileChunk, is Frame.FileEnd, is Frame.Profile -> Unit
            is Frame.MediaOffer -> mediaService.onMediaOffer(convId, frame)
        }
        if (frame !is Frame.Token || frame.done) persistence.save(convId)
    }

    // ---- peer profile handshake ----

    fun applyProfile(convId: String, p: Frame.Profile) {
        if (p.name.isNotBlank()) {
            // The fallback kind matches ConnectService.kindForAlpn(Alpns.CHAT) --
            // a Profile frame is only ever sent over the CHAT ALPN.
            convStore.ensureConv(convId, convStore.conversationOrNull(convId)?.kind ?: ConversationKind.PEER, p.name)
        }
        convStore.convOrNull(convId)?.let { cs ->
            cs.peerDescription = p.description
            cs.peerAvatarB64 = p.avatar
            cs.peerAvatarMime = p.mime
            cs.peerAvatarBitmap = p.avatar?.let { decodeAvatar(it) }
        }
        persistence.save(convId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeAvatar(b64: String): ImageBitmap? =
        runCatching { decodeImageBitmap(Base64.decode(b64)) }.getOrNull()

    fun enqueueShareRequest(convId: String) {
        // Reconnects are routine now (auto-reconnect) — don't re-prompt a peer
        // the user already answered this session, or one whose profile we
        // already hold from an earlier exchange.
        if (convId in respondedShares) return
        val cs = convStore.convOrNull(convId)
        if (cs != null && (cs.peerAvatarB64 != null || cs.peerDescription != null)) return
        if (pendingShares.any { it.convId == convId }) return
        pendingShares.add(PendingShareRequest(convId, convStore.mnemonicCode(convId)))
        if (!foreground()) {
            notifier?.postMessage(convId, "Connection request", "${convStore.mnemonicCode(convId)} connected")
        }
    }

    /** Conversations whose share prompt was answered (either way) this session. */
    private val respondedShares = mutableSetOf<String>()

    fun respondShare(convId: String, frame: Frame.Profile?) {
        respondedShares += convId
        pendingShares.removeAll { it.convId == convId }
        if (frame != null) {
            convStore.convOrNull(convId)?.stream?.let { s ->
                scope.launch { runCatching { s.sendLine(FrameCodec.encode(frame)) } }
            }
        }
    }

    fun notify(convId: String, body: String) {
        if (foreground()) return
        if (convStore.conversationOrNull(convId)?.enabled == false) return
        val n = notifier ?: return
        val title = runCatching { convStore.conversation(convId).name }.getOrDefault(convId)
        n.postMessage(convId, title, body)
    }
}
