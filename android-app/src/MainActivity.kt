package app.azula

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import dev.azula.AzulaApp
import dev.azula.link.handleDeepLink
import dev.azula.state.AppStateHost

class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge-to-edge (under the status/navigation bars and any camera
        // cutout); the Compose layer applies the safe-area insets. Light bar icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // Demo/UI-test build: `adb shell am start --ez demo true …` enables seeds.
        val isDemo = intent?.getBooleanExtra("demo", false) ?: false

        // The node is owned by the Application (process-scoped) so it survives
        // backgrounding; rememberAzulaState returns this singleton. The
        // ProcessLifecycleOwner observer + foreground service live in
        // AzulaApplication — we no longer pause()/shutdown() on background.
        (application as AzulaApplication).obtainState(isDemo)

        requestNotificationPermission()

        setContent { AzulaApp(isDemo) }

        // A deeplink (cold start) and/or a notification tap that launched us.
        intent?.dataString?.let { handleDeepLink(it) }
        openConversationFromIntent(intent)
    }

    // A deeplink / notification tap that arrived while we were already running (singleTask).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { handleDeepLink(it) }
        openConversationFromIntent(intent)
    }

    /** Open the conversation referenced by a tapped message notification, if any. */
    private fun openConversationFromIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(AndroidNotifier.EXTRA_CONVERSATION_ID) ?: return
        AppStateHost.shared?.openChatMobile(convId)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
