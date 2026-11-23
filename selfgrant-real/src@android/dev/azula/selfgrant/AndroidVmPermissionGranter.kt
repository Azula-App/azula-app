package dev.azula.selfgrant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.StateFlow

/** The per-process pieces shared by the service, the reply receiver and the app-facing granter. */
internal class SelfGrantRuntime private constructor(context: Context) {
    val notifier = SelfGrantNotifier(context)
    val runner = SelfGrantRunner(context, SelfGrantStore(context), notifier)

    companion object {
        @Volatile private var instance: SelfGrantRuntime? = null

        /**
         * Process-wide on purpose: a notification reply can start a fresh process, and
         * the receiver and service have no graph to be injected from (same reasoning
         * as AppStateHost.shared).
         */
        fun get(context: Context): SelfGrantRuntime =
            instance ?: synchronized(this) { instance ?: SelfGrantRuntime(context.applicationContext).also { instance = it } }
    }
}

/**
 * Grants the app its own VM permissions through Wireless debugging. Hosts must
 * declare [SelfGrantService] and [PairingReplyReceiver] in their manifest, the two
 * permissions as `uses-permission`, and ask for POST_NOTIFICATIONS before [start].
 */
class AndroidVmPermissionGranter(private val context: Context) : VmPermissionGranter {
    private val runtime get() = SelfGrantRuntime.get(context)

    override val state: StateFlow<GrantState> get() = runtime.runner.state

    override fun refresh() = runtime.runner.refresh()

    override fun start() {
        if (Build.VERSION.SDK_INT < 34) {
            runtime.runner.refresh()
            return
        }
        if (runtime.runner.missing().isEmpty()) { runtime.runner.refresh(); return }
        val notificationsOk = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!notificationsOk) { runtime.runner.notificationsBlocked(); return }
        SelfGrantService.start(context)
    }

    override fun cancel() {
        runtime.runner.cancel()
        runtime.notifier.clear()
    }
}

/** Settings state and deep links for the checklist steps (design D9, S2). */
object SelfGrantSettings {
    fun developerOptionsEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    fun wirelessDebuggingEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) != 0

    /** About phone, where Build number lives (tap it seven times). */
    fun openAboutPhone() = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Developer options, scrolled to the Wireless debugging switch where the OS supports it. */
    fun openWirelessDebugging() = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
