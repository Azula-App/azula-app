package dev.azula.ui.setup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.azula.state.AzulaState

/**
 * The step machine driving the flow, per the onboarding capability's Identity
 * Fork / Recovery-Phrase Back-Up / Persona Step requirements:
 * `FORK -> (RESTORE on "I have a recovery phrase") -> BACKUP -> PERSONA`.
 * [RESTORE] isn't a first-class rail/dots stage: a successful restore skips
 * [BACKUP] entirely, and both [FORK] and [RESTORE] map to the same "identity"
 * rail stage (see `railIndexFor` in `SetupShells.kt`).
 */
enum class SetupStep { FORK, RESTORE, LINK, BACKUP, PERSONA }

/**
 * The first-run setup flow (see the onboarding capability): identity fork ->
 * (restore) -> back up -> persona -> done, presented as a desktop step rail
 * (>=820dp, blueprint 1c, [SetupWideShell]) or mobile stepped cards (<820dp,
 * blueprint 1a, [SetupCompactShell]).
 *
 * Step *position* is a small local state machine private to this composable
 * (design.md D3) -- not new [AzulaState] surface. The state layer only
 * supplies the seams each step calls: [AzulaState.exportRecoveryPhrase],
 * [AzulaState.importRecoveryPhrase], [AzulaState.deferBackup],
 * [AzulaState.upsertPersona] and [AzulaState.completeSetup].
 *
 * Both shells render the same step content composables -- only the
 * surrounding chrome (rail vs. cards+dots) differs -- so the current step's
 * content is built once here and handed to whichever shell the breakpoint
 * (shared with `App.kt`'s `maxWidth >= 820.dp` cut line) selects.
 */
@Composable
fun SetupFlow(state: AzulaState) {
    var step by remember { mutableStateOf(SetupStep.FORK) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("setup-flow")) {
        val wide = maxWidth >= 820.dp
        val columns = if (wide) 3 else 2
        val stepContent: @Composable () -> Unit = {
            when (step) {
                SetupStep.FORK -> ForkStepContent(
                    onCreate = { step = SetupStep.BACKUP },
                    onRestore = { step = SetupStep.RESTORE },
                    onLink = { step = SetupStep.LINK },
                )
                SetupStep.RESTORE -> RestoreStepContent(
                    state = state,
                    onRestored = { step = SetupStep.PERSONA },
                    onBack = { step = SetupStep.FORK },
                )
                // A QR-linked device holds no root secret (multi-device-identity: "a
                // QR-linked device SHALL NOT receive the root secret"), so it has no
                // recovery phrase to back up -- success skips BACKUP entirely, exactly
                // like a successful RESTORE does.
                SetupStep.LINK -> DeviceLinkStepContent(
                    state = state,
                    onLinked = { step = SetupStep.PERSONA },
                    onBack = { step = SetupStep.FORK },
                )
                SetupStep.BACKUP -> BackupStepContent(
                    state = state,
                    columns = columns,
                    onContinue = { step = SetupStep.PERSONA },
                    onDeferred = { step = SetupStep.PERSONA },
                )
                SetupStep.PERSONA -> PersonaStepContent(state = state)
            }
        }
        if (wide) {
            SetupWideShell(step = step, content = stepContent)
        } else {
            SetupCompactShell(step = step, content = stepContent)
        }
    }
}
