import dev.azula.host.GrantTtl
import dev.azula.host.HostRetryPolicy
import dev.azula.host.HostSetupService
import dev.azula.host.HostStage
import dev.azula.host.computeSnippet
import dev.azula.mock.FakeHostPlatform
import dev.azula.mock.FakeVmHost
import dev.azula.mock.FakeVmPermissionGranter
import dev.azula.selfgrant.GrantPhase
import dev.azula.selfgrant.GrantState
import dev.azula.selfgrant.VmPermission
import dev.azula.vmhost.CannotHostReason
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.GrantSummary
import dev.azula.vmhost.HostPhase
import dev.azula.vmhost.HostState
import dev.azula.vmhost.PlanOverride
import dev.azula.vmhost.ProvisionStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host-compute state machine (compute-android-app-host 3.4/3.6, design D9): which stage shows,
 * what "set up" kicks off, resume after process death, the automatic retries, completion, and
 * the grant mint/list/revoke path. The screens over it are covered by HostComputeUiTest.
 */
class HostSetupServiceTest {
    private class Rig(
        scope: CoroutineScope,
        committed: Boolean = false,
        done: Boolean = false,
        labs: Boolean = true,
        dev: Boolean = true,
        notifications: Boolean = true,
    ) {
        val host = FakeVmHost()
        val granter = FakeVmPermissionGranter()
        val platform = FakeHostPlatform(labs = labs, developerOptions = dev, notifications = notifications, committed = committed, done = done)
        val svc = HostSetupService(scope, host, granter, platform)
    }

    @AfterTest fun resetPolicy() { HostRetryPolicy.backoffMs = longArrayOf(3_000, 8_000) }

    @Test
    fun noHostBindingMeansUnavailableAndHidden() = runTest {
        val svc = HostSetupService(backgroundScope, null, null, null)
        assertFalse(svc.available)
        assertFalse(svc.entryVisible)
        assertFalse(svc.homeCardVisible)
    }

    @Test
    fun entryFollowsTheLabsSwitch() = runTest {
        val r = Rig(backgroundScope, labs = false)
        assertFalse(r.svc.entryVisible, "a release build with labs off shows no entry")
        r.svc.enableLabs()
        assertTrue(r.svc.entryVisible)
        assertTrue("enableLabs" in r.platform.calls)
    }

    @Test
    fun freshInstallStartsAtTheCapabilityCheckAndHomeCardAppearsOnceItPasses() = runTest {
        val r = Rig(backgroundScope)
        runCurrent()
        assertEquals(HostStage.CHECK, r.svc.stage)
        assertFalse(r.svc.homeCardVisible, "not known to qualify until checked")
        r.svc.check(); runCurrent()
        assertTrue(r.svc.capability is CapabilityResult.CanHost)
        assertTrue(r.svc.homeCardVisible)
        assertEquals(0, r.host.provisionCalls, "checking never downloads or provisions")
        assertEquals(0, r.granter.startCalls)
    }

    @Test
    fun aPhoneThatCannotHostHasNoHomeCardAndNothingStarts() = runTest {
        val r = Rig(backgroundScope)
        r.host.capability = CapabilityResult.CannotHost(CannotHostReason.LOW_RAM, "needs 6 GB of ram · this phone has 4 GB")
        r.svc.check(); runCurrent()
        assertFalse(r.svc.homeCardVisible)
        assertEquals(0, r.host.provisionCalls)
    }

    @Test
    fun setUpCommitsPersistsAndStartsProvisioningWithTheOverride() = runTest {
        val r = Rig(backgroundScope)
        r.granter.missing(VmPermission.ManageVirtualMachine, VmPermission.UseCustomVirtualMachine)
        runCurrent()
        r.svc.setMemoryMb(3072); r.svc.setDiskGb(12)
        r.svc.setUp(); runCurrent()
        assertTrue(r.platform.setupCommitted, "committed is persisted so setup resumes after process death")
        assertEquals(HostStage.SETUP, r.svc.stage)
        assertEquals(1, r.host.provisionCalls)
        assertEquals(PlanOverride(3072, 12), r.host.overrideAtProvision, "the override is stored before provisioning starts")
        assertEquals(1, r.granter.startCalls, "with notifications and developer options ready, the grant flow starts in parallel")
    }

