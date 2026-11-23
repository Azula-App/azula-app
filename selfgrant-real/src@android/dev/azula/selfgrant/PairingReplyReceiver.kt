package dev.azula.selfgrant

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Receives the pairing code typed into the notification's reply field. */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SelfGrantNotifier.KEY_CODE)?.toString() ?: return
        Log.d("SelfGrant", "reply received (${code.length} chars)")
        val app = context.applicationContext
        try {
            app.startForegroundService(
                Intent(app, SelfGrantService::class.java).setAction(SelfGrantService.ACTION_CODE)
                    .putExtra(SelfGrantService.EXTRA_CODE, code),
            )
        } catch (e: Exception) {
            // Background-start restriction: the service is normally already running
            // in the foreground; fall back to handing the code to the live runtime.
            Log.w("SelfGrant", "could not start service for reply", e)
            SelfGrantRuntime.get(app).runner.submitCode(code)
        }
    }
}
