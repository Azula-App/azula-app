package app.azula

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.azula.net.IrohConfig

/**
 * Foreground service that keeps the process — and thus the iroh node owned by
 * [AzulaApplication] — alive while the app is backgrounded, so messages arrive in
 * real time. Shows the persistent notification Android requires (with a Stop
 * action to turn background listening off). It holds no transport reference; the
 * node lives on the application scope.
 */
class IrohService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The user tapped "Stop" on the notification: shut the node down so it
        // stops listening in the background, drop the service + its notification,
        // and don't auto-restart. Reopening the app rebinds (AzulaApplication.onStart).
        if (intent?.action == ACTION_STOP) {
            runCatching { IrohConfig.activeTransport?.pause() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Guarantee the node exists even on a headless START_STICKY restart.
        runCatching { (application as AzulaApplication).obtainState(false) }

        ensureChannel()
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, IrohService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("azula — connected")
            .setContentText("Receiving messages over iroh")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Keeps azula connected in the background" }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "azula_connection"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "app.azula.action.STOP_LISTENING"
    }
}
