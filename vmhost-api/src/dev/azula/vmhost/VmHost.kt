package dev.azula.vmhost

import kotlinx.coroutines.flow.Flow

/**
 * The UI's handle on the on-phone VM host. The implementation runs the VM in a
 * separate `:vmhost` process (vmhost-real's `HostService`); everything here is
 * safe to call from the UI process.
 */
interface VmHost {
    /** The persisted host state; emits the current value first, then every change. */
    val state: Flow<HostState>

    /** The capability check (S1). Cheap and side-effect free apart from probing the hidden-API exemption. */
    suspend fun checkCapability(): CapabilityResult

    /**
     * Sets the phone up as a host, and keeps going until it is hosting (design D9 S3). Call
     * it the moment the user taps "set up", in parallel with the permission step: it
     * downloads, verifies and unpacks the guest image (no permission needed), then waits for
     * the two VM permissions before the first boot, and ends RUNNING with no more input.
     * Progress arrives through [state] as [HostPhase.PROVISIONING] with a [ProvisionStep].
     *
     * Idempotent, and also the **retry**: every step resumes where it stopped, including
     * after the `:vmhost` process died, so the UI calls it again on an ERROR whose
     * [HostState.retryable] is set, and on resume when the state says setup was interrupted.
     * When the image is already in place it just boots (like [start]).
     */
    fun provision()

    /** Starts hosting. Same as [provision] if setup isn't finished. Returns immediately; progress arrives through [state]. */
    fun start()

    /** Stops hosting: broker, bridge, proxy, then the VM. */
    fun stop()

    /** Mints a grant. Fails with a [HostException] unless the host is RUNNING (the broker lives in `:vmhost`). */
    suspend fun mintGrant(ttl: String, label: String): Result<MintedGrant>

    suspend fun listGrants(): Result<List<GrantSummary>>

    /** True if a grant with [id] existed. */
    suspend fun revoke(id: String): Result<Boolean>
}
