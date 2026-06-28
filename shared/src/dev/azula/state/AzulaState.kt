package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.model.Attachment
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.model.ConnectStage
import dev.azula.model.DicePhase
import dev.azula.model.InlineWidget
import dev.azula.model.Message
import dev.azula.model.PollOption
import dev.azula.model.Role
import dev.azula.model.TerminalLine
import dev.azula.model.WidgetState
import dev.azula.net.Alpns
import dev.azula.net.FakeTransport
import dev.azula.net.FileEncoding
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import dev.azula.net.OutgoingFile
import dev.azula.net.P2pStream
import dev.azula.net.receiveFile
import dev.azula.net.sendFile
import dev.azula.link.DeepLinkBus
import dev.azula.terminal.TermScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class MScreen { HOME, CONNECT, CHAT }

/** Per-conversation mutable state: the thread, the draft, and any live stream. */
class ConversationState(seed: List<Message>) {
    val messages = mutableStateListOf<Message>().also { it.addAll(seed) }
    var draft by mutableStateOf("")
    var online by mutableStateOf(false)
    var stream: P2pStream? = null
    var streamingMsgId: String? = null
}

/**
 * The single source of truth for the whole app — a plain holder of Compose
 * snapshot state, observed identically on every platform. Owns the [transport]
 * and a long-lived [scope]. Conversations stay lively offline (canned azula,
 * local terminal) and go live the moment a real iroh stream is wired in.
 */
