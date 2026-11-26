@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import dev.azula.host.HostRetryPolicy
import dev.azula.mock.FakeHostPlatform
import dev.azula.mock.FakeVmHost
import dev.azula.mock.FakeVmPermissionGranter
import dev.azula.selfgrant.GrantError
import dev.azula.selfgrant.GrantPhase
import dev.azula.selfgrant.GrantState
import dev.azula.selfgrant.VmPermission
import dev.azula.state.AzulaState
import dev.azula.vmhost.CannotHostReason
import dev.azula.vmhost.CapabilityInputs
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.GrantSummary
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostState
import dev.azula.vmhost.PlanOverride
import dev.azula.vmhost.ProvisionStep
import dev.azula.vmhost.evaluateCapability
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for "host compute on this phone" (compute-android-app-host 3.4 and 3.6, design
 * D9): every screen S0-S5 and every row of the error table, driven through the real
 * [dev.azula.ui.DesktopApp] over fake `VmHost` / `VmPermissionGranter` / `HostPlatform` (mock-support).
 * Same harness as SettingsE2eTest.
 */
class HostComputeUiTest {
    private class Fakes(
        dev: Boolean = true,
        notifications: Boolean = true,
        committed: Boolean = false,
        done: Boolean = false,
    ) {
        val host = FakeVmHost()
        val granter = FakeVmPermissionGranter()
        val platform = FakeHostPlatform(dev, notifications, committed, done)
    }

    @AfterTest fun resetPolicy() { HostRetryPolicy.backoffMs = longArrayOf(3_000, 8_000) }

    private fun hostTest(f: Fakes = Fakes(), block: ComposeUiTest.(Fakes, AzulaState) -> Unit) =
        desktopE2eTest(vmHost = f.host, granter = f.granter, hostPlatform = f.platform) { state, _, _ ->
            // A host that finished setup opens like any other Settings entry; one that's mid-setup
            // reopens by itself (that's the resume-after-process-death behavior under test).
            if (f.platform.setupDone) openHost()
            block(f, state)
        }

    private fun ComposeUiTest.openSettings() {
        waitUntilAtLeastOneExists(hasText("profiles & settings") and hasClickAction(), timeoutMillis = 15_000)
        onNode(hasText("profiles & settings") and hasClickAction()).performClick()
    }

