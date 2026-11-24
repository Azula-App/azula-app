package dev.azula.vmhost

/**
 * Decides when a boot's tools count as in (design D9 S3: Ready means the tools are in).
 * Only the guest's own "rc=0" does: a failed attempt, or no word at all by the deadline, never does.
 * [needed] is false on a boot whose tools this host already saw succeed, with this seed.
 *
 * Pure, so every path is a unit test. [HostService] calls it on its own executor.
 */
internal class ToolsWait(needed: Boolean) {
    enum class Verdict {
        /** Still waiting for the guest. */
        WAITING,

        /** Ready, as far as the tools go. */
        DONE,

        /** Out of time. A retry boots the VM again, and the guest tries again by itself. */
        GIVE_UP,
    }

    var verdict = if (needed) Verdict.WAITING else Verdict.DONE
        private set

    /** The last failed attempt, for the status line; null until one fails. */
    var failure: ConsoleMarks.Tools.Failed? = null
        private set

    /** Returns true if the status line should change: a new failed attempt. */
    fun on(tools: ConsoleMarks.Tools): Boolean = when (tools) {
        is ConsoleMarks.Tools.Done -> {
            if (verdict == Verdict.WAITING) verdict = Verdict.DONE
            false
        }
        is ConsoleMarks.Tools.Failed -> (verdict == Verdict.WAITING && tools != failure).also { if (it) failure = tools }
    }

    /** The deadline passed. Returns true if it ends the wait. */
    fun onDeadline(): Boolean = (verdict == Verdict.WAITING).also { if (it) verdict = Verdict.GIVE_UP }
}
