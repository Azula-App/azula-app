import SwiftUI
import KotlinModules

// The iroh transport is provided by the `app.azula.iroh:iroh-kmp` Kotlin/Native
// binding (linked into this app via the shared framework); no Swift bridge is
// needed. See azula-docs/docs/iroh-kmp.md.

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> some UIViewController {
        // Demo/UI-test build: set AZULA_DEMO=1 in the scheme's environment to seed content.
        let isDemo = ProcessInfo.processInfo.environment["AZULA_DEMO"] == "1"
        return ViewControllerKt.ViewController(isDemo: isDemo)
    }
    func updateUIViewController(_ uiViewController: UIViewControllerType, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            .ignoresSafeArea(.keyboard)
            // Universal links (https://azula.app/s/<token>) and the azula:// scheme.
            .onOpenURL { url in
                DeepLinkKt.handleDeepLink(url: url.absoluteString)
            }
    }
}

@main
struct iosApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
