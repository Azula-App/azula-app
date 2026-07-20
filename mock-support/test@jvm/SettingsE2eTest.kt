@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import dev.azula.state.AzulaState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop (JVM) Compose UI tests for the security-adjacent Settings dialogs
 * (personas, recovery-phrase reveal/restore) that
 * azula-docs/openspec/changes/settings-ui-coverage/ flagged as having no UI coverage. Same harness as `DesktopAppE2eTest.kt`
 * ([desktopE2eTest] in `ComposeE2eSupport.kt`): mounts the real [DesktopApp][
 * dev.azula.ui.DesktopApp] over a fresh mock [AzulaState] and drives it with
 * real clicks/typing. "profiles & settings" renders twice once the Settings
 * screen is open -- once as the (non-clickable) screen header, once as the
 * sidebar's still-visible nav row -- so every test disambiguates the sidebar
 * row via its click action, exactly like `DesktopAppE2eTest.connectToMockPeer`
 * does for "connect a peer".
 */
class SettingsE2eTest {

    private fun ComposeUiTest.openSettings() {
        // A real, retrying poll for the exact node about to be clicked, not a
        // bare performClick() -- see desktopE2eTest's doc in ComposeE2eSupport.kt
        // for why a test's first interaction needs this against a still-settling
        // first scene.
        waitUntilAtLeastOneExists(hasText("profiles & settings") and hasClickAction(), timeoutMillis = 15_000)
        onNode(hasText("profiles & settings") and hasClickAction()).performClick()
    }

    @Test
    fun openingSettingsShowsThePersonasAndBackupSections() = desktopE2eTest { _, _, _ ->
        openSettings()
        // Sections unique to the Settings screen body (not the sidebar row),
        // so their presence alone proves navigation actually landed there.
        onNodeWithText("YOUR PERSONAS").assertIsDisplayed()
        onNodeWithText("BACKUP & RECOVERY").assertIsDisplayed()
        onNodeWithTag("reveal-phrase-row").assertIsDisplayed()
        onNodeWithTag("restore-phrase-row").assertIsDisplayed()
        onNodeWithText("No personas yet.").assertIsDisplayed()
    }

    @Test
    fun creatingAPersonaAddsItToTheList() = desktopE2eTest { state, _, _ ->
        openSettings()
        onNodeWithTag("new-persona-button").performClick()
        onNodeWithText("New persona").assertIsDisplayed()

        onNodeWithTag("persona-name-input").performTextInput("Ivy")
        onNodeWithTag("persona-editor-save").performClick()

        waitUntilAtLeastOneExists(hasText("Ivy"), timeoutMillis = 10_000)
        assertEquals(1, state.personas.size, "saving the editor must upsert a persona into state.personas")
        assertEquals("Ivy", state.personas.single().name)
        onNodeWithTag("persona-row-${state.personas.single().id}").assertIsDisplayed()
    }

    @Test
    fun revealPhraseDialogRendersThePhraseAfterConfirming() = desktopE2eTest { state, _, _ ->
        openSettings()
        onNodeWithTag("reveal-phrase-row").performClick()
        onNodeWithText("Reveal recovery phrase?").assertIsDisplayed()

        // Wired to AzulaState.exportRecoveryPhrase() -- FakeTransport.secretKeyBytes()
        // is a fixed 32-byte array, so a phrase is always available in the mock,
        // unlike a real transport before its first bind.
        onNodeWithTag("reveal-phrase-confirm").performClick()
        waitUntilAtLeastOneExists(hasTestTag("recovery-phrase-text"), timeoutMillis = 10_000)
        val phrase = state.exportRecoveryPhrase()
        requireNotNull(phrase)
        onNodeWithText(phrase).assertIsDisplayed()
    }

    @Test
    fun restorePhraseDialogAcceptsThisDevicesOwnPhraseAndCloses() = desktopE2eTest { state, _, _ ->
        val phrase = requireNotNull(state.exportRecoveryPhrase())
        openSettings()
        onNodeWithTag("restore-phrase-row").performClick()
        onNodeWithText("Restore from recovery phrase").assertIsDisplayed()

        onNodeWithTag("restore-phrase-input").performTextInput(phrase)
        onNodeWithTag("restore-phrase-confirm").performClick()

        // A valid phrase (this device's own, decoded and re-encoded identically by
        // RecoveryPhrase) must drive AzulaState.importRecoveryPhrase to succeed and
        // close the dialog -- proving the Restore button is actually wired, not
        // just present.
        waitUntilDoesNotExist(hasText("Restore from recovery phrase"), timeoutMillis = 10_000)
    }
}
