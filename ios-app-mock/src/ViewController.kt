import androidx.compose.ui.window.ComposeUIViewController
import dev.azula.AzulaApp
import dev.azula.mock.buildMockState
import dev.azula.state.AppStateHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun ViewController() = run {
    // -mock build: install an AzulaState wired to the fakes (FakeTransport + no
    // persistence) before the composition, so rememberAzulaState() returns it and
    // the real iroh transport is never created.
    if (AppStateHost.shared == null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AppStateHost.shared = buildMockState(scope).also { scope.launch { it.start() } }
    }
    ComposeUIViewController { AzulaApp() }
}
