package dev.azula.persist

import platform.Foundation.NSUserDefaults

actual fun platformInvitationsStore(): InvitationsStore? = NsUserDefaultsInvitationsStore()

/** Stores the whole InvitationsBook as a JSON string in NSUserDefaults. */
class NsUserDefaultsInvitationsStore : InvitationsStore {
    private val key = "azula_invitations"
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): InvitationsBook {
        val s = defaults.stringForKey(key) ?: return InvitationsBook()
        return decodeInvitationsBook(s) ?: InvitationsBook()
    }

    override fun save(book: InvitationsBook) {
        runCatching { defaults.setObject(encodeInvitationsBook(book), key) }
    }
}
