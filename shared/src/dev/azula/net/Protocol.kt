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
    /** Client → peer/server: a chat / LLM prompt. */
    @Serializable
    @SerialName("chat")
    data class Chat(val text: String) : Frame()

    /** Client → server: terminal keystrokes / a command line. */
    @Serializable
    @SerialName("input")
    data class Input(val text: String) : Frame()

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
