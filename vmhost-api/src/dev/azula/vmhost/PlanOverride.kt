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
    fun applyTo(plan: HostPlan): HostPlan =
        plan.copy(memoryMb = memoryMb ?: plan.memoryMb, diskGb = diskGb ?: plan.diskGb)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(o: PlanOverride): String = json.encodeToString(serializer(), o)
        fun decode(text: String): PlanOverride? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
