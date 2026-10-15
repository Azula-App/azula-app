package dev.azula.state

/**
 * Delivers "open the invites screen" to whoever is listening -- the iOS
 * equivalent of [dev.azula.link.DeepLinkBus] (there's no [AppStateHost] on
 * iOS to reach [AzulaState] from outside the composition). Buffers one request
 * if the handler isn't registered yet (e.g. a notification tap that launches
 * the app cold, racing the composition wiring up [AzulaState]).
 *
 * Driven by the iOS app delegate's `UNUserNotificationCenterDelegate` when the
 * user taps the "Connection requests" local notification posted by
 * `IosNotifier` -- see `ios-app/src/iosApp.swift` -- and by Android's
 * `MainActivity.openConversationFromIntent` for the same notification, so both
 * get [AzulaState]'s handler, which opens the inbox in either layout. Registered
 * in [AzulaState]'s `init` (mirroring where [dev.azula.link.DeepLinkBus] is
 * registered in [ConnectService.start]).
 */
object InviteBus {
    private var handler: (() -> Unit)? = null
    private var pending = false

    fun setHandler(h: () -> Unit) {
        handler = h
        if (pending) { pending = false; h() }
    }

    fun openInvites() {
        val h = handler
        if (h != null) h() else pending = true
    }
}

/** Entry point for a tapped "Connection requests" notification (mirrors
 *  [dev.azula.link.handleDeepLink]): called by the iOS app delegate's
 *  `UNUserNotificationCenterDelegate` (`ios-app/src/iosApp.swift`) and by Android's
 *  `MainActivity.openConversationFromIntent`. */
fun openInvitesFromNotification() = InviteBus.openInvites()
