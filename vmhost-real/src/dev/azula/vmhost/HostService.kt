package dev.azula.vmhost

import app.azula.vmhost.AzulaHost
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * Runs the on-phone VM host. Declared with `android:process=":vmhost"` so the
 * hidden-API exemption, the JNI broker and the VM's child processes live apart from
 * the UI: a UI crash doesn't take the VM down.
 *
 * Commands: START and STOP arrive as intents ([HostProtocol.ACTION_START]/STOP,
 * via startForegroundService); MINT, LIST and REVOKE arrive over a bound
 * [Messenger] because they return data (a bearer token that must not touch
 * disk). State goes out through [HostStateStore] (see its docs and vmhost-real's
 * module.yaml).
 *
 * All lifecycle work (start, stop, automatic restart) is serialized on one executor.
 */
class HostService : Service() {
    private lateinit var store: HostStateStore
    private val ops = Executors.newSingleThreadExecutor { Thread(it, "vmhost-ops") }
    private lateinit var ipcThread: HandlerThread
    private lateinit var messenger: Messenger

    // Mutated only on [ops]; `vm` and `brokerUp` are read from other threads.
    @Volatile private var vm: VirtualMachine? = null
    @Volatile private var brokerUp = false
    private var active = false
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var bridge: Bridge? = null
    private var proxy: Proxy? = null
    private var brokerThread: Thread? = null
    private var restarts = 0
    private var startedAt = 0L
    private var runningSince = 0L
    private var loginSeen = false
    private var plan: HostPlan? = null

