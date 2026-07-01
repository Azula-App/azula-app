package dev.azula.notify

/**
 * Surfaces an incoming message as a platform notification when the app is in the
 * background. Injected into the Metro graph by the platform (Android posts a
 * system notification; iOS/desktop bind null and nothing is posted).
 */
interface Notifier {
    /** Post a notification for a message that arrived in [conversationId]. Tapping
     *  it should open that conversation. */
    fun postMessage(conversationId: String, title: String, body: String)
}
