package app.azula.vmhost.ftl

/**
 * How one step ended. PASS_THROTTLED is a pass: the relay held its traffic back, which is why it was
 * slow. TIMED_OUT used up its time, which says nothing about the answer it would have given.
 */
enum class StepOutcome(val passed: Boolean) {
    PASS(true), PASS_THROTTLED(true), FAIL(false), TIMED_OUT(false), NOT_RUN(false)
}

/** A step that wasn't done, as opposed to one that was done and failed. */
class NotRunException(val reason: String) : Exception(reason)

/** Something in a step used up its time. */
class TimedOutException(message: String) : IllegalStateException(message)

/**
 * The named checks of one stage, each with its own outcome and time. A step that fails, or can't be
 * run, never stops the next one. Pure (time and scrubbing come in), so it is unit-tested.
 *
 * [elapsed] is seconds since the run started and [deadline] is on the same clock: a step that would
 * start with less than [minSeconds] left isn't run.
 */
class Steps(
    private val elapsed: () -> Double,
    private val deadline: Double,
    private val minSeconds: Double = 30.0,
    private val clean: (String) -> String = { it },
    private val log: (String) -> Unit = {},
) {
    /** [throttledBy]: who the relay said it was throttling meanwhile. Set whatever the outcome. */
    data class Step(
        val name: String,
        val covers: String,
        val outcome: StepOutcome,
        val detail: String,
        val seconds: Double = 0.0,
        val throttledBy: List<String> = emptyList(),
    )

    private val done = LinkedHashMap<String, Step>()
    private var stepEnd = deadline

    fun all(): List<Step> = done.values.toList()

    fun outcomeOf(name: String): StepOutcome = done[name]?.outcome ?: StepOutcome.NOT_RUN

    /** How many seconds [what] may take: [cap], or what the step or the run has left if that is less. */
    fun limit(what: String, cap: Long): Long {
        val left = minOf(stepEnd, deadline) - elapsed()
        if (left < 1) throw TimedOutException("no time left for $what")
        return minOf(cap, left.toLong())
    }

    /** Runs [body], which has [maxSeconds]; whether [throttledBy] names anyone is asked afterwards. */
    fun run(name: String, covers: String, maxSeconds: Double, throttledBy: () -> List<String> = { emptyList() }, body: () -> String) {
        val t = elapsed()
        if (deadline - t < minSeconds) {
            record(Step(name, covers, StepOutcome.NOT_RUN, "out of time: %.0f s of the run's time left".format((deadline - t).coerceAtLeast(0.0))))
            return
        }
        stepEnd = t + maxSeconds
        val (outcome, detail) = try {
            StepOutcome.PASS to body()
        } catch (e: NotRunException) {
            StepOutcome.NOT_RUN to e.reason
        } catch (e: TimedOutException) {
            StepOutcome.TIMED_OUT to e.message.orEmpty()
        } catch (e: Throwable) {
            StepOutcome.FAIL to (e.message ?: e.toString())
        }
        val by = runCatching(throttledBy).getOrDefault(emptyList())
        val slow = outcome == StepOutcome.PASS && by.isNotEmpty()
        record(Step(name, covers, if (slow) StepOutcome.PASS_THROTTLED else outcome, clean(detail), elapsed() - t, by))
    }

    /**
     * Why the stage didn't pass, or null when it did: every step of [expected] must have passed, so
     * one that was never recorded counts against it as much as one that failed.
     */
    fun problem(expected: List<String>): String? {
        val bad = (done.values + expected.filter { it !in done }.map { Step(it, "", StepOutcome.NOT_RUN, "never reached") })
            .filterNot { it.outcome.passed }
        // By outcome, whatever the outcome: one that isn't a pass can't go unsaid.
        return bad.groupBy { it.outcome }.toSortedMap().map { (outcome, steps) ->
            val said = when (outcome) {
                StepOutcome.FAIL -> "failed"
                StepOutcome.TIMED_OUT -> "timed out"
                StepOutcome.NOT_RUN -> "not run"
                else -> "ended $outcome"
            }
            "${steps.size} of ${expected.size} steps $said: " + steps.joinToString("; ") { "${it.name} (${it.detail})" }
        }.joinToString(". ").ifEmpty { null }
    }

    private fun record(s: Step) {
        done[s.name] = s
        val by = if (s.throttledBy.isEmpty()) "" else " (the relay throttled: ${s.throttledBy.joinToString()})"
        log("step ${s.name}: ${s.outcome} in ${"%.1f".format(s.seconds)} s$by: ${s.detail}")
    }
}
