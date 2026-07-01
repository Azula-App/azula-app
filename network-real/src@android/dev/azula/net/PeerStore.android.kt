package dev.azula.net

import android.content.Context

// Android needs a Context, so the default is null and MainActivity injects an
// [AndroidPeerStore] into [PeerStoreConfig].
actual fun platformPeerStore(): PeerStore? = null

class AndroidPeerStore(context: Context) : PeerStore {
    private val prefs = context.getSharedPreferences("azula_peers", Context.MODE_PRIVATE)

    override fun load(): List<String> =
        (prefs.getString("tickets", "") ?: "").split("\n").filter { it.isNotBlank() }

    override fun save(tickets: List<String>) {
        prefs.edit().putString("tickets", tickets.joinToString("\n")).apply()
    }
}
