@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.text.AnnotatedString
import dev.azula.mock.FakeTransport
import dev.azula.net.IrohTransport
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaTheme
import dev.azula.ui.LocalPhraseFiller
import dev.azula.ui.LocalPhraseSaver
import dev.azula.ui.PhraseFillResult
import dev.azula.ui.PhraseFiller
import dev.azula.ui.PhraseSaveResult
import dev.azula.ui.PhraseSaver
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
private fun setupFlowE2eTest(
    phraseSaver: PhraseSaver? = null,
    clipboard: ClipboardManager? = null,
    phraseFiller: PhraseFiller? = null,
    // Defaults to the same `hasPersistedIdentity = true` FakeTransport() every
    // other test here uses (harmless per the class doc below) -- overridable for
    // a test that needs `state.setupRequired` to stay a genuine "fresh install"
    // signal instead of resolving to false as soon as `state.start()` runs.
    transport: IrohTransport = FakeTransport(),
    block: ComposeUiTest.(state: AzulaState) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        runComposeUiTest {
            val state = testAzulaState(scope, transport)
            scope.launch { state.start() }
            // A [phraseSaver] override replaces the platform save sheet (Credential
            // Manager on android, a share sheet on ios), which is a native modal no
            // UI test can drive -- same rationale as [LocalFilePicker]. The
            // clipboard override lets a test assert what did (or didn't) get
            // written there. [phraseFiller] is the read-side equivalent, and is
            // additionally what makes the fill affordance exist at all here: the JVM
            // actual returns null (no desktop credential store), so without an
            // override there is deliberately no button to press.
            setContent {
                CompositionLocalProvider(
                    LocalPhraseSaver provides phraseSaver,
                    LocalPhraseFiller provides phraseFiller,
                    *(clipboard?.let { arrayOf(LocalClipboardManager provides it) } ?: emptyArray()),
                ) {
                    AzulaTheme { SetupFlow(state) }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag("setup-flow"), timeoutMillis = 30_000)
            block(state)
        }
    } finally {
        scope.cancel()
    }
}

/** Records what the UI wrote to the clipboard, so a test can assert the phrase
 *  was *not* copied on a path that must not copy it. */
private class RecordingClipboard : ClipboardManager {
    // Not named `text`: a `var text` would compile to getText/setText and clash
    // with ClipboardManager's own JVM signatures.
    var written: AnnotatedString? = null
    override fun setText(annotatedString: AnnotatedString) { written = annotatedString }
    override fun getText(): AnnotatedString? = written
}

/** A [PhraseSaver] that reports [result] without presenting anything. */
private fun fakeSaver(result: PhraseSaveResult) = PhraseSaver { _, onResult -> onResult(result) }

/** A [PhraseFiller] that reports [result] without presenting anything. */
private fun fakeFiller(result: PhraseFillResult) = PhraseFiller { onResult -> onResult(result) }

/** Advances to the back-up step, where both phrase actions live. */
private fun ComposeUiTest.gotoBackupStep() {
    onNodeWithTag("setup-fork-create").performClick()
    waitUntilAtLeastOneExists(hasTestTag("setup-backup-phrase"), timeoutMillis = 10_000)
}

