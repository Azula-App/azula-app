package dev.azula.persist

import android.content.Context

// Android needs a Context, so the default is null; AzulaApplication builds the
// AppGraph with an AndroidInvitationsStore(applicationContext) instead.
actual fun platformInvitationsStore(): InvitationsStore? = null

/** Stores the whole InvitationsBook as a JSON string in SharedPreferences. */
fun AndroidInvitationsStore(context: Context): InvitationsStore {
    val prefs = context.getSharedPreferences("azula_invitations", Context.MODE_PRIVATE)
    return RawInvitationsStore(
        readRaw = { prefs.getString("book", null) },
        writeRaw = { prefs.edit().putString("book", it).apply() },
    )
}
