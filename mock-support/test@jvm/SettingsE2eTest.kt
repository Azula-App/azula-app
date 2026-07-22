@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import dev.azula.persist.Persona
import dev.azula.state.AzulaState
import dev.azula.ui.PickedFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun restorePhraseDialogRejectsAnInvalidPhraseAndStaysOpen() = desktopE2eTest { state, _, _ ->
        val phraseBefore = state.exportRecoveryPhrase()
        openSettings()
        onNodeWithTag("restore-phrase-row").performClick()

        // 24 well-formed-looking but non-BIP39 words: exercises the failure branch
        // (importRecoveryPhrase returns false) rather than the blank-input guard,
        // which merely leaves the button disabled.
        onNodeWithTag("restore-phrase-input").performTextInput(List(24) { "zzzz" }.joinToString(" "))
        onNodeWithTag("restore-phrase-confirm").performClick()

        waitUntilAtLeastOneExists(hasText("That isn't a valid recovery phrase."), timeoutMillis = 10_000)
        // The dialog must stay open so the user can correct the paste, and the
        // identity must be untouched.
        onNodeWithText("Restore from recovery phrase").assertIsDisplayed()
        assertEquals(phraseBefore, state.exportRecoveryPhrase(), "a rejected phrase must not replace the identity")
    }

    @Test
    fun settingDefaultOnASecondPersonaMovesTheDefault() = desktopE2eTest { state, _, _ ->
        // PersonaService.upsert auto-defaults the *first* persona, so a lone
        // persona renders "default ✓" and has no "set default" chip to click --
        // moving the default is only observable with two.
        val first = state.newPersonaId()
        state.upsertPersona(Persona(id = first, name = "Ivy"))
        val second = state.newPersonaId()
        state.upsertPersona(Persona(id = second, name = "Wren"))
        assertEquals(first, state.defaultPersonaId, "the first persona is the implicit default")

        openSettings()
        waitUntilAtLeastOneExists(hasText("set default"), timeoutMillis = 10_000)
        onNode(hasText("set default")).performClick()

        waitUntil(timeoutMillis = 10_000) { state.defaultPersonaId == second }
        assertEquals(second, state.defaultPersonaId)
    }

    @Test
    fun editingAPersonaFromItsRowRenamesItInPlace() = desktopE2eTest { state, _, _ ->
        val id = state.newPersonaId()
        state.upsertPersona(Persona(id = id, name = "Ivy"))
        openSettings()

        // Clicking the row (not "+ new persona") must open the editor seeded with
        // the existing persona -- the "Edit persona" title proves `initial` was
        // threaded through rather than a blank New-persona sheet.
        waitUntilAtLeastOneExists(hasTestTag("persona-row-$id"), timeoutMillis = 10_000)
        onNodeWithTag("persona-row-$id").performClick()
        onNodeWithText("Edit persona").assertIsDisplayed()

        onNodeWithTag("persona-name-input").performTextReplacement("Ivy Renamed")
        onNodeWithTag("persona-editor-save").performClick()

        waitUntilAtLeastOneExists(hasText("Ivy Renamed"), timeoutMillis = 10_000)
        // Upserted in place, not appended as a second persona.
        assertEquals(1, state.personas.size, "editing must upsert onto the same id, not create a new persona")
        assertEquals("Ivy Renamed", state.personas.single().name)
        assertEquals(id, state.personas.single().id)
    }

    @Test
    fun deletingAPersonaRemovesItFromState() = desktopE2eTest { state, _, _ ->
        val id = state.newPersonaId()
        state.upsertPersona(Persona(id = id, name = "Ivy"))
        openSettings()

        waitUntilAtLeastOneExists(hasTestTag("persona-row-$id"), timeoutMillis = 10_000)
        // Wait until the sidebar's seeded conversation has landed too, so this
        // test always runs in the *ambiguous* regime rather than racing it.
        // FakeTransport.incoming() emits that conversation on a 300ms delay, and
        // it carries its own ConvOverflowMenu "⋮" -- so an idle machine used to
        // get here first and see one node, while a loaded one arrived second and
        // saw two, failing with "Expected exactly '1' node but found '2'" (a real
        // 1-in-6 failure caught while measuring the baseline for
        // stabilize-headless-test-flakes). Forcing two makes the anchoring below
        // the thing under test instead of a coin flip.
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("⋮")).fetchSemanticsNodes().size >= 2
        }
        // Scope the overflow to *this persona's row*; a bare hasText("⋮") is
        // ambiguous exactly as described above.
        onNode(hasText("⋮") and hasAnyAncestor(hasTestTag("persona-row-$id"))).performClick()
        waitUntilExactlyOneExists(hasText("Delete") and hasClickAction(), timeoutMillis = 10_000)
        onNode(hasText("Delete") and hasClickAction()).performClick()

        // ConvOverflowMenu is shared with conversation rows, but PersonaRow passes
        // persona-specific confirm copy -- waiting on that title is how we know the
        // menu item fired and the confirm step is up.
        waitUntilAtLeastOneExists(hasText("Delete persona?"), timeoutMillis = 10_000)
        // Wait out the dropdown's dismiss animation: until it finishes, both the
        // menu item and the dialog's confirm button match "Delete", and the onNode
        // below would throw the same "found '2'" assertion.
        waitUntilExactlyOneExists(hasText("Delete") and hasClickAction(), timeoutMillis = 10_000)
        onNode(hasText("Delete") and hasClickAction()).performClick()

        waitUntilDoesNotExist(hasTestTag("persona-row-$id"), timeoutMillis = 10_000)
        assertEquals(0, state.personas.size)
    }

    @Test
    fun pickingAnImageInThePersonaEditorSetsTheAvatar() {
        val picked = PickedFile(name = "avatar.png", mime = "image/png", bytes = onePixelPng())
        desktopE2eTest(filePicker = { onPicked -> onPicked(picked) }) { state, _, _ ->
            openSettings()
            onNodeWithTag("new-persona-button").performClick()
            onNodeWithTag("persona-name-input").performTextInput("Ivy")

            onNodeWithText("pick picture").performClick()
            // The label flips only once encodeAvatar() actually decoded, scaled and
            // re-encoded the bytes -- so this asserts the whole seam, not just that
            // the picker fired.
            waitUntilAtLeastOneExists(hasText("change picture"), timeoutMillis = 10_000)
            onNodeWithText("remove").assertIsDisplayed()

            onNodeWithTag("persona-editor-save").performClick()
            waitUntilAtLeastOneExists(hasText("Ivy"), timeoutMillis = 10_000)
            assertNotNull(state.personas.single().avatarB64, "a picked image must be saved onto the persona")
            assertEquals("image/jpeg", state.personas.single().mime, "avatars are re-encoded to JPEG by scaleImageForAvatar")
        }
    }

    @Test
    fun cancellingThePickerLeavesTheAvatarUnset() {
        // A picker that never calls onPicked is exactly what a cancelled native
        // dialog does.
        desktopE2eTest(filePicker = { /* user cancelled */ }) { state, _, _ ->
            openSettings()
            onNodeWithTag("new-persona-button").performClick()
            onNodeWithTag("persona-name-input").performTextInput("Ivy")

            onNodeWithText("pick picture").performClick()
            waitForIdle()
            onNodeWithText("pick picture").assertIsDisplayed()

            onNodeWithTag("persona-editor-save").performClick()
            waitUntilAtLeastOneExists(hasText("Ivy"), timeoutMillis = 10_000)
            assertNull(state.personas.single().avatarB64, "a cancelled pick must leave the avatar unset")
        }
    }

    @Test
    fun pickingANonImageIsRejected() {
        val picked = PickedFile(name = "notes.txt", mime = "text/plain", bytes = "hello".encodeToByteArray())
        desktopE2eTest(filePicker = { onPicked -> onPicked(picked) }) { state, _, _ ->
            openSettings()
            onNodeWithTag("new-persona-button").performClick()
            onNodeWithTag("persona-name-input").performTextInput("Ivy")

            onNodeWithText("pick picture").performClick()
            waitForIdle()
            // Settings.kt guards on mime.startsWith("image/"), so the label must not flip.
            onNodeWithText("pick picture").assertIsDisplayed()

            onNodeWithTag("persona-editor-save").performClick()
            waitUntilAtLeastOneExists(hasText("Ivy"), timeoutMillis = 10_000)
            assertNull(state.personas.single().avatarB64, "a non-image pick must be ignored")
        }
    }
}

/** Smallest valid PNG (1x1) -- enough for `Image.makeFromEncoded` to decode and
 *  `scaleImageForAvatar` to re-encode, without checking a fixture into the repo. */
@OptIn(ExperimentalEncodingApi::class)
private fun onePixelPng(): ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
)
