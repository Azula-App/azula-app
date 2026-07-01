package dev.azula.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The wire protocol shared with the Rust `azula-cli` server. Messages are
 * newline-delimited JSON, one [Frame] per line, internally tagged on a "type"
 * field (kotlinx.serialization's default discriminator). The Rust side mirrors
 * this with `#[serde(tag = "type")]` and matching rename values.
 */
@Serializable
sealed class Frame {
    /**
     * Client → peer/server: the first frame on a new outbound connection — a
     * presence "ping" that announces this device and (importantly) unblocks the
     * bridge's accept loop so it flushes any mail queued for us. [name] is a
     * display fallback; a paired bridge identifies us by node id regardless.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(val name: String) : Frame()

    /** Client → peer/server: a chat / LLM prompt. */
    @Serializable
    @SerialName("chat")
    data class Chat(val text: String) : Frame()

    /** Client → server: terminal keystrokes / a command line. */
    @Serializable
    @SerialName("input")
    data class Input(val text: String) : Frame()

    /** Client → server: the terminal's size in character cells, so the remote
     *  PTY wraps output to match the app's viewport. */
    @Serializable
    @SerialName("resize")
    data class Resize(val cols: Int, val rows: Int) : Frame()

    /** Server → client: a streamed LLM token (or `done` sentinel). */
    @Serializable
    @SerialName("token")
    data class Token(val delta: String, val done: Boolean = false) : Frame()

    /** Server → client: the assistant is thinking. */
    @Serializable
    @SerialName("thinking")
    data class Thinking(val on: Boolean) : Frame()

    /** Server → client: a chunk of shell output. */
    @Serializable
    @SerialName("term")
    data class Term(val line: String) : Frame()

    /** Server → client: an A2UI message (create/update/delete surface). */
    @Serializable
    @SerialName("a2ui")
    data class A2ui(val message: JsonObject) : Frame()

    /** Client → server: a user action on an A2UI surface. */
    @Serializable
    @SerialName("a2ui_action")
    data class A2uiAction(val action: JsonObject) : Frame()

    /** File transfer: begins a multipart transfer. encoding is "base64" or "binary". */
    @Serializable
    @SerialName("file_begin")
    data class FileBegin(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val encoding: String,
        val caption: String? = null,
    ) : Frame()

    /** File transfer: one base64-encoded chunk (used when encoding == "base64" only). */
    @Serializable
    @SerialName("file_chunk")
    data class FileChunk(
        val id: String,
        val seq: Int,
        val data: String,
    ) : Frame()

    /** File transfer: signals that all body bytes / chunks have been sent. */
    @Serializable
    @SerialName("file_end")
    data class FileEnd(val id: String) : Frame()

    /**
     * Peer → peer: the sender's chosen profile (a "persona"). Sent over the CHAT
     * ALPN only — the receiver names the conversation after [name] and shows the
     * avatar. All fields but [name] are optional so the sender can share a
     * subset; an empty [name] means "name not shared". [avatar] is base64-encoded,
     * downscaled image bytes. Servers ignore this frame.
     */
    @Serializable
    @SerialName("profile")
    data class Profile(
        val name: String,
        val description: String? = null,
        val avatar: String? = null,
        val mime: String? = null,
    ) : Frame()
}

object FrameCodec {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Encode a frame to a single JSON line (no trailing newline). */
    fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame)

    /** Decode one JSON line into a frame. */
    fun decode(line: String): Frame = json.decodeFromString(Frame.serializer(), line)
}
