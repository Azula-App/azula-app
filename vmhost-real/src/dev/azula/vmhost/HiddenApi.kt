package dev.azula.vmhost

import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * The hidden-API exemption that lets the process reach AVF's `@hide`
 * custom-image classes. The exemption is per-process, so it's enabled in the
 * `:vmhost` process when the service starts, and by the capability check in
 * whichever process asks (the UI process, for S1).
 */
internal object HiddenApi {
    @Volatile var lastResult: String = "not run"
        private set

    private const val AVF_BUILDER = "android.system.virtualmachine.VirtualMachineCustomImageConfig\$Builder"

    @Synchronized fun enable(): Boolean {
        val sb = StringBuilder()
        var ok = avfReachable(sb)
        if (!ok) {
            try {
                sb.append(" lsposed=").append(HiddenApiBypass.addHiddenApiExemptions("L"))
            } catch (t: Throwable) {
                sb.append(" lsposed-threw=").append(t)
            }
            ok = avfReachable(sb)
        }
        if (!ok) {
            // Fallback: meta-reflection to reach VMRuntime.setHiddenApiExemptions.
            try {
                val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                    "getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass,
                )
                val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
                val set = getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java),
                ) as Method
                set.invoke(getRuntime.invoke(null), arrayOf<Any>(arrayOf("L")))
                sb.append(" meta-reflection=ok")
            } catch (t: Throwable) {
                sb.append(" meta-reflection-failed=").append(t)
            }
            ok = avfReachable(sb)
        }
        lastResult = "avf-reachable=$ok$sb"
        HostLog.to("host", "hidden-api: $lastResult")
        return ok
    }

    /** Does the real AVF API resolve and its hidden constructor work? */
    private fun avfReachable(sb: StringBuilder): Boolean = try {
        Class.forName(AVF_BUILDER).getDeclaredConstructor().newInstance()
        true
    } catch (t: Throwable) {
        sb.append(" probe=").append(t.javaClass.simpleName)
        false
    }
}
