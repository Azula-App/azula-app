package dev.azula.vmhost

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The UI-process [VmHost]: commands go to `HostService` (in `:vmhost`) as intents
 * and bound-Messenger requests; state is read from the file that process writes.
 */
class AndroidVmHost(context: Context) : VmHost {
    private val ctx = context.applicationContext
    private val store = HostStateStore(HostService.stateDir(ctx))

    override val state: Flow<HostState> = store.observe(::isProcessAlive)

    override suspend fun checkCapability(): CapabilityResult =
        withContext(Dispatchers.IO) { CapabilityProbe(ctx).check() }

    override fun provision() {
        ctx.startForegroundService(Intent(ctx, HostService::class.java).setAction(HostProtocol.ACTION_PROVISION))
    }

    override fun start() {
        ctx.startForegroundService(Intent(ctx, HostService::class.java).setAction(HostProtocol.ACTION_START))
    }

    override fun stop() {
        // A running service gets a STOP command; if it isn't running there's nothing to stop
        // (startForegroundService would otherwise create it just to tear it down).
        if (effectiveState(store.read(), ::isProcessAlive).active) {
            ctx.startForegroundService(Intent(ctx, HostService::class.java).setAction(HostProtocol.ACTION_STOP))
        }
    }

    override suspend fun mintGrant(ttl: String, label: String): Result<MintedGrant> =
        request(HostProtocol.MSG_MINT, Bundle().apply {
            putString(HostProtocol.KEY_TTL, ttl)
            putString(HostProtocol.KEY_LABEL, label)
        }).mapCatching { GrantJson.parseMint(it).getOrThrow() }

    override suspend fun listGrants(): Result<List<GrantSummary>> =
        request(HostProtocol.MSG_LIST, Bundle()).mapCatching { GrantJson.parseList(it).getOrThrow() }

    override suspend fun revoke(id: String): Result<Boolean> =
        request(HostProtocol.MSG_REVOKE, Bundle().apply { putString(HostProtocol.KEY_ID, id) })
            .mapCatching { text ->
                // The service replies "true"/"false", or {"error": …}.
                if (text == "true" || text == "false") text.toBoolean()
                else throw GrantJson.parseList(text).exceptionOrNull() ?: HostException("unexpected reply")
            }

    /** One request/reply round trip over a short-lived binding, unbound whether it answers, times out or is cancelled. */
    private suspend fun request(what: Int, data: Bundle): Result<String> {
        if (effectiveState(store.read(), ::isProcessAlive).phase != HostPhase.RUNNING) {
            return Result.failure(HostException("the host isn't running"))
        }
        var conn: ServiceConnection? = null
        val reply = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { cont ->
                    val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
                        override fun handleMessage(m: Message) {
                            if (cont.isActive) cont.resume(m.data.getString(HostProtocol.KEY_JSON))
                        }
                    })
                    val c = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            val m = Message.obtain(null, what).apply { this.data = data; replyTo = inbox }
                            try { Messenger(binder).send(m) } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) { if (cont.isActive) cont.resume(null) }
                    }
                    conn = c
                    // flags = 0: bind only to a service that is already running; never create one.
                    if (!ctx.bindService(Intent(ctx, HostService::class.java), c, 0)) cont.resume(null)
                }
            }
        } finally {
            conn?.let { runCatching { ctx.unbindService(it) } }
        }
        return if (reply == null) Result.failure(HostException("the host didn't answer")) else Result.success(reply)
    }

    private fun isProcessAlive(pid: Int): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses?.any { it.pid == pid } ?: true
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}
