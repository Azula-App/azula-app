package app.azula.vmhost.ftl

import android.os.Build
import org.json.JSONObject

/** What the device is and how it's doing, and the host app's own logs, as files in the report. */
class DeviceInfo(private val shell: Shell, private val report: Report) {
    private val since = shell.run("date '+%Y-%m-%d %H:%M:%S.000'").stdout.trim()

    fun describe(): JSONObject {
        val o = JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("codename", Build.DEVICE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("fingerprint", Build.FINGERPRINT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
        for (p in PROPS) o.put(p, shell.run("getprop $p").stdout.trim())
        o.put("virtApex", shell.run("pm list packages --apex-only --show-versioncode | grep -F virt").stdout.trim())
        return o
    }

    /** Battery, thermal, memory, storage and what AVF says about itself: the state [label]led before or after the run. */
    fun save(label: String) {
        val sections = listOf(
            "battery" to "dumpsys battery",
            "thermal" to "dumpsys thermalservice | head -n 60",
            "storage" to "df -h /data /sdcard",
            "memory" to "head -n 5 /proc/meminfo; cat /proc/loadavg",
            // Present and runnable by the shell on builds with AVF; the error is the finding elsewhere.
            "avf (vm info)" to "ls -l /apex/com.android.virt/bin/vm; /apex/com.android.virt/bin/vm info",
            "avf features" to "pm list features | grep -iE 'virtual|hypervisor'; pm list permissions -f | grep -A 5 VIRTUAL_MACHINE",
            "host package" to "dumpsys package ${HostApp.PACKAGE} | grep -E 'versionName|userId|gids|firstInstall|granted='",
            "processes" to "ps -A -o USER,PID,PPID,RSS,NAME | grep -E 'azula|virtmgr|crosvm|virtualization'",
            "network" to "ip -o addr show up | awk '{print \$2, \$3}'; getprop | grep -i 'net.dns'",
        )
        report.write(
            "device-$label.txt",
            sections.joinToString("\n") { (name, cmd) -> "== $name\n${shell.run(cmd, 20_000).all}\n" },
        )
        if (label == "before") report.write("getprop.txt", shell.run("getprop").stdout)
    }

    /** The host app's logs and persisted state, through run-as, and what it and this harness logged. */
    fun hostLogs(host: HostApp) {
        for (n in listOf("host", "provision", "broker", "proxy", "console")) {
            report.write("log-$n.txt", host.file("files/logs/$n.log") ?: "(no files/logs/$n.log)\n")
        }
        report.write("host-state.json", host.file("files/vmhost-state/host-state.json") ?: "(no host-state.json)\n")
        report.write("host-files.txt", shell.run("run-as ${HostApp.PACKAGE} ls -laR files").all)
        report.write(
            "logcat.txt",
            shell.run("logcat -d -T '$since' -v threadtime -s vmhost:V ${Report.TAG}:V AndroidRuntime:E virtmgr:V VirtualMachineManager:V", 30_000).stdout,
        )
    }

    private companion object {
        val PROPS = listOf(
            "ro.boot.hypervisor.vm.supported",
            "ro.boot.hypervisor.protected_vm.supported",
            "ro.boot.hypervisor.version",
            "ro.soc.model",
            "ro.build.type",
            "ro.kernel.version",
            "ro.product.cpu.abilist",
        )
    }
}
