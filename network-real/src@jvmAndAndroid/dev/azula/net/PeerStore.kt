package dev.azula.net

/**
 * Stores paired-peer tickets one-per-line via a platform-supplied raw string
 * accessor (SharedPreferences on Android, a file on JVM).
 */
class RawLinePeerStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) : PeerStore {
    override fun load(): List<String> = (readRaw() ?: "").split("\n").filter { it.isNotBlank() }

    override fun save(tickets: List<String>) {
        writeRaw(tickets.joinToString("\n"))
    }
}
