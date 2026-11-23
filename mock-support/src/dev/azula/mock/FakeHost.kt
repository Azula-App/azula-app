package dev.azula.mock

import dev.azula.host.HostPlatform
import dev.azula.selfgrant.GrantPhase
import dev.azula.selfgrant.GrantState
import dev.azula.selfgrant.VmPermission
import dev.azula.selfgrant.VmPermissionGranter
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.GrantSummary
import dev.azula.vmhost.HostException
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostPlan
import dev.azula.vmhost.HostState
import dev.azula.vmhost.MintedGrant
import dev.azula.vmhost.PlanOverride
import dev.azula.vmhost.ProvisionStep
import dev.azula.vmhost.VmHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A fake [VmHost] for the `-mock` apps and the UI tests. With [demo] it plays the whole setup
 * on a timer (download, verify, unpack, first boot, running); otherwise tests drive [set]
 * themselves and read the recorded calls.
 */
class FakeVmHost(
    private val scope: CoroutineScope? = null,
    private val demo: Boolean = false,
    /** In demo mode first boot waits for this to be granted, as the real host does. */
    var granted: () -> Boolean = { true },
) : VmHost {
    private val flow = MutableStateFlow(HostState())
    override val state: Flow<HostState> = flow

    var capability: CapabilityResult = CapabilityResult.CanHost(HostPlan(cpus = 8, memoryMb = 4096, diskGb = 16, downloadMb = 400))
    var provisionCalls = 0
    var startCalls = 0
    var stopCalls = 0
    var override = PlanOverride()
    var overrideAtProvision: PlanOverride? = null
    var failMint: String? = null
    var failList: String? = null
    val mintCalls = mutableListOf<Pair<String, String>>()
    private val grants = mutableListOf<GrantSummary>()
    private var counter = 0
    var nowSeconds: () -> Long = { dev.azula.epochSeconds() }

    fun set(s: HostState) { flow.value = s }
    fun current(): HostState = flow.value
    fun seedGrant(g: GrantSummary) { grants += g }
    fun liveGrants(): List<GrantSummary> = grants.toList()

    override suspend fun checkCapability(): CapabilityResult = capability

    override fun setPlanOverride(override: PlanOverride) { this.override = override }
    override fun planOverride(): PlanOverride = override

    override fun provision() {
        provisionCalls++
        overrideAtProvision = override
        if (demo && scope != null) scope.launch { runDemo() }
    }

    private var demoRunning = false

    private suspend fun runDemo() {
        if (demoRunning || flow.value.phase == HostPhase.RUNNING) return
        demoRunning = true
        try {
            val total = 300L shl 20
            for (i in 0..10) {
                flow.value = HostState(HostPhase.PROVISIONING, step = ProvisionStep.DOWNLOADING, bytesDone = total * i / 10, bytesTotal = total)
                delay(500)
            }
            flow.value = HostState(HostPhase.PROVISIONING, step = ProvisionStep.VERIFYING, bytesDone = total, bytesTotal = total)
            delay(600)
            flow.value = HostState(HostPhase.PROVISIONING, step = ProvisionStep.EXTRACTING, bytesDone = 400L shl 20, bytesTotal = 1200L shl 20)
            delay(1200)
            flow.value = HostState(HostPhase.PROVISIONING, step = ProvisionStep.PREPARING_DISK)
            delay(600)
            flow.value = HostState(HostPhase.PROVISIONING, step = ProvisionStep.FIRST_BOOT, detail = "waiting for permission")
            while (!granted()) delay(500)
            flow.value = HostState(HostPhase.PROVISIONING, step = ProvisionStep.FIRST_BOOT)
            delay(3000)
            flow.value = HostState(HostPhase.RUNNING, startedAtMs = nowSeconds() * 1000)
        } finally {
            demoRunning = false
        }
    }

    override fun start() {
        startCalls++
        if (demo) flow.value = HostState(HostPhase.RUNNING, startedAtMs = nowSeconds() * 1000)
    }

    override fun stop() {
        stopCalls++
        if (demo) flow.value = HostState(HostPhase.STOPPED)
    }

    override suspend fun mintGrant(ttl: String, label: String): Result<MintedGrant> {
        mintCalls += ttl to label
        failMint?.let { return Result.failure(HostException(it)) }
        if (flow.value.phase != HostPhase.RUNNING) return Result.failure(HostException("the host isn't running"))
        val secs = when (ttl) { "1h" -> 3600L; "7d" -> 604_800L; else -> 21_600L }
        val now = nowSeconds()
        val g = GrantSummary(id = "g${++counter}", label = label, created = now, expires = now + secs, ops = listOf("exec"), vms = listOf("default"))
        grants += g
        return Result.success(MintedGrant(g, "azc1.fake${g.id}"))
    }

    override suspend fun listGrants(): Result<List<GrantSummary>> =
        failList?.let { Result.failure(HostException(it)) } ?: Result.success(grants.toList())

    override suspend fun revoke(id: String): Result<Boolean> {
        val removed = grants.removeAll { it.id == id }
        return Result.success(removed)
    }
}

/** A fake [VmPermissionGranter]. With [demo], [start] walks discovery, the code prompt and the grant on a timer. */
class FakeVmPermissionGranter(
    private val scope: CoroutineScope? = null,
    private val demo: Boolean = false,
    initial: GrantState = GrantState(),
) : VmPermissionGranter {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<GrantState> = flow.asStateFlow()
    var startCalls = 0
    var cancelCalls = 0
    var refreshCalls = 0

    fun set(s: GrantState) { flow.value = s }
    fun grantAll() { flow.value = GrantState(GrantPhase.Granted, missing = emptySet()) }
    fun missing(vararg p: VmPermission, phase: GrantPhase = GrantPhase.Idle) { flow.value = GrantState(phase, missing = p.toSet()) }

    override fun refresh() { refreshCalls++ }

    override fun start() {
        startCalls++
        if (demo && scope != null && flow.value.phase == GrantPhase.Idle) scope.launch {
            flow.value = flow.value.copy(phase = GrantPhase.Discovering)
            delay(2500)
            flow.value = flow.value.copy(phase = GrantPhase.AwaitingCode)
            delay(3500)
            flow.value = flow.value.copy(phase = GrantPhase.Granting)
            delay(1500)
            grantAll()
        }
    }

    override fun cancel() {
        cancelCalls++
        if (flow.value.phase != GrantPhase.Granted) flow.value = flow.value.copy(phase = GrantPhase.Idle, error = null, detail = null)
    }
}

/** A fake [HostPlatform] that records every call, for the `-mock` apps and the UI tests. */
class FakeHostPlatform(
    var labs: Boolean = true,
    var developerOptions: Boolean = true,
    var notifications: Boolean = true,
    committed: Boolean = false,
    done: Boolean = false,
) : HostPlatform {
    val calls = mutableListOf<String>()
    val shared = mutableListOf<String>()
    override var setupCommitted: Boolean = committed
    override var setupDone: Boolean = done

    override fun entryVisible() = labs
    override fun enableLabs() { labs = true; calls += "enableLabs" }
    override fun developerOptionsEnabled() = developerOptions
    override fun notificationsAllowed() = notifications
    override fun requestNotifications() { calls += "requestNotifications" }
    override fun openAboutPhone() { calls += "openAboutPhone" }
    override fun openWirelessDebugging() { calls += "openWirelessDebugging" }
    override fun openUrl(url: String) { calls += "openUrl:$url" }
    override fun share(text: String) { shared += text; calls += "share" }
    override fun localTime(epochSeconds: Long): String = "12:34"
}
