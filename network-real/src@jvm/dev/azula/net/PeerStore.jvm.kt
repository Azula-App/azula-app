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

private fun peerFile() = File(System.getProperty("user.home"), ".azula/peers.txt")
