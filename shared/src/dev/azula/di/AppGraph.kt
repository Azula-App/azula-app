package dev.azula.di

import dev.azula.core.AppScope
import dev.azula.net.IrohTransport
import dev.azula.net.PeerStore
import dev.azula.notify.Notifier
import dev.azula.persist.MessageStore
import dev.azula.persist.ProfileStore
import dev.azula.state.AzulaState
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/**
 * The single, flat application dependency graph. Its [Factory] takes the
 * platform-provided collaborators (coroutine scope, the iroh transport, and the
 * persistence/notification impls) — the composition root that replaced the old
 * service-locator `*Config` globals. Accessors expose the roots the UI needs.
 *
 * The transport is still created by the platform (`createTransport()`) and passed
 * in for now; it moves into a contributed binding when the network module lands.
 */
@SingleIn(AppScope::class)
@DependencyGraph(scope = AppScope::class)
interface AppGraph {
    val azulaState: AzulaState

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides scope: CoroutineScope,
            @Provides transport: IrohTransport,
            @Provides notifier: Notifier?,
            @Provides messageStore: MessageStore?,
            @Provides profileStore: ProfileStore?,
            @Provides peerStore: PeerStore?,
        ): AppGraph
    }
}
