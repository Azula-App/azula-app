package dev.azula.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.a2ui.A2uiAction
import dev.azula.a2ui.A2uiCodec
import dev.azula.a2ui.A2uiExamples
import dev.azula.a2ui.A2uiSurface
import dev.azula.a2ui.CreateSurface
import dev.azula.a2ui.DeleteSurface
import dev.azula.model.Attachment
import dev.azula.model.Conversation
import dev.azula.model.ConversationKind
import dev.azula.model.ConnectStage
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.model.TerminalLine
import dev.azula.net.Alpns
import dev.azula.net.FakeTransport
import dev.azula.net.FileEncoding
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.azula.net.IrohTransport
import dev.azula.net.OutgoingFile
import dev.azula.net.P2pStream
import dev.azula.net.pairedPeerStore
import dev.azula.net.receiveFile
import dev.azula.net.sendFile
import dev.azula.link.DeepLinkBus
import dev.azula.terminal.TermScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

enum class MScreen { HOME, CONNECT, CHAT }

/** Per-conversation mutable state: the thread, the draft, and any live stream. */
class ConversationState(seed: List<Message>) {
    val messages = mutableStateListOf<Message>().also { it.addAll(seed) }
    var draft by mutableStateOf("")
    var online by mutableStateOf(false)
    var stream: P2pStream? = null
    var streamingMsgId: String? = null
    /** Each terminal session gets its own screen buffer. */
    val termScreen = TermScreen()
    /** The peer ticket this conversation was dialed from — used to spawn siblings. */
    var sourceTicket: String? = null
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
    val conversations = mutableStateListOf(
        Conversation("azula", "azula", ConversationKind.LLM, "✦", "your llm · always on", "tap to ask or play"),
        Conversation("zuko", "zuko", ConversationKind.PEER, "z", "connected · direct p2p", "12ms?! that's nuts"),
        Conversation("term", "terminal", ConversationKind.TERMINAL, "›_", "zuko@remote · live", "▸ ready on :5173"),
    )

    private val convState = mutableStateMapOf(
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

    /** All live A2UI surfaces, keyed by surfaceId. */
    val surfaces = mutableStateMapOf<String, A2uiSurface>()

    val termLines = mutableStateListOf<TerminalLine>().also { it.addAll(seedTerm()) }

    init {
        // Register any surfaces embedded in the seed messages so actions work immediately.
        convState.values.forEach { cs ->
            cs.messages.forEach { m ->
                m.a2ui?.let { surfaces[it.surfaceId] = it }
            }
        }
    }

    private var azIdx = 0
    private var idCounter = 0
    /** True when the real transport failed to bind and we fell back to demo mode. */
    private var demoMode = false
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
            demoMode = true
            transport = FakeTransport().also { it.bind(Alpns.ALL) }
        }
        myCode = runCatching { transport.myTicket() }.getOrDefault("")
        scope.launch {
            runCatching {
                transport.incoming().collect { inc -> wireStream(inc.alpn, inc.stream) }
            }
        }
        // Re-dial peers we've paired with before so a bridge can immediately
        // flush any messages it queued while we were backgrounded or closed.
        reconnectSaved()
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
        if (conversation(convId).kind == ConversationKind.TERMINAL) { cs.draft = ""; terminalInput(convId, txt); return }
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

