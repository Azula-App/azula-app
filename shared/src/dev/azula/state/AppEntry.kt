package dev.azula.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.azula.di.AppGraph
import dev.azula.net.createTransport
import dev.azula.notify.platformNotifier
import dev.azula.persist.platformBlobStore
import dev.azula.persist.platformInvitationsStore
import dev.azula.persist.platformMediaExporter
import dev.azula.persist.platformMessageStore
import dev.azula.persist.platformProfileStore
import dev.azula.persist.platformSettingsStore
import dev.azula.net.platformPeerStore
import dev.zacsweers.metro.createGraphFactory
import kotlin.concurrent.Volatile

/**
 * Lets a platform own [AzulaState] outside the composition. Android sets this to a
 * process-scoped singleton (created by its `Application`) so the iroh node keeps
 * running while the Activity is backgrounded/destroyed — see the foreground
 * service. iOS/desktop leave it null and create per-composition (below).
 */
object AppStateHost {
    @Volatile
    var shared: AzulaState? = null
}

/**
 * Creates the app-wide [AzulaState] once — unless a platform has pre-installed a
 * process-scoped singleton in [AppStateHost] (Android). The scope is tied to the
 * root composition, i.e. the app's lifetime.
 *
 * Note: this no longer starts the state. `start()` now runs from [dev.azula.SetupGate]
 * (see the onboarding capability) so the pre-bind setup-gate decision can be made
 * before the transport binds; the `-mock` apps and tests call `start()` themselves
 * (see `installMockState` / `testAzulaState`), so this move only affects the
 * composition-scoped desktop/iOS path, which now starts via the gate.
 */
@Composable
fun rememberAzulaState(): AzulaState {
    AppStateHost.shared?.let { return it }
    val scope = rememberCoroutineScope()
    // Composition-scoped path (desktop/iOS): build the flat graph with the real
    // iroh transport + platform default stores. Android pre-installs a process-scoped
    // instance in AppStateHost (AzulaApplication); the -mock apps pre-install one too.
    return remember {
        createGraphFactory<AppGraph.Factory>().create(
            scope = scope,
            transport = createTransport(),
            notifier = platformNotifier(),
            messageStore = platformMessageStore(),
            profileStore = platformProfileStore(),
            settingsStore = platformSettingsStore(),
            peerStore = platformPeerStore(),
            blobStore = platformBlobStore(),
            invitationsStore = platformInvitationsStore(),
            mediaExporter = platformMediaExporter(),
        ).azulaState
    }
}
