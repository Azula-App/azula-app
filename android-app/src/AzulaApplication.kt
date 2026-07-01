package app.azula

import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.azula.iroh.IrohAndroid
import dev.azula.net.AndroidPeerStore
import dev.azula.net.IrohConfig
import dev.azula.net.PeerStoreConfig
import dev.azula.net.createTransport
import dev.azula.notify.NotifierConfig
import dev.azula.persist.AndroidMessageStore
import dev.azula.persist.AndroidProfileStore
import dev.azula.persist.MessageStoreConfig
import dev.azula.persist.ProfileStoreConfig
import dev.azula.state.AppStateHost
import dev.azula.state.AzulaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.app.Application

/**
 * Owns the iroh node for the whole process, so it keeps running while the
 * Activity is backgrounded or destroyed — the foreground [IrohService] keeps the
 * process alive. All the once-per-process iroh / persistence / notification
 * wiring lives here, before any Activity exists.
 */
class AzulaApplication : Application() {
    /** Process-lifetime scope that owns the node's coroutines (not the composition). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // iroh's DNS resolver reads the device's DNS servers through `ndk_context`,
        // which must be initialized with the JavaVM + app context before any
        // endpoint is bound. Once per process.
        IrohAndroid.installAndroidContext(applicationContext)

        // Persist the iroh key (stable node id), paired peers, conversation history,
        // and route incoming background messages to system notifications.
        IrohConfig.keyStore = AndroidSecretKeyStore(applicationContext)
        PeerStoreConfig.store = AndroidPeerStore(applicationContext)
        MessageStoreConfig.store = AndroidMessageStore(applicationContext)
        ProfileStoreConfig.store = AndroidProfileStore(applicationContext)
        NotifierConfig.notifier = AndroidNotifier(this)

        // App-wide foreground/background: gate notifications and keep the node alive.
        // We start the service (plain startService) while we're in the foreground,
        // which keeps it eligible to promote itself to a foreground service later.
        // The service shows its persistent notification only in the background and
        // removes it in the foreground (see IrohService). We never stop the service
        // on background and never call the transport's pause()/shutdown(), so the
        // node keeps receiving.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppStateHost.shared?.setForeground(true)
                // Rebind if a previous "Stop" paused the node (no-op otherwise).
                IrohConfig.activeTransport?.resume()
                runCatching {
                    startService(Intent(this@AzulaApplication, IrohService::class.java))
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                AppStateHost.shared?.setForeground(false)
            }
        })
    }

    /**
     * Create the singleton [AzulaState] on first use (the Activity supplies isDemo;
     * a headless service restart defaults it to false). Subsequent calls return the
     * existing instance.
     */
    @Synchronized
    fun obtainState(isDemo: Boolean): AzulaState =
        AppStateHost.shared ?: AzulaState(createTransport(), appScope, isDemo).also {
            AppStateHost.shared = it
            appScope.launch { it.start() }
        }
}
