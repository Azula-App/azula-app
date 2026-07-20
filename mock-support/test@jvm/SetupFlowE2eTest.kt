@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import dev.azula.mock.FakeTransport
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaTheme
import dev.azula.ui.setup.SetupFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Desktop (JVM) Compose UI tests for the first-run setup flow (the onboarding
 * capability, azula-docs/openspec/changes/initial-setup-flow/): [SetupFlow] is
 * mounted directly (design.md D6), not the whole [dev.azula.ui.DesktopApp] /
 * `SetupGate`, since the flow itself -- not the gate that decides whether to
 * show it -- is what's under test here (`SetupGateTest.kt` at the state layer
 * covers the gate decision).
 *
 * [setupFlowE2eTest] mirrors [desktopE2eTest] (`ComposeE2eSupport.kt`) but
 * over a plain [testAzulaState] with a default [FakeTransport] (harmless --
 * FakeTransport's `secretKeyBytes()` is a fixed 32-byte array regardless of
 * `hasPersistedIdentity`, so `state.exportRecoveryPhrase()` is always non-null
 * and the back-up step's word grid/copy/checkbox/Continue render immediately,
 * with no bind needed). `state.start()` isn't required for any of the seams
 * [SetupFlow] calls (`exportRecoveryPhrase`/`importRecoveryPhrase`/
 * `deferBackup`/`upsertPersona`/`completeSetup`), but is still invoked for
 * parity with a real launch -- see [desktopE2eTest]'s doc for the cold-start
 * `waitUntilAtLeastOneExists` rationale, reused here against `setup-flow`.
 */
private fun setupFlowE2eTest(block: ComposeUiTest.(state: AzulaState) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        runComposeUiTest {
            val state = testAzulaState(scope, FakeTransport())
            scope.launch { state.start() }
            setContent { AzulaTheme { SetupFlow(state) } }
            waitUntilAtLeastOneExists(hasTestTag("setup-flow"), timeoutMillis = 30_000)
            block(state)
        }
    } finally {
        scope.cancel()
    }
}

class SetupFlowE2eTest {

    @Test
    fun forkCreateBackupPersonaHappyPath() = setupFlowE2eTest { state ->
        onNodeWithTag("setup-fork-create").performClick()
        waitUntilAtLeastOneExists(hasTestTag("setup-backup-phrase"), timeoutMillis = 10_000)

        // Continue gated on confirmation (Recovery-Phrase Back-Up Step): disabled
        // until the checkbox is checked.
        onNodeWithTag("setup-backup-continue").assertIsNotEnabled()
        onNodeWithTag("setup-backup-confirm").performClick()
        onNodeWithTag("setup-backup-continue").assertIsEnabled()
        onNodeWithTag("setup-backup-continue").performClick()

        waitUntilAtLeastOneExists(hasTestTag("setup-persona-name"), timeoutMillis = 10_000)
        onNodeWithTag("setup-persona-name").performTextInput("Ivy")
        onNodeWithTag("setup-persona-save").performClick()

        waitUntil(timeoutMillis = 10_000) { state.personas.isNotEmpty() }
        assertEquals(1, state.personas.size, "saving the persona step must upsert exactly one persona")
        assertEquals("Ivy", state.personas.single().name)
        assertEquals(false, state.setupRequired, "finishing the persona step must complete setup and drop the gate")
    }

    @Test
    fun restoreWithAnInvalidPhraseShowsInlineErrorAndStaysOnRestore() = setupFlowE2eTest { _ ->
        onNodeWithTag("setup-fork-restore").performClick()
        waitUntilAtLeastOneExists(hasTestTag("setup-restore-input"), timeoutMillis = 10_000)

        onNodeWithTag("setup-restore-input").performTextInput("not a real phrase")
        onNodeWithTag("setup-restore-submit").performClick()

        waitUntilAtLeastOneExists(hasTestTag("setup-restore-error"), timeoutMillis = 10_000)
        onNodeWithTag("setup-restore-error").assertIsDisplayed()
        // Still on the restore step -- the identity was left unchanged.
        onNodeWithTag("setup-restore-input").assertIsDisplayed()
    }

    @Test
    fun personaSkipWritesNoPersonaData() = setupFlowE2eTest { state ->
        onNodeWithTag("setup-fork-create").performClick()
        waitUntilAtLeastOneExists(hasTestTag("setup-backup-confirm"), timeoutMillis = 10_000)
        onNodeWithTag("setup-backup-confirm").performClick()
        onNodeWithTag("setup-backup-continue").performClick()

        waitUntilAtLeastOneExists(hasTestTag("setup-persona-skip"), timeoutMillis = 10_000)
        onNodeWithTag("setup-persona-skip").performClick()

        waitUntil(timeoutMillis = 10_000) { state.setupRequired == false }
        assertTrue(state.personas.isEmpty(), "skip for now must complete setup without writing any persona data")
    }
}
