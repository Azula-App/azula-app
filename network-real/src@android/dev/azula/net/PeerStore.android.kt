package dev.azula.net

import android.content.Context

// Android needs a Context, so the default is null; AzulaApplication builds the
// AppGraph with an AndroidPeerStore(applicationContext) instead.
actual fun platformPeerStore(): PeerStore? = null

/** Stores paired-peer tickets one-per-line in SharedPreferences. */
fun AndroidPeerStore(context: Context): PeerStore {
    val prefs = context.getSharedPreferences("azula_peers", Context.MODE_PRIVATE)
    return RawLinePeerStore(
        readRaw = { prefs.getString("tickets", "") },
        writeRaw = { prefs.edit().putString("tickets", it).apply() },
    )
}
