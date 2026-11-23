package app.azula.vmhost

/**
 * JNI surface of `libazula_host.so` (azula-cli's `android-host-jni` crate): the
 * attach-mode compute broker, run in-process.
 *
 * **Do not rename or move this object or its methods.** The library's exported
 * symbols are `Java_app_azula_vmhost_AzulaHost_*`; the package, the object name and
 * the method names are the binding. `@JvmStatic` makes them real static methods
 * on the `AzulaHost` class, which is what those symbols implement.
 */
object AzulaHost {
    init {
        System.loadLibrary("azula_host")
    }

    /**
     * Starts the broker on a background thread. [configJson]:
     * `{computeDir, vmName, workerSocket, vmCpus, vmMemoryMb, logFile}`.
     * Returns `""` once the broker is serving, else an error message.
     */
    @JvmStatic external fun start(configJson: String): String

    /** Graceful shutdown; returns when the broker has stopped. */
    @JvmStatic external fun stop()

    /** Same JSON as `azula compute grant --json`, or `{"error": …}`. Needs a prior [start]. */
    @JvmStatic external fun mintGrant(ttl: String, label: String): String

    /** JSON array of grants (no secrets), or `{"error": …}`. */
    @JvmStatic external fun listGrants(): String

    /** True if a grant with this id existed. */
    @JvmStatic external fun revoke(id: String): Boolean
}
