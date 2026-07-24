@file:OptIn(ExperimentalSerializationApi::class)

package dev.azula.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A contact entry inside an [IdentityBundle] snapshot: pins either the
 * contact's root public key (a certified contact) or its legacy node id --
 * exactly one of the two, never both -- plus an optional display name.
 * Senders are responsible for setting exactly one of [rootPk]/[nodeId]; this
 * isn't enforced at the type level, matching the Rust `Contact`'s convention
 * of leaving encode-side invariants to the caller. Unlike the rest of this
 * file's frames, absent fields here are omitted entirely (never emitted as
 * an explicit `null`) to match `proto.rs`'s `skip_serializing_if` pinning.
 */
@Serializable
data class Contact(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val rootPk: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nodeId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: String? = null,
)

/**
 * The identity snapshot delivered inside a [Frame.LinkGrant] (device-linking
 * spec: "the grant SHALL deliver the new certificate and an identity bundle:
 * root public key, all known certificates, revocation set, contacts
 * snapshot, and a mailbox hint when one exists"). [mailbox] is the connect
 * ticket of a mailbox-role sibling, when the identity has one -- omitted
 * (never emitted as `null`) otherwise.
 */
@Serializable
data class IdentityBundle(
    val rootPk: String,
    /** `"azd…"`-encoded device certificates. */
    val certs: List<String>,
    /** `"azr…"`-encoded revocation statements. */
    val revocations: List<String>,
    val contacts: List<Contact>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mailbox: String? = null,
)

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
     * [invite] is the full encoded invite string (`azi…`, see
     * `dev.azula.link.InviteCodec`), sent by a redeemer dialing a stranger until
     * the issuer has accepted it (see azula-docs/openspec/specs/invitations/design.md). Known
     * peers omit it; old peers ignore it. [cert] is the sender's `"azd…"`-encoded
     * device certificate, when it has one (multi-device-identity); an invalid
     * cert is treated the same as an absent one. Old peers omit/ignore it --
     * omitted entirely from the wire when absent, never sent as an explicit
     * `null`, so a legacy peer's frame still parses.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val name: String,
        val invite: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val cert: String? = null,
    ) : Frame()

    /**
     * Client → peer/server: a chat / LLM prompt. [id] is 16 random bytes as
     * lowercase hex, used for retry deduplication (multi-device-identity) --
     * omitted entirely from the wire when absent, never sent as an explicit
     * `null`, so a legacy peer's frame still parses.
     */
    @Serializable
    @SerialName("chat")
    data class Chat(
        val text: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val id: String? = null,
    ) : Frame()

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

    /**
     * Client → server: the first frame on a new TERM stream, opting into a
     * persistent (tmux-like) shell session. [session] null/absent asks the
     * server to start a fresh persistent session; a previously-received
     * [TermSession.session] id asks it to re-attach to that one (replaying
     * its scrollback) instead of spawning a new shell. Old servers don't
     * know this frame and ignore it (`Frame::Unknown` on the Rust side),
     * so sending it is always safe -- the legacy ephemeral-shell behavior
     * just continues exactly as before when no [TermSession] ever arrives.
     */
    @Serializable
    @SerialName("term_attach")
    data class TermAttach(val session: String? = null) : Frame()

    /**
     * Server → client: the session id now bound to this TERM stream, in
     * reply to [TermAttach] (or on a fresh ephemeral-turned-persistent
     * connect). [resumed] true means this re-attached an existing session --
     * a scrollback replay follows immediately as ordinary [Term] frames,
     * then live output resumes. A stream that never sent [TermAttach] never
     * receives this frame.
     */
    @Serializable
    @SerialName("term_session")
    data class TermSession(val session: String, val resumed: Boolean = false) : Frame()

    /** Server → client: the shell backing [session] exited (e.g. the user
     *  typed `exit`); [code] is its exit status if the server knows it. */
    @Serializable
    @SerialName("term_exit")
    data class TermExit(val session: String, val code: Int? = null) : Frame()

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

    /**
     * Peer → peer: metadata for a streamed media attachment (image/audio/video/
     * file). The body is NOT sent inline — the receiver pulls it on demand over
     * the `azula/media/0` ALPN (see `MediaFetchRequest` in MediaProtocol.kt) by
     * dialing [fetchTicket], the sender's own ticket at offer time. [thumbB64] is
     * a small (≤32 KiB) preview so the receiver can show something instantly.
     * [kind] is the wire string for a `MediaKind` (e.g. "image").
     */
    @Serializable
    @SerialName("media_offer")
    data class MediaOffer(
        val id: String,
        val kind: String,
        val name: String,
        val mime: String,
        val size: Long,
        val caption: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
        val thumbB64: String? = null,
        val fetchTicket: String,
    ) : Frame()

    // -----------------------------------------------------------------
    // Sync (`azula/sync/0`) and link (`azula/link/0`) frames — see
    // `azula-docs/openspec/changes/multi-device-identity/specs/
    // {account-sync,device-linking}/spec.md`. These JSON shapes are fixed:
    // they must match the Rust `proto.rs` `Frame` enum byte-for-byte.
    // -----------------------------------------------------------------

    /**
     * Device → device (sync ALPN): first frame on a new connection. [cert]
     * is the sender's `"azd…"`-encoded device certificate. Each side
     * verifies the other's cert before any vector or entry exchange; any
     * failure closes the connection.
     */
    @Serializable
    @SerialName("sync_hello")
    data class SyncHello(val cert: String) : Frame()

    /**
     * Device → device (sync ALPN): the sender's per-device high-water
     * vector — device public key hex to the highest **contiguous** seq
     * held for that device.
     */
    @Serializable
    @SerialName("sync_vector")
    data class SyncVector(val vector: Map<String, Long>) : Frame()

    /**
     * Device → device (sync ALPN): a batch of base64-encoded log entries,
     * at most 64 per frame, per-device in ascending seq order.
     */
    @Serializable
    @SerialName("sync_entries")
    data class SyncEntries(val entries: List<String>) : Frame()

    /**
     * Device → device (sync ALPN): acknowledges that the vector exchange
     * above is complete. Sent after the last [SyncEntries] batch; entries
     * appended afterwards are pushed live as further [SyncEntries] frames
     * with no further [SyncVector]/[SyncAck] round trip.
     */
    @Serializable
    @SerialName("sync_ack")
    data class SyncAck(val vector: Map<String, Long>) : Frame()

    /**
     * New device → root-holding device (link ALPN): first frame,
     * presenting the new device's freshly generated node public key,
     * requested display name, and requested roles bitfield.
     */
    @Serializable
    @SerialName("link_hello")
    data class LinkHello(val devicePk: String, val name: String, val roles: Int) : Frame()

    /**
     * Root-holding device → new device (link ALPN): grants a certificate
     * plus an [IdentityBundle] snapshot. Sent only after explicit user
     * confirmation on the root-holding device.
     */
    @Serializable
    @SerialName("link_grant")
    data class LinkGrant(val cert: String, val bundle: IdentityBundle) : Frame()

    /**
     * Root-holding device → new device (link ALPN): the link was declined
     * (or otherwise failed) — no certificate exists.
     */
    @Serializable
    @SerialName("link_reject")
    data class LinkReject(val reason: String) : Frame()
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

    /**
     * Encode/decode an [IdentityBundle] standalone -- multi-device-identity task 6.1's
     * persisted-on-the-linked-device form (see `dev.azula.persist.AppSettings.linkedIdentityBundleJson`)
     * and task 6.2's in-memory construction, both outside any [Frame] envelope. Reuses [json]
     * (same `ignoreUnknownKeys`/defaults posture as every other frame on the wire) rather than a
     * second `Json` instance.
     */
    fun encodeBundle(bundle: IdentityBundle): String = json.encodeToString(IdentityBundle.serializer(), bundle)

    /** Null on any decode failure (corrupt/foreign JSON) -- callers treat that exactly like
     *  "no linked identity persisted yet". */
    fun decodeBundle(s: String): IdentityBundle? =
        runCatching { json.decodeFromString(IdentityBundle.serializer(), s) }.getOrNull()
}
