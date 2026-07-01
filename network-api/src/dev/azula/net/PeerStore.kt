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
