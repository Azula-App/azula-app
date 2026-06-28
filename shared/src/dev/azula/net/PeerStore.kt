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

object PeerStoreConfig {
    // Platforms that need a Context (Android) inject their store here.
    @kotlin.concurrent.Volatile
    var store: PeerStore? = null
}

/**
 * The platform default store (a file on JVM, NSUserDefaults on iOS). Android
 * returns null here and injects a Context-backed store via [PeerStoreConfig].
 */
expect fun platformPeerStore(): PeerStore?

/** The active store: an injected one (Android) or the platform default. */
fun pairedPeerStore(): PeerStore? = PeerStoreConfig.store ?: platformPeerStore()