    @Test
    fun setUpDoesNotStartTheGrantFlowWhileDeveloperOptionsAreOff() = runTest {
        val r = Rig(backgroundScope, dev = false)
        r.granter.missing(VmPermission.ManageVirtualMachine)
        r.svc.setUp(); runCurrent()
        assertEquals(1, r.host.provisionCalls, "the download starts regardless")
        assertEquals(0, r.granter.startCalls)
        // The user turns Developer options on and returns: resume re-detects and starts the flow.
        r.platform.developerOptions = true
        r.svc.refresh(); runCurrent()
        assertTrue(r.svc.developerOptions)
        assertEquals(1, r.granter.startCalls)
    }

    @Test
    fun resumeAfterProcessDeathReopensSetupAndCallsProvisionAgain() = runTest {
        // committed but not done: the app process was killed by the permission grant.
        val r = Rig(backgroundScope, committed = true, done = false)
        runCurrent()
        assertTrue(r.svc.open, "the host screen reopens by itself")
        assertEquals(HostStage.SETUP, r.svc.stage)
        assertTrue(r.host.provisionCalls >= 1, "provision() is called on resume, so the host carries on")
    }

    @Test
    fun theInterruptedErrorAutoResumesWithoutTheUser() = runTest {
        val r = Rig(backgroundScope, committed = true)
        runCurrent()
        val before = r.host.provisionCalls
        r.host.set(HostState(HostPhase.ERROR, detail = "setup was interrupted", retryable = true))
        runCurrent()
        assertEquals(before + 1, r.host.provisionCalls)
    }

    @Test
    fun reachingRunningWithBothGrantsIsReadyAndPersistsDone() = runTest {
        val r = Rig(backgroundScope, committed = true)
        r.granter.grantAll()
        r.host.set(HostState(HostPhase.PROVISIONING, step = ProvisionStep.FIRST_BOOT))
        runCurrent()
        assertEquals(HostStage.SETUP, r.svc.stage)
        r.host.set(HostState(HostPhase.RUNNING))
        runCurrent()
        assertEquals(HostStage.READY, r.svc.stage)
        assertTrue(r.platform.setupDone)
    }

    @Test
    fun runningWithoutTheGrantsIsNotReady() = runTest {
        val r = Rig(backgroundScope, committed = true)
        r.granter.missing(VmPermission.ManageVirtualMachine)
        r.host.set(HostState(HostPhase.RUNNING))
        runCurrent()
        assertEquals(HostStage.SETUP, r.svc.stage)
        assertFalse(r.platform.setupDone)
    }

    @Test
    fun aDownloadFailureRetriesAutomaticallyThenAsksAfterTheThirdFailure() = runTest {
        val r = Rig(backgroundScope, committed = true)
        runCurrent()
        val base = r.host.provisionCalls
        r.host.set(HostState(HostPhase.ERROR, detail = "download failed — no network (1)", retryable = true)); runCurrent()
        assertTrue(r.svc.retryPending, "first failure: back off, then try again by itself")
        advanceTimeBy(3_001); runCurrent()
        assertEquals(base + 1, r.host.provisionCalls)
        r.host.set(HostState(HostPhase.ERROR, detail = "download failed — no network (2)", retryable = true)); runCurrent()
        assertTrue(r.svc.retryPending)
        advanceTimeBy(8_001); runCurrent()
        assertEquals(base + 2, r.host.provisionCalls)
        r.host.set(HostState(HostPhase.ERROR, detail = "download failed — no network (3)", retryable = true)); runCurrent()
        assertFalse(r.svc.retryPending, "third failure: stop retrying, show retry")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(base + 2, r.host.provisionCalls)
        r.svc.retry(); runCurrent()
        assertEquals(base + 3, r.host.provisionCalls, "the button runs the step again")
        assertEquals(0, r.svc.autoRetries, "and gives it a fresh automatic budget")
    }

