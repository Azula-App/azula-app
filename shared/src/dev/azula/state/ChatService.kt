package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.a2ui.A2uiExamples
import dev.azula.core.AppScope
import dev.azula.model.Attachment
import dev.azula.model.ConversationKind
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The chat feature carved out of AzulaState: sending text/file messages on a live
 * stream, and the canned local assistant used for an offline LLM conversation.
 * Sends go over the conversation's own [dev.azula.net.P2pStream] (not the transport
 * directly), so this service is independent of transport rebinding.
 */
@Inject
@SingleIn(AppScope::class)
class ChatService(
    private val convStore: ConversationStore,
    private val surfaceStore: SurfaceStore,
    private val persistence: PersistenceCoordinator,
    private val scope: CoroutineScope,
) {
    /** True while the (canned or remote) assistant is composing a reply. */
    var thinking by mutableStateOf(false)
        private set
    fun updateThinking(on: Boolean) { thinking = on }

    private var azIdx = 0

    /** Append a chat message locally, persist, then ship it on the live stream or
     *  fall back to the canned local assistant for an offline LLM chat. */
    fun send(convId: String, txt: String, isDemo: Boolean) {
        val cs = convStore.conv(convId)
        cs.draft = ""
        cs.messages.add(Message(convStore.nextId("u"), Role.ME, text = txt))
        persistence.save(convId, isDemo)
        val stream = cs.stream
        when {
            stream != null -> scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Chat(txt))) } }
            convStore.conversationOrNull(convId)?.kind == ConversationKind.LLM -> azulaReply(convId, txt, isDemo)
        }
    }

    fun sendFile(
        convId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        encoding: FileEncoding,
        caption: String?,
        isDemo: Boolean,
    ) {
        val cs = convStore.conv(convId)
        val msgId = convStore.nextId("f")
        val attachment = Attachment(msgId, name, mime, bytes.size.toLong(), bytes, caption)
        cs.messages.add(Message(msgId, Role.ME, text = caption, attachments = listOf(attachment)))
        persistence.save(convId, isDemo)
        val stream = cs.stream ?: return
        scope.launch {
            runCatching { sendFile(stream, OutgoingFile(name = name, mime = mime, bytes = bytes, caption = caption), encoding) }
        }
    }

    private fun azulaReply(convId: String, userTxt: String, isDemo: Boolean) {
        thinking = true
        val low = userTxt.lowercase()
        val reply: List<Message> = when {
            Regex("game|dice|play|roll").containsMatchIn(low) -> {
                val surface = A2uiExamples.diceSurface(convStore.nextId("ds"))
                surfaceStore.surfaces[surface.surfaceId] = surface
                listOf(
                    Message(convStore.nextId("r"), Role.AZULA, text = "you're on. **first to roll higher wins.** 🎲"),
                    Message(convStore.nextId("r"), Role.AZULA, a2ui = surface),
                )
            }
            Regex("poll|vote").containsMatchIn(low) -> {
                val surface = A2uiExamples.pollSurface(
                    convStore.nextId("ps"),
                    "ship the build tonight?",
                    listOf("ship it" to 9, "sleep on it" to 4),
                )
                surfaceStore.surfaces[surface.surfaceId] = surface
                listOf(
                    Message(convStore.nextId("r"), Role.AZULA, text = "spinning one up — tap to vote:"),
                    Message(convStore.nextId("r"), Role.AZULA, a2ui = surface),
                )
            }
            Regex("terminal|shell|ssh").containsMatchIn(low) -> listOf(
                Message(
                    convStore.nextId("r"), Role.AZULA,
                    text = "opening zuko's shell — switch to the **terminal** tab and you'll share a live cursor.\n\n```bash\n$ whoami\nzuko\n```",
                ),
            )
            else -> {
                val cycle = listOf(
                    listOf(Message(convStore.nextId("r"), Role.AZULA, text = "current round-trips:\n\n| relay | rtt | load |\n|---|---|---|\n| **sfo** | 12ms | low |\n| ams | 88ms | low |\n| tok | 140ms | med |\n\nwe're direct anyway — this is just insurance.")),
                    run {
                        val surface = A2uiExamples.decideSurface(
                            convStore.nextId("dc"),
                            "what next?",
                            listOf("play dice", "run a poll", "open terminal"),
                        )
                        surfaceStore.surfaces[surface.surfaceId] = surface
                        listOf(
                            Message(convStore.nextId("r"), Role.AZULA, text = "sure — pick how we settle it:"),
                            Message(convStore.nextId("r"), Role.AZULA, a2ui = surface),
                        )
                    },
                    listOf(Message(convStore.nextId("r"), Role.AZULA, text = "> **tip:** type `/terminal` to drive a shell, `/poll`, or `play a game`.\n\nI render **markdown**, tables, code, and spin up little UIs inline. what do you need?")),
                )
                cycle[azIdx % cycle.size].also { azIdx++ }
            }
        }
        scope.launch {
            delay(800)
            thinking = false
            reply.forEach { convStore.conv(convId).messages.add(it) }
            persistence.save(convId, isDemo)
        }
    }
}
