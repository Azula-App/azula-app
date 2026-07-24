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

/** `$AZULA_DATA_DIR/.azula/invitations.json` when the isolation override is set
 *  (see [azulaHomeDir]), else `~/.azula/invitations.json` as before. */
internal fun invitationsFile(env: (String) -> String? = System::getenv) = File(azulaHomeDir(env), ".azula/invitations.json")
