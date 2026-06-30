package dev.azula.notify

import kotlin.concurrent.Volatile

/**
 * Surfaces an incoming message as a platform notification when the app is in the
 * background. Injected by the platform (Android posts a system notification);
 * iOS/desktop leave it null and nothing is posted. Mirrors the
 * [dev.azula.persist.MessageStore] / [dev.azula.net.PeerStore] config pattern.
 */
interface Notifier {
    /** Post a notification for a message that arrived in [conversationId]. Tapping
     *  it should open that conversation. */
    fun postMessage(conversationId: String, title: String, body: String)
}

object NotifierConfig {
    @Volatile
    var notifier: Notifier? = null
}
