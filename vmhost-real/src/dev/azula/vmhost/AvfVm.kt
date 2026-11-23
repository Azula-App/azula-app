package dev.azula.vmhost

import android.content.Context
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineManager
import java.io.File

/** Builds the AVF virtual machine: u-boot, a RW root disk, a RO cloud-init seed. */
internal object AvfVm {
    const val VM_NAME = "azula"
    const val U_BOOT = "/apex/com.android.virt/etc/u-boot.bin"
    private const val CPU_TOPOLOGY_MATCH_HOST = 1
    private const val DEBUG_LEVEL_FULL = 1

    /**
     * A fresh VM instance for [plan]. `getOrCreate()` returns an existing instance
     * with its OLD config (for example without the seed disk), so any previous
     * instance is deleted first. Nothing in it is ours to keep: the disk images
     * live in `filesDir/vm`.
     */
    fun recreate(ctx: Context, plan: HostPlan, disk: File, seed: File?): VirtualMachine {
        val image = VirtualMachineCustomImageConfig.Builder()
            .setName(VM_NAME)
            .setBootloaderPath(U_BOOT)
            .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(disk.absolutePath))
        if (seed != null) image.addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(seed.absolutePath))
        val config = VirtualMachineConfig.Builder(ctx)
            .setCustomImageConfig(image.build())
            .setMemoryBytes(plan.memoryMb.toLong() shl 20)
            .setCpuTopology(CPU_TOPOLOGY_MATCH_HOST)
            .setProtectedVm(false)
            .setDebugLevel(DEBUG_LEVEL_FULL)
            .setVmOutputCaptured(true)
            .build()
        val vmm = ctx.getSystemService(VirtualMachineManager::class.java)
            ?: throw IllegalStateException("VirtualMachineManager service unavailable")
        if (vmm.get(VM_NAME) != null) {
            try { vmm.delete(VM_NAME) } catch (e: Throwable) { HostLog.to("host", "delete failed: $e") }
        }
        return vmm.getOrCreate(VM_NAME, config)
    }
}