    private fun ComposeUiTest.waitTag(tag: String, timeout: Long = 15_000) =
        waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis = timeout)

    private fun ComposeUiTest.tap(tag: String) {
        waitTag(tag)
        onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** Polls until [tag]'s text contains [text]: state arrives from background coroutines. */
    private fun ComposeUiTest.assertText(tag: String, text: String) {
        waitTag(tag)
        try {
            waitUntil(timeoutMillis = 15_000) { runCatching { onNodeWithTag(tag).assertTextContains(text, substring = true) }.isSuccess }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("'$tag' never contained '$text'; it has: " + onNodeWithTag(tag).printToString(), e)
        }
    }

    private fun ComposeUiTest.openHost() {
        openSettings()
        tap("host-compute-row")
    }

    private fun ready(f: Fakes) {
        f.granter.grantAll()
        f.host.set(HostState(HostPhase.RUNNING))
    }

    private val inputs = CapabilityInputs(
        sdkInt = 37, avfServicePresent = true, nonProtectedVmSupported = true, protectedVmSupported = true,
        totalRamBytes = 8L shl 30, freeStorageBytes = 40L shl 30, cpuCount = 8, hiddenApiWorks = true,
    )

    // ---- S0 ----

    @Test
    fun entryIsHiddenWhenNoHostIsBound() = desktopE2eTest { _, _, _ ->
        openSettings()
        waitTag("reveal-phrase-row")
        onNodeWithTag("host-compute-row").assertDoesNotExist()
    }

    @Test
    fun theEntryShowsWhereverAHostIsBound() = hostTest { _, _ ->
        openSettings()
        waitTag("host-compute-row")
    }

    @Test
    fun theSingleSettingsRowOpensTheCapabilityCheck() = hostTest { _, _ ->
        openHost()
        waitTag("host-can")
        onNodeWithTag("host-set-up").assertIsDisplayed()
    }

    // ---- S1 ----

    @Test
    fun canHostShowsTheDefaultsSummaryAndACollapsedAdvancedPanel() = hostTest { f, _ ->
        openHost()
        assertText("host-summary", "8 cores · 4 GB for jobs · 16 GB disk · 0.4 GB download")
        onNodeWithTag("host-memory-value").assertDoesNotExist()
        assertEquals(0, f.host.provisionCalls, "the check downloads nothing")
        assertEquals(0, f.granter.startCalls, "and requests nothing")
    }

    @Test
    fun advancedChangesMemoryAndDiskAndSetUpCarriesThemToTheHost() = hostTest { f, _ ->
        openHost()
        tap("host-advanced-toggle")
        tap("host-memory-plus")
        tap("host-disk-minus")
        assertText("host-memory-value", "4.5 GB")
        assertText("host-disk-value", "14 GB")
        assertText("host-summary", "4.5 GB for jobs · 14 GB disk")
        tap("host-set-up")
        waitUntilAtLeastOneExists(hasTestTag("host-checklist"))
        assertEquals(PlanOverride(memoryMb = 4608, diskGb = 14), f.host.overrideAtProvision)
    }

    private fun cannotHost(reason: CannotHostReason, mutate: CapabilityInputs.() -> CapabilityInputs, terminalLink: Boolean = false) {
        val result = evaluateCapability(inputs.mutate()) as CapabilityResult.CannotHost
        assertEquals(reason, result.reason)
        val f = Fakes().also { it.host.capability = result }
        hostTest(f) { _, _ ->
            openHost()
            waitTag("host-cannot")
            onNodeWithTag("host-cannot").assertTextEquals(result.message)
            onNodeWithTag("host-set-up").assertDoesNotExist()
            if (terminalLink) {
                tap("host-terminal-path")
                assertTrue(f.platform.calls.any { it.startsWith("openUrl:") })
            } else {
                onNodeWithTag("host-terminal-path").assertDoesNotExist()
            }
            assertEquals(0, f.host.provisionCalls, "nothing is downloaded or requested")
            assertEquals(0, f.granter.startCalls)
        }
    }

    @Test fun cannotHostAndroidTooOld() = cannotHost(CannotHostReason.ANDROID_TOO_OLD, { copy(sdkInt = 33) })
    @Test fun cannotHostUnsupportedAbi() = cannotHost(CannotHostReason.UNSUPPORTED_ABI, { copy(hostLibraryPresent = false) })
    @Test fun cannotHostNoVirtualizationService() = cannotHost(CannotHostReason.AVF_UNAVAILABLE, { copy(avfServicePresent = false) })
    @Test fun cannotHostProtectedVmsOnly() = cannotHost(CannotHostReason.PROTECTED_VMS_ONLY, { copy(nonProtectedVmSupported = false) })
    @Test fun cannotHostLowRam() = cannotHost(CannotHostReason.LOW_RAM, { copy(totalRamBytes = 4L shl 30) })
    @Test fun cannotHostLowStorage() = cannotHost(CannotHostReason.LOW_STORAGE, { copy(freeStorageBytes = 9L shl 30) })
    @Test fun cannotHostHiddenApiBlockedOffersTheTerminalPath() =
        cannotHost(CannotHostReason.HIDDEN_API_BLOCKED, { copy(hiddenApiWorks = false) }, terminalLink = true)

    // ---- S2 ----

    private fun settingUp(dev: Boolean = true, notifications: Boolean = true) = Fakes(dev = dev, notifications = notifications, committed = true).also {
        it.granter.missing(VmPermission.ManageVirtualMachine, VmPermission.UseCustomVirtualMachine)
        it.host.set(HostState(HostPhase.PROVISIONING, step = ProvisionStep.DOWNLOADING, bytesDone = 150L shl 20, bytesTotal = 300L shl 20))
    }

    @Test
    fun checklistOffersAboutPhoneWhileDeveloperOptionsAreOff() = hostTest(settingUp(dev = false)) { f, _ ->
        waitTag("host-checklist")
        assertText("host-line-dev", "tap build number 7 times, then come back")
        tap("host-open-about")
        assertTrue("openAboutPhone" in f.platform.calls)
        onNodeWithTag("host-open-wd").assertDoesNotExist()
        assertEquals(0, f.granter.startCalls, "the grant flow waits for developer options")
    }

    @Test
    fun checklistAsksForNotificationsJustBeforeWirelessDebugging() = hostTest(settingUp(notifications = false)) { f, _ ->
        waitTag("host-checklist")
        onNodeWithTag("host-open-wd").assertDoesNotExist()
        tap("host-allow-notifications")
        assertTrue("requestNotifications" in f.platform.calls)
    }

    @Test
    fun checklistDeepLinksToWirelessDebuggingAndStartsDiscovery() = hostTest(settingUp()) { f, _ ->
        waitTag("host-checklist")
        assertText("host-line-dev", "on")
        tap("host-open-wd")
        assertTrue("openWirelessDebugging" in f.platform.calls)
        assertTrue(f.granter.startCalls >= 1, "mDNS discovery is already running when the pairing dialog appears")
    }

    @Test
    fun checklistFollowsTheGrantFlowToTheCodePromptAndOnToGranted() = hostTest(settingUp()) { f, _ ->
        f.granter.set(GrantState(GrantPhase.Discovering, missing = f.granter.state.value.missing))
        assertText("host-line-grant", "looking for the pairing screen")
        f.granter.set(GrantState(GrantPhase.AwaitingCode, missing = f.granter.state.value.missing))
        assertText("host-line-grant", "enter the 6-digit code in the notification")
        f.granter.set(GrantState(GrantPhase.Granting, missing = f.granter.state.value.missing))
        assertText("host-line-grant", "granting")
        f.granter.grantAll()
        assertText("host-line-grant", "you can turn wireless debugging and developer options off now")
        onNodeWithTag("host-open-wd").assertDoesNotExist()
    }

    @Test
    fun aWrongCodeOffersTryAgain() = hostTest(settingUp()) { f, _ ->
        f.granter.set(GrantState(GrantPhase.Failed, missing = f.granter.state.value.missing, error = GrantError.WrongCode))
        assertText("host-line-grant", "that code didn't match")
        val cancels = f.granter.cancelCalls
        tap("host-grant-retry")
        assertTrue(f.granter.cancelCalls > cancels)
        assertTrue(f.granter.startCalls >= 1, "the flow starts again")
    }

    @Test
    fun checklistIsReDetectedOnEveryResume() = hostTest(settingUp(dev = false)) { f, state ->
        waitTag("host-open-about")
        f.platform.developerOptions = true
        state.setForeground(false)
        waitForIdle()
        state.setForeground(true)
        waitUntilDoesNotExist(hasTestTag("host-open-about"), timeoutMillis = 15_000)
        assertText("host-line-dev", "on")
    }

    // ---- S3 ----

    @Test
    fun stepperShowsDownloadProgressVerifyingUnpackingAndFirstBoot() = hostTest(settingUp()) { f, _ ->
        assertText("host-step-download", "150 MB / 300 MB · 50%")
        f.host.set(HostState(HostPhase.PROVISIONING, step = ProvisionStep.VERIFYING, bytesDone = 300L shl 20, bytesTotal = 300L shl 20))
        assertText("host-step-download", "verifying")
        f.host.set(HostState(HostPhase.PROVISIONING, step = ProvisionStep.EXTRACTING, bytesDone = 400L shl 20, bytesTotal = 1200L shl 20))
        assertText("host-step-disk", "unpacking · 400 MB")
        f.host.set(HostState(HostPhase.PROVISIONING, step = ProvisionStep.FIRST_BOOT, detail = "waiting for permission"))
        assertText("host-step-boot", "waiting for permission")
    }

    @Test
    fun firstBootThenRunningEndsAtReadyWithNoInput() = hostTest(settingUp()) { f, _ ->
        f.host.set(HostState(HostPhase.PROVISIONING, step = ProvisionStep.FIRST_BOOT))
        f.granter.grantAll()
        f.host.set(HostState(HostPhase.RUNNING))
        waitTag("host-status")
        assertText("host-status-text", "hosting · running")
        assertTrue(f.platform.setupDone)
    }

    // ---- the error table ----

    @Test
    fun downloadFailureRetriesAutomaticallyThenShowsOneLinePlusRetry() {
        HostRetryPolicy.backoffMs = longArrayOf(40, 40)
        hostTest(settingUp()) { f, _ ->
            waitTag("host-provision")
            val base = f.host.provisionCalls
            f.host.set(HostState(HostPhase.ERROR, detail = "download failed — no network (1)", retryable = true))
            waitUntil(timeoutMillis = 10_000) { f.host.provisionCalls == base + 1 }
            f.host.set(HostState(HostPhase.ERROR, detail = "download failed — no network (2)", retryable = true))
            waitUntil(timeoutMillis = 10_000) { f.host.provisionCalls == base + 2 }
            f.host.set(HostState(HostPhase.ERROR, detail = "download failed — no network (3)", retryable = true))
            assertText("host-error", "download failed — no network (3)")
            tap("host-retry")
            waitUntil(timeoutMillis = 10_000) { f.host.provisionCalls == base + 3 }
        }
    }

    @Test
    fun checksumMismatchGetsTheSameRetryPath() {
        HostRetryPolicy.backoffMs = longArrayOf()
        hostTest(settingUp()) { f, _ ->
            f.host.set(HostState(HostPhase.ERROR, detail = "the download didn't match its checksum", retryable = true))
            assertText("host-error", "didn't match its checksum")
            val before = f.host.provisionCalls
            tap("host-retry")
            waitUntil(timeoutMillis = 10_000) { f.host.provisionCalls == before + 1 }
        }
    }

    @Test
    fun lowStorageShowsTheExactSpaceAndResumesOnRetry() = hostTest(settingUp()) { f, _ ->
        f.host.set(HostState(HostPhase.ERROR, detail = "needs 4.2 GB free · 3.1 GB free now", retryable = true))
        assertText("host-error", "needs 4.2 GB free · 3.1 GB free now")
        val before = f.host.provisionCalls
        tap("host-retry")
        waitUntil(timeoutMillis = 10_000) { f.host.provisionCalls == before + 1 }
    }

    @Test
    fun interruptedSetupResumesByItselfAfterProcessDeath() = hostTest(Fakes(committed = true).also {
        it.granter.missing(VmPermission.ManageVirtualMachine)
        // The gid kill: nobody wrote STOPPED, so the observer reports the interruption.
        it.host.set(HostState(HostPhase.ERROR, detail = "setup was interrupted", retryable = true))
    }) { f, state ->
        // No taps: the host screen is already up, on the right step, and provision() was called.
        waitTag("host-provision")
        waitTag("host-checklist")
        assertEquals("settings", state.desktopActive)
        waitUntil(timeoutMillis = 10_000) { f.host.provisionCalls >= 1 }
        f.granter.grantAll()
        f.host.set(HostState(HostPhase.PROVISIONING, step = ProvisionStep.FIRST_BOOT))
        f.host.set(HostState(HostPhase.RUNNING))
        assertText("host-status-text", "hosting · running")
    }

    // ---- S4 ----

    @Test
    fun readyShowsTheStatusCardToggleAndConnectButton() = hostTest(Fakes(committed = true, done = true).also(::ready)) { f, _ ->
        assertText("host-status-text", "hosting · running · 8 cores · 4 GB · 16 GB")
        onNodeWithTag("host-connect").assertIsDisplayed()
        onNodeWithTag("host-toggle").assertIsDisplayed()
        assertText("host-no-grants", "no active grants")
        tap("host-toggle")
        waitUntil(timeoutMillis = 10_000) { f.host.stopCalls == 1 }
    }

    @Test
    fun readyTogglesHostingOnWhenStopped() = hostTest(Fakes(committed = true, done = true).also { it.granter.grantAll() }) { f, _ ->
        assertText("host-status-text", "not hosting")
        tap("host-toggle")
        waitUntil(timeoutMillis = 10_000) { f.host.startCalls == 1 }
        onNodeWithTag("host-connect").assertIsNotEnabled()
        assertText("host-connect-hint", "start hosting")
    }

    @Test
    fun aKilledVmShowsRestartingThenRunning() = hostTest(Fakes(committed = true, done = true).also(::ready)) { f, _ ->
        f.host.set(HostState(HostPhase.RESTARTING, restarts = 1))
        assertText("host-status-text", "restarting…")
        f.host.set(HostState(HostPhase.RUNNING))
        assertText("host-status-text", "hosting · running")
    }

    @Test
    fun aRepeatedlyKilledVmShowsTheCauseAndStart() = hostTest(Fakes(committed = true, done = true).also(::ready)) { f, _ ->
        f.host.set(HostState(HostPhase.ERROR, detail = "stopped — the vm was killed (out of memory)"))
        assertText("host-status-text", "stopped — the vm was killed (out of memory)")
        tap("host-start")
        waitUntil(timeoutMillis = 10_000) { f.host.startCalls == 1 }
    }

    @Test
    fun aMissingGrantSaysPermissionMissingAndTheButtonReRunsOnlyS2() = hostTest(Fakes(committed = true, done = true)) { f, _ ->
        f.granter.missing(VmPermission.UseCustomVirtualMachine)
        f.host.set(HostState(HostPhase.STOPPED))
        assertText("host-permission-missing", "permission missing · use custom virtual machine")
        onNodeWithTag("host-checklist").assertDoesNotExist()
        tap("host-regrant")
        waitTag("host-checklist")
        assertEquals(0, f.host.provisionCalls, "provisioning is not touched")
        f.granter.grantAll()
        waitUntilDoesNotExist(hasTestTag("host-checklist"), timeoutMillis = 15_000)
    }

    @Test
    fun activeGrantsListWithRevoke() {
        val f = Fakes(committed = true, done = true).also(::ready)
        val now = f.host.nowSeconds()
        f.host.seedGrant(GrantSummary("g9", "cloud session · 09:15", created = now - 100, expires = now + 7_300, lastUsed = now - 30))
        f.host.seedGrant(GrantSummary("g8", "expired", created = now - 900, expires = now - 5))
        hostTest(f) { _, _ ->
            waitTag("host-grant-g9")
            assertText("host-grant-g9", "cloud session · 09:15")
            assertText("host-grant-g9", "expires in 2h")
            onNodeWithTag("host-grant-g8").assertDoesNotExist()
            tap("host-revoke-g9")
            waitUntilDoesNotExist(hasTestTag("host-grant-g9"), timeoutMillis = 15_000)
            assertTrue(f.host.liveGrants().none { it.id == "g9" })
        }
    }

    // ---- S5 ----

    private fun connectTest(block: ComposeUiTest.(Fakes) -> Unit) =
        hostTest(Fakes(committed = true, done = true).also(::ready)) { f, _ ->
            tap("host-connect")
            block(f)
        }

    @Test
    fun connectSheetMintsASixHourGrantRightAwayAndShowsTheSnippet() = connectTest { f ->
        waitTag("host-snippet")
        assertEquals("6h" to "cloud session · 12:34", f.host.mintCalls.single())
        assertText("host-snippet", "export AZULA_COMPUTE_TOKEN='azc1.fakeg1' AZULA_RELAY_ONLY=1")
        assertText("host-snippet", "npx -y @azula-app/cli compute doctor && npx -y @azula-app/cli compute exec -- uname -a")
        assertText("host-grant-label", "cloud session · 12:34")
    }

    @Test
    fun connectSheetChipsMintANewGrantAndRevokeTheOneTheyReplace() = connectTest { f ->
        waitTag("host-snippet")
        tap("host-ttl-1h")
        waitUntil(timeoutMillis = 10_000) { f.host.mintCalls.size == 2 }
        assertEquals("1h", f.host.mintCalls.last().first)
        waitUntil(timeoutMillis = 10_000) { f.host.liveGrants().map { it.id } == listOf("g2") }
        tap("host-ttl-7d")
        waitUntil(timeoutMillis = 10_000) { f.host.mintCalls.size == 3 }
        assertEquals("7d", f.host.mintCalls.last().first)
        waitUntil(timeoutMillis = 10_000) { f.host.liveGrants().map { it.id } == listOf("g3") }
    }

    @Test
    fun connectSheetCopyShareAndQr() = connectTest { f ->
        waitTag("host-snippet")
        tap("host-copy")
        assertText("host-copy", "copied")
        tap("host-share")
        assertTrue(f.platform.shared.single().startsWith("export AZULA_COMPUTE_TOKEN='azc1.fakeg1'"))
        onNodeWithTag("host-qr-code").assertDoesNotExist()
        tap("host-qr")
        waitTag("host-qr-code")
    }

    @Test
    fun connectSheetNotesTheRelayAllowlistWithADocsLink() = connectTest { f ->
        waitTag("host-relay-note")
        tap("host-relay-docs")
        assertTrue(f.platform.calls.any { it.startsWith("openUrl:https://azula.app/docs/compute") })
    }

    @Test
    fun connectSheetReportsAMintFailure() = hostTest(Fakes(committed = true, done = true).also(::ready)) { f, _ ->
        f.host.failMint = "the host didn't answer"
        tap("host-connect")
        assertText("host-mint-error", "the host didn't answer")
        onNodeWithTag("host-snippet").assertDoesNotExist()
    }
}
