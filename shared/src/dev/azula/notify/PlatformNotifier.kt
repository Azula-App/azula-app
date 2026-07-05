package dev.azula.notify

/**
 * The platform's default [Notifier], used by [dev.azula.state.rememberAzulaState]
 * (the composition-scoped path -- iOS and desktop/JVM; Android instead
 * pre-installs an [AndroidNotifier][app.azula.AndroidNotifier] via its
 * `Application`, so its actual here just returns null):
 *  - **iOS**: a `UNUserNotificationCenter`-backed `IosNotifier`.
 *  - **JVM**: null (desktop shows invites/messages inline, no system tray yet).
 *  - **Android**: null (see above -- `AzulaApplication.obtainState` wins).
 */
expect fun platformNotifier(): Notifier?
