package dev.azula.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Per-kind JSON body shapes from
// azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
// "Event Kinds": `LogEntry.body` is UTF-8 JSON, kind-specific. Field names are
// snake_case to match the wire format azula-cli's `eventlog.rs`/fold produce and
// consume (see that spec's cross-language vector: `up_to_lamport`, not
// `upToLamport`, appears literally in the recorded three-entry-log bytes).
//
// Mirrors persistence-api/MessageStore.kt's codec convention: a shared [Json]
// instance, explicit serializers (no reified generics in actuals), and
// `encode`/`decode` functions that return `null` on any decode failure rather
// than throwing -- a malformed body (a corrupt entry, or an unknown kind's
// payload) must never crash the fold; see [AccountSyncFold].

val EventBodyJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** `0x01 message_out`: `{conversation, text, id?}`. */
@Serializable
data class MessageOutBody(
    val conversation: String,
    val text: String,
    val id: String? = null,
)

/** `0x02 message_in`: `{conversation, from_device_pk, text, id?}`. */
@Serializable
data class MessageInBody(
    val conversation: String,
    @SerialName("from_device_pk") val fromDevicePk: String,
    val text: String,
    val id: String? = null,
)

/** `0x03 read_marker`: `{conversation, up_to_lamport}`. */
@Serializable
data class ReadMarkerBody(
    val conversation: String,
    @SerialName("up_to_lamport") val upToLamport: Long,
)

/**
 * `0x04 contact_add` / `0x05 contact_remove`: `{root_pk | node_id, name?}` -- exactly
 * one of [rootPk]/[nodeId] is set. [key] is the contact/conversation key this entry
 * names: the hex root public key for a certified contact, or the hex node id for a
 * legacy one (spec: "conversation keys are the contact's root public key in hex, or
 * node id in hex for legacy contacts").
 */
@Serializable
data class ContactKeyBody(
    @SerialName("root_pk") val rootPk: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    val name: String? = null,
) {
    val key: String? get() = rootPk ?: nodeId
}

/** `0x06 device_add`: `{cert}` -- an `"azd…"`-encoded [dev.azula.core.DeviceCert]. */
@Serializable
data class DeviceAddBody(val cert: String)

/** `0x07 device_revoke`: `{revocation}` -- an `"azr…"`-encoded [dev.azula.core.DeviceRevocation]. */
@Serializable
data class DeviceRevokeBody(val revocation: String)

/** `0x08 profile_update`: `{name?, description?}`. A `null` field means "not touched
 *  by this entry" (per-field last-writer-wins -- see [AccountSyncFold]'s profile
 *  handling), not "clear this field". */
@Serializable
data class ProfileUpdateBody(
    val name: String? = null,
    val description: String? = null,
)

/**
 * `0x09 agent_in`: `{conversation, text, id?, from_name?}` -- cli-multi-session-relay's
 * relay-carried agent chat. [conversation] is the SESSION's public key in hex (not a
 * contact root/node id -- a session conversation's key space, distinct from peer
 * conversations). [fromName] is the session's announced display name, when known. Field
 * order here is pinned: it must match azula-cli's `eventlog.rs` byte-for-byte (see the
 * cross-language vector in `core/test/CrossLanguageVectorTest.kt`).
 */
@Serializable
data class AgentInBody(
    val conversation: String,
    val text: String,
    val id: String? = null,
    @SerialName("from_name") val fromName: String? = null,
)

/** `0x0A agent_out`: `{conversation, text, id?}` -- the identity's own reply into a
 *  session conversation. Same field shape as [MessageOutBody] but a distinct kind/type,
 *  since direction is determined by kind, not by an explicit field (mirrors
 *  message_out/message_in). */
@Serializable
data class AgentOutBody(
    val conversation: String,
    val text: String,
    val id: String? = null,
)

fun decodeMessageOutBody(body: ByteArray): MessageOutBody? =
    runCatching { EventBodyJson.decodeFromString(MessageOutBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeMessageInBody(body: ByteArray): MessageInBody? =
    runCatching { EventBodyJson.decodeFromString(MessageInBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeReadMarkerBody(body: ByteArray): ReadMarkerBody? =
    runCatching { EventBodyJson.decodeFromString(ReadMarkerBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeContactKeyBody(body: ByteArray): ContactKeyBody? =
    runCatching { EventBodyJson.decodeFromString(ContactKeyBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeDeviceAddBody(body: ByteArray): DeviceAddBody? =
    runCatching { EventBodyJson.decodeFromString(DeviceAddBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeDeviceRevokeBody(body: ByteArray): DeviceRevokeBody? =
    runCatching { EventBodyJson.decodeFromString(DeviceRevokeBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeProfileUpdateBody(body: ByteArray): ProfileUpdateBody? =
    runCatching { EventBodyJson.decodeFromString(ProfileUpdateBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeAgentInBody(body: ByteArray): AgentInBody? =
    runCatching { EventBodyJson.decodeFromString(AgentInBody.serializer(), body.decodeToString()) }.getOrNull()

fun decodeAgentOutBody(body: ByteArray): AgentOutBody? =
    runCatching { EventBodyJson.decodeFromString(AgentOutBody.serializer(), body.decodeToString()) }.getOrNull()

fun encodeMessageOutBody(value: MessageOutBody): ByteArray =
    EventBodyJson.encodeToString(MessageOutBody.serializer(), value).encodeToByteArray()

fun encodeMessageInBody(value: MessageInBody): ByteArray =
    EventBodyJson.encodeToString(MessageInBody.serializer(), value).encodeToByteArray()

fun encodeReadMarkerBody(value: ReadMarkerBody): ByteArray =
    EventBodyJson.encodeToString(ReadMarkerBody.serializer(), value).encodeToByteArray()

fun encodeContactKeyBody(value: ContactKeyBody): ByteArray =
    EventBodyJson.encodeToString(ContactKeyBody.serializer(), value).encodeToByteArray()

fun encodeDeviceAddBody(value: DeviceAddBody): ByteArray =
    EventBodyJson.encodeToString(DeviceAddBody.serializer(), value).encodeToByteArray()

fun encodeDeviceRevokeBody(value: DeviceRevokeBody): ByteArray =
    EventBodyJson.encodeToString(DeviceRevokeBody.serializer(), value).encodeToByteArray()

fun encodeProfileUpdateBody(value: ProfileUpdateBody): ByteArray =
    EventBodyJson.encodeToString(ProfileUpdateBody.serializer(), value).encodeToByteArray()

fun encodeAgentInBody(value: AgentInBody): ByteArray =
    EventBodyJson.encodeToString(AgentInBody.serializer(), value).encodeToByteArray()

fun encodeAgentOutBody(value: AgentOutBody): ByteArray =
    EventBodyJson.encodeToString(AgentOutBody.serializer(), value).encodeToByteArray()
