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

    /** Starts hosting. Returns immediately; progress arrives through [state]. */
    fun start()

    /** Stops hosting: broker, bridge, proxy, then the VM. */
    fun stop()

    /** Mints a grant. Fails with a [HostException] unless the host is RUNNING (the broker lives in `:vmhost`). */
    suspend fun mintGrant(ttl: String, label: String): Result<MintedGrant>

    suspend fun listGrants(): Result<List<GrantSummary>>

    /** True if a grant with [id] existed. */
    suspend fun revoke(id: String): Result<Boolean>
}
