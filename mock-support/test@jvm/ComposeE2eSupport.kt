@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import dev.azula.mock.FakeTransport
import dev.azula.ui.FilePicker
import dev.azula.ui.LocalFilePicker
import dev.azula.mock.InMemoryBlobStore
import dev.azula.mock.MockInvitationsStore
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaTheme
import dev.azula.ui.DesktopApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shared harness for the JVM Compose UI e2e suites (`DesktopAppE2eTest`,
 * `SettingsE2eTest`, `InviteReviewSheetE2eTest`): mounts the real [DesktopApp]
 * root composable over a fresh mock [AzulaState] (a fresh [FakeTransport],
 * never the process-wide `AppStateHost.shared`, so tests can't see each
 * other's conversations) and runs [block] against it via [runComposeUiTest].
 * Extracted from DesktopAppE2eTest.kt so every Compose UI test file gets the
 * exact same setup instead of duplicating it -- see that file's class doc for
 * the fuller rationale (DI wiring, why a real transport instance is threaded
 * through, the "never advanceUntilIdle" background-dispatcher note).
 *
 * A real polling wait for the sidebar brand ("azula", always present -- see
 * [dev.azula.ui.Brand] -- regardless of any conversation/connect/invite state)
 * runs right after [setContent], before [block] is ever invoked. This was
 * found necessary, not merely defensive: the very first [runComposeUiTest]
 * scene created in a whole test JVM process pays a one-time Skiko/font-loading
 * cold-start cost that isn't always fully settled by the time [setContent]
 * returns -- reproduced directly as a "could not find node" failure on a
 * test's very first UI action, but *only* for whichever test happened to run
 * first in the process (see azula-docs/openspec/specs/testing/design.md, "Known flakes"). A single
 * [ComposeUiTest.waitForIdle] call in the same spot was tried first and did
 * NOT reliably clear it (idling settles Compose's own recomposition/snapshot
 * state, but doesn't retry against whatever the cold-start latency actually
 * is); a real `waitUntilAtLeastOneExists` polls and retries up to its timeout,
 * which does. Every test using this harness gets this for free instead of
 * each one needing its own defensive wait before its first interaction.
 */
internal fun desktopE2eTest(
    filePicker: FilePicker? = null,
    // Defaults to a fresh, ordinary root-holding transport, same as every existing
    // caller got before this parameter existed. A test that needs a specific transport
    // shape (e.g. FakeTransport(rootless = true) for the reveal-phrase rootless-copy
    // scenario) supplies its own instance instead.
    transport: FakeTransport = FakeTransport(),
    block: ComposeUiTest.(state: AzulaState, transport: FakeTransport, scope: CoroutineScope) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        runComposeUiTest {
            val state = testAzulaState(scope, transport, messageStore = null, blobStore = InMemoryBlobStore(), invitationsStore = MockInvitationsStore())
            scope.launch { state.start() }
            // A [filePicker] override replaces the platform picker, which is a
            // native modal no UI test can drive -- see [LocalFilePicker].
            setContent {
                CompositionLocalProvider(LocalFilePicker provides filePicker) {
                    AzulaTheme { DesktopApp(state) }
                }
            }
            waitUntilAtLeastOneExists(hasText("azula"), timeoutMillis = 30_000)
            block(state, transport, scope)
        }
    } finally {
        scope.cancel()
    }
}
