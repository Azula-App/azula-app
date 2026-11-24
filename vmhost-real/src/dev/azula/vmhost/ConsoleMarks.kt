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

    /** What the guest has said about its tools so far, from its `AZULA-SEED provision rc=<n>` console lines. */
    sealed interface Tools {
        /** The tools are in, checked by the guest (git, gcc, make and file run). Final. */
        data object Done : Tools

        /** An install attempt failed with [rc]; the guest is trying again by itself. [uptime] tells attempts apart. */
        data class Failed(val rc: Int, val uptime: String) : Tools
    }

    // The guest's azula-provision (azula-cli vm/avf-seed) echoes "AZULA-SEED provision rc=<n> uptime=<s>"
    // once per attempt. Requiring what follows the digits keeps a read that ends mid-number
    // ("rc=1" of "rc=100") from matching early.
    private val TOOLS = Regex("""AZULA-SEED provision rc=(\d+) uptime=([\d.]+)\s""")

    /** The latest word on the tools in [tail], or null if none has come. A success outranks every failure. */
    fun tools(tail: String): Tools? {
        val all = TOOLS.findAll(tail).toList()
        if (all.any { it.groupValues[1].toIntOrNull() == 0 }) return Tools.Done
        return all.lastOrNull()?.let { Tools.Failed(it.groupValues[1].toInt(), it.groupValues[2]) }
    }

    fun appendTail(tail: String, chunk: String) = (tail + chunk).takeLast(TAIL_CHARS)
}
