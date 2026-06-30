package app.azula

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.azula.notify.Notifier

/**
 * Posts a system notification for a message that arrived while the app was
 * backgrounded. Tapping it opens the relevant conversation. Injected into
 * [dev.azula.notify.NotifierConfig] by [AzulaApplication].
 */
class AndroidNotifier(private val context: Context) : Notifier {
    override fun postMessage(conversationId: String, title: String, body: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(mgr)

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
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()

        mgr.notify(conversationId.hashCode(), n)
    }

    private fun ensureChannel(mgr: NotificationManager) {
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Incoming azula messages" }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "azula_messages"
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}
