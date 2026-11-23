package app.azula.vmhost.test

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.system.Os
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.azula.vmhost.AndroidVmHost
import dev.azula.vmhost.CapabilityResult
import dev.azula.vmhost.HostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/**
 * Harness: Start, Stop and Mint buttons and a status line. Mint writes the token
 * JSON to filesDir/token.json (0600) so a test run can fetch it with `run-as`;
 * delete that file afterwards. Intent extras `--es cmd start|stop|mint|revoke`
 * let adb drive it without taps.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var host: AndroidVmHost
    private lateinit var status: TextView
    private var last: HostState = HostState()
    private var grant = ""
    private var capability = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        host = AndroidVmHost(this)
        val p = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(p, p * 2, p, p)
        }
        status = TextView(this)
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            root.addView(this)
        }
        root.addView(status)
        button("Start") { host.start() }
        button("Stop") { host.stop() }
        button("Mint grant") { mint() }
        setContentView(ScrollView(this).apply { addView(root) })

        host.state.onEach { last = it; render() }.launchIn(scope)
        scope.launch { capability = describe(host.checkCapability()); render() }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleCmd(intent.getStringExtra("cmd"))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleCmd(intent.getStringExtra("cmd"))
    }

    private fun handleCmd(cmd: String?) {
        when (cmd) {
            "start" -> host.start()
            "stop" -> host.stop()
            "mint" -> mint()
            "revoke" -> scope.launch {
                val id = runCatching {
                    val t = File(filesDir, "token.json").readText()
                    Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(t)!!.groupValues[1]
                }.getOrNull()
                grant = if (id == null) "revoke: no token.json" else "revoke $id -> ${host.revoke(id)}"
                render()
            }
        }
    }

    private fun mint() = scope.launch {
        val r = host.mintGrant("6h", "vmhost-test")
        grant = r.fold(
            onSuccess = {
                val f = File(filesDir, "token.json")
                f.writeText("""{"id":"${it.grant.id}","token":"${it.token}"}""")
                Os.chmod(f.absolutePath, 384) // 0600
                "grant ${it.grant.id} -> token.json"
            },
            onFailure = { "mint failed: ${it.message}" },
        )
        render()
    }

    private fun describe(c: CapabilityResult) = when (c) {
        is CapabilityResult.CanHost -> "can host · ${c.plan.cpus} cores · ${c.plan.memoryMb} MB · ${c.plan.diskGb} GB disk"
        is CapabilityResult.CannotHost -> "cannot host: ${c.message}"
    }

    private fun render() {
        status.text = "state: ${last.phase}${if (last.detail.isEmpty()) "" else " (${last.detail})"}" +
            " · restarts ${last.restarts}\ncapability: $capability\n$grant"
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
