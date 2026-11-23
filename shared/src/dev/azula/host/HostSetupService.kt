package dev.azula.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.azula.core.AppScope
import dev.azula.epochSeconds
import dev.azula.selfgrant.GrantState
import dev.azula.selfgrant.VmPermissionGranter
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.GrantSummary
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostState
import dev.azula.vmhost.MintedGrant
import dev.azula.vmhost.PlanOverride
import dev.azula.vmhost.VmHost
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where "host compute on this phone" is (design D9): S1, S2 and S3 together, or S4. */
enum class HostStage { CHECK, SETUP, READY }

/** S5's grant lifetimes: the chips, in the order shown. */
enum class GrantTtl(val label: String, val arg: String, val seconds: Long) {
    ONE_HOUR("1 h", "1h", 3_600),
    SIX_HOURS("6 h", "6h", 21_600),
    SEVEN_DAYS("7 d", "7d", 604_800),
}

/** Automatic retries before the user sees **retry** (design D9: the third failure in a row shows **retry**, so two automatic retries, with backoff). Mutable for tests only. */
object HostRetryPolicy {
    var backoffMs: LongArray = longArrayOf(3_000, 8_000)
}

const val COMPUTE_DOCS_URL = "https://azula.app/docs/compute#in-the-cloud-sandbox"
const val DEFAULT_GRANT_TTL_LABEL = "6 h"

/** The snippet S5 shows for a cloud session (design D9 S5). */
fun computeSnippet(token: String): String =
    "export AZULA_COMPUTE_TOKEN='$token' AZULA_RELAY_ONLY=1\n" +
        "npx -y @azula-app/cli compute doctor && npx -y @azula-app/cli compute exec -- uname -a"

/**
 * Drives the host-compute screens (compute-android-app-host tasks 3.4, 3.6): S1 capability
 * check, S2 grant checklist, S3 provisioning, S4 Ready with grants, S5 minting. All the
 * state is derived from three persisted sources, so it comes back after process death
 * (the VM permission grant kills the app): the host's state file ([VmHost.state]), the
 * granted permissions ([VmPermissionGranter]) and two flags in [HostPlatform].
 *
 * Absent (`available == false`) when the platform has no host: JVM and iOS.
 */
