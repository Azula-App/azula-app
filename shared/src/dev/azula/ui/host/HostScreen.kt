package dev.azula.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.epochSeconds
import dev.azula.host.HostSetupService
import dev.azula.host.HostStage
import dev.azula.selfgrant.GrantError
import dev.azula.selfgrant.GrantPhase
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaCard
import dev.azula.theme.azulaMutedPanel
import dev.azula.vmhost.CannotHostReason
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.GrantSummary
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostPlan
import dev.azula.vmhost.HostState
import dev.azula.vmhost.ProvisionStep
import dev.azula.ui.StatusDot

/** S0: the single Settings row. Rendered by SettingsScreen only when [HostSetupService.available]. */
@Composable
fun HostSettingsRow(state: AzulaState) {
    val svc = state.hostSetup
    HostPanelRow("host-compute-row", Modifier.clickable { svc.open = true }) {
        Column(Modifier.weight(1f)) {
            Text("host compute on this phone", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                when (svc.stage) {
                    HostStage.READY -> "hosting · connect a cloud session"
                    HostStage.SETUP -> "setting up…"
                    HostStage.CHECK -> "run a linux vm for your cloud sessions"
                },
                color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp,
            )
        }
        Text("›", color = AzulaColors.textDim, fontSize = 18.sp)
    }
}

