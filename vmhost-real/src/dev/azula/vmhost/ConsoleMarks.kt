package dev.azula.vmhost

/**
 * The guest console milestones the host waits for. [HostService] matches them
 * against the last few hundred characters of console output, so a mark split
 * across two reads still matches once its second half arrives.
 */
internal object ConsoleMarks {
    /** How much console output to keep for matching. */
    const val TAIL_CHARS = 256

    /** getty's prompt: the guest has booted. */
    fun loginPrompt(tail: String) = "login:" in tail

    // The seed (azula-cli vm/avf-seed/user-data) echoes "AZULA-SEED provision rc=<n> uptime=<s>"
    // once its first-boot tool install is over. Requiring the space after the digits keeps a
    // read that ends mid-number ("rc=1" of "rc=100") from matching early.
    private val TOOLS = Regex("""AZULA-SEED provision rc=(\d+)\s""")

    /** The first-boot tool install's exit status, or null while it's still running. */
    fun toolsRc(tail: String): Int? = TOOLS.find(tail)?.groupValues?.get(1)?.toIntOrNull()

    fun appendTail(tail: String, chunk: String) = (tail + chunk).takeLast(TAIL_CHARS)
}
