package dev.azula.net

import platform.Foundation.NSUserDefaults

actual fun platformPeerStore(): PeerStore? = NsUserDefaultsPeerStore()

class NsUserDefaultsPeerStore : PeerStore {
    private val key = "azula_paired_peers"
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): List<String> =
        defaults.arrayForKey(key)?.mapNotNull { it as? String } ?: emptyList()

    override fun save(tickets: List<String>) {
        defaults.setObject(tickets, key)
    }
}
