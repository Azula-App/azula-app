package app.azula

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.azula.AzulaApp
import dev.azula.link.handleDeepLink
import dev.azula.net.MAX_MEDIA_BYTES
import dev.azula.state.AppStateHost
import dev.azula.state.AzulaState
import dev.azula.state.openConversationFromNotification
import dev.azula.state.openInvitesFromNotification
import dev.azula.ui.PickedFile
import dev.azula.ui.resolvePickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge-to-edge (under the status/navigation bars and any camera
        // cutout); the Compose layer applies the safe-area insets. Light bar icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // The endpoint is owned by the Application (process-scoped) so it survives
        // backgrounding; rememberAzulaState returns this singleton. The
        // ProcessLifecycleOwner observer + foreground service live in
        // AzulaApplication — we no longer pause()/shutdown() on background.
        (application as AzulaApplication).obtainState()

        requestNotificationPermission()

        setContent { AzulaApp() }

        // A deeplink, notification tap and/or OS share that launched us -- only on a new
        // launch, so each intent is handled once. Recreated after a config change (rotation)
        // or process death, this activity gets a saved state and a getIntent() it already
        // handled: the last one onNewIntent set, or the task's root intent. A new intent
        // never arrives here then -- Android delivers it to onNewIntent right after this
        // onCreate, even when the process was killed -- so gating on the saved state drops
        // no real taps, and no consumed-flag bookkeeping is needed. Reopening from recents
        // after the activity was destroyed (force-stop, reboot) re-sends the task's root
        // intent with no saved state, but marks it FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.
        val launchedFromHistory =
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            intent?.dataString?.let { handleDeepLink(it) }
            openConversationFromIntent(intent)
            handleShareIntent(intent)
        }
    }

    // A deeplink / notification tap / OS share for the existing activity (singleTask) --
    // including one that restarts a killed process, which reaches here, not onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { handleDeepLink(it) }
        openConversationFromIntent(intent)
        handleShareIntent(intent)
    }

    /** Open the conversation (or the invites inbox) referenced by a tapped
     *  notification, if any. See [InviteService][dev.azula.state.InviteService]'s
     *  "invites" sentinel conversation id for batched connection-request notifications.
     *  Goes through the same entry points as the iOS app delegate rather than calling
     *  [AppStateHost.shared] directly: they navigate both the narrow and the wide
     *  (sidebar) layout, which a phone held sideways already reaches, and a message tap
     *  that cold-started the process waits in `ConversationTapBus` for
     *  [AzulaState.start] -- this runs before the composition starts the state, when
     *  the conversation isn't restored yet and couldn't be redialed or marked read. */
    private fun openConversationFromIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(AndroidNotifier.EXTRA_CONVERSATION_ID) ?: return
        if (convId == "invites") openInvitesFromNotification()
        else openConversationFromNotification(convId)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * The OS "Share into Azula" entry point: `ACTION_SEND`/`ACTION_SEND_MULTIPLE`
     * from another app's share sheet (see the intent-filter in AndroidManifest.xml).
     * Resolves each shared `content://` Uri (`EXTRA_STREAM`) into a [PickedFile] off
     * the main thread, or stages a plain-text share (`EXTRA_TEXT`) directly, then
     * hands the result to [AzulaState.stageShare]/[AzulaState.stageShareText] --
     * [dev.azula.ui.SharePickerOverlay] then prompts for a destination conversation.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = singleStreamUri(intent)
                if (uri != null) {
                    resolveAndStageShare(listOf(uri))
                } else {
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { text ->
                        lifecycleScope.launch { deliverToState { it.stageShareText(text) } }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = multipleStreamUris(intent)
                if (uris.isNotEmpty()) resolveAndStageShare(uris)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun singleStreamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun multipleStreamUris(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    /** Reads each Uri's bytes (whole-stream read, hence [Dispatchers.IO]) and stages
     *  the result; oversized/unreadable items are dropped with a toast rather than
     *  failing the whole share. */
    private fun resolveAndStageShare(uris: List<Uri>) {
        lifecycleScope.launch {
            val items: List<PickedFile> = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val picked = resolvePickedFile(this@MainActivity, uri) ?: return@mapNotNull null
                    if (picked.bytes.size.toLong() > MAX_MEDIA_BYTES) null else picked
                }
            }
            if (items.size < uris.size) {
                Toast.makeText(
                    this@MainActivity,
                    "Some shared items were skipped (too large or unreadable)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            if (items.isNotEmpty()) deliverToState { it.stageShare(items) }
        }
    }

    /**
     * [AppStateHost.shared] is installed synchronously by `obtainState()` before
     * this activity handles any intent, so this should always resolve on the first
     * try -- but a share delivered from a process the OS is still cold-starting
     * could theoretically race it. Retry briefly (mirroring
     * [dev.azula.link.DeepLinkBus]'s buffer-if-not-ready pattern) rather than
     * silently dropping the share.
     */
    private suspend fun deliverToState(action: (AzulaState) -> Unit) {
        repeat(40) {
            AppStateHost.shared?.let { action(it); return }
            delay(50)
        }
    }
}
