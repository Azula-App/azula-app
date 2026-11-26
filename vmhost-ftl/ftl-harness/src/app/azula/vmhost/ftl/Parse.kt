package app.azula.vmhost.ftl

/** The status line of vmhost-testapp's MainActivity (`render()`), parsed. Pure, so it is unit-tested. */
data class HostStatus(
    val phase: String,
    val step: String?,
    val done: Long,
    val total: Long,
    val detail: String,
    val retryable: Boolean,
    val restarts: Int,
    /** The capability line: "can host · …" or "cannot host: …"; empty until the app has run the check. */
    val capability: String,
    /** The last line: what Mint or a revoke said. */
    val grant: String,
) {
    /** Why this phone can't host, if the app's own capability check says so. */
    val cannotHost: String? get() = capability.removePrefix(CANNOT).takeIf { it != capability }

    /** What changes between timeline entries; byte counts don't. */
    val key: String get() = listOfNotNull(phase, step, detail.ifEmpty { null }).joinToString(" / ")

    companion object {
        private const val CANNOT = "cannot host: "

        // The detail can hold anything, newlines and parentheses included, so the match is anchored
        // on what surrounds it.
        private val LINE = Regex(
            """^state: (\w+)(?: · (\w+) (\d+)/(\d+))?(?: \((.*)\))?( \[retry])? · restarts (\d+)\ncapability: ?([^\n]*)(?:\n(.*))?$""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** Null when [text] isn't the status line (the activity is still starting, or the layout changed). */
        fun parse(text: String): HostStatus? {
            val m = LINE.matchEntire(text.trim()) ?: return null
            val (phase, step, done, total, detail, retry, restarts, capability, grant) = m.destructured
            return HostStatus(
                phase = phase,
                step = step.ifEmpty { null },
                done = done.toLongOrNull() ?: 0,
                total = total.toLongOrNull() ?: 0,
                detail = detail,
                retryable = retry.isNotEmpty(),
                restarts = restarts.toInt(),
                capability = capability.trim(),
                grant = grant.trim(),
            )
        }
    }
}

object CannotHost {
    /** The sentences `evaluateCapability` and the setup's space check (vmhost-api) give for a phone that can't host. */
    private val SENTENCES = listOf(
        Regex("""needs android 14 or newer.*"""),
        Regex("""this phone can't run azula vms.*"""),
        Regex("""needs [\d.]+ GB of ram.*"""),
        Regex("""needs [\d.]+ GB free.*"""),
        Regex("""this android version blocks the vm api azula needs.*"""),
    )

    /**
     * Why the phone can't host, when an ERROR's detail is one of the app's own sentences
     * (HostService may prefix it with "stopped — "). Anything else is a failure, not a verdict
     * on the phone.
     */
    fun fromErrorDetail(detail: String): String? {
        val d = detail.removePrefix("stopped — ")
        return d.takeIf { s -> SENTENCES.any { it.matches(s) } }
    }

    /**
     * Why `pm grant` can't work on this build at all. `null` when [output] is some other failure
     * (a missing package, a manifest that doesn't request the permission): those are ours to fix.
     */
    fun fromPmGrant(permission: String, output: String): String? = when {
        "Unknown permission" in output -> "this android build doesn't define $permission"
        "not a changeable permission type" in output || "is managed by role" in output ->
            "this android build doesn't let adb grant $permission"
        else -> null
    }
}

object DumpsysPackage {
    /**
     * Whether `dumpsys package <pkg>` says each of [permissions] is granted; a permission it never
     * lists maps to null. A permission can be listed more than once (per user), so any
     * `granted=true` wins.
     */
    fun grants(dumpsys: String, permissions: List<String>): Map<String, Boolean?> = permissions.associateWith { p ->
        val all = Regex("""(?m)^\s*${Regex.escape(p)}: granted=(true|false)""").findAll(dumpsys)
            .map { it.groupValues[1] == "true" }.toList()
        if (all.isEmpty()) null else all.any { it }
    }
}

object TokenJson {
    /** The token in MainActivity's token.json (`{"id":"…","token":"azc1.…"}`); null when there is none. */
    fun token(json: String): String? = field(json, "token")

    /** The id of its grant, which the host app's answers name. */
    fun id(json: String): String? = field(json, "id")

    private fun field(json: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
}

object Revoke {
    /**
     * Whether the host app's last line says it revoked grant [id]. `Success(false)` (it has no such
     * grant) and `Failure(…)` (the host didn't answer) don't, nor does another grant's line.
     */
    fun confirmed(line: String, id: String): Boolean = line == "revoke $id -> Success(true)"
}

object Doctor {
    data class Stage(val name: String, val ok: Boolean, val ms: Long, val detail: String)

    private val LINE = Regex("""^([✓✗]) (\w+)\s+(\d+) ms\s+(.*)$""")

    /** The stages in `azula compute doctor`'s text output, in order. */
    fun stages(output: String): List<Stage> = output.lines().mapNotNull { l ->
        LINE.matchEntire(l.trim())?.destructured?.let { (mark, name, ms, detail) ->
            Stage(name, mark == "✓", ms.toLong(), detail)
        }
    }

    enum class Access { ACCEPTED, REFUSED, UNKNOWN }

    /**
     * What the host made of the token, by its answer to hello. UNKNOWN when it gave none: it couldn't
     * be reached, or it failed some other way.
     */
    fun access(output: String): Access {
        val auth = stages(output).lastOrNull { it.name == "auth" } ?: return Access.UNKNOWN
        return when {
            auth.ok -> Access.ACCEPTED
            auth.detail.startsWith("unauthorized") -> Access.REFUSED
            else -> Access.UNKNOWN
        }
    }
}
