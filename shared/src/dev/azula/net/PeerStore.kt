package dev.azula.net

/**
 * Persists the tickets of peers/bridges this device has paired with, so the app
 * can auto-reconnect on launch — which lets an always-on bridge immediately
 * flush any messages it queued while the app was backgrounded or closed.
 */
interface PeerStore {
    fun load(): List<String>
    fun save(tickets: List<String>)
}

/**
 * The platform default store (a file on JVM, NSUserDefaults on iOS). Android
 * returns null here; its Context-backed store is bound into the Metro graph by
 * the app module.
 */
expect fun platformPeerStore(): PeerStore?
