package app.azula

import android.content.Intent
import androidx.core.content.ContextCompat
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
import dev.azula.persist.MessageStoreConfig
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
        NotifierConfig.notifier = AndroidNotifier(this)

        // App-wide foreground/background: gate notifications and keep the node alive.
        // We start the foreground service while we're in the foreground (reliable on
        // Android 12+) and deliberately do NOT stop it on background, so the node
        // keeps receiving. We also never call the transport's pause()/shutdown().
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppStateHost.shared?.setForeground(true)
                ContextCompat.startForegroundService(
                    this@AzulaApplication, Intent(this@AzulaApplication, IrohService::class.java)
                )
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
