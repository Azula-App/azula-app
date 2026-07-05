package dev.azula.notify

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Posts local notifications via `UNUserNotificationCenter`. iOS has no
 * background transport for this app, so -- unlike Android -- this only covers
 * messages/requests that arrive while the process is alive (foreground or
 * suspended-but-resident); still useful for the common "app running,
 * backgrounded" case. Authorization is requested lazily on first post rather
 * than at launch, so a user who never gets a notification is never prompted.
 *
 * The identifier used for the connection-request notification
 * ([INVITES_NOTIFICATION_IDENTIFIER]) is stable, so repeated requests update a
 * single delivered notification instead of stacking -- mirrors
 * `AndroidNotifier.INVITES_NOTIFICATION_ID`. The iOS app delegate's
 * `UNUserNotificationCenterDelegate` (see `ios-app/src/iosApp.swift`) checks
 * this identifier on tap to route into `InviteBus.openInvites()`, the same way
 * `MainActivity.openConversationFromIntent` checks the "invites" sentinel on
 * Android.
 */
class IosNotifier : Notifier {
    private var authorizationRequested = false

    override fun postMessage(conversationId: String, title: String, body: String) {
        post(identifier = "message:$conversationId", title = title, body = body)
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
    }
}
