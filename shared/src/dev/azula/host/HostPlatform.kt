package dev.azula.host

/**
 * What the host-compute screens need from the platform that isn't the VM host or the grant
 * flow themselves: Settings deep links, the notification permission, share/open-url, the two
 * pieces of setup state that must survive process death, and the "labs" switch that hides the
 * entry in release builds. Android implements it (android-app); the `-mock` apps use a fake;
 * JVM and iOS pass null, which hides the entry altogether.
 */
interface HostPlatform {
    /** S0: the entry shows only in a debuggable build, or with the labs preference on. */
    fun entryVisible(): Boolean

    /** Turns the labs preference on (the hidden 7-tap gesture on Settings' title), which shows the entry in release builds. */
    fun enableLabs()

    /** Whether Developer options are on (see `SelfGrantSettings.developerOptionsEnabled`). */
    fun developerOptionsEnabled(): Boolean

    fun notificationsAllowed(): Boolean

    /** The system `POST_NOTIFICATIONS` dialog. Result arrives by re-checking on resume. */
    fun requestNotifications()

    /** Deep link to About phone (Build number lives there). */
    fun openAboutPhone()

    /** Deep link to Developer options with Wireless debugging highlighted. */
    fun openWirelessDebugging()

    fun openUrl(url: String)

    /** The system share sheet for [text]. */
    fun share(text: String)

    /** "HH:mm" in the phone's time zone, for the auto-label "cloud session · 14:05". */
    fun localTime(epochSeconds: Long): String

    /** The user tapped "set up". Persisted so every step resumes after process death. */
    var setupCommitted: Boolean

    /** The host reached Ready once: S4 from now on, and no further provisioning. */
    var setupDone: Boolean
}
