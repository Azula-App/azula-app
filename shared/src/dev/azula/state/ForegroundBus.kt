package dev.azula.state

/**
 * Delivers the app's foreground/background state to whoever is listening -- the iOS
 * equivalent of `AzulaApplication`'s `AppStateHost.shared?.setForeground(...)` on Android
 * (there's no [AppStateHost] on iOS to reach [AzulaState] from outside the composition, the
 * same reason [InviteBus] and [ShareBus] exist). Driven by the `scenePhase` observer in
 * `ios-app/src/iosApp.swift` through [setAppForeground]. Registered in [AzulaState]'s
 * `init` (mirroring [InviteBus]/[ShareBus]), and harmless to register on Android too
 * (nothing calls [deliver] there).
 *
 * Buffers only the latest value if the handler isn't registered yet: foreground is a level,
 * not an event, so transitions that land before [AzulaState] exists collapse to whatever
 * state the app is in once it does. A buffered `true` reaching a fresh state is a
 * true→true no-op in [AzulaState.setForeground], so a cold launch never counts as a resume.
 */
object ForegroundBus {
    private var handler: ((Boolean) -> Unit)? = null
    private var pending: Boolean? = null

    fun setHandler(h: (Boolean) -> Unit) {
        handler = h
        pending?.let { p -> pending = null; h(p) }
    }

    fun deliver(foreground: Boolean) {
        val h = handler
        if (h != null) h(foreground) else pending = foreground
    }
}

/** Entry point for the iOS Swift layer (mirrors [dev.azula.link.handleDeepLink]): called
 *  from the `scenePhase` observer in `ios-app/src/iosApp.swift`, which decides which scene
 *  phases count as foreground. */
fun setAppForeground(foreground: Boolean) = ForegroundBus.deliver(foreground)
