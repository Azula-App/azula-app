package dev.azula.notify

// Desktop shows invites (InvitesPane) and new messages inline in-window; no
// system notification backend yet (a tray notification would be a follow-up).
actual fun platformNotifier(): Notifier? = null
