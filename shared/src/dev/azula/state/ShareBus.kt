package dev.azula.state

import dev.azula.ui.PickedFile

/**
 * Delivers a drained OS share to whoever is listening -- the iOS equivalent of
 * [dev.azula.link.DeepLinkBus]/[InviteBus] (there's no [AppStateHost] on iOS to
 * reach [AzulaState] from outside the composition). Driven by
 * `dev.azula.state.drainShareInbox` (`ShareInbox.kt`, `src@ios`), which the app
 * delegate calls whenever the scene becomes active (see
 * `ios-app/src/iosApp.swift`) to read whatever the `AzulaShare` extension left
 * in the App Group inbox. Buffers one delivery if the handler isn't registered
 * yet (e.g. the app cold-launching, racing [AzulaState]'s construction).
 * Registered in [AzulaState]'s `init` (mirroring where [InviteBus] is
 * registered), and harmless to register on Android too (Android delivers
 * shares directly via [AppStateHost] instead -- see `MainActivity.stageShare` --
 * so nothing ever calls [deliver] there).
 */
object ShareBus {
    private var handler: ((List<PickedFile>) -> Unit)? = null
    private var pending: List<PickedFile>? = null

    fun setHandler(h: (List<PickedFile>) -> Unit) {
        handler = h
        pending?.let { p -> pending = null; h(p) }
    }

    fun deliver(items: List<PickedFile>) {
        if (items.isEmpty()) return
        val h = handler
        if (h != null) h(items) else pending = items
    }
}
