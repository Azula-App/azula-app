package dev.azula.selfgrant

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive and the code-prompt notification up while the user is
 * in Settings. Sticky: if the process dies mid-flow the system restarts the
 * service with a null intent and the flow resumes from its persisted phase.
 */
class SelfGrantService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = SelfGrantRuntime.get(this)
        val starting: Notification = runtime.notifier.starting()
        startForeground(SelfGrantNotifier.ONGOING_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Log.d("SelfGrant", "service command action=${intent?.action}")
        runtime.runner.onStateChanged = { s ->
            if (s.phase == GrantPhase.Granted || s.phase == GrantPhase.Failed || s.phase == GrantPhase.Idle) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                runtime.runner.onStateChanged = null
                stopSelf()
            }
        }
        when (intent?.action) {
            ACTION_CODE -> {
                runtime.runner.startOrResume() // after process death, resume first so the machine is live
                intent.getStringExtra(EXTRA_CODE)?.let(runtime.runner::submitCode)
            }
            ACTION_CANCEL -> runtime.runner.cancel()
            else -> runtime.runner.startOrResume()
        }
        return START_STICKY
    }

    companion object {
        const val ACTION_START = "dev.azula.selfgrant.START"
        const val ACTION_CODE = "dev.azula.selfgrant.CODE"
        const val ACTION_CANCEL = "dev.azula.selfgrant.CANCEL"
        const val EXTRA_CODE = "code"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SelfGrantService::class.java).setAction(ACTION_START))
        }
    }
}
