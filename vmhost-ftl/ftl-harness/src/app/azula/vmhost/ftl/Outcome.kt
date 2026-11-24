package app.azula.vmhost.ftl

/** This device can't host: a finding about the device, not a failure of the host. */
class CannotHostException(val reason: String) : Exception(reason)

enum class Outcome { NOT_RUN, PASS, CANNOT_HOST, FAIL }

/** One stage's result, and the run's verdict over all of them. Pure, so it is unit-tested. */
class Verdict {
    data class Stage(val name: String, val outcome: Outcome, val reason: String, val seconds: Double)

    private val stages = LinkedHashMap<String, Stage>()

    fun record(name: String, outcome: Outcome, reason: String = "", seconds: Double = 0.0) {
        stages[name] = Stage(name, outcome, reason, seconds)
    }

    fun stages(): List<Stage> = stages.values.toList()

    fun outcomeOf(name: String): Outcome = stages[name]?.outcome ?: Outcome.NOT_RUN

    /**
     * PASS needs every stage of [expected] to have passed. A failure outranks "can't host", so a
     * broken run is never read as a verdict on the phone.
     */
    fun overall(expected: List<String>): Outcome = when {
        stages.values.any { it.outcome == Outcome.FAIL } -> Outcome.FAIL
        stages.values.any { it.outcome == Outcome.CANNOT_HOST } -> Outcome.CANNOT_HOST
        expected.all { outcomeOf(it) == Outcome.PASS } -> Outcome.PASS
        else -> Outcome.FAIL
    }

    /** The stage that decided [overall], if one did. */
    fun decisive(): Stage? = stages.values.firstOrNull { it.outcome == Outcome.FAIL }
        ?: stages.values.firstOrNull { it.outcome == Outcome.CANNOT_HOST }
}
