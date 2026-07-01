package app.azula.mock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.azula.AzulaApp
import dev.azula.mock.buildMockState
import dev.azula.state.AppStateHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The whole `-mock` Android app: no process-scoped iroh node and no foreground
 * service (the fake transport needs neither). We pre-install an [AzulaState] wired
 * to the fakes so [AzulaApp]'s `rememberAzulaState()` returns it and the real
 * transport is never created.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        if (AppStateHost.shared == null) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            AppStateHost.shared = buildMockState(scope).also { scope.launch { it.start() } }
        }

        setContent { AzulaApp() }
    }
}
