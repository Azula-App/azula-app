import SwiftUI
import UserNotifications
import KotlinModules

// The iroh transport is provided by the `app.azula.iroh:iroh-kmp` Kotlin/Native
// binding (linked into this app via the shared framework); no Swift bridge is
// needed. See azula-docs/docs/iroh-kmp.md.

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> some UIViewController {
        return ViewControllerKt.ViewController()
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

// Routes a tap on the "Connection requests" local notification (posted by
// IosNotifier, shared/src@ios/dev/azula/notify/IosNotifier.kt) into Compose via
// InviteBus/openInvitesFromNotification -- the iOS equivalent of
// MainActivity.openConversationFromIntent's "invites" sentinel on Android.
// Also registers as the delegate so a tap is delivered here at all (the system
// default is to just clear the banner without calling anything).
//
// NOTE: this compiles and links against the shared framework (verified with a
// real `xcodebuild ... -destination iphonesimulator build`), but tapping an
// actual delivered notification to confirm `openInvitesMobile()` fires was NOT
// exercised -- that needs a live simulator/device run posting then tapping a
// notification, which this environment can't drive interactively. The Kotlin
// side (IosNotifier, InviteBus, the AzulaState.init registration) is fully
// wired and covered by the shared-module build.
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    // Called when the user taps a delivered notification (the app may be
    // foreground, background, or not-yet-running at this point).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.notification.request.identifier == IosNotifier.companion.INVITES_NOTIFICATION_IDENTIFIER {
            InviteBusKt.openInvitesFromNotification()
        }
        completionHandler()
    }
}

@main
struct iosApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                // Drain whatever the AzulaShare extension left in the App
                // Group inbox (see ShareInbox.kt, src@ios) every time the app
                // comes to the foreground -- a share extension can't
                // foreground its host app, so this is the only point a share
                // actually reaches the running app: the user shares from
                // another app, then switches to Azula themselves.
                .onChange(of: scenePhase) { _, newPhase in
                    if newPhase == .active {
                        ShareInboxKt.drainShareInbox()
                    }
                }
        }
    }
}
