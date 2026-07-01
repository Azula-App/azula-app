package dev.azula.persist

/**
 * Platform default stores. JVM writes files; iOS uses NSUserDefaults; Android
 * returns null here and binds a Context-backed store into the Metro graph via the
 * app module. The actuals live in this module's src@jvm / src@android / src@ios.
 */
expect fun platformMessageStore(): MessageStore?

expect fun platformProfileStore(): ProfileStore?
