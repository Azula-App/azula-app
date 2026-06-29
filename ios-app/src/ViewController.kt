import androidx.compose.ui.window.ComposeUIViewController
import dev.azula.AzulaApp

fun ViewController(isDemo: Boolean = false) = ComposeUIViewController { AzulaApp(isDemo) }