@Inject
@SingleIn(AppScope::class)
class HostSetupService(
    private val scope: CoroutineScope,
    private val vmHost: VmHost?,
    private val granter: VmPermissionGranter?,
    private val platform: HostPlatform?,
) {
    val available: Boolean = vmHost != null && granter != null && platform != null

    /** S0: whether the Settings row (and the Home card) show. */
    var entryVisible by mutableStateOf(false)
        private set

    /** The host screen is showing in place of Settings (mobile and desktop alike). */
    var open by mutableStateOf(false)

    /** S1 result; null until checked. */
    var capability by mutableStateOf<CapabilityResult?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var planOverride by mutableStateOf(PlanOverride())
        private set

    var committed by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set

    var host by mutableStateOf(HostState())
        private set
    var grant by mutableStateOf(GrantState())
        private set
    var developerOptions by mutableStateOf(false)
        private set
    var notifications by mutableStateOf(false)
        private set

    /** READY with a missing permission: the user tapped the button that re-runs S2. */
    var regranting by mutableStateOf(false)
        private set

    var grants by mutableStateOf<List<GrantSummary>>(emptyList())
        private set

    /** A one-line failure for the grants list or the connect sheet. */
    var grantsError by mutableStateOf<String?>(null)
        private set

    /** Automatic retries spent on the current failure (design D9: three, then the user retries). */
    var autoRetries by mutableStateOf(0)
        private set

    /** An automatic retry is waiting out its backoff. */
    var retryPending by mutableStateOf(false)
        private set

    private var retryJob: Job? = null

    val stage: HostStage
        get() = when {
            done -> HostStage.READY
            committed -> HostStage.SETUP
            else -> HostStage.CHECK
        }

    val permissionMissing: Boolean get() = !grant.granted

    /** S2 shows the checklist: while setup is under way (ticked off as it goes), or when a ready host lost a permission and the user tapped to fix it. */
    val showChecklist: Boolean
        get() = stage == HostStage.SETUP || (stage == HostStage.READY && permissionMissing && regranting)

    /** The home card: known to qualify, and not set up yet. */
    val homeCardVisible: Boolean
        get() = entryVisible && capability is CapabilityResult.CanHost && stage == HostStage.CHECK

    init {
        if (available) {
            committed = platform!!.setupCommitted
            done = platform.setupDone
            entryVisible = platform.entryVisible()
            planOverride = vmHost!!.planOverride()
            scope.launch { vmHost!!.state.collect { onHostState(it) } }
            scope.launch { granter!!.state.collect { grant = it; onGrantState(it) } }
            granter!!.refresh()
            readDeviceState()
            // Back from a process death mid-setup: show where we are, and carry on.
            if (committed && !done) {
                open = true
                resumeSetup()
            }
        }
    }

    /** Call on every resume (and when the host screen opens): re-reads everything the OS can change behind our back. */
    fun refresh() {
        if (!available) return
        entryVisible = platform!!.entryVisible()
        granter!!.refresh()
        readDeviceState()
        if (stage == HostStage.SETUP) {
            resumeSetup()
            if (permissionMissing) maybeStartGrant()
        }
        if (stage == HostStage.READY) refreshGrants()
    }

    /**
     * A light re-read for the checklist while it's showing: the notification dialog and the
     * Settings screens change what's true without the app ever leaving the foreground.
     */
    fun redetect() {
        if (!available) return
        readDeviceState()
        if (stage == HostStage.SETUP && permissionMissing) maybeStartGrant()
    }

    private fun readDeviceState() {
        developerOptions = platform!!.developerOptionsEnabled()
        notifications = platform.notificationsAllowed()
    }

    /** The labs gesture: shows the entry in a release build. */
    fun enableLabs() {
        if (!available) return
        platform!!.enableLabs()
        entryVisible = platform.entryVisible()
    }

    // ---- S1 ----

    /** Runs the capability check (cheap; nothing downloaded or granted). */
    fun check() {
        if (!available || checking) return
        checking = true
        scope.launch {
            val r = runCatching { vmHost!!.checkCapability() }
                .getOrElse { CapabilityResult.CannotHost(dev.azula.vmhost.CannotHostReason.AVF_UNAVAILABLE, "couldn't check this phone — ${it.message ?: "unknown error"}") }
            capability = r
            checking = false
        }
    }

    fun setMemoryMb(mb: Int) { planOverride = planOverride.copy(memoryMb = mb) }
    fun setDiskGb(gb: Int) { planOverride = planOverride.copy(diskGb = gb) }

    /** "set up": commit, start provisioning now, and start the grant flow in parallel when it can. */
    fun setUp() {
        if (!available) return
        platform!!.setupCommitted = true
        committed = true
        autoRetries = 0
        vmHost!!.setPlanOverride(planOverride)
        vmHost.provision()
        readDeviceState()
        if (permissionMissing) maybeStartGrant()
    }

    // ---- S2 ----

    fun openAboutPhone() { platform?.openAboutPhone() }

    fun requestNotifications() { platform?.requestNotifications() }

    /** "open wireless debugging": starts discovery first so the pairing dialog is noticed the moment it appears. */
    fun openWirelessDebugging() {
        if (!available) return
        granter!!.start()
        platform!!.openWirelessDebugging()
    }

    /** "try again" after a failed grant, or the READY "permission missing" button (re-runs S2 only). */
    fun regrant() {
        regranting = true
        granter!!.cancel()
        granter.refresh()
        if (notifications) granter.start()
    }

    private fun maybeStartGrant() {
        val g = granter ?: return
        if (notifications && developerOptions && g.state.value.phase == dev.azula.selfgrant.GrantPhase.Idle) g.start()
    }

    private fun onGrantState(g: GrantState) {
        if (g.granted) regranting = false
        completeIfReady()
    }

    // ---- S3 / S4 ----

    private fun resumeSetup() {
        val h = host
        val interrupted = h.phase == HostPhase.ERROR && h.retryable && h.detail == INTERRUPTED
        if (interrupted || (h.phase == HostPhase.STOPPED && !h.active)) vmHost!!.provision()
    }

    private fun onHostState(s: HostState) {
        host = s
        completeIfReady()
        if (!done && committed && s.phase == HostPhase.ERROR && s.retryable) {
            when {
                s.detail == INTERRUPTED -> vmHost!!.provision()
                !s.detail.startsWith("needs ") && autoRetries < HostRetryPolicy.backoffMs.size -> scheduleRetry()
            }
        }
    }

    private fun completeIfReady() {
        if (!done && committed && host.phase == HostPhase.RUNNING && grant.granted) {
            platform!!.setupDone = true
            done = true
            retryJob?.cancel()
            retryPending = false
            refreshGrants()
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        val wait = HostRetryPolicy.backoffMs[autoRetries]
        autoRetries++
        retryPending = true
        retryJob = scope.launch {
            delay(wait)
            retryPending = false
            vmHost!!.provision()
        }
    }

    /** The **retry** button: runs the outstanding setup step again (S3), and resets the automatic budget. */
    fun retry() {
        retryJob?.cancel()
        retryPending = false
        autoRetries = 0
        vmHost?.provision()
    }

    /** The **start** button after a repeated failure (S4). */
    fun startHosting() { vmHost?.start() }

    fun setHosting(on: Boolean) {
        if (on) vmHost?.start() else vmHost?.stop()
    }

    // ---- grants (task 3.4) ----

    fun refreshGrants() {
        val h = vmHost ?: return
        scope.launch {
            h.listGrants()
                .onSuccess { all ->
                    val now = epochSeconds()
                    grants = all.filter { it.isLive(now) }.sortedByDescending { it.created }
                    grantsError = null
                }
                .onFailure { grantsError = if (host.phase == HostPhase.RUNNING) (it.message ?: "couldn't list grants") else null }
        }
    }

    /** Mints a grant for S5. The token is returned to the caller only; it is never kept here. */
    suspend fun mint(ttl: GrantTtl): Result<MintedGrant> {
        val h = vmHost ?: return Result.failure(IllegalStateException("no host"))
        val label = "cloud session · ${platform!!.localTime(epochSeconds())}"
        return h.mintGrant(ttl.arg, label).also { r ->
            if (r.isSuccess) refreshGrants()
        }
    }

    fun revoke(id: String) {
        val h = vmHost ?: return
        scope.launch {
            h.revoke(id)
                .onFailure { grantsError = it.message ?: "couldn't revoke" }
            refreshGrants()
        }
    }

    // ---- links ----

    fun openDocs() { platform?.openUrl(COMPUTE_DOCS_URL) }
    fun share(text: String) { platform?.share(text) }

    private companion object {
        const val INTERRUPTED = "setup was interrupted"
    }
}
