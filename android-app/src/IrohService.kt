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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.azula.net.IrohConfig

/**
 * Keeps the process — and thus the iroh node owned by [AzulaApplication] — alive
 * while the app is backgrounded, so messages arrive in real time.
 *
 * The persistent "connected" notification only makes sense in the background:
 * while the app is in the foreground the Activity already keeps the process
 * alive, so the service runs as an ordinary started service with **no**
 * notification. When the app goes to the background it promotes itself to a
 * foreground service (Android's requirement for surviving there), showing the
 * notification; on return to the foreground it drops back down and removes it.
 *
 * The service is started with plain `startService` while the app is visible, so
 * the OS lets it promote to a foreground service later (the background-start
 * restriction targets launching a service from the background, not an
 * already-running one promoting itself).
 */
class IrohService : Service() {
    private var observer: DefaultLifecycleObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val obs = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = hideNotification() // app → foreground
            override fun onStop(owner: LifecycleOwner) = showNotification()  // app → background
        }
        observer = obs
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
    }

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
        runCatching { (application as AzulaApplication).obtainState() }

        // Reflect the app's current state: hidden in the foreground, shown in the
        // background. This also covers a START_STICKY restart while backgrounded
        // (the lifecycle observer won't re-fire onStop for an already-stopped app).
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            hideNotification()
        } else {
            showNotification()
        }
        return START_STICKY
    }

    /** Promote to a foreground service with the persistent notification (background). */
    private fun showNotification() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, IrohService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("azula — connected")
            .setContentText("Receiving messages over iroh")
            .setSmallIcon(R.drawable.ic_stat_azula)
            .setColor(0xFFFF2D9B.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        // Guarded: on some OEM/Android-12+ states startForeground can be refused;
        // failing to show the notification is preferable to crashing.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
    }

    /** Drop back to an ordinary started service and remove the notification (foreground). */
    private fun hideNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        observer?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        super.onDestroy()
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
