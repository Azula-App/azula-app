package dev.azula.state

/**
 * Delivers "open this conversation" from a tapped message notification to whoever is
 * listening -- the iOS equivalent of `MainActivity.openConversationFromIntent` on Android
 * (there's no [AppStateHost] on iOS to reach [AzulaState] from outside the composition, the
 * same reason [InviteBus] exists for the "Connection requests" notification). Driven by the
 * iOS app delegate's `UNUserNotificationCenterDelegate` through
 * [openConversationFromNotification] -- see `ios-app/src/iosApp.swift`.
 *
 * Registered at the end of [AzulaState.start], not in its `init` (mirroring where
 * [dev.azula.link.DeepLinkBus] is registered in [ConnectService.start]): opening a
 * conversation looks it up, redials it and marks it read, which needs the restored history,
 * the bound transport and the event log. Buffers one tap until then, which is the cold-launch
 * case: iOS can terminate a suspended azula, and tapping one of its notifications afterwards
 * launches it fresh.
 */
object ConversationTapBus {
    private var handler: ((String) -> Unit)? = null
    private var pending: String? = null

    fun setHandler(h: (String) -> Unit) {
        handler = h
        pending?.let { p -> pending = null; h(p) }
    }

    fun deliver(conversationId: String) {
        val h = handler
        if (h != null) h(conversationId) else pending = conversationId
    }
}

/** Entry point for the iOS Swift layer (mirrors [openInvitesFromNotification]): called by the
 *  app delegate's `UNUserNotificationCenterDelegate` when the user taps a message
 *  notification -- see `ios-app/src/iosApp.swift`. */
fun openConversationFromNotification(conversationId: String) = ConversationTapBus.deliver(conversationId)
