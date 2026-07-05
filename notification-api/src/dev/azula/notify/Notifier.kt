package dev.azula.notify

/**
 * Surfaces an incoming message or connection request as a platform notification
 * when the app is in the background. Injected into the Metro graph by the
 * platform (Android posts a system notification; iOS posts a local
 * UNUserNotificationCenter notification; desktop binds null and nothing is
 * posted).
 */
interface Notifier {
    /** Post a notification for a message that arrived in [conversationId]. Tapping
     *  it should open that conversation. */
    fun postMessage(conversationId: String, title: String, body: String)

    /** Post a notification that a stranger wants to connect: [count] pending
     *  request(s), the most recently-received one identified by [peerCode].
     *  Tapping it should open the invites screen. See [connectionRequestBody]
     *  for the singular/plural body text every implementation should use. */
    fun postConnectionRequest(count: Int, peerCode: String)
}

/** Shared singular/plural body text for [Notifier.postConnectionRequest]:
 *  "abc-def wants to connect" when [count] is 1, else "3 connection requests". */
fun connectionRequestBody(count: Int, peerCode: String): String =
    if (count == 1) "$peerCode wants to connect" else "$count connection requests"
