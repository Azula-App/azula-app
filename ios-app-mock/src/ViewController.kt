import androidx.compose.ui.window.ComposeUIViewController
import dev.azula.AzulaApp
import dev.azula.mock.installMockState

fun ViewController() = run {
    // -mock build: install an AzulaState wired to the fakes (FakeTransport + no
    // persistence) before the composition, so rememberAzulaState() returns it and
    // the real iroh transport is never created.
    installMockState()
    ComposeUIViewController { AzulaApp() }
}
