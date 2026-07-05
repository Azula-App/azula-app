package app.azula

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.azula.notify.Notifier
import dev.azula.notify.connectionRequestBody

/**
 * Posts a system notification for a message that arrived while the app was
 * backgrounded. Tapping it opens the relevant conversation. Injected into
 * [dev.azula.notify.NotifierConfig] by [AzulaApplication].
 */
class AndroidNotifier(private val context: Context) : Notifier {
    override fun postMessage(conversationId: String, title: String, body: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(mgr, CHANNEL_ID, "Messages", "Incoming azula messages")

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val pending = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            // Same azula monochrome status icon as the background "connected"
            // notification (IrohService), so both read as azula in the status bar.
            .setSmallIcon(R.drawable.ic_stat_azula)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()

        mgr.notify(conversationId.hashCode(), n)
    }

    override fun postConnectionRequest(count: Int, peerCode: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(mgr, INVITES_CHANNEL_ID, "Connection requests", "Strangers asking to connect")

        // Sentinel conversation id "invites" -- MainActivity.openConversationFromIntent
        // already routes this to AppStateHost.openInvitesMobile(), no MainActivity change needed.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, "invites")
        }
        val pending = PendingIntent.getActivity(
            context,
            INVITES_NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(context, INVITES_CHANNEL_ID)
            .setContentTitle("Connection requests")
            .setContentText(connectionRequestBody(count, peerCode))
            .setSmallIcon(R.drawable.ic_stat_azula)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()

        mgr.notify(INVITES_NOTIFICATION_ID, n)
    }

    private fun ensureChannel(mgr: NotificationManager, id: String, name: String, desc: String) {
        if (mgr.getNotificationChannel(id) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = desc }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "azula_messages"
        const val INVITES_CHANNEL_ID = "azula_invites"
        const val EXTRA_CONVERSATION_ID = "conversationId"
        // Stable id (distinct from any conversationId.hashCode()) so repeated
        // connection requests update a single notification rather than stacking.
        val INVITES_NOTIFICATION_ID = "invites".hashCode()
    }
}