    override fun onCreate() {
        super.onCreate()
        HostLog.dir = File(filesDir, "logs").also { it.mkdirs() }
        store = HostStateStore(stateDir(this))
        ipcThread = HandlerThread("vmhost-ipc").also { it.start() }
        messenger = Messenger(object : Handler(ipcThread.looper) {
            override fun handleMessage(msg: Message) = handleIpc(msg)
        })
        HiddenApi.enable()
        // A previous :vmhost process may have died with a live state on disk.
        publish(HostPhase.STOPPED, "")
        HostLog.to("host", "service created (pid ${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: HostProtocol.ACTION_START
        HostLog.to("host", "onStartCommand action=$action")
        promote(notificationText(store.read()))
        when (action) {
            HostProtocol.ACTION_STOP -> ops.execute {
                stopAll()
                publish(HostPhase.STOPPED, "")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> ops.execute { startAll(isRestart = false) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Reached after STOP (already torn down) or if the system destroys the service.
        ops.execute {
            stopAll()
            if (store.read().active) publish(HostPhase.STOPPED, "")
        }
        ops.shutdown()
        ipcThread.quitSafely()
        super.onDestroy()
    }

    // ---- state + notification ----

    private fun publish(phase: HostPhase, detail: String) {
        val now = System.currentTimeMillis()
        val s = HostState(
            phase = phase,
            detail = detail,
            startedAtMs = if (phase == HostPhase.STOPPED) 0 else startedAt,
            updatedAtMs = now,
            restarts = restarts,
            pid = android.os.Process.myPid(),
        )
        try { store.write(s) } catch (e: Exception) { HostLog.to("host", "state write failed: $e") }
        if (phase.isLive()) runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(notificationText(s)))
        }
    }

    private fun HostPhase.isLive() = this == HostPhase.PREPARING || this == HostPhase.BOOTING ||
        this == HostPhase.RUNNING || this == HostPhase.RESTARTING

    private fun notificationText(s: HostState) = when (s.phase) {
        HostPhase.RUNNING -> "hosting · running"
        HostPhase.RESTARTING -> "restarting…"
        HostPhase.PREPARING, HostPhase.BOOTING -> "hosting · starting"
        else -> "hosting"
    }

    private fun promote(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Compute host", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HostService::class.java).setAction(HostProtocol.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle("azula — compute host")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            b.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    /** The app's `ic_stat_azula` when it has one, else a platform glyph. */
    private fun smallIcon(): Int {
        val id = resources.getIdentifier("ic_stat_azula", "drawable", packageName)
        return if (id != 0) id else android.R.drawable.stat_sys_data_bluetooth
    }

    // ---- start ----

    private fun startAll(isRestart: Boolean) {
        if (active) { HostLog.to("host", "already started"); return }
        active = true
        stopping = false
        loginSeen = false
        brokerUp = false
        if (!isRestart) { restarts = 0; startedAt = System.currentTimeMillis() }
        try {
            publish(HostPhase.PREPARING, "")
            val p = when (val r = CapabilityProbe(this).check()) {
                is CapabilityResult.CanHost -> r.plan
                is CapabilityResult.CannotHost -> throw IllegalStateException(r.message)
            }
            plan = p
            val vmDir = File(filesDir, "vm").also { it.mkdirs() }
            val disk = stageDisk(vmDir) ?: throw IllegalStateException("no disk.raw in $vmDir or ${getExternalFilesDir(null)}")
            val seed = stageSeed(vmDir)
            // The cloud image is ~3 GiB; grow the file (sparse) and let cloud-init's
            // growpart/resizefs expand the root filesystem on boot.
            java.io.RandomAccessFile(disk, "rw").use { raf ->
                val want = p.diskGb.toLong() shl 30
                if (raf.length() < want) {
                    HostLog.to("host", "growing disk.raw ${raf.length()} -> $want")
                    raf.setLength(want)
                }
            }

            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "azula:vmhost").also { it.acquire() }

            publish(HostPhase.BOOTING, "")
            val v = AvfVm.recreate(this, p, disk, seed)
            vm = v
            v.setCallback(Executors.newSingleThreadExecutor(), callback(v))
            HostLog.to("host", "VM status before run=${v.status}; calling run()")
            v.run()
            HostLog.to("host", "run() returned; status=${v.status} (+${elapsed()}s)")
            captureConsole(v)

            val conn = VsockConnector { port ->
                val cur = vm ?: throw IllegalStateException("vm not running")
                cur.connectVsock(port)
            }
            bridge = Bridge(File(filesDir, "run"), conn).also { it.start() }
            proxy = Proxy(conn).also { it.start() }
            startBroker(p)
        } catch (e: Throwable) {
            HostLog.to("host", "start failed: ${android.util.Log.getStackTraceString(e)}")
            failAndStop("stopped — ${e.message ?: e}")
        }
    }

    private fun failAndStop(detail: String) {
        stopAll()
        publish(HostPhase.ERROR, detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun elapsed() = String.format("%.1f", (System.currentTimeMillis() - startedAt) / 1000.0)

    /** The broker comes up on its own thread; `AzulaHost.start` blocks until it is serving. */
    private fun startBroker(p: HostPlan) {
        File(filesDir, "compute").mkdirs()
        val config = BrokerConfig.forHost(filesDir, p).toJson()
        brokerThread = Thread({
            while (active && !stopping) {
                val err = AzulaHost.start(config)
                if (err.isEmpty()) {
                    brokerUp = true
                    HostLog.to("host", "broker: serving in-process (+${elapsed()}s)")
                    ops.execute { markRunningIfReady() }
                    return@Thread
                }
                HostLog.to("host", "broker: start failed: $err")
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "vmhost-broker").apply { isDaemon = true; start() }
    }

    private fun markRunningIfReady() {
        if (active && !stopping && loginSeen && brokerUp && store.read().phase != HostPhase.RUNNING) {
            runningSince = System.currentTimeMillis()
            publish(HostPhase.RUNNING, "")
            HostLog.to("host", "RUNNING (+${elapsed()}s since START)")
        }
    }

    private fun stageDisk(dir: File): File? = stage("disk.raw", dir)

    /** The seed ships as an app asset; a copy in the external files dir is the dev fallback. */
    private fun stageSeed(dir: File): File? {
        val dst = File(dir, "seed.iso")
        return try {
            assets.open("seed.iso").use { copyAtomically(it, dst) }
            dst
        } catch (_: java.io.FileNotFoundException) {
            stage("seed.iso", dir)
        }
    }

    /** Copies from `getExternalFilesDir(null)` when missing in `filesDir/vm`. TODO(3.2): the download replaces this for disk.raw. */
    private fun stage(name: String, dir: File): File? {
        val dst = File(dir, name)
        if (dst.exists()) return dst
        val src = File(getExternalFilesDir(null), name)
        if (!src.exists()) return null
        HostLog.to("host", "copying $src -> $dst")
        val tmp = File(dir, "$name.part")
        Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (!tmp.renameTo(dst)) throw java.io.IOException("rename failed for $name")
        HostLog.to("host", "copied $name (${dst.length()} bytes)")
        return dst
    }

    private fun copyAtomically(src: InputStream, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".part")
        FileOutputStream(tmp).use { src.copyTo(it) }
        if (!tmp.renameTo(dst)) throw java.io.IOException("rename failed for ${dst.name}")
    }

    private fun captureConsole(v: VirtualMachine) {
        Thread({
            val logs = File(filesDir, "logs").also { it.mkdirs() }
            var seen = false
            var tail = ""
            try {
                v.consoleOutput.use { inp ->
                    FileOutputStream(File(logs, "console.log"), false).use { out ->
                        val buf = ByteArray(4096)
                        while (true) {
                            val r = inp.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            out.flush()
                            if (!seen) {
                                tail = (tail + String(buf, 0, r, Charsets.ISO_8859_1)).takeLast(256)
                                if ("login:" in tail) {
                                    seen = true
                                    HostLog.to("host", "LOGIN prompt seen +${elapsed()}s since START")
                                    ops.execute { loginSeen = true; markRunningIfReady() }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                HostLog.to("host", "console reader ended: $e")
            }
            HostLog.to("host", "console stream closed")
        }, "vmhost-console").apply { isDaemon = true; start() }
    }

    private fun callback(v: VirtualMachine) = object : VirtualMachineCallback {
        override fun onPayloadStarted(vm: VirtualMachine) {}
        override fun onPayloadReady(vm: VirtualMachine) {}
        override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {}
        override fun onGuestAgentRegistered(vm: VirtualMachine, cid: Int) {}
        override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
            HostLog.to("host", "cb onError $errorCode $message")
            died(v, "vm error $errorCode: $message")
        }
        override fun onStopped(vm: VirtualMachine, reason: Int) {
            HostLog.to("host", "cb onStopped reason=$reason (+${elapsed()}s)")
            died(v, "vm stopped (reason $reason)")
        }
    }

    // ---- death and restart (design D9: restart once, then say why) ----

    private fun died(v: VirtualMachine, cause: String) {
        ops.execute {
            if (stopping || vm !== v) return@execute
            if (runningSince != 0L && System.currentTimeMillis() - runningSince > STABLE_MS) restarts = 0
            stopAll()
            if (restarts < MAX_AUTO_RESTARTS) {
                restarts++
                publish(HostPhase.RESTARTING, cause)
                HostLog.to("host", "restarting after: $cause (restart $restarts)")
                startAll(isRestart = true)
            } else {
                HostLog.to("host", "giving up after: $cause")
                publish(HostPhase.ERROR, "stopped — $cause")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ---- stop ----

    private fun stopAll() {
        stopping = true
        HostLog.to("host", "stopping")
        brokerThread?.interrupt()
        brokerThread = null
        if (brokerUp) runCatching { AzulaHost.stop() }
        brokerUp = false
        proxy?.stop(); proxy = null
        bridge?.stop(); bridge = null
        val v = vm
        vm = null
        if (v != null) {
            try { v.stop() } catch (e: Throwable) { HostLog.to("host", "vm.stop: $e") }
            try { v.close() } catch (e: Throwable) { HostLog.to("host", "vm.close: $e") }
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        runningSince = 0
        active = false
        HostLog.to("host", "stopped")
    }

    // ---- IPC: requests that return data ----

    private fun handleIpc(msg: Message) {
        val reply = msg.replyTo ?: return
        val json = if (!brokerUp) {
            """{"error":"the host isn't running"}"""
        } else {
            try {
                when (msg.what) {
                    HostProtocol.MSG_MINT -> {
                        val d = msg.data
                        AzulaHost.mintGrant(d.getString(HostProtocol.KEY_TTL).orEmpty().ifEmpty { "6h" },
                            d.getString(HostProtocol.KEY_LABEL).orEmpty().ifEmpty { "azula" })
                    }
                    HostProtocol.MSG_LIST -> AzulaHost.listGrants()
                    HostProtocol.MSG_REVOKE -> AzulaHost.revoke(msg.data.getString(HostProtocol.KEY_ID).orEmpty()).toString()
                    else -> """{"error":"unknown request ${msg.what}"}"""
                }
            } catch (t: Throwable) {
                HostLog.to("host", "ipc ${msg.what} failed: $t")
                """{"error":${org.json.JSONObject.quote(t.toString())}}"""
            }
        }
        runCatching {
            reply.send(Message.obtain(null, HostProtocol.MSG_REPLY, msg.arg1, 0).apply {
                data = Bundle().apply { putString(HostProtocol.KEY_JSON, json) }
            })
        }
    }

    companion object {
        private const val CHANNEL_ID = "vmhost"
        private const val NOTIFICATION_ID = 7301
        private const val MAX_AUTO_RESTARTS = 1
        /** A run this long counts as stable: the restart budget resets. */
        private const val STABLE_MS = 5 * 60_000L

        /** The directory holding the state file; watched by the UI process. */
        fun stateDir(ctx: Context): File = File(ctx.filesDir, "vmhost-state")
    }
}
