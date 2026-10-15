package dev.azula.notify

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Posts local notifications via `UNUserNotificationCenter`. Called only while
 * `AzulaState.inForeground` is false, which iOS drives from the `scenePhase`
 * observer in `ios-app/src/iosApp.swift` (via `ForegroundBus`). iOS has no
 * background transport for this app and it declares no background modes, so --
 * unlike Android -- this only covers messages/requests processed while the
 * backgrounded process is still running, which is a narrow window: iOS
 * suspends it almost as soon as it leaves the screen (~0.2 s on the
 * simulator), a suspended process receives nothing, and whatever was sent
 * meanwhile arrives on resume with the app back in the foreground. A post made
 * at the `.background` transition itself is still a foreground presentation to
 * iOS, which the app delegate doesn't opt into (no `willPresent`), so it is
 * dropped. Authorization is requested lazily on first post rather than at
 * launch, so a user who never gets a notification is never prompted; because
 * that first post happens in the background, iOS shows the prompt over
 * whichever app is frontmost and holds the notification until it's answered
 * (both observed on the simulator).
 *
 * The identifier used for the connection-request notification
 * ([INVITES_NOTIFICATION_IDENTIFIER]) is stable, so repeated requests update a
 * single delivered notification instead of stacking -- mirrors
 * `AndroidNotifier.INVITES_NOTIFICATION_ID`. The iOS app delegate's
 * `UNUserNotificationCenterDelegate` (see `ios-app/src/iosApp.swift`) checks
 * this identifier on tap to route into `InviteBus.openInvites()`, the same way
 * `MainActivity.openConversationFromIntent` checks the "invites" sentinel on
 * Android. A tapped message notification ([messageIdentifier]) is routed the same
 * way into `ConversationTapBus`, which opens its conversation.
 */
class IosNotifier : Notifier {
    private var authorizationRequested = false

    override fun postMessage(conversationId: String, title: String, body: String) {
        post(identifier = messageIdentifier(conversationId), title = title, body = body)
    }

    override fun postConnectionRequest(count: Int, peerCode: String) {
        post(
            identifier = INVITES_NOTIFICATION_IDENTIFIER,
            title = "Connection requests",
            body = connectionRequestBody(count, peerCode),
        )
    }

    private fun post(identifier: String, title: String, body: String) {
        ensureAuthorization()
        // UNNotificationContent (the base class) declares title/body/sound
        // readonly, so cinterop exposes them as `val` even on the mutable
        // subclass -- go through the synthesized ObjC setter methods instead
        // of property-assignment syntax.
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        content.setSound(UNNotificationSound.defaultSound)
        // trigger = null delivers as soon as possible (Apple's documented
        // meaning for a nil trigger) -- the "immediate" local notification.
        val request = UNNotificationRequest.requestWithIdentifier(identifier, content, null)
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request, withCompletionHandler = null)
    }

    private fun ensureAuthorization() {
        if (authorizationRequested) return
        authorizationRequested = true
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
            completionHandler = { _, _ -> },
        )
    }

    companion object {
        const val INVITES_NOTIFICATION_IDENTIFIER = "invites"

        /** A message notification's identifier is this prefix plus its conversation id:
         *  one per conversation, so a newer message replaces the older banner instead of
         *  stacking. [conversationIdFor] reverses it for the tap handler in
         *  `ios-app/src/iosApp.swift`. */
        const val MESSAGE_IDENTIFIER_PREFIX = "message:"

        fun messageIdentifier(conversationId: String): String = MESSAGE_IDENTIFIER_PREFIX + conversationId

        /** The conversation a tapped notification's request [identifier] names, or null if
         *  it isn't a message notification. */
        fun conversationIdFor(identifier: String): String? =
            if (identifier.startsWith(MESSAGE_IDENTIFIER_PREFIX)) {
                identifier.removePrefix(MESSAGE_IDENTIFIER_PREFIX).ifEmpty { null }
            } else {
                null
            }
    }
}
