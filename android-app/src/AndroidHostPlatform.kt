package app.azula

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import dev.azula.host.HostPlatform
import dev.azula.selfgrant.SelfGrantSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android's [HostPlatform]: Settings deep links, the notification permission (through the
 * activity, which owns the launcher), share/open-url, the two setup flags, and the labs switch.
 *
 * **Labs:** the host-compute entry (S0) shows in a debuggable build, or after seven taps on the
 * "profiles & settings" title in Settings; see [enableLabs].
 */
class AndroidHostPlatform(context: Context) : HostPlatform {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("host_compute", Context.MODE_PRIVATE)

    /** Set by `MainActivity` while it exists: launches the system POST_NOTIFICATIONS dialog. */
    @Volatile var requestNotificationsHook: (() -> Unit)? = null

    override fun entryVisible(): Boolean =
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 || prefs.getBoolean(KEY_LABS, false)

    override fun enableLabs() { prefs.edit().putBoolean(KEY_LABS, true).apply() }

    override fun developerOptionsEnabled(): Boolean = SelfGrantSettings.developerOptionsEnabled(app)

    override fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun requestNotifications() { requestNotificationsHook?.invoke() }

    override fun openAboutPhone() { launch(SelfGrantSettings.openAboutPhone()) }

    override fun openWirelessDebugging() {
        // Developer options with the Wireless debugging switch highlighted; plain Settings if that's missing.
        if (!launch(SelfGrantSettings.openWirelessDebugging())) launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openUrl(url: String) { launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun localTime(epochSeconds: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))

    override var setupCommitted: Boolean
        get() = prefs.getBoolean(KEY_COMMITTED, false)
        set(v) { prefs.edit().putBoolean(KEY_COMMITTED, v).apply() }

    override var setupDone: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(v) { prefs.edit().putBoolean(KEY_DONE, v).apply() }

    private fun launch(intent: Intent): Boolean = try {
        app.startActivity(intent); true
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val KEY_LABS = "labs"
        const val KEY_COMMITTED = "setup_committed"
        const val KEY_DONE = "setup_done"
    }
}
