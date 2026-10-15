package dev.azula.state

/**
 * Delivers "open this conversation" from a tapped message notification to whoever is
 * listening. Driven through [openConversationFromNotification] by the iOS app delegate's
 * `UNUserNotificationCenterDelegate` (`ios-app/src/iosApp.swift`; there's no [AppStateHost]
 * on iOS to reach [AzulaState] from outside the composition) and by Android's
 * `MainActivity.openConversationFromIntent`, which has [AppStateHost] but needs the same
 * wait for [AzulaState.start] described below.
 *
 * Registered at the end of [AzulaState.start], not in its `init` (mirroring where
 * [dev.azula.link.DeepLinkBus] is registered in [ConnectService.start]): opening a
 * conversation looks it up, redials it and marks it read, which needs the restored history,
 * the bound transport and the event log. Buffers one tap until then, which is the cold-launch
 * case: the OS can kill a backgrounded azula, and tapping one of its notifications afterwards
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

/** Entry point for a tapped message notification (mirrors [openInvitesFromNotification]):
 *  called by the iOS app delegate's `UNUserNotificationCenterDelegate`
 *  (`ios-app/src/iosApp.swift`) and by Android's `MainActivity.openConversationFromIntent`. */
fun openConversationFromNotification(conversationId: String) = ConversationTapBus.deliver(conversationId)
