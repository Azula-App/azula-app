package dev.azula.di

import dev.azula.core.AppScope
import dev.azula.core.Ed25519
import dev.azula.net.IrohTransport
import dev.azula.net.PeerStore
import dev.azula.notify.Notifier
import dev.azula.persist.BlobStore
import dev.azula.persist.InvitationsStore
import dev.azula.persist.MediaExporter
import dev.azula.persist.MessageStore
import dev.azula.persist.ProfileStore
import dev.azula.persist.SettingsStore
import dev.azula.state.AzulaState
import dev.azula.sync.EventLogStore
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
            @Provides settingsStore: SettingsStore?,
            @Provides peerStore: PeerStore?,
            @Provides blobStore: BlobStore?,
            @Provides invitationsStore: InvitationsStore?,
            @Provides mediaExporter: MediaExporter?,
            // multi-device-identity tasks 4.4/4.5: the per-device append-only event
            // log (task 4.1 registered the sync-api/sync-real modules but stopped
            // short of wiring EventLogStore into this graph -- see AccountSyncService).
            // Both null on the `-mock` apps and in most tests, same nullable-optional
            // pattern as every other platform store above. The concrete instance
            // (built by the caller via `platformEventLogStore`/`AndroidEventLogStore`)
            // is root-pk-namespaced per task 4.6 -- see those functions' kdoc for why
            // that namespacing is resolved lazily rather than passed in here already
            // known.
            @Provides eventLogStore: EventLogStore?,
            @Provides ed25519: Ed25519?,
        ): AppGraph
    }
}
