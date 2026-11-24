package dev.azula.vmhost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What S1's "advanced" panel changes: memory and disk for the guest. A null field keeps the
 * default [evaluateCapability] chose. Persisted by the UI process and read by `:vmhost` when it
 * sizes the VM and the disk, so it must be set before [VmHost.provision].
 */
@Serializable
data class PlanOverride(val memoryMb: Int? = null, val diskGb: Int? = null) {
    /**
     * The plan with this override. Memory stays between [MIN_PLAN_MEMORY_MB] and [maxMemoryMb]
     * of the plan's own, however the override was stored: it outlives the panel that made it,
     * and could come from an older build or another phone's backup.
     */
    fun applyTo(plan: HostPlan): HostPlan = plan.copy(
        memoryMb = (memoryMb ?: plan.memoryMb)
            .coerceIn(MIN_PLAN_MEMORY_MB, maxOf(MIN_PLAN_MEMORY_MB, maxMemoryMb(plan.memoryMb))),
        diskGb = diskGb ?: plan.diskGb,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(o: PlanOverride): String = json.encodeToString(serializer(), o)
        fun decode(text: String): PlanOverride? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** The most memory the guest may have when the default is [defaultMb] (half the phone's RAM): the "advanced" stepper's ceiling. */
fun maxMemoryMb(defaultMb: Int): Int = defaultMb * 3 / 2

/** Below this the guest doesn't boot its tools. */
const val MIN_PLAN_MEMORY_MB = 1024