/** S0: the Home card, shown once the phone is known to qualify (and no setup has begun). */
@Composable
fun HostHomeCard(state: AzulaState, onOpen: () -> Unit) {
    val svc = state.hostSetup
    if (!svc.available) return
    LaunchedEffect(svc) { if (svc.capability == null) svc.check() }
    if (!svc.homeCardVisible) return
    Column(
        Modifier.fillMaxWidth().azulaMutedPanel().clickable { onOpen() }.padding(horizontal = 13.dp, vertical = 10.dp)
            .testTag("host-home-card"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("host compute on this phone", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("this phone can run azula vms · set up ›", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
    }
    Spacer(Modifier.height(9.dp))
}

/** The host screens, in place of Settings while [HostSetupService.open]. */
@Composable
fun HostScreen(state: AzulaState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val svc = state.hostSetup
    var connectOpen by remember { mutableStateOf(false) }
    // Re-detect on every resume: the user spends S2 in system Settings.
    LaunchedEffect(state.inForeground) {
        if (state.inForeground) {
            svc.refresh()
            if (svc.stage != HostStage.SETUP) svc.check()
        }
    }
    // The notification dialog and the Settings screens change the checklist's facts without the
    // app ever leaving the foreground, so poll lightly while it shows.
    LaunchedEffect(svc.showChecklist) {
        while (svc.showChecklist) {
            kotlinx.coroutines.delay(1500)
            svc.redetect()
        }
    }
    Column(modifier.fillMaxSize().background(AzulaColors.bg1)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("‹", color = AzulaColors.textDim, fontSize = 20.sp, modifier = Modifier.clickable { onBack() }.testTag("host-back"))
            Text("host compute", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (svc.stage) {
                HostStage.CHECK -> CheckContent(svc)
                HostStage.SETUP -> SetupContent(svc)
                HostStage.READY -> ReadyContent(svc) { connectOpen = true }
            }
        }
    }
    if (connectOpen) ConnectSheet(svc, onDismiss = { connectOpen = false })
}

// ---------------------------------------------------------------------------
// S1 · capability check
// ---------------------------------------------------------------------------

@Composable
private fun CheckContent(svc: HostSetupService) {
    LaunchedEffect(Unit) { svc.check() }
    when (val cap = svc.capability) {
        null -> HostMono("checking this phone…", tag = "host-checking")
        is CapabilityResult.CannotHost -> CannotHostContent(cap, svc)
        is CapabilityResult.CanHost -> CanHostContent(cap.plan, svc)
    }
}

@Composable
private fun CannotHostContent(cap: CapabilityResult.CannotHost, svc: HostSetupService) {
    Column(Modifier.fillMaxWidth().azulaMutedPanel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(AzulaColors.warn)
            Text("can't host here", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(cap.message, color = AzulaColors.text2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.testTag("host-cannot"))
        if (cap.reason == CannotHostReason.HIDDEN_API_BLOCKED) {
            Text(
                "the terminal app can still host one · how ›", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontSize = 12.sp,
                modifier = Modifier.clickable { svc.openDocs() }.testTag("host-terminal-path"),
            )
        }
    }
}

@Composable
private fun CanHostContent(default: HostPlan, svc: HostSetupService) {
    val plan = svc.planOverride.applyTo(default)
    var advanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().azulaCard().padding(16.dp).testTag("host-can"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(AzulaColors.online)
            Text("this phone can host", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        HostMono(
            "${plan.cpus} cores · ${gbText(plan.memoryMb)} for jobs · ${plan.diskGb} GB disk · ${gbText(plan.downloadMb)} download",
            AzulaColors.text2, 12, tag = "host-summary",
        )
        HostBody("a debian vm runs inside azula. your cloud sessions run commands in it, over iroh, with no server in the middle.")
    }
    Column(Modifier.fillMaxWidth().azulaMutedPanel().padding(13.dp).testTag("host-advanced"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (advanced) "advanced ⌄" else "advanced ›",
            color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 11.sp,
            modifier = Modifier.clickable { advanced = !advanced }.testTag("host-advanced-toggle"),
        )
        if (advanced) {
            Stepper("memory", gbText(plan.memoryMb), "host-memory", 
                onMinus = { svc.setMemoryMb((plan.memoryMb - 512).coerceAtLeast(MIN_MEMORY_MB)) },
                onPlus = { svc.setMemoryMb((plan.memoryMb + 512).coerceAtMost(default.memoryMb * 3 / 2)) })
            Stepper("disk", "${plan.diskGb} GB", "host-disk",
                onMinus = { svc.setDiskGb((plan.diskGb - 2).coerceAtLeast(MIN_DISK_GB)) },
                onPlus = { svc.setDiskGb((plan.diskGb + 2).coerceAtMost(MAX_DISK_GB)) })
            HostBody("more memory leaves less for your phone. about half of ram is the safe default.")
        }
    }
    HostButton("set up", "host-set-up") { svc.setUp() }
}

private const val MIN_MEMORY_MB = 1024
private const val MIN_DISK_GB = 8
private const val MAX_DISK_GB = 64

@Composable
private fun Stepper(label: String, value: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AzulaColors.text2, fontFamily = Fonts.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StepButton("−", "$tag-minus", onMinus)
        Text(value, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp).testTag("$tag-value"))
        StepButton("+", "$tag-plus", onPlus)
    }
}

@Composable
private fun StepButton(glyph: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(AzulaShapes.rSm).background(AzulaColors.inputBg).border(1.dp, AzulaColors.border4, AzulaShapes.rSm)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 5.dp).testTag(tag),
    ) { Text(glyph, color = AzulaColors.pinkLight, fontFamily = Fonts.mono, fontSize = 14.sp) }
}

internal fun gbText(mb: Int): String {
    val tenths = (mb * 10 + 512) / 1024
    return "${tenths / 10}${if (tenths % 10 == 0) "" else ".${tenths % 10}"} GB"
}

// ---------------------------------------------------------------------------
// S2 + S3 · the grant checklist beside hands-off provisioning
// ---------------------------------------------------------------------------

@Composable
private fun SetupContent(svc: HostSetupService) {
    if (svc.showChecklist) ChecklistCard(svc)
    ProvisionCard(svc)
}

/** S2: three lines, re-detected on every resume. */
@Composable
internal fun ChecklistCard(svc: HostSetupService) {
    val g = svc.grant
    val granted = g.granted
    Column(Modifier.fillMaxWidth().azulaCard().padding(16.dp).testTag("host-checklist"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HostLabel("ONE-TIME PERMISSION")
        HostLine(
            if (svc.developerOptions) LineState.DONE else LineState.CURRENT,
            "developer options", "host-line-dev",
            detail = if (svc.developerOptions) "on" else "tap build number 7 times, then come back",
        ) {
            if (!svc.developerOptions) HostChipButton("open about phone", "host-open-about") { svc.openAboutPhone() }
        }
        val wdState = when {
            granted -> LineState.DONE
            !svc.developerOptions -> LineState.PENDING
            else -> LineState.CURRENT
        }
        HostLine(
            wdState, "wireless debugging", "host-line-wd",
            detail = when {
                granted -> "paired"
                !svc.developerOptions -> "needs developer options"
                !svc.notifications -> "azula asks for the pairing code in a notification"
                else -> "turn it on, then tap pair device with pairing code"
            },
        ) {
            if (!granted && svc.developerOptions) {
                if (!svc.notifications) HostChipButton("allow notifications", "host-allow-notifications") { svc.requestNotifications() }
                else HostChipButton("open wireless debugging", "host-open-wd") { svc.openWirelessDebugging() }
            }
        }
        val (grantState, grantDetail) = grantLine(svc)
        HostLine(
            grantState, "permission granted", "host-line-grant",
            detail = grantDetail,
            detailColor = if (grantState == LineState.FAILED) AzulaColors.error else AzulaColors.textDim2,
        ) {
            if (g.phase == GrantPhase.Failed && g.error != GrantError.NotificationsBlocked) {
                HostChipButton("try again", "host-grant-retry") { svc.regrant() }
            }
        }
    }
}

private fun grantLine(svc: HostSetupService): Pair<LineState, String?> {
    val g = svc.grant
    if (g.granted) return LineState.DONE to "you can turn wireless debugging and developer options off now"
    return when (g.phase) {
        GrantPhase.Failed -> LineState.FAILED to when (g.error) {
            GrantError.NotificationsBlocked -> "allow notifications so azula can ask for the code"
            GrantError.WrongCode -> "that code didn't match — try again"
            GrantError.DiscoveryTimeout -> "didn't see the pairing screen — open it and try again"
            GrantError.Unsupported -> "needs android 14 or newer"
            else -> g.detail ?: "couldn't finish the grant — try again"
        }
        GrantPhase.Discovering -> LineState.CURRENT to "looking for the pairing screen…"
        GrantPhase.AwaitingCode -> LineState.CURRENT to "enter the 6-digit code in the notification"
        GrantPhase.Pairing, GrantPhase.Connecting, GrantPhase.Granting -> LineState.CURRENT to "granting…"
        else -> LineState.PENDING to null
    }
}

/** S3: the stepper, driven by [HostState]. */
@Composable
internal fun ProvisionCard(svc: HostSetupService) {
    val h = svc.host
    val failed = h.phase == HostPhase.ERROR
    val idx = provisionStepIndex(h)
    fun st(i: Int): LineState = when {
        failed && i == idx -> LineState.FAILED
        i < idx -> LineState.DONE
        i == idx -> LineState.CURRENT
        else -> LineState.PENDING
    }
    Column(Modifier.fillMaxWidth().azulaCard().padding(16.dp).testTag("host-provision"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HostLabel("SETTING UP · HANDS-OFF")
        HostLine(st(0), "downloading debian · 0.4 GB", "host-step-download", detail = if (idx == 0) downloadDetail(h) else null)
        HostLine(st(1), "preparing disk", "host-step-disk", detail = if (idx == 1) diskDetail(h) else null)
        HostLine(
            st(2), "first boot · installing tools (about 2 min)", "host-step-boot",
            detail = if (idx == 2 && !failed) bootDetail(h) else null,
        )
        if (failed) FailureBlock(svc)
        else HostBody("you can leave — setup carries on, and ends ready.")
    }
}

/** One line of failure plus retry (or, while an automatic retry is pending, just the line). */
@Composable
private fun FailureBlock(svc: HostSetupService) {
    val h = svc.host
    val auto = svc.retryPending
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(h.detail.ifBlank { "setup stopped" }, color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.5.sp, modifier = Modifier.testTag("host-error"))
        if (auto) HostMono("trying again shortly…", tag = "host-auto-retry")
        else if (h.retryable) HostButton("retry", "host-retry", primary = false) { svc.retry() }
        else HostButton("start", "host-start", primary = false) { svc.startHosting() }
    }
}

internal fun provisionStepIndex(h: HostState): Int = when (h.step) {
    ProvisionStep.DOWNLOADING, ProvisionStep.VERIFYING -> 0
    ProvisionStep.EXTRACTING, ProvisionStep.PREPARING_DISK -> 1
    ProvisionStep.FIRST_BOOT -> 2
    null -> if (h.phase == HostPhase.PREPARING || h.phase == HostPhase.BOOTING || h.phase == HostPhase.RUNNING) 2 else 0
}

private fun mbText(bytes: Long) = "${bytes shr 20} MB"

private fun progress(done: Long, total: Long): String =
    if (total > 0) "${mbText(done)} / ${mbText(total)} · ${(done * 100 / total).coerceIn(0, 100)}%" else mbText(done)

private fun downloadDetail(h: HostState): String? = when (h.step) {
    ProvisionStep.VERIFYING -> "verifying…"
    ProvisionStep.DOWNLOADING -> if (h.phase == HostPhase.PROVISIONING) progress(h.bytesDone, h.bytesTotal) else null
    else -> null
}

private fun diskDetail(h: HostState): String? =
    if (h.step == ProvisionStep.EXTRACTING && h.bytesDone > 0) "unpacking · ${mbText(h.bytesDone)}" else "growing the disk…"

private fun bootDetail(h: HostState): String =
    h.detail.ifBlank { "starting the vm…" }

// ---------------------------------------------------------------------------
// S4 · ready
// ---------------------------------------------------------------------------

@Composable
private fun ReadyContent(svc: HostSetupService, onConnect: () -> Unit) {
    val h = svc.host
    LaunchedEffect(Unit) { if (svc.capability == null) svc.check(); svc.refreshGrants() }
    val running = h.phase == HostPhase.RUNNING
    // "used just now" and expiries move while a cloud session works: keep the list fresh.
    LaunchedEffect(running) {
        while (running) {
            kotlinx.coroutines.delay(10_000)
            svc.refreshGrants()
        }
    }
    val plan = (svc.capability as? CapabilityResult.CanHost)?.plan?.let { svc.planOverride.applyTo(it) }

    Column(Modifier.fillMaxWidth().azulaCard().padding(16.dp).testTag("host-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val (color, label) = when (h.phase) {
            HostPhase.RUNNING -> AzulaColors.online to "hosting · running" + (plan?.let { " · ${it.cpus} cores · ${gbText(it.memoryMb)} · ${it.diskGb} GB" } ?: "")
            HostPhase.PROVISIONING, HostPhase.PREPARING, HostPhase.BOOTING -> AzulaColors.pink to "hosting · starting…"
            HostPhase.RESTARTING -> AzulaColors.warn to "restarting…"
            HostPhase.ERROR -> AzulaColors.error to h.detail.ifBlank { "stopped" }
            HostPhase.STOPPED -> AzulaColors.textFaint2 to "not hosting"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatusDot(color)
            Text(label, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 12.sp, modifier = Modifier.testTag("host-status-text"))
        }
        if (svc.permissionMissing) {
            Text(
                "permission missing · " + svc.grant.missing.joinToString(" · ") { it.name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase() },
                color = AzulaColors.warn, fontFamily = Fonts.mono, fontSize = 11.sp, modifier = Modifier.testTag("host-permission-missing"),
            )
            if (!svc.showChecklist) HostButton("grant permission", "host-regrant", primary = false) { svc.regrant() }
        } else if (h.phase == HostPhase.ERROR) {
            if (h.retryable) HostButton("retry", "host-retry", primary = false) { svc.retry() }
            else HostButton("start", "host-start", primary = false) { svc.startHosting() }
        }
    }
    if (svc.showChecklist) ChecklistCard(svc)

    HostPanelRow("host-toggle-row") {
        Column(Modifier.weight(1f)) {
            Text("hosting", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("the vm runs while this is on · stop it from the notification too", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
        }
        Switch(
            checked = h.active, onCheckedChange = { svc.setHosting(it) },
            enabled = !svc.permissionMissing,
            modifier = Modifier.testTag("host-toggle"),
        )
    }

    HostButton("connect a cloud session", "host-connect", enabled = running) { onConnect() }
    if (!running) HostMono("start hosting to connect a session", tag = "host-connect-hint")

    HostLabel("ACTIVE GRANTS")
    if (svc.grantsError != null) Text(svc.grantsError!!, color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.sp, modifier = Modifier.testTag("host-grants-error"))
    if (svc.grants.isEmpty()) HostMono("no active grants", tag = "host-no-grants")
    svc.grants.forEach { g -> GrantRow(g) { svc.revoke(g.id) } }
}

@Composable
private fun GrantRow(g: GrantSummary, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(AzulaShapes.rMd).background(AzulaColors.bg2).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("host-grant-${g.id}").semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(g.label.ifBlank { g.id }, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 12.sp)
            val used = g.lastUsed?.let { " · used ${ago(it)}" } ?: " · unused"
            Text("expires in ${duration(g.expires - epochSeconds())}$used", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 10.sp)
        }
        Text("revoke", color = AzulaColors.pink, fontFamily = Fonts.ui, fontSize = 12.sp, modifier = Modifier.clickable { onRevoke() }.padding(8.dp).testTag("host-revoke-${g.id}"))
    }
}

internal fun duration(seconds: Long): String = when {
    seconds < 60 -> "<1m"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3600}h"
    else -> "${seconds / 86_400}d"
}

private fun ago(epoch: Long): String {
    val d = (epochSeconds() - epoch).coerceAtLeast(0)
    return if (d < 60) "just now" else duration(d) + " ago"
}
