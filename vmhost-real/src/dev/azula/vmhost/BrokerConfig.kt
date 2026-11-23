package dev.azula.vmhost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The JSON `AzulaHost.start` takes (field names are the native side's). */
@Serializable
internal data class BrokerConfig(
    val computeDir: String,
    val vmName: String,
    val workerSocket: String,
    val vmCpus: Int,
    val vmMemoryMb: Int,
    val logFile: String,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        /** The broker's name for the VM (what `--vm default` addresses), not AVF's instance name. */
        const val BROKER_VM_NAME = "default"

        fun forHost(filesDir: File, plan: HostPlan) = BrokerConfig(
            computeDir = File(filesDir, "compute").absolutePath,
            vmName = BROKER_VM_NAME,
            workerSocket = File(File(filesDir, "run"), Bridge.SOCKET_NAME).absolutePath,
            vmCpus = plan.cpus,
            vmMemoryMb = plan.memoryMb,
            logFile = File(filesDir, "logs/broker.log").absolutePath,
        )
    }
}
