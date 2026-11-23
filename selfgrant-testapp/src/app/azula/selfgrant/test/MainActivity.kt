package app.azula.selfgrant.test

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.azula.selfgrant.AndroidVmPermissionGranter
import dev.azula.selfgrant.GrantState
import dev.azula.selfgrant.SelfGrantSettings
import dev.azula.selfgrant.VmPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Drives AndroidVmPermissionGranter on a real phone: one status block, a few buttons. */
class MainActivity : Activity() {
    private lateinit var granter: AndroidVmPermissionGranter
    private lateinit var status: TextView
    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        granter = AndroidVmPermissionGranter(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); tag = "status" }
        fun button(label: String, tag: String, onClick: () -> Unit) = Button(this).apply {
            text = label; this.tag = tag; setOnClickListener { onClick() }
        }.also { col.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        col.addView(status)
        button("allow notifications", "notifications") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        button("open wireless debugging", "wireless") { startActivity(SelfGrantSettings.openWirelessDebugging()) }
        button("start grant", "start") { granter.start() }
        button("cancel", "cancel") { granter.cancel() }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onStart() {
        super.onStart()
        granter.refresh()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch { granter.state.collect { render(it) } }
        }
    }

    override fun onStop() {
        scope?.cancel()
        super.onStop()
    }

    private fun render(s: GrantState) {
        status.text = buildString {
            appendLine("phase: ${s.phase}")
            appendLine("error: ${s.error ?: "-"} ${s.detail ?: ""}")
            VmPermission.entries.forEach { appendLine("${it.name}: ${if (it in s.missing) "MISSING" else "granted"}") }
            appendLine("developer options: ${SelfGrantSettings.developerOptionsEnabled(this@MainActivity)}")
            appendLine("wireless debugging: ${SelfGrantSettings.wirelessDebuggingEnabled(this@MainActivity)}")
        }
    }
}