    @Test
    fun lowStorageNeverRetriesAutomatically() = runTest {
        val r = Rig(backgroundScope, committed = true)
        runCurrent()
        val base = r.host.provisionCalls
        r.host.set(HostState(HostPhase.ERROR, detail = "needs 4.2 GB free · 3.1 GB free now", retryable = true)); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertFalse(r.svc.retryPending)
        assertEquals(base, r.host.provisionCalls, "freeing space is the user's move; retry resumes")
        r.svc.retry(); runCurrent()
        assertEquals(base + 1, r.host.provisionCalls)
    }

    @Test
    fun aGrantMissingOnAReadyHostIsFixedByRerunningOnlyTheGrantStep() = runTest {
        val r = Rig(backgroundScope, committed = true, done = true)
        r.granter.missing(VmPermission.UseCustomVirtualMachine)
        r.host.set(HostState(HostPhase.STOPPED))
        runCurrent()
        assertEquals(HostStage.READY, r.svc.stage)
        assertTrue(r.svc.permissionMissing)
        assertFalse(r.svc.showChecklist, "S4 says permission missing first; the checklist waits for the button")
        val provisions = r.host.provisionCalls
        r.svc.regrant(); runCurrent()
        assertTrue(r.svc.showChecklist)
        assertEquals(1, r.granter.startCalls)
        assertEquals(provisions, r.host.provisionCalls, "only S2 re-runs, never provisioning")
        r.granter.grantAll(); runCurrent()
        assertFalse(r.svc.showChecklist, "once granted the checklist goes away")
    }

    @Test
    fun hostingToggleStartsAndStops() = runTest {
        val r = Rig(backgroundScope, committed = true, done = true)
        r.granter.grantAll(); runCurrent()
        r.svc.setHosting(true); r.svc.setHosting(false)
        assertEquals(1, r.host.startCalls)
        assertEquals(1, r.host.stopCalls)
    }

    @Test
    fun mintLabelsTheGrantAndListsOnlyLiveOnesNewestFirst() = runTest {
        val r = Rig(backgroundScope, committed = true, done = true)
        r.granter.grantAll()
        r.host.set(HostState(HostPhase.RUNNING))
        val now = r.host.nowSeconds()
        r.host.seedGrant(GrantSummary("old", "expired one", created = now - 900, expires = now - 10))
        r.host.seedGrant(GrantSummary("rev", "revoked one", created = now - 800, expires = now + 900, revoked = now - 5))
        runCurrent()
        val minted = r.svc.mint(GrantTtl.ONE_HOUR).getOrThrow(); runCurrent()
        assertEquals("1h" to "cloud session · 12:34", r.host.mintCalls.single())
        assertTrue(minted.token.startsWith("azc1."))
        assertEquals(listOf(minted.grant.id), r.svc.grants.map { it.id }, "expired and revoked grants don't show")
    }

    @Test
    fun revokeRemovesTheGrantAndMintFailureIsReported() = runTest {
        val r = Rig(backgroundScope, committed = true, done = true)
        r.granter.grantAll()
        r.host.set(HostState(HostPhase.RUNNING))
        runCurrent()
        val g = r.svc.mint(GrantTtl.SIX_HOURS).getOrThrow(); runCurrent()
        r.svc.revoke(g.grant.id); runCurrent()
        assertTrue(r.svc.grants.isEmpty())
        r.host.failMint = "the host didn't answer"
        assertEquals("the host didn't answer", r.svc.mint(GrantTtl.SIX_HOURS).exceptionOrNull()?.message)
    }

    @Test
    fun theSnippetIsTheDocumentedTwoLines() {
        assertEquals(
            "export AZULA_COMPUTE_TOKEN='azc1.abc' AZULA_RELAY_ONLY=1\n" +
                "npx -y @azula-app/cli compute doctor && npx -y @azula-app/cli compute exec -- uname -a",
            computeSnippet("azc1.abc"),
        )
    }

    @Test
    fun grantFlowFailureLeavesStageAlone() = runTest {
        val r = Rig(backgroundScope, committed = true)
        r.granter.set(GrantState(GrantPhase.Failed, missing = setOf(VmPermission.ManageVirtualMachine)))
        runCurrent()
        assertEquals(HostStage.SETUP, r.svc.stage)
        assertNull(r.svc.grant.error)
    }
}
