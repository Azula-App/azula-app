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
import java.util.concurrent.atomic.AtomicBoolean

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

    // Setup (image download etc.) runs on its own thread so STOP and IPC stay responsive.
    private val provisionExec = Executors.newSingleThreadExecutor { Thread(it, "vmhost-provision") }
    private val provisionCancel = AtomicBoolean(false)
    private val provisionLock = Any()
    @Volatile private var provisioning = false
    /** True until the VM has booted once: phases then read PROVISIONING/FIRST_BOOT instead of PREPARING/BOOTING. */
    @Volatile private var firstBoot = false
    private lateinit var provisioner: ImageProvisioner
    private var lastProgressPublish = 0L
    private var lastProgressStep: ProvisionStep? = null

    override fun onCreate() {
        super.onCreate()
        HostLog.dir = File(filesDir, "logs").also { it.mkdirs() }
        store = HostStateStore(stateDir(this))
        ipcThread = HandlerThread("vmhost-ipc").also { it.start() }
        messenger = Messenger(object : Handler(ipcThread.looper) {
            override fun handleMessage(msg: Message) = handleIpc(msg)
        })
        provisioner = ImageProvisioner(
            dir = File(filesDir, "vm").also { it.mkdirs() },
            freeBytes = { runCatching { android.os.StatFs(filesDir.absolutePath).availableBytes }.getOrDefault(0L) },
            stageSeed = { stageSeed(File(filesDir, "vm")) },
            listener = { step, done, total -> onProvisionProgress(step, done, total) },
            cancelled = { provisionCancel.get() },
            log = { HostLog.to("provision", it) },
            diskGbOverride = { PlanOverrideFile.read(stateDir(this)).diskGb },
        )
        HiddenApi.enable()
        // A previous :vmhost process may have died with a live state on disk.
        publish(HostPhase.STOPPED, "")
        HostLog.to("host", "service created (pid ${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // The system restarted a sticky service after killing the process (the permission grant
            // does that: it changes the app's gids). Only unfinished setup carries on by itself;
            // a host that was running is never revived behind the user's back.
            if (provisioner.load().let { it.imageReady && it.firstBootDone }) {
                HostLog.to("host", "restarted by the system with setup complete; staying stopped")
                stopSelf()
                return START_NOT_STICKY
            }
            HostLog.to("host", "restarted by the system mid-setup; resuming")
            promote(store.read())
            beginProvisionOrStart()
            return START_STICKY
        }
        val action = intent.action ?: HostProtocol.ACTION_START
        HostLog.to("host", "onStartCommand action=$action")
        promote(store.read())
        when (action) {
            HostProtocol.ACTION_STOP -> {
                synchronized(provisionLock) { provisionCancel.set(true); provisioning = false }
                ops.execute {
                    stopAll()
                    publish(HostPhase.STOPPED, "")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            // START and PROVISION are the same command: set up if needed, then boot.
            else -> beginProvisionOrStart()
        }
        // Sticky while setup is unfinished, so a kill (the permission grant) resumes it.
        return if (provisioner.load().let { it.imageReady && it.firstBootDone }) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        provisionCancel.set(true)
        provisionExec.shutdownNow()
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

    private fun publish(phase: HostPhase, detail: String, step: ProvisionStep? = null, done: Long = 0, total: Long = 0, retryable: Boolean = false) {
        val now = System.currentTimeMillis()
        // The first boot is the last step of setup: the same start-up phases read as PROVISIONING/FIRST_BOOT.
        val first = firstBoot && (phase == HostPhase.PREPARING || phase == HostPhase.BOOTING)
        val s = HostState(
            phase = if (first) HostPhase.PROVISIONING else phase,
            step = if (first) ProvisionStep.FIRST_BOOT else step,
            bytesDone = done,
            bytesTotal = total,
            retryable = retryable,
            detail = if (first && detail.isEmpty()) "installing tools · about 4 min" else detail,
            startedAtMs = if (phase == HostPhase.STOPPED) 0 else startedAt,
            updatedAtMs = now,
            restarts = restarts,
            pid = android.os.Process.myPid(),
        )
        try { store.write(s) } catch (e: Exception) { HostLog.to("host", "state write failed: $e") }
        if (s.phase.isLive()) runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s))
        }
    }

    private fun HostPhase.isLive() = this == HostPhase.PROVISIONING || this == HostPhase.PREPARING || this == HostPhase.BOOTING ||
        this == HostPhase.RUNNING || this == HostPhase.RESTARTING

    private fun stepLabel(s: HostState): String {
        val pct = if (s.bytesTotal > 0) " · ${s.bytesDone * 100 / s.bytesTotal}%" else ""
        return when (s.step) {
            ProvisionStep.DOWNLOADING -> "downloading debian$pct"
            ProvisionStep.VERIFYING -> "verifying$pct"
            ProvisionStep.EXTRACTING -> "unpacking$pct"
            ProvisionStep.PREPARING_DISK -> "preparing disk"
            ProvisionStep.FIRST_BOOT -> s.detail.ifEmpty { "first boot" }
            null -> "setting up"
        }
    }

    private fun notificationText(s: HostState) = when (s.phase) {
        HostPhase.PROVISIONING -> "setting up · ${stepLabel(s)}"
        HostPhase.RUNNING -> "hosting · running"
        HostPhase.RESTARTING -> "restarting…"
        HostPhase.PREPARING, HostPhase.BOOTING -> "hosting · starting"
        else -> "hosting"
    }

    private fun promote(s: HostState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Compute host", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, buildNotification(s), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun buildNotification(s: HostState): Notification {
        val text = notificationText(s)
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HostService::class.java).setAction(HostProtocol.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle("azula — compute host")
            .setContentText(text)
            .setOngoing(true)
            .apply {
                // A progress bar while a download, check or unpack has a known length (in per mille).
                if (s.phase == HostPhase.PROVISIONING && s.bytesTotal > 0) {
                    setProgress(1000, (s.bytesDone * 1000 / s.bytesTotal).toInt(), false)
                } else if (s.phase == HostPhase.PROVISIONING) setProgress(0, 0, true)
            }
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
            // Storage was checked when setup began, and the disk is already allocated, so the
            // 20 GB-free gate (S1) doesn't apply to booting what's already there.
            val probe = CapabilityProbe(this).inputs()
            val p = when (val r = evaluateCapability(probe.copy(freeStorageBytes = maxOf(probe.freeStorageBytes, MIN_FREE_BYTES)))) {
                is CapabilityResult.CanHost -> PlanOverrideFile.read(stateDir(this)).applyTo(r.plan)
                    .let { it.copy(diskGb = maxOf(it.diskGb, provisioner.load().diskGb)) }
                is CapabilityResult.CannotHost -> throw IllegalStateException(r.message)
            }
            plan = p
            val vmDir = File(filesDir, "vm").also { it.mkdirs() }
            val disk = File(vmDir, ImageSource.DISK_ENTRY).takeIf { it.exists() }
                ?: throw IllegalStateException("the guest image isn't set up yet")
            val seed = stageSeed(vmDir)
            // The disk grows sparse to the planned size; cloud-init's growpart/resizefs
            // expands the root filesystem on boot.
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
        firstBoot = false
        // provision() again is the retry: it resumes at whatever step is outstanding.
        publish(HostPhase.ERROR, detail, retryable = true)
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
            if (firstBoot) {
                firstBoot = false
                runCatching { provisioner.markFirstBootDone() }
                HostLog.to("provision", "first boot reached RUNNING (+${elapsed()}s since the boot began)")
            }
            publish(HostPhase.RUNNING, "")
            HostLog.to("host", "RUNNING (+${elapsed()}s since START)")
        }
    }

    /** Copies the seed (an app asset) to `filesDir/vm/seed.iso` (design D2). */
    private fun stageSeed(dir: File): File {
        val dst = File(dir, "seed.iso")
        dir.mkdirs()
        assets.open("seed.iso").use { copyAtomically(it, dst) }
        return dst
    }

    // ---- setup (design D9 S3) ----

    /** START and PROVISION: finish setup if the image or the first boot is outstanding, else just boot. */
    private fun beginProvisionOrStart() {
        val st = provisioner.load()
        if (st.imageReady && st.firstBootDone) {
            firstBoot = false
            ops.execute { startAll(isRestart = false) }
            return
        }
        synchronized(provisionLock) {
            if (provisioning || active) {
                HostLog.to("provision", "already running"); return
            }
            provisioning = true
            provisionCancel.set(false)
        }
        startedAt = System.currentTimeMillis()
        lastProgressStep = null
        publish(HostPhase.PROVISIONING, "", ProvisionStep.DOWNLOADING)
        provisionExec.execute { runProvision(st.imageReady) }
    }

    private fun runProvision(imageAlreadyReady: Boolean) {
        try {
            if (!imageAlreadyReady) {
                // No point downloading for a phone that can't host (S1 already said so; this is the backstop).
                // Bytes from an earlier attempt count as free: they're about to be reused.
                val probe = CapabilityProbe(this).inputs()
                val r = evaluateCapability(probe.copy(freeStorageBytes = probe.freeStorageBytes + provisioner.footprintBytes()))
                if (r is CapabilityResult.CannotHost) throw ProvisionFailure(r.message)
                provisioner.run()
            }
            // Downloading needs no permission, but the VM does: wait for the grants here.
            firstBoot = true
            var announced = false
            while (!haveVmPermissions()) {
                if (provisionCancel.get()) throw CancelledException()
                if (!announced) {
                    announced = true
                    HostLog.to("provision", "image ready; waiting for the VM permissions")
                    provisionPublish(HostPhase.PROVISIONING, "waiting for permission", ProvisionStep.FIRST_BOOT)
                }
                Thread.sleep(1500)
            }
            synchronized(provisionLock) {
                if (provisionCancel.get()) throw CancelledException()
                provisioning = false
            }
            ops.execute { if (!provisionCancel.get()) startAll(isRestart = false) }
        } catch (_: CancelledException) {
            HostLog.to("provision", "cancelled")
        } catch (e: ProvisionFailure) {
            HostLog.to("provision", "failed: ${e.message}")
            provisionFail(e.message ?: "setup failed", e.retryable)
        } catch (e: Throwable) {
            HostLog.to("provision", "failed: ${android.util.Log.getStackTraceString(e)}")
            provisionFail("setup failed: ${e.message ?: e}", true)
        }
    }

    private fun haveVmPermissions() =
        checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Publishes setup state unless STOP got there first (a late write would undo STOPPED). */
    private fun provisionPublish(phase: HostPhase, detail: String, step: ProvisionStep?, done: Long = 0, total: Long = 0) =
        synchronized(provisionLock) {
            if (!provisionCancel.get()) publish(phase, detail, step, done, total)
        }

    private fun provisionFail(message: String, retryable: Boolean) = synchronized(provisionLock) {
        if (provisionCancel.get()) return@synchronized
        provisioning = false
        firstBoot = false
        publish(HostPhase.ERROR, message, retryable = retryable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Throttled: the state file is fsynced, so at most one write a second (and on every step change). */
    private fun onProvisionProgress(step: ProvisionStep, done: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (step == lastProgressStep && now - lastProgressPublish < 1000 && done != total) return
        lastProgressStep = step
        lastProgressPublish = now
        provisionPublish(HostPhase.PROVISIONING, "", step, done, total)
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
