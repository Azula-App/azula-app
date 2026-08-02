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
 * (fake network + fake terminal + chat loopback), a [SeededMessageStore] so the
 * sidebar launches pre-populated with demo chats instead of empty, and null
 * profile/peer stores otherwise (still no real persistence — a seeded chat
 * doesn't survive a restart, and no peer store means nothing gets re-dialed on
 * launch). Also wires in a real [InMemoryBlobStore], so the `-mock` apps can
 * actually send/receive media (MediaService refuses to send with no BlobStore
 * bound), and a [MockInvitationsStore] so ConnectService's stranger gate
 * recognizes FakeTransport's auto-surfaced demo terminals as known contacts
 * instead of closing them. The `-mock` platform apps call this and install it
 * as the process-scoped state before `AzulaApp()`, so the real transport is
 * never created.
 */
fun buildMockState(scope: CoroutineScope): AzulaState =
    createGraphFactory<AppGraph.Factory>().create(
        scope = scope,
        // demoTerminal: the -mock apps get the extra canned build terminal on top of
        // the interactive one. It stays off for the test suites, which share this
        // transport and shouldn't have to disambiguate a terminal they never asked for.
        transport = FakeTransport(demoTerminal = true),
        notifier = null,
        messageStore = SeededMessageStore(),
        profileStore = null,
        settingsStore = null,
        peerStore = null,
        blobStore = InMemoryBlobStore(),
        invitationsStore = MockInvitationsStore(),
        mediaExporter = null,
        // No real per-device event log for the `-mock` apps -- FakeTransport's
        // endpointId isn't real key material anyway (see its kdoc), so there is no
        // consistent signing identity to back one with.
        eventLogStore = null,
        ed25519 = null,
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