/** Advances to the restore step, where the fill affordance lives. */
private fun ComposeUiTest.gotoRestoreStep() {
    onNodeWithTag("setup-fork-restore").performClick()
    waitUntilAtLeastOneExists(hasTestTag("setup-restore-input"), timeoutMillis = 10_000)
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

    // ---- save to password manager (onboarding-password-manager-save) ----
    // Every matcher below is anchored to a test tag rather than a bare text
    // match -- see azula-docs/openspec/specs/testing/design.md, "Known flakes".

    @Test
    fun savingToAPasswordManagerReportsSavedAndDoesNotTouchTheClipboard() {
        val clipboard = RecordingClipboard()
        setupFlowE2eTest(fakeSaver(PhraseSaveResult.SAVED), clipboard) { _ ->
            gotoBackupStep()
            onNodeWithTag("setup-backup-save").performClick()

            // Label first: it auto-clears 1400 ms after the tap (the cadence
            // markCopied() uses), so anything slow in front of this assertion
            // would eat the window. The clipboard assertion below never expires.
            onNodeWithTag("setup-backup-save").assertTextEquals("✓ saved")
            assertEquals(null, clipboard.written, "a successful vault save must not also copy the phrase")
        }
    }

    @Test
    fun cancellingTheSheetCopiesNothingAndClaimsNothing() {
        val clipboard = RecordingClipboard()
        setupFlowE2eTest(fakeSaver(PhraseSaveResult.CANCELLED), clipboard) { _ ->
            gotoBackupStep()
            onNodeWithTag("setup-backup-save").performClick()

            // The user declined to store the phrase: it must not land on the
            // clipboard as a consolation, and the button must not claim success.
            assertEquals(null, clipboard.written, "cancelling must not put the identity key on the clipboard")
            onNodeWithTag("setup-backup-save").assertTextEquals("save to password manager")
        }
    }

    @Test
    fun noProviderFallsBackToTheClipboardAndSaysCopiedNotSaved() {
        val clipboard = RecordingClipboard()
        setupFlowE2eTest(fakeSaver(PhraseSaveResult.UNAVAILABLE), clipboard) { state ->
            gotoBackupStep()
            onNodeWithTag("setup-backup-save").performClick()

            // Must not read "saved" -- the phrase is on the clipboard, not in a
            // vault, and claiming otherwise would leave the user believing the
            // identity key is backed up when it isn't. Asserted first: this label
            // auto-clears after 1400 ms, the clipboard write doesn't.
            onNodeWithTag("setup-backup-save").assertTextEquals("✓ copied instead")
            assertEquals(state.exportRecoveryPhrase(), clipboard.written?.text)
        }
    }

    @Test
    fun copyAndSaveReportIndependently() {
        setupFlowE2eTest(fakeSaver(PhraseSaveResult.SAVED), RecordingClipboard()) { _ ->
            gotoBackupStep()
            onNodeWithTag("setup-backup-copy").performClick()

            // Both buttons used to hang off the same app-wide `state.copied` flash,
            // so copying lit up "✓ saved" on the save button too. The copy label is
            // the one on a 1400 ms timer, so assert it first.
            onNodeWithTag("setup-backup-copy").assertTextEquals("✓ copied")
            onNodeWithTag("setup-backup-save").assertTextEquals("save to password manager")
        }
    }

    // ---- fill from password manager (recovery-phrase-credential-fill) ----
    // Tag-anchored for the same reason as the save tests above.

    @Test
    fun fillingFromAPasswordManagerFillsTheInputWithoutRestoring() {
        // The phrase FakeTransport's key exports -- a real, checksum-valid one, so
        // this test can only pass because fill *doesn't* commit, not because the
        // phrase happened to be rejected.
        var phrase: String? = null
        setupFlowE2eTest(
            phraseFiller = PhraseFiller { onResult -> onResult(PhraseFillResult.Filled(phrase!!)) },
            // A fresh install (no persisted identity, no completed flag): with the
            // default FakeTransport(), `state.start()` marks setup complete on its
            // own (pre-existing install upgrading) before this block even runs,
            // which would make the assertion below trivially true regardless of
            // what filling does.
            transport = FakeTransport(hasPersistedIdentity = false),
        ) { state ->
            phrase = state.exportRecoveryPhrase()
            assertTrue(phrase != null, "FakeTransport must export a phrase for this test to mean anything")
            gotoRestoreStep()
            onNodeWithTag("setup-restore-fill").performClick()

            onNodeWithTag("setup-restore-input").assertTextEquals(phrase!!)
            // Still on the restore step with Restore un-pressed: a picker selection
            // must not replace this device's identity key on its own.
            onNodeWithTag("setup-restore-submit").assertIsDisplayed()
            assertTrue(state.setupRequired != false, "filling alone must not complete the restore")
        }
    }

    @Test
    fun dismissingTheProviderSheetLeavesTheInputUnchanged() {
        setupFlowE2eTest(phraseFiller = fakeFiller(PhraseFillResult.Cancelled)) { _ ->
            gotoRestoreStep()
            onNodeWithTag("setup-restore-input").performTextInput("half typed")
            onNodeWithTag("setup-restore-fill").performClick()

            onNodeWithTag("setup-restore-input").assertTextEquals("half typed")
            // Nothing to report either -- the user declined, they didn't fail.
            onNodeWithTag("setup-restore-fill").assertTextEquals("fill from password manager")
        }
    }

    @Test
    fun anEmptyVaultSaysSoRatherThanFailingSilently() {
        setupFlowE2eTest(phraseFiller = fakeFiller(PhraseFillResult.Unavailable)) { _ ->
            gotoRestoreStep()
            onNodeWithTag("setup-restore-fill").performClick()

            // Asserted first: this label clears itself after 1400 ms. A silent no-op
            // here would be indistinguishable from a broken button.
            onNodeWithTag("setup-restore-fill").assertTextEquals("no saved phrase found")
            // The field itself stayed empty: an empty BasicTextField's decorationBox
            // renders the placeholder as a merged sibling Text node, so the node's
            // accessible text is the placeholder plus an empty editable text, not "".
            onNodeWithTag("setup-restore-input").assertTextEquals("word word word …", "")
        }
    }

    @Test
    fun aFilledButInvalidPhraseStillShowsTheInlineError() {
        setupFlowE2eTest(phraseFiller = fakeFiller(PhraseFillResult.Filled("not a real phrase"))) { _ ->
            gotoRestoreStep()
            onNodeWithTag("setup-restore-fill").performClick()
            onNodeWithTag("setup-restore-submit").performClick()

            // A provider handing back a truncated or altered phrase must land on the
            // existing validate-then-commit path, not a parallel one -- which is the
            // whole reason fill routes through `input` instead of restoring directly.
            waitUntilAtLeastOneExists(hasTestTag("setup-restore-error"), timeoutMillis = 10_000)
            onNodeWithTag("setup-restore-error").assertIsDisplayed()
            onNodeWithTag("setup-restore-input").assertIsDisplayed()
        }
    }

    @Test
    fun noAffordanceWhereThePlatformHasNoCredentialStore() {
        // No filler override, so `rememberPhraseFiller()` falls through to the JVM
        // actual's null: desktop renders no button at all rather than one whose only
        // possible answer is "no". The step stays usable by typing.
        setupFlowE2eTest { _ ->
            gotoRestoreStep()
            onNodeWithTag("setup-restore-fill").assertDoesNotExist()
            onNodeWithTag("setup-restore-input").performTextInput("typed by hand")
            onNodeWithTag("setup-restore-input").assertTextEquals("typed by hand")
        }
    }
}
