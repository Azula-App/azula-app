import SwiftUI
import UserNotifications
import KotlinModules

// The iroh transport is provided by the `app.azula.iroh:iroh-kmp` Kotlin/Native
// binding (linked into this app via the shared framework); no Swift bridge is
// needed. See azula-docs/openspec/specs/iroh-kmp/design.md.

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

// Routes a tap on a local notification posted by IosNotifier
// (shared/src@ios/dev/azula/notify/IosNotifier.kt) into Compose -- the iOS
// equivalent of MainActivity.openConversationFromIntent on Android: "Connection
// requests" via InviteBus/openInvitesFromNotification, a message via
// ConversationTapBus/openConversationFromNotification. Also registers as the
// delegate so a tap is delivered here at all (the system default is to just
// clear the banner without calling anything).
//
// NOTE: this compiles and links against the shared framework (verified with
// `./kotlin build -m ios-app -p iosSimulatorArm64`), but tapping an actual
// delivered notification was NOT exercised on a simulator or device for either
// identifier -- it needs a posted notification for a conversation that exists,
// which a fresh simulator install doesn't have. The Kotlin side of the message
// route (ConversationTapBus into AzulaState.openChatFromNotification) is covered
// by mock-support's ConversationTapTest.
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
        let identifier = response.notification.request.identifier
        if identifier == IosNotifier.companion.INVITES_NOTIFICATION_IDENTIFIER {
            InviteBusKt.openInvitesFromNotification()
        } else if let conversationId = IosNotifier.companion.conversationIdFor(identifier: identifier) {
            // A message notification: open its conversation, like
            // MainActivity.openConversationFromIntent on Android. Buffered by
            // ConversationTapBus until AzulaState has started, for a tap that
            // cold-launched the app.
            ConversationTapBusKt.openConversationFromNotification(conversationId: conversationId)
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
                //
                // Single-closure-param onChange(of:perform:), not the two-param
                // onChange(of:initial:_:) -- the latter is iOS 17+, and the
                // deployment target is 15.0 (IPHONEOS_DEPLOYMENT_TARGET). We only
                // need the new value, so the older form is a clean fit; it's
                // deprecated on 17 but the project doesn't treat warnings as errors.
                //
                // The same observer is iOS's foreground signal for AzulaState
                // (ForegroundBus.kt; Android's is ProcessLifecycleOwner in
                // AzulaApplication). It gates notifications (posted only while
                // backgrounded), and the background-to-foreground edge redials
                // siblings and offline CLI sessions. `.inactive` counts as
                // foreground: Apple defines it as foreground-but-not-receiving-
                // events, and the app is still on screen in every case that
                // produces it (Control Center or Notification Center pulled over
                // it, an incoming-call banner, a system alert, the app switcher).
                // It is also the first phase delivered on the way back from
                // .background, so the resume edge fires at the first scene
                // callback after the suspended process thaws.
                .onChange(of: scenePhase) { newPhase in
                    switch newPhase {
                    case .active:
                        ForegroundBusKt.setAppForeground(foreground: true)
                        ShareInboxKt.drainShareInbox()
                    case .inactive:
                        ForegroundBusKt.setAppForeground(foreground: true)
                    case .background:
                        ForegroundBusKt.setAppForeground(foreground: false)
                    @unknown default:
                        break
                    }
                }
        }
    }
}
