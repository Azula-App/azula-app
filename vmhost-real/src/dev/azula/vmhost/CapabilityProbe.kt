package dev.azula.vmhost

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.virtualmachine.VirtualMachineManager

/** Gathers [CapabilityInputs] on the device; the judgement is the pure [evaluateCapability]. */
internal class CapabilityProbe(private val ctx: Context) {
    /**
     * Lifting the hidden-API limits is process-wide, and the Home card runs this at launch on
     * every phone, so a phone that fails anything else never gets that far.
     */
    fun check(): CapabilityResult {
        val cheap = evaluateCapability(publicInputs())
        return if (cheap is CapabilityResult.CanHost) evaluateCapability(inputs()) else cheap
    }

    /** What the public SDK can tell: "might be able to host" until [inputs] says otherwise. */
    private fun publicInputs(): CapabilityInputs {
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val free = runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val sdk = Build.VERSION.SDK_INT
        val avf = sdk >= MIN_SDK_INT && (
            ctx.packageManager.hasSystemFeature(AVF_FEATURE) ||
                prop("ro.boot.hypervisor.vm.supported") == "1" ||
                prop("ro.boot.hypervisor.protected_vm.supported") == "1"
            )
        return CapabilityInputs(sdk, avf, avf, false, mem.totalMem, free, cpus(), true, Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
    }

    fun inputs(): CapabilityInputs {
        val sdk = Build.VERSION.SDK_INT
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val free = runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        // Not a file check: the toolchain keeps native libs uncompressed inside the APK, so
        // nativeLibraryDir is empty. The app ships libazula_host.so for arm64-v8a only, and a
        // device whose primary ABI is arm64 installs that set.
        val libPresent = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        if (sdk < MIN_SDK_INT) {
            // Don't touch AVF classes that don't exist yet.
            return CapabilityInputs(sdk, false, false, false, mem.totalMem, free, cpus(), false, libPresent)
        }
        val hiddenOk = HiddenApi.enable()
        var service = false
        var caps = -1
        try {
            val vmm = ctx.getSystemService(VirtualMachineManager::class.java)
            service = vmm != null
            if (vmm != null) caps = vmm.capabilities
        } catch (t: Throwable) {
            HostLog.to("host", "getCapabilities failed: $t")
        }
        // getCapabilities() can refuse before the VM permissions are granted (S1 runs
        // first), so the boot properties are the fallback, as the design lists both.
        val nonProtected: Boolean
        val protected: Boolean
        if (caps >= 0) {
            nonProtected = caps and VirtualMachineManager.CAPABILITY_NON_PROTECTED_VM != 0
            protected = caps and VirtualMachineManager.CAPABILITY_PROTECTED_VM != 0
        } else {
            nonProtected = prop("ro.boot.hypervisor.vm.supported") == "1"
            protected = prop("ro.boot.hypervisor.protected_vm.supported") == "1"
            service = service || nonProtected || protected
        }
        return CapabilityInputs(sdk, service, nonProtected, protected, mem.totalMem, free, cpus(), hiddenOk, libPresent)
    }

    private companion object {
        /** The platform feature behind AVF; its PackageManager constant is a system API. */
        const val AVF_FEATURE = "android.software.virtualization_framework"
    }

    private fun cpus() = Runtime.getRuntime().availableProcessors()

    private fun prop(name: String): String = try {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Throwable) {
        ""
    }
}
