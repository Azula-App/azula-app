package dev.azula.mock

import dev.azula.di.AppGraph
import dev.azula.state.AzulaState
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Build an [AzulaState] wired to the fakes for the `-mock` apps: a [FakeTransport]
 * (fake network + fake terminal + chat loopback) and null stores (no persistence).
 * The `-mock` platform apps call this and install it as the process-scoped state
 * before `AzulaApp()`, so the real transport is never created.
 */
fun buildMockState(scope: CoroutineScope): AzulaState =
    createGraphFactory<AppGraph.Factory>().create(
        scope = scope,
        transport = FakeTransport(),
        notifier = null,
        messageStore = null,
        profileStore = null,
        peerStore = null,
    ).azulaState
