package dev.azula.net

import dev.azula.model.InlineWidget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    /** Either direction: an inline widget to render. */
    @Serializable
    @SerialName("widget")
    data class WidgetFrame(val widget: InlineWidget) : Frame()
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
