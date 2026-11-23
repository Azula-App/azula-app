package dev.azula.selfgrant

import kotlinx.coroutines.flow.StateFlow

/** The two `signature|development` permissions a VM host needs. */
enum class VmPermission(val androidName: String) {
    ManageVirtualMachine("android.permission.MANAGE_VIRTUAL_MACHINE"),
    UseCustomVirtualMachine("android.permission.USE_CUSTOM_VIRTUAL_MACHINE"),
}

/**
 * Where the grant flow is. [Discovering] waits for Wireless debugging's pairing
 * service (mDNS), [AwaitingCode] is showing the "enter the 6-digit code"
 * notification, and [Pairing]/[Connecting]/[Granting] are hands-off.
 */
enum class GrantPhase { Idle, Discovering, AwaitingCode, Pairing, Connecting, Granting, Granted, Failed }

enum class GrantError {
    /** POST_NOTIFICATIONS is denied, so the code prompt can't be shown. */
    NotificationsBlocked,
    /** The entered pairing code didn't match (or the pairing service rejected it). */
    WrongCode,
    PairingFailed,
    ConnectFailed,
    /** `pm grant` ran but the permissions are still not held. */
    GrantFailed,
    /** No pairing/connect service appeared in time. */
    DiscoveryTimeout,
    /** This Android version can't run the flow (needs 14+). */
    Unsupported,
}

data class GrantState(
    val phase: GrantPhase = GrantPhase.Idle,
    /** Permissions the app does not hold right now (empty once granted). */
    val missing: Set<VmPermission> = VmPermission.entries.toSet(),
    val error: GrantError? = null,
    /** One line of detail for [error], for logs and the UI. */
    val detail: String? = null,
) {
    val granted: Boolean get() = missing.isEmpty()
}

/** Grants the app its own VM permissions once, through Wireless debugging. */
interface VmPermissionGranter {
    val state: StateFlow<GrantState>

    /** Re-reads which permissions are held (call on every resume). */
    fun refresh()

    /** Starts (or resumes) the flow. No-op when everything is already granted. */
    fun start()

    fun cancel()
}
