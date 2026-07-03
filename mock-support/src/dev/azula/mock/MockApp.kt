package dev.azula.mock

import dev.azula.di.AppGraph
import dev.azula.state.AppStateHost
import dev.azula.state.AzulaState
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Build an [AzulaState] wired to the fakes for the `-mock` apps: a [FakeTransport]
 * (fake network + fake terminal + chat loopback) and null message/profile/peer
 * stores (no persistence) — but a real [InMemoryBlobStore], so the `-mock` apps
 * can actually send/receive media (MediaService refuses to send with no
 * BlobStore bound), and a [MockInvitationsStore] so ConnectService's stranger
 * gate recognizes FakeTransport's auto-surfaced demo terminal as a known
 * contact instead of closing it. The `-mock` platform apps call this and
 * install it as the process-scoped state before `AzulaApp()`, so the real
 * transport is never created.
 */
fun buildMockState(scope: CoroutineScope): AzulaState =
    createGraphFactory<AppGraph.Factory>().create(
        scope = scope,
        transport = FakeTransport(),
        notifier = null,
        messageStore = null,
        profileStore = null,
        peerStore = null,
        blobStore = InMemoryBlobStore(),
        invitationsStore = MockInvitationsStore(),
    ).azulaState

/**
 * Installs [buildMockState] as the process-scoped [AppStateHost.shared] (a no-op
 * if one is already installed) and starts it. Shared by the three `-mock` entry
 * points (android-app-mock, jvm-app-mock, ios-app-mock).
 */
fun installMockState() {
    if (AppStateHost.shared == null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AppStateHost.shared = buildMockState(scope).also { scope.launch { it.start() } }
    }
}
