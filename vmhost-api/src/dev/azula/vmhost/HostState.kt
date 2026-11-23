package dev.azula.vmhost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the on-phone VM host is, as the UI shows it (design D9 S4). */
@Serializable
enum class HostPhase {
    /** Not running. The resting state. */
    STOPPED,

    /** Staging the disk and seed, acquiring the wake lock. */
    PREPARING,

    /** The VM is starting; the broker, bridge and proxy are coming up. */
    BOOTING,

    /** The VM reached its login prompt and the broker is serving. */
    RUNNING,

    /** The VM died and the host is bringing it back (once, automatically). */
    RESTARTING,

    /** Stopped on a failure; [HostState.detail] says why. */
    ERROR,
}

/**
 * Persisted by the `:vmhost` process and observed by the UI process (see
 * `HostStateStore` in vmhost-real). [pid] is the writer's process id, used to
 * notice a `:vmhost` process that died without writing STOPPED.
 */
@Serializable
data class HostState(
    val phase: HostPhase = HostPhase.STOPPED,
    /** One human line: the error cause, or the current sub-step. Empty when there's nothing to add. */
    val detail: String = "",
    /** Epoch millis of the most recent START; 0 when stopped. */
    val startedAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    /** Automatic restarts since the last stable run. */
    val restarts: Int = 0,
    val pid: Int = 0,
) {
    /** True while the service holds the VM or is bringing it up. */
    val active: Boolean
        get() = phase == HostPhase.PREPARING || phase == HostPhase.BOOTING ||
            phase == HostPhase.RUNNING || phase == HostPhase.RESTARTING
}

/** JSON for [HostState]: tolerant on read (a torn or foreign file reads as `null`). */
object HostStateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: HostState): String = json.encodeToString(HostState.serializer(), state)

    fun decode(text: String): HostState? =
        runCatching { json.decodeFromString(HostState.serializer(), text) }.getOrNull()
}
