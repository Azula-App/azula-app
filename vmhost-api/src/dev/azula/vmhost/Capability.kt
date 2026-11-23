package dev.azula.vmhost

/** What hosting would use. Defaults chosen by [evaluateCapability]; S1's "advanced" panel changes them. */
data class HostPlan(
    val cpus: Int,
    val memoryMb: Int,
    val diskGb: Int,
    val downloadMb: Int,
)

/** Why a phone can't host. One plain sentence per reason lives in [CannotHost.message]. */
enum class CannotHostReason {
    ANDROID_TOO_OLD,
    /** The broker library isn't in this install (an ABI other than arm64-v8a). */
    UNSUPPORTED_ABI,
    AVF_UNAVAILABLE,
    PROTECTED_VMS_ONLY,
    LOW_RAM,
    LOW_STORAGE,
    HIDDEN_API_BLOCKED,
}

/** The S1 outcome. Typed so screens render the pass card or the single fail sentence. */
sealed interface CapabilityResult {
    data class CanHost(val plan: HostPlan) : CapabilityResult
    data class CannotHost(val reason: CannotHostReason, val message: String) : CapabilityResult
}

/** Everything the check reads, gathered by the platform (vmhost-real) so [evaluateCapability] stays pure. */
data class CapabilityInputs(
    val sdkInt: Int,
    /** The VirtualMachineManager system service exists. */
    val avfServicePresent: Boolean,
    /** getCapabilities() has CAPABILITY_NON_PROTECTED_VM. */
    val nonProtectedVmSupported: Boolean,
    /** getCapabilities() has CAPABILITY_PROTECTED_VM. */
    val protectedVmSupported: Boolean,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val cpuCount: Int,
    /** The hidden-API exemption took effect (the @hide AVF custom-image API is reachable). */
    val hiddenApiWorks: Boolean,
    /** the device installs the arm64-v8a native libs (libazula_host.so ships for that ABI only) (the app ships it for arm64-v8a only). */
    val hostLibraryPresent: Boolean = true,
)

const val MIN_SDK_INT = 34
const val MIN_RAM_BYTES = 6L shl 30
const val MIN_FREE_BYTES = 20L shl 30
const val DEFAULT_DISK_GB = 16
const val DOWNLOAD_MB = 400
private const val MEMORY_STEP_MB = 256

/**
 * The capability check (design D9 S1). Pure: checks run in the order the user
 * could fix them (OS, hypervisor, RAM, storage), the hidden-API probe last because
 * it is the only one that needs the exemption to have been attempted.
 *
 * The plan defaults: about 50% of RAM (floored to 256 MB), every core, and a disk
 * of `min(16 GiB, 25% of free space)`.
 */
fun evaluateCapability(i: CapabilityInputs): CapabilityResult {
    fun no(reason: CannotHostReason, message: String) = CapabilityResult.CannotHost(reason, message)
    if (i.sdkInt < MIN_SDK_INT) {
        return no(CannotHostReason.ANDROID_TOO_OLD, "needs android 14 or newer · this phone runs android ${androidRelease(i.sdkInt)}")
    }
    if (!i.hostLibraryPresent) {
        return no(CannotHostReason.UNSUPPORTED_ABI, "this phone can't run azula vms — they need a 64-bit arm cpu")
    }
    if (!i.avfServicePresent) {
        return no(CannotHostReason.AVF_UNAVAILABLE, "this phone can't run azula vms — it has no virtualization service")
    }
    if (!i.nonProtectedVmSupported) {
        return if (i.protectedVmSupported) {
            no(CannotHostReason.PROTECTED_VMS_ONLY, "this phone can't run azula vms — its hypervisor only allows protected vms")
        } else {
            no(CannotHostReason.AVF_UNAVAILABLE, "this phone can't run azula vms — its hypervisor doesn't support them")
        }
    }
    if (i.totalRamBytes < MIN_RAM_BYTES) {
        return no(CannotHostReason.LOW_RAM, "needs 6 GB of ram · this phone has ${gb(i.totalRamBytes)}")
    }
    if (i.freeStorageBytes < MIN_FREE_BYTES) {
        return no(CannotHostReason.LOW_STORAGE, "needs 20 GB free · ${gb(i.freeStorageBytes)} free now")
    }
    if (!i.hiddenApiWorks) {
        return no(CannotHostReason.HIDDEN_API_BLOCKED, "this android version blocks the vm api azula needs")
    }
    val memoryMb = ((i.totalRamBytes shr 20) / 2 / MEMORY_STEP_MB * MEMORY_STEP_MB).toInt()
    val diskGb = minOf(DEFAULT_DISK_GB.toLong(), (i.freeStorageBytes shr 30) / 4).toInt()
    return CapabilityResult.CanHost(HostPlan(i.cpuCount.coerceAtLeast(1), memoryMb, diskGb, DOWNLOAD_MB))
}

private fun gb(bytes: Long): String {
    val tenths = (bytes * 10 + (1L shl 29)) shr 30
    return "${tenths / 10}.${tenths % 10} GB".replace(".0 GB", " GB")
}

private fun androidRelease(sdk: Int): String = when {
    sdk >= 33 -> "13"
    sdk == 32 -> "12L"
    sdk == 31 -> "12"
    sdk == 30 -> "11"
    sdk == 29 -> "10"
    else -> "9 or older"
}