    fun terminalInput(convId: String, cmdRaw: String) {
        val cmd = cmdRaw.trim()
        val stream = conv(convId).stream
        if (stream != null) {
            // Live mode: send the line raw; the shell echoes it back via termScreen.
            scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(cmd + "\n"))) } }
            return
        }
        // Demo mode (the seeded offline terminal): canned output.
        termLines.add(TerminalLine(TerminalLine.Kind.CMD, cmd))
        termOut(cmd).forEach { termLines.add(it) }
    }

    /** Send Ctrl-C to the remote shell when a live term stream is open. */
    fun terminalInterrupt(convId: String) {
        val stream = conv(convId).stream ?: return
        scope.launch { runCatching { stream.sendLine(FrameCodec.encode(Frame.Input(""))) } }
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
            Regex("game|dice|play|roll").containsMatchIn(low) -> {
                val surface = A2uiExamples.diceSurface(nextId("ds"))
                surfaces[surface.surfaceId] = surface
                listOf(
                    Message(nextId("r"), Role.AZULA, text = "you're on. **first to roll higher wins.** 🎲"),
                    Message(nextId("r"), Role.AZULA, a2ui = surface),
                )
            }
            Regex("poll|vote").containsMatchIn(low) -> {
                val surface = A2uiExamples.pollSurface(
                    nextId("ps"),
                    "ship the build tonight?",
                    listOf("ship it" to 9, "sleep on it" to 4),
                )
                surfaces[surface.surfaceId] = surface
                listOf(
                    Message(nextId("r"), Role.AZULA, text = "spinning one up — tap to vote:"),
                    Message(nextId("r"), Role.AZULA, a2ui = surface),
                )
            }
            Regex("terminal|shell|ssh").containsMatchIn(low) -> listOf(
                Message(
                    nextId("r"), Role.AZULA,
                    text = "opening zuko's shell — switch to the **terminal** tab and you'll share a live cursor.\n\n```bash\n$ whoami\nzuko\n```",
                ),
            )
            else -> {
                val cycle = listOf(
                    listOf(Message(nextId("r"), Role.AZULA, text = "current round-trips:\n\n| relay | rtt | load |\n|---|---|---|\n| **sfo** | 12ms | low |\n| ams | 88ms | low |\n| tok | 140ms | med |\n\nwe're direct anyway — this is just insurance.")),
                    run {
                        val surface = A2uiExamples.decideSurface(
                            nextId("dc"),
                            "what next?",
                            listOf("play dice", "run a poll", "open terminal"),
                        )
                        surfaces[surface.surfaceId] = surface
                        listOf(
                            Message(nextId("r"), Role.AZULA, text = "sure — pick how we settle it:"),
                            Message(nextId("r"), Role.AZULA, a2ui = surface),
                        )
                    },
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

    // ---- A2UI actions ----

    /**
     * Called when the user triggers an action on an A2UI surface.
     * If a live stream is open, encodes and sends it; otherwise interprets locally.
     */
    fun onA2uiAction(convId: String, action: A2uiAction) {
        val stream = conv(convId).stream
        if (stream != null) {
            scope.launch {
                runCatching {
                    stream.sendLine(FrameCodec.encode(Frame.A2uiAction(A2uiCodec.encodeAction(action))))
                }
            }
        } else {
            surfaces[action.surfaceId]?.let { A2uiExamples.handleEvent(it, action) }
        }
    }

    /**
     * Called when a two-way input (TextField, CheckBox, etc.) changes its value.
     */
    fun onA2uiDataChange(surface: A2uiSurface, path: String, value: JsonElement) {
        surface.setData(path, value)
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
            persistPeer(ticket)
            // Remember which connection each conversation came from so we can
            // spawn sibling sessions over it without re-pairing.
            results.forEach { (alpn, _) -> convIdForAlpn(alpn)?.let { conv(it).sourceTicket = ticket } }
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

    /**
     * Open a NEW session over the same peer connection as [convId] — a fresh
     * terminal or LLM conversation that reuses the known ticket (no re-pairing).
     * The server multiplexes it onto the existing connection as a new stream.
     */
    fun newSessionFrom(convId: String) {
        val ticket = conv(convId).sourceTicket ?: return
        val kind = conversation(convId).kind
        val alpn = when (kind) {
            ConversationKind.TERMINAL -> Alpns.TERM
            ConversationKind.LLM -> Alpns.LLM
            ConversationKind.PEER -> Alpns.CHAT
        }
        scope.launch {
            val stream = runCatching { transport.connect(ticket, alpn) }.getOrNull() ?: return@launch
            val n = conversations.count { it.kind == kind } + 1
            val id = nextId("sess")
            val (name, glyph, sub) = when (kind) {
                ConversationKind.TERMINAL -> Triple("terminal $n", "›_", "live session")
                ConversationKind.LLM -> Triple("session $n", "✦", "llm · live")
                ConversationKind.PEER -> Triple("peer $n", "z", "direct p2p")
            }
            convState[id] = ConversationState(emptyList()).also { it.sourceTicket = ticket }
            conversations.add(Conversation(id, name, kind, glyph, sub, "new session"))
            wireConv(id, stream, isTerminal = kind == ConversationKind.TERMINAL)
            mActive = id
            desktopActive = id
            mScreen = MScreen.CHAT
        }
    }

    /** Remember a peer ticket (most-recent 10) so we can auto-reconnect later. */
    private fun persistPeer(ticket: String) {
        pairedPeerStore()?.let { store ->
            store.save((store.load() + ticket).distinct().takeLast(10))
        }
    }

    /**
     * Re-dial every saved peer silently (no connect UI), filling in any
     * conversation that isn't already live. Retries a few times to ride out the
     * endpoint still coming up (e.g. just after a foreground rebind). No-op in
     * demo mode.
     */
    fun reconnectSaved() {
        if (demoMode) return
        val saved = pairedPeerStore()?.load().orEmpty()
        if (saved.isEmpty()) return
        saved.forEach { ticket ->
            scope.launch {
                repeat(3) {
                    val results = runCatching {
                        coroutineScope {
                            Alpns.ALL.map { alpn ->
                                async {
                                    runCatching { transport.connect(ticket, alpn) }.getOrNull()?.let { alpn to it }
                                }
                            }.awaitAll().filterNotNull()
                        }
                    }.getOrDefault(emptyList())
                    if (results.isNotEmpty()) {
                        results.forEach { (alpn, stream) -> wireStream(alpn, stream, replace = false) }
                        return@launch
                    }
                    delay(700)
                }
            }
        }
    }

    // ---- stream wiring + incoming frames ----

    private fun convIdForAlpn(alpn: String): String? = when (alpn) {
        Alpns.CHAT -> "zuko"
        Alpns.LLM -> "azula"
        Alpns.TERM -> "term"
        else -> null
    }

    private fun wireStream(alpn: String, stream: P2pStream, replace: Boolean = true) {
        val convId = convIdForAlpn(alpn) ?: return
        wireConv(convId, stream, isTerminal = alpn == Alpns.TERM, replace = replace)
    }

    /** Attach a live [stream] to [convId], replacing any current one unless [replace] is false. */
    private fun wireConv(convId: String, stream: P2pStream, isTerminal: Boolean, replace: Boolean = true) {
        val cs = conv(convId)
        // A silent reconnect must never disrupt an already-live conversation.
        if (!replace && cs.online && cs.stream != null) {
            scope.launch { runCatching { stream.close() } }
            return
        }
        cs.stream = stream
        if (isTerminal) cs.termScreen.clear()
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
            is Frame.Term -> cs.termScreen.feed(frame.line)
            is Frame.Thinking -> if (convId == "azula") thinking = frame.on
            is Frame.A2ui -> {
                val msg = A2uiCodec.parse(frame.message.toString()) ?: return
                when (msg) {
                    is CreateSurface -> {
                        val surface = A2uiSurface(msg.surfaceId, msg.catalogId)
                        surfaces[msg.surfaceId] = surface
                        cs.messages.add(Message(nextId("p"), Role.AZULA, a2ui = surface))
                    }
                    is DeleteSurface -> surfaces.remove(msg.surfaceId)
                    else -> surfaces[msg.surfaceId]?.apply(msg)
                }
            }
            is Frame.A2uiAction -> Unit // client→server only; ignored on receive
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

        private fun seedAzula(): List<Message> {
            val decideSurface = A2uiExamples.decideSurface("a4", "pick a backup relay", listOf("sfo", "ams", "tok"))
            return listOf(
                Message("a1", Role.AZULA, text = "**hey — I'm azula.** your peer that thinks.\n\nask me anything, drive a terminal, or say *play a game* and I'll build one right here in the chat."),
                Message("a2", Role.ME, text = "explain how iroh connects us, briefly"),
                Message("a3", Role.AZULA, text = "**the gist:**\n\n- iroh dials a **direct path** between us via UDP holepunching\n- no path? it falls back to a relay, then *upgrades* to direct\n- all traffic is **end-to-end encrypted** over QUIC\n\n```bash\niroh doctor connect <ticket>\n# ✓ direct · 12ms · e2e — no server in the middle\n```\n\nwant me to lock a backup relay, just in case?"),
                Message("a4", Role.AZULA, a2ui = decideSurface),
            )
        }

        private fun seedZuko(): List<Message> = listOf(
            Message("z1", Role.THEM, text = "did the tunnel come up?"),
            Message("z2", Role.ME, text = "yeah — **fully p2p**, no server between us"),
            Message("z3", Role.THEM, text = "ping?"),
            Message("z4", Role.ME, text = "`12ms`. direct."),
            Message("z5", Role.THEM, text = "**12ms?!** that's nuts 🔥"),
            demoImageMessage(),
        )

        // A small checkerboard BMP, embedded so the inline-image rendering path
        // is visible offline (no live transfer needed). BMP decodes on skiko
        // (JVM/iOS) and Android's BitmapFactory.
        private fun demoImageMessage(): Message {
            val bmp = demoImageBmp()
            return Message(
                "z6", Role.THEM, text = "sent you a pic 📷",
                attachments = listOf(Attachment("z6-img", "azula.bmp", "image/bmp", bmp.size.toLong(), bmp, null)),
            )
        }

        private fun demoImageBmp(): ByteArray {
            val w = 160
            val h = 100
            val rowSize = ((w * 3 + 3) / 4) * 4
            val out = ByteArray(54 + rowSize * h)
            fun le32(off: Int, v: Int) {
                out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v shr 8) and 0xFF).toByte()
                out[off + 2] = ((v shr 16) and 0xFF).toByte(); out[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun le16(off: Int, v: Int) { out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v shr 8) and 0xFF).toByte() }
            out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
            le32(2, out.size); le32(10, 54)
            le32(14, 40); le32(18, w); le32(22, h); le16(26, 1); le16(28, 24)
            le32(34, rowSize * h); le32(38, 2835); le32(42, 2835)
            var off = 54
            for (row in 0 until h) {
                for (x in 0 until w) {
                    val pink = ((x / 20) + (row / 20)) % 2 == 0
                    out[off] = (if (pink) 0x9B else 0x1A).toByte()
                    out[off + 1] = (if (pink) 0x2D else 0x10).toByte()
                    out[off + 2] = (if (pink) 0xFF else 0x16).toByte()
                    off += 3
                }
                off += rowSize - w * 3
            }
            return out
        }

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
