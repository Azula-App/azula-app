package dev.azula.net

import java.io.File

actual fun platformPeerStore(): PeerStore? = RawLinePeerStore(
    readRaw = { runCatching { peerFile().takeIf { it.exists() }?.readText() }.getOrNull() },
    writeRaw = { text ->
        runCatching {
            val f = peerFile()
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    },
)

/** `$AZULA_DATA_DIR/.azula/peers.txt` when the isolation override is set (see
 *  [azulaHomeDir] in `IrohFfiTransport.kt`), else `~/.azula/peers.txt` as before. */
internal fun peerFile(env: (String) -> String? = System::getenv) = File(azulaHomeDir(env), ".azula/peers.txt")
