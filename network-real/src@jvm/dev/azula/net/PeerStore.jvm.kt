package dev.azula.net

import java.io.File

actual fun platformPeerStore(): PeerStore? = FilePeerStore()

/** Stores paired tickets one-per-line in ~/.azula/peers.txt. */
class FilePeerStore : PeerStore {
    private val file = File(System.getProperty("user.home"), ".azula/peers.txt")

    override fun load(): List<String> =
        runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    override fun save(tickets: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(tickets.joinToString("\n"))
        }
    }
}