class AzulaState(
    private var transport: IrohTransport,
    private val scope: CoroutineScope,
) {
    val conversations: List<Conversation> = listOf(
        Conversation("azula", "azula", ConversationKind.LLM, "✦", "your llm · always on", "tap to ask or play"),
        Conversation("zuko", "zuko", ConversationKind.PEER, "z", "connected · direct p2p", "12ms?! that's nuts"),
        Conversation("term", "terminal", ConversationKind.TERMINAL, "›_", "zuko@remote · live", "▸ ready on :5173"),
    )

    private val convState: Map<String, ConversationState> = mapOf(
        "azula" to ConversationState(seedAzula()),
        "zuko" to ConversationState(seedZuko()),
        "term" to ConversationState(emptyList()),
    )

    // Desktop: a conversation id, or "connect".
    var desktopActive by mutableStateOf("azula")
        private set

    // Mobile navigation.
    var mScreen by mutableStateOf(MScreen.HOME)
        private set
    var mActive by mutableStateOf("azula")
        private set

    var thinking by mutableStateOf(false)
        private set
    var copied by mutableStateOf(false)
        private set
    var myCode by mutableStateOf("")
        private set
    var connectStage by mutableStateOf(ConnectStage.IDLE)
        private set
    var connectStep by mutableStateOf(0)
        private set
    var connectInput by mutableStateOf("")
    var rttMs by mutableStateOf<Int?>(null)
        private set

    val widgets = mutableStateMapOf<String, WidgetState>()
    val termLines = mutableStateListOf<TerminalLine>().also { it.addAll(seedTerm()) }
    val termScreen = TermScreen()

    private var azIdx = 0
    private var idCounter = 0
    private fun nextId(p: String): String = "$p${idCounter++}"

    fun conv(id: String): ConversationState = convState.getValue(id)
    fun conversation(id: String): Conversation = conversations.first { it.id == id }

    /** A cosmetic, human-friendly label derived from the node id. Copy uses [myCode]. */
    val shortCode: String
        get() {
            val src = transport.nodeId ?: return "connecting…"
            val h = src.filter { it.isLetterOrDigit() }
            if (h.length < 4) return "··· offline"
            val words = MNEMONICS
            fun pick(i: Int) = words[(h[i % h.length].code) % words.size]
            return "${h.take(2)} · ${pick(2)} · ${pick(5)}"
        }

    // ---- lifecycle ----

    suspend fun start() {
        // An incoming deeplink (azula://connect?code= / azula.app/s/<token>) dials it.
        DeepLinkBus.setHandler { token -> connectPeer(token) }
        try {
            transport.bind(Alpns.ALL)
        } catch (e: Throwable) {
            // Real binding unavailable (e.g. missing native lib) — degrade to demo.
            transport = FakeTransport().also { it.bind(Alpns.ALL) }
        }
        myCode = runCatching { transport.myTicket() }.getOrDefault("")
        scope.launch {
            runCatching {
                transport.incoming().collect { inc -> wireStream(inc.alpn, inc.stream) }
            }
        }
    }

    // ---- navigation ----

    fun selectDesktop(id: String) { desktopActive = id }
    fun openConnectDesktop() { desktopActive = "connect"; resetConnect() }
    fun openChatMobile(id: String) { mActive = id; mScreen = MScreen.CHAT }
    fun openConnectMobile() { mScreen = MScreen.CONNECT; resetConnect() }
    fun mobileBack() { mScreen = MScreen.HOME }

    private fun resetConnect() {
        if (connectStage != ConnectStage.DIALING) {
            connectStage = ConnectStage.IDLE
            connectStep = 0
        }
    }

    // ---- input / sending ----

    fun updateDraft(convId: String, text: String) { conv(convId).draft = text }

    fun send(convId: String) {
        val cs = conv(convId)
        val txt = cs.draft.trim()
        if (txt.isEmpty()) return
        if (convId == "term") { cs.draft = ""; runCommand(txt); return }
        cs.draft = ""
        cs.messages.add(Message(nextId("u"), Role.ME, text = txt))
        val stream = cs.stream
        when {
            stream != null -> scope.launch {
                runCatching { stream.sendLine(FrameCodec.encode(Frame.Chat(txt))) }
            }
            convId == "azula" -> azulaReply(txt)
        }
    }

    /**
     * Send a file attachment to the conversation identified by [convId].
     * Appends a local [Message] immediately (so the UI reacts), then launches the
     * transfer on the live stream if one is open.
     */
    fun sendFile(
        convId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        encoding: FileEncoding = FileEncoding.BASE64,
        caption: String? = null,
    ) {
        val cs = conv(convId)
        val msgId = nextId("f")
        val attachment = Attachment(
            id = msgId,
            name = name,
            mime = mime,
            size = bytes.size.toLong(),
            bytes = bytes,
            caption = caption,
        )
        cs.messages.add(Message(msgId, Role.ME, text = caption, attachments = listOf(attachment)))
        val stream = cs.stream ?: return
        scope.launch {
            runCatching {
                sendFile(stream, OutgoingFile(name = name, mime = mime, bytes = bytes, caption = caption), encoding)
            }
        }
    }

    fun markCopied() {
        copied = true
        scope.launch { delay(1400); copied = false }
    }

    // ---- terminal ----

    fun runCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim()
        val stream = conv("term").stream
        if (stream != null) {
            // Live mode: send the line raw; the shell will echo it back via termScreen.
            scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(cmd + "\n"))) } }
            return
        }
        // Demo mode: show the prompt line locally and produce canned output.
        termLines.add(TerminalLine(TerminalLine.Kind.CMD, cmd))
        termOut(cmd).forEach { termLines.add(it) }
    }

    /** Send Ctrl-C to the remote shell when a live term stream is open. */
    fun terminalInterrupt() {
        val stream = conv("term").stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(""))) } }
    }

    private fun termOut(cmdRaw: String): List<TerminalLine> {
        val c = cmdRaw.trim().lowercase()
        fun out(t: String, tint: TerminalLine.Tint = TerminalLine.Tint.NORMAL) =
            TerminalLine(TerminalLine.Kind.OUT, t, tint)
        return when {
            c == "ls" -> listOf(out("src/   package.json   iroh.toml   README.md"))
            c == "help" -> listOf(out("commands: ls · whoami · ping · iroh status · clear"))
            c == "whoami" -> listOf(out("zuko"))
            c == "ping" -> listOf(out("64 bytes from peer: time=12.0 ms (direct, e2e)"))
            c.startsWith("iroh") -> listOf(out("✓ direct · 12ms · e2e · 0 relays", TerminalLine.Tint.GREEN))
            c == "clear" -> { scope.launch { termLines.clear() }; emptyList() }
            c.isEmpty() -> emptyList()
            else -> listOf(out("azula: command not found: $cmdRaw (try `help`)", TerminalLine.Tint.ERROR))
        }
    }

    // ---- canned azula (used until a real LLM stream is wired) ----

    private fun azulaReply(userTxt: String) {
        thinking = true
        val low = userTxt.lowercase()
        val reply: List<Message> = when {
            Regex("game|dice|play|roll").containsMatchIn(low) -> listOf(
                Message(nextId("r"), Role.AZULA, text = "you're on. **first to roll higher wins.** 🎲"),
                Message(nextId("r"), Role.AZULA, widget = InlineWidget.Dice()),
            )
            Regex("poll|vote").containsMatchIn(low) -> listOf(
                Message(nextId("r"), Role.AZULA, text = "spinning one up — tap to vote:"),
                Message(
                    nextId("r"), Role.AZULA,
                    widget = InlineWidget.Poll(
                        "ship the build tonight?",
                        listOf(PollOption("ship it", 9), PollOption("sleep on it", 4)),
                    ),
                ),
            )
            Regex("terminal|shell|ssh").containsMatchIn(low) -> listOf(
                Message(
                    nextId("r"), Role.AZULA,
                    text = "opening zuko's shell — switch to the **terminal** tab and you'll share a live cursor.\n\n```bash\n$ whoami\nzuko\n```",
                ),
            )
            else -> {
                val cycle = listOf(
                    listOf(Message(nextId("r"), Role.AZULA, text = "current round-trips:\n\n| relay | rtt | load |\n|---|---|---|\n| **sfo** | 12ms | low |\n| ams | 88ms | low |\n| tok | 140ms | med |\n\nwe're direct anyway — this is just insurance.")),
                    listOf(
                        Message(nextId("r"), Role.AZULA, text = "sure — pick how we settle it:"),
                        Message(nextId("r"), Role.AZULA, widget = InlineWidget.Decide("what next?", listOf("play dice", "run a poll", "open terminal"))),
                    ),
                    listOf(Message(nextId("r"), Role.AZULA, text = "> **tip:** type `/terminal` to drive a shell, `/poll`, or `play a game`.\n\nI render **markdown**, tables, code, and spin up little UIs inline. what do you need?")),
                )
                cycle[azIdx % cycle.size].also { azIdx++ }
            }
        }
        scope.launch {
            delay(800)
            thinking = false
            reply.forEach { conv("azula").messages.add(it) }
        }
    }

    // ---- widget interactions ----

    fun pickDecide(msgId: String, opt: String) {
        widgets[msgId] = (widgets[msgId] ?: WidgetState()).copy(chosen = opt)
    }

    fun vote(msgId: String, index: Int) {
        val w = widgets[msgId] ?: WidgetState()
        if (w.voted != null) return
        widgets[msgId] = w.copy(voted = index)
    }

    fun rollDice(msgId: String) {
        val w = widgets[msgId] ?: WidgetState()
        if (w.dicePhase == DicePhase.ROLLING) return
        widgets[msgId] = w.copy(dicePhase = DicePhase.ROLLING)
        scope.launch {
            delay(700)
            widgets[msgId] = (widgets[msgId] ?: WidgetState()).copy(
                dicePhase = DicePhase.DONE,
                you = Random.nextInt(1, 7),
                them = Random.nextInt(1, 7),
            )
        }
    }

    // ---- connecting ----

    fun connectPeer(ticketRaw: String) {
        val ticket = ticketRaw.trim()
        if (ticket.isEmpty() || connectStage == ConnectStage.DIALING) return
        connectStage = ConnectStage.DIALING
        connectStep = 0
        scope.launch {
            val anim = launch { while (connectStep < 2) { delay(650); connectStep++ } }
            val results = Alpns.ALL.map { alpn ->
                async { runCatching { transport.connect(ticket, alpn) }.getOrNull()?.let { alpn to it } }
            }.awaitAll().filterNotNull()
            anim.cancel()
            if (results.isEmpty()) {
                connectStage = ConnectStage.FAILED
                return@launch
            }
            connectStep = 3
            results.forEach { (alpn, stream) -> wireStream(alpn, stream) }
            rttMs = 12
            connectStage = ConnectStage.CONNECTED
            delay(1200)
            val target = when {
                results.any { it.first == Alpns.CHAT } -> "zuko"
                results.any { it.first == Alpns.LLM } -> "azula"
                else -> "term"
            }
            navigateAfterConnect(target)
            connectStage = ConnectStage.IDLE
            connectInput = ""
            connectStep = 0
        }
    }

    private fun navigateAfterConnect(target: String) {
        desktopActive = target
        mActive = target
        mScreen = MScreen.CHAT
    }

    // ---- stream wiring + incoming frames ----

    private fun wireStream(alpn: String, stream: P2pStream) {
        val convId = when (alpn) {
            Alpns.CHAT -> "zuko"
            Alpns.LLM -> "azula"
            Alpns.TERM -> "term"
            else -> return
        }
        val cs = conv(convId)
        cs.stream = stream
        if (alpn == Alpns.TERM) termScreen.clear()
        cs.online = true
        receiveLoop(convId, stream)
    }

    private fun receiveLoop(convId: String, stream: P2pStream) {
        scope.launch {
            try {
                while (true) {
                    val line = stream.receiveLine() ?: break
                    if (line.isBlank()) continue
                    val frame = runCatching { FrameCodec.decode(line) }.getOrNull() ?: continue
                    // FileBegin must be handled here (suspend context, access to stream).
                    if (frame is Frame.FileBegin) {
                        runCatching {
                            val received = receiveFile(stream, frame) { stream.receiveLine() }
                            val attachment = Attachment(
                                id = received.id,
                                name = received.name,
                                mime = received.mime,
                                size = received.bytes.size.toLong(),
                                bytes = received.bytes,
                                caption = received.caption,
                            )
                            conv(convId).messages.add(
                                Message(
                                    nextId("r"),
                                    Role.THEM,
                                    text = received.caption,
                                    attachments = listOf(attachment),
                                )
                            )
                        }
                        continue
                    }
                    // Stray file_chunk / file_end outside a transfer are ignored.
                    if (frame is Frame.FileChunk || frame is Frame.FileEnd) continue
                    applyFrame(convId, frame)
                }
            } finally {
                conv(convId).online = false
                conv(convId).stream = null
            }
        }
    }

    private fun applyFrame(convId: String, frame: Frame) {
        val cs = conv(convId)
        when (frame) {
            is Frame.Chat -> cs.messages.add(Message(nextId("p"), Role.THEM, text = frame.text))
            is Frame.Term -> if (convId == "term") termScreen.feed(frame.line) else termLines.add(TerminalLine(TerminalLine.Kind.OUT, frame.line.trimEnd('\n')))
            is Frame.Thinking -> if (convId == "azula") thinking = frame.on
            is Frame.WidgetFrame -> cs.messages.add(Message(nextId("p"), Role.AZULA, widget = frame.widget))
            is Frame.Token -> {
                val role = if (convId == "azula") Role.AZULA else Role.THEM
                if (cs.streamingMsgId == null && !frame.done) {
                    val id = nextId("s")
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
                if (frame.done) cs.streamingMsgId = null
            }
            is Frame.Input -> Unit // server-side only
            // File frames are handled in receiveLoop directly; these are unreachable here.
            is Frame.FileBegin, is Frame.FileChunk, is Frame.FileEnd -> Unit
        }
    }

    companion object {
        private val MNEMONICS = listOf(
            "fox", "ember", "lyra", "onyx", "moth", "ivy", "rune", "echo",
            "wren", "kite", "sol", "vex", "nyx", "zephyr", "cobalt", "flint",
        )

        private fun seedAzula(): List<Message> = listOf(
            Message("a1", Role.AZULA, text = "**hey — I'm azula.** your peer that thinks.\n\nask me anything, drive a terminal, or say *play a game* and I'll build one right here in the chat."),
            Message("a2", Role.ME, text = "explain how iroh connects us, briefly"),
            Message("a3", Role.AZULA, text = "**the gist:**\n\n- iroh dials a **direct path** between us via UDP holepunching\n- no path? it falls back to a relay, then *upgrades* to direct\n- all traffic is **end-to-end encrypted** over QUIC\n\n```bash\niroh doctor connect <ticket>\n# ✓ direct · 12ms · e2e — no server in the middle\n```\n\nwant me to lock a backup relay, just in case?"),
            Message("a4", Role.AZULA, widget = InlineWidget.Decide("pick a backup relay", listOf("sfo", "ams", "tok"))),
        )

        private fun seedZuko(): List<Message> = listOf(
            Message("z1", Role.THEM, text = "did the tunnel come up?"),
            Message("z2", Role.ME, text = "yeah — **fully p2p**, no server between us"),
            Message("z3", Role.THEM, text = "ping?"),
            Message("z4", Role.ME, text = "`12ms`. direct."),
            Message("z5", Role.THEM, text = "**12ms?!** that's nuts 🔥"),
        )

        private fun seedTerm(): List<TerminalLine> = listOf(
            TerminalLine(TerminalLine.Kind.OUT, "azula terminal · sharing zuko@remote — 2 cursors live", TerminalLine.Tint.GREEN),
            TerminalLine(TerminalLine.Kind.CMD, "iroh doctor connect"),
            TerminalLine(TerminalLine.Kind.OUT, "✓ direct path established · 12ms · holepunched"),
            TerminalLine(TerminalLine.Kind.OUT, "✓ end-to-end encrypted · QUIC"),
            TerminalLine(TerminalLine.Kind.CMD, "npm run dev"),
            TerminalLine(TerminalLine.Kind.OUT, "▸ ready on :5173 — forwarded to you"),
        )
    }
}
