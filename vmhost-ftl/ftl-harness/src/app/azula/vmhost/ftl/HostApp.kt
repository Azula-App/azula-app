package app.azula.vmhost.ftl

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Drives vmhost-testapp (`app.azula.vmhost.test`) the way a person would: its buttons and status line. */
class HostApp(private val ui: UiDevice, private val shell: Shell, private val report: Report) {

    fun installed(): Boolean = shell.run("pm path $PACKAGE").let { it.ok && "package:" in it.stdout }

    fun grants(pkg: String = PACKAGE): Map<String, Boolean?> =
        DumpsysPackage.grants(shell.run("dumpsys package $pkg").stdout, PERMISSIONS)

    fun uiPid(): Int? = shell.run("pidof $PACKAGE").stdout.trim().toIntOrNull()

    /** Every process of the app, `:vmhost` included, by name. */
    fun processes(): String =
        shell.run("ps -A -o PID,NAME | grep -F $PACKAGE").stdout.trim().replace('\n', ' ').ifEmpty { "none" }

    fun forceStop() {
        shell.run("am force-stop $PACKAGE")
    }

    fun launch(cmd: String? = null): String =
        shell.run("am start -W -n $PACKAGE/.MainActivity${if (cmd == null) "" else " --es cmd $cmd"}").all

    /** The status line on screen; null when it isn't there. */
    fun status(): HostStatus? = try {
        ui.findObject(By.desc("vmhost-status"))?.text?.let(HostStatus::parse)
    } catch (_: StaleObjectException) {
        null
    }

    /** Polls the status line until [ready] or the deadline; the last status either way. */
    fun await(timeoutMs: Long, ready: (HostStatus) -> Boolean): HostStatus? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var last: HostStatus? = null
        while (true) {
            status()?.let { last = it; if (ready(it)) return it }
            if (SystemClock.elapsedRealtime() >= end) return last
            SystemClock.sleep(POLL_MS)
        }
    }

    fun tap(button: String) {
        report.log("tap $button")
        val b = ui.wait(Until.findObject(By.desc("vmhost-$button")), 10_000)
            ?: error("the host app shows no '$button' button")
        b.click()
    }

    /** Launches the app and waits for its capability check to say something. */
    fun open(): HostStatus {
        val started = launch()
        return await(30_000) { it.capability.isNotEmpty() }
            ?: error("the host app's status line never appeared; am start said: $started")
    }

    /** A file of the app's, read with run-as (it is debuggable). Never logged; callers scrub it. */
    fun file(path: String, maxBytes: Int = 400_000): String? =
        shell.run("run-as $PACKAGE tail -c $maxBytes $path").takeIf { it.ok }?.stdout

    companion object {
        const val PACKAGE = "app.azula.vmhost.test"
        const val STUB = "app.azula.vmhost.ftl.stub"
        const val HARNESS = "app.azula.vmhost.ftl.harness"
        val VM_PERMISSIONS = listOf(
            "android.permission.MANAGE_VIRTUAL_MACHINE",
            "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
        )
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        val PERMISSIONS = VM_PERMISSIONS + POST_NOTIFICATIONS
        const val POLL_MS = 1_000L
    }
}
