package app.azula

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.core.view.WindowCompat
import dev.azula.AzulaApp
import dev.azula.link.handleDeepLink
import dev.azula.net.AndroidPeerStore
import dev.azula.net.IrohConfig
import dev.azula.net.PeerStoreConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw edge-to-edge (under the status/navigation bars and any camera
        // cutout); the Compose layer applies the safe-area insets to content so
        // the full-bleed gradient shows through. Light bar icons suit the dark bg.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // Persist the iroh secret key so this device keeps a stable node id (and
        // therefore a stable "your code") across restarts — important because
        // Android aggressively kills backgrounded apps.
        IrohConfig.keyStore = AndroidSecretKeyStore(applicationContext)

        // Persist paired peer tickets so we auto-reconnect on launch.
        PeerStoreConfig.store = AndroidPeerStore(applicationContext)

        // Treat app foreground/background as iroh lifecycle events: shut the
        // endpoint down cleanly when backgrounded, re-bind when we return.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { IrohConfig.activeTransport?.pause() }
            override fun onStart(owner: LifecycleOwner) { IrohConfig.activeTransport?.resume() }
        })

        setContent { AzulaApp() }

        // A deeplink that launched us (cold start).
        intent?.dataString?.let { handleDeepLink(it) }
    }

    // A deeplink that arrived while we were already running (singleTask).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { handleDeepLink(it) }
    }
}
