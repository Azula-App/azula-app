package dev.azula.selfgrant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/**
 * The notifications of the grant flow. One ongoing notification (id [ONGOING_ID])
 * doubles as the foreground-service notification and carries the pairing-code
 * reply field, so the user can type the code while Settings is on screen. The
 * terminal "done" / failure notifications are separate and dismissible. Copy
 * follows the azula voice: lowercase, `·` separators.
 */
internal class SelfGrantNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "vm permission setup", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "the one-time pairing code prompt for hosting compute on this phone"
                setShowBadge(false)
            },
        )
    }

    private fun builder(): Notification.Builder =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(Icon.createWithResource(context, android.R.drawable.ic_dialog_info))
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp())

    private fun openApp(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The notification the service starts in the foreground with. */
    fun starting(): Notification = builder()
        .setContentTitle("azula · vm permission setup")
        .setContentText("starting…")
        .setOngoing(true)
        .build()

    fun waiting(service: Service, retry: Boolean) = post(
        builder()
            .setContentTitle(
                if (service == Service.Pairing) "turn on wireless debugging" else "connecting to wireless debugging",
            )
            .setContentText(
                if (service == Service.Pairing) "then tap pair device with pairing code" else "just a moment",
            )
            .setOngoing(true)
            .build(),
    )

    fun codePrompt(retry: Boolean) {
        val reply = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PairingReplyReceiver::class.java).setAction(ACTION_REPLY).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_send), "enter code", reply,
        ).addRemoteInput(input).setAllowGeneratedReplies(false).build()
        post(
            builder()
                .setContentTitle(if (retry) "that code didn't work · try again" else "enter the 6-digit pairing code")
                .setContentText("shown in the wireless debugging pairing dialog")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(false)
                .addAction(action)
                .build(),
        )
    }

    fun working(text: String) = post(
        builder().setContentTitle("azula · vm permission setup").setContentText(text).setOngoing(true).build(),
    )

    fun done() {
        nm.cancel(ONGOING_ID)
        nm.notify(
            RESULT_ID,
            builder()
                .setContentTitle("done — back to azula")
                .setContentText("you can turn wireless debugging and developer options off now")
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(false)
                .build(),
        )
    }

    fun failure(error: GrantError) {
        nm.cancel(ONGOING_ID)
        nm.notify(
            RESULT_ID,
            builder()
                .setContentTitle("vm permission setup stopped")
                .setContentText(
                    when (error) {
                        GrantError.DiscoveryTimeout -> "wireless debugging never showed up · open azula to try again"
                        GrantError.NotificationsBlocked -> "allow notifications so azula can ask for the code"
                        else -> "something went wrong · open azula to try again"
                    },
                )
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clear() {
        nm.cancel(ONGOING_ID)
    }

    private fun post(n: Notification) = nm.notify(ONGOING_ID, n)

    companion object {
        const val CHANNEL = "selfgrant"
        // The host feature's HostService (vmhost-real) owns 7301 and re-posts it on
        // every download-progress tick; sharing that id overwrote this code prompt
        // mid-setup. Keep these distinct from it and from IrohService's 1.
        const val ONGOING_ID = 7311
        const val RESULT_ID = 7312
        const val KEY_CODE = "pairing_code"
        const val ACTION_REPLY = "dev.azula.selfgrant.REPLY_CODE"
    }
}
