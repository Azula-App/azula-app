package dev.azula.persist

import java.io.File

actual fun platformInvitationsStore(): InvitationsStore? = RawInvitationsStore(
    readRaw = { runCatching { invitationsFile().takeIf { it.exists() }?.readText() }.getOrNull() },
    writeRaw = { text ->
        runCatching {
            val f = invitationsFile()
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    },
)

private fun invitationsFile() = File(System.getProperty("user.home"), ".azula/invitations.json")
