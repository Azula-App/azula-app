package dev.azula.notify

// Android keeps building its Notifier (AndroidNotifier) via AzulaApplication /
// AppStateHost, a process-scoped instance created before this expect is ever
// consulted -- see rememberAzulaState's doc. Null here just satisfies the expect.
actual fun platformNotifier(): Notifier? = null
