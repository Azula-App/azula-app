package dev.azula.vmhost

import java.io.File

/** The [PlanOverride] file in the state directory: written by the UI process, read by `:vmhost`. */
internal object PlanOverrideFile {
    private const val NAME = "plan-override.json"

    fun write(dir: File, o: PlanOverride) {
        val tmp = File(dir, "$NAME.tmp")
        tmp.writeText(PlanOverride.encode(o))
        tmp.renameTo(File(dir, NAME))
    }

    fun read(dir: File): PlanOverride =
        runCatching { PlanOverride.decode(File(dir, NAME).readText()) }.getOrNull() ?: PlanOverride()
}
