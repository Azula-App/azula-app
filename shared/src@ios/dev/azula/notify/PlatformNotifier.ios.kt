package dev.azula.notify

actual fun platformNotifier(): Notifier? = IosNotifier()
