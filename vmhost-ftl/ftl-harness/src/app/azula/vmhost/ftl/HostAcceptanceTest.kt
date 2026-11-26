package app.azula.vmhost.ftl

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.AssumptionViolatedException
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Proves the on-phone VM host on a real device, in one instrumentation run (task 5.3 of
 * compute-android-app-host). Five stages, in order, each its own test case:
 *
 * 1. the static arm64 `azula` runs from the shell;
 * 2. the VM permissions reach the host app, and only the host app, and outlive it;
 * 3. Start brings the VM to RUNNING;
 * 4. `azula compute doctor` and `exec` reach it over the relay with a minted token;
 * 5. the steps of azula-cli's `vm/e2e/cloud.sh` pass over the relay, each checked on its own.
 *
 * A stage passes, fails, or finds that the device can't host ([CannotHostException]). A device that
 * can't host is an assumption failure (skipped, not failed) unless the `requireHost` argument is
 * true. Later stages are skipped with the reason. result.json and the instrumentation results
 * (`vmhost_outcome`, `vmhost_reason`) carry the verdict either way. See ../../README.md.
 *
 * Arguments (`-e`, or Test Lab's --environment-variables): `bootTimeoutMin` (15), `runLimitMin` (34),
 * `requireHost` (false), `azulaPath` (/data/local/tmp/azula), `outDir` or `additionalTestOutputDir`.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HostAcceptanceTest {

    @Test fun t1_azulaRunsFromTheShell() = stage("azula") {
        val ls = shell.run("ls -lZ $azulaPath")
        check(ls.ok) { "no azula at $azulaPath: push it there (Test Lab: --other-files). ${ls.all}" }
        // Test Lab's push drops the exec bit, and may leave the file owned by root: run a copy.
        val cp = shell.run("mkdir -p $RUN/home && cp -f $azulaPath $RUN/azula && chmod 755 $RUN/azula", 60_000)
        check(cp.ok) { "couldn't make $azulaPath runnable: ${cp.all}" }
        val v = azula("--version", 20)
        info("azula").put("pushedAs", ls.stdout.trim()).put("version", v.stdout.trim())
        check(v.ok && v.stdout.startsWith("azula ")) { "azula --version exited ${v.code}: ${v.all}" }
        "${v.stdout.trim()} runs as ${shell.run("id -un; id -Z").stdout.trim().replace('\n', ' ')}"
    }

    @Test fun t2_grantsReachTheHostOnly() = stage("grants") {
        check(host.installed()) { "${HostApp.PACKAGE} isn't installed (Test Lab: --additional-apks)" }
        val g = info("grants")
        val before = host.grants()
        g.put("before", JSONObject(before))
        // An install with -g (Test Lab's default) already grants development permissions, the VM
        // ones included. Start without them, or the grants below would prove nothing.
        val preGranted = HostApp.VM_PERMISSIONS.filter { before[it] == true }
        if (preGranted.isNotEmpty()) {
            for (p in preGranted) report.log("pm revoke $p: ${shell.run("pm revoke ${HostApp.PACKAGE} $p").let { "exit ${it.code} ${it.all}" }}")
            g.put("afterRevoke", JSONObject(host.grants()))
        }

        // Notifications first, so the app's own request dialog doesn't cover it when it starts.
        grant(HostApp.POST_NOTIFICATIONS)
        // Grant with the app running, as a person does: it shows what the grant does to its processes.
        host.open()
        val pid = host.uiPid()
        for (p in HostApp.VM_PERMISSIONS) grant(p)
        SystemClock.sleep(2_000)
        val after = host.uiPid()
        g.put("hostPidBeforeGrant", pid).put("hostPidAfterGrant", after ?: "none")
            .put("hostKilledByGrant", pid != null && pid != after)
        report.log("host ui pid before the grant: $pid, after: ${after ?: "none"}")

        val granted = host.grants()
        g.put("after", JSONObject(granted))
        val missing = HostApp.PERMISSIONS.filter { granted[it] != true }
        check(missing.isEmpty()) { "dumpsys doesn't show these granted to ${HostApp.PACKAGE}: $missing" }

        // Not by accident to the instrumented packages too: those must outlive the gid kill.
        for (other in listOf(HostApp.STUB, HostApp.HARNESS)) {
            val held = host.grants(other).filterKeys { it in HostApp.VM_PERMISSIONS }.filterValues { it == true }.keys
            g.put(other, JSONArray(held.toList()))
            check(held.isEmpty()) { "$other holds $held" }
        }

        host.forceStop()
        val survived = host.grants()
        g.put("afterForceStop", JSONObject(survived))
        check(HostApp.PERMISSIONS.all { survived[it] == true }) { "lost by a force-stop: $survived" }
        "granted to ${HostApp.PACKAGE} only; the grant ${if (pid != null && pid != after) "killed" else "didn't kill"} its running process"
    }

    @Test fun t3_hostReachesRunning() = stage("boot", needs = listOf("grants")) {
        val h = info("host")
        host.forceStop()
        val opened = host.open()
        h.put("capability", opened.capability)
        report.log("capability: ${opened.capability}")
        report.mark(opened)
        report.screenshot("1-opened")

        // A phone that says it can't host still gets its Start tapped: the host must give the same
        // answer, and a RUNNING host would prove the check wrong.
        val started = SystemClock.elapsedRealtime()
        host.tap("start")
        val limitMs = if (opened.cannotHost != null) CANNOT_HOST_WAIT_MS else bootTimeoutMs
        val end = watch(started, limitMs, opened)
        report.screenshot("2-after-start")
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        h.put("finalPhase", end.phase).put("finalDetail", end.detail).put("restarts", end.restarts)

        if (end.phase == "RUNNING") {
            h.put("secondsToRunning", seconds)
            val held = host.grants()
            check(HostApp.VM_PERMISSIONS.all { held[it] == true }) { "running, but the grants are gone: $held" }
            return@stage "RUNNING %.1f s after Start".format(seconds)
        }
        opened.cannotHost?.let { throw CannotHostException(it) }
        if (end.phase == "ERROR") {
            CannotHost.fromErrorDetail(end.detail)?.let { throw CannotHostException(it) }
            error("the host stopped with an error after %.0f s: %s".format(seconds, end.detail.ifEmpty { "(no detail)" }))
        }
        error("not RUNNING after %.0f s; it was still ${end.key}".format(seconds))
    }

    @Test fun t4_doctorAndExecOverTheRelay() = stage("doctor", needs = listOf("azula", "grants", "boot")) {
        val a = info("azula")
        withToken(a) { token ->
            // The first attempt can beat the host's relay address to the discovery service.
            var doctor: Shell.Result? = null
            val log = StringBuilder()
            for (attempt in 1..DOCTOR_ATTEMPTS) {
                if (attempt > 1) SystemClock.sleep(10_000)
                val r = azula("compute doctor --vm", 150, token)
                doctor = r
                log.append("attempt $attempt: exit ${r.code}\n${r.all}\n\n")
                report.log("doctor attempt $attempt: exit ${r.code}\n${r.all}")
                if (r.ok) break
            }
            report.write("azula-doctor.txt", log.toString())
            val d = doctor!!
            a.put("doctorExit", d.code)
            a.put("doctorStages", JSONArray(Doctor.stages(d.stdout).map { JSONObject().put("stage", it.name).put("ok", it.ok).put("ms", it.ms) }))
            check(d.ok) {
                val bad = Doctor.stages(d.stdout).firstOrNull { !it.ok }
                "azula compute doctor exited ${d.code}${bad?.let { " at ${it.name}: ${it.detail}" }.orEmpty()}"
            }

            val e = azula("compute exec -- uname -a", 120, token)
            report.write("azula-exec.txt", "exit ${e.code}\n${e.all}\n")
            report.log("exec: exit ${e.code}\n${e.all}")
            a.put("execExit", e.code).put("uname", report.scrub.clean(e.stdout.trim()))
            check(e.ok && "Linux" in e.stdout && "aarch64" in e.stdout) { "compute exec -- uname -a exited ${e.code}: ${e.all}" }
            "doctor passed every stage (${Doctor.stages(d.stdout).joinToString { it.name }}); ${e.stdout.trim()}"
        }
    }

    @Test fun t5_cloudStepsOverTheRelay() = stage("cloud", needs = listOf("azula", "grants", "boot", "doctor")) {
        val c = info("cloud")
        withToken(c) { token ->
            val steps = Steps(report::elapsedSeconds, runLimitS, clean = report.scrub::clean, log = report::log)
            val cloud = CloudSteps(shell, steps, "$RUN/azula", "$RUN/cloud", host::throttleWarnings) { script, seconds -> client(script, seconds, token) }
            try {
                cloud.runAll()
            } finally {
                c.put(
                    "steps",
                    JSONArray(CloudChecks.STEPS.map { (name, spec) ->
                        val s = steps.all().firstOrNull { it.name == name }
                        JSONObject().put("step", name).put("covers", spec.covers).put("outcome", steps.outcomeOf(name).name)
                            .put("seconds", s?.seconds ?: 0.0).put("maxSeconds", spec.maxSeconds)
                            .put("throttledBy", JSONArray(s?.throttledBy.orEmpty()))
                            .put("detail", s?.detail ?: "never reached")
                    }),
                )
                c.put("hostThrottleWarnings", host.throttleWarnings())
                report.write("azula-cloud.txt", cloud.transcript())
            }
            steps.problem(CloudChecks.STEPS.keys.toList())?.let { error(it) }
            val slow = steps.all().filter { it.outcome == StepOutcome.PASS_THROTTLED }.joinToString { it.name }
            "all ${CloudChecks.STEPS.size} steps of cloud.sh passed in ${steps.all().sumOf { it.seconds }.toInt()} s" +
                (if (slow.isEmpty()) "" else "; the relay throttled $slow")
        }
    }

    /**
     * Mints a token in the host app and gives it to [body]. Whatever happens, the grant is revoked and
     * token.json deleted afterwards, and a token that may still be alive fails the stage even when
     * [body] passed. What became of it goes into [out].
     */
    private fun withToken(out: JSONObject, body: (String) -> String): String {
        shell.run("run-as ${HostApp.PACKAGE} rm -f files/token.json")
        var minted: Pair<String, String>? = null
        val result = runCatching {
            val before = host.status()?.grant
            host.tap("mint")
            val said = host.await(45_000) { it.grant != before && (it.grant.startsWith("grant ") || it.grant.startsWith("mint failed")) }?.grant.orEmpty()
            val (id, token) = host.mintedToken() ?: error(if (said.startsWith("grant ")) "Mint left no token.json" else "Mint said: ${said.ifEmpty { "nothing" }}")
            report.scrub.add(token)
            minted = id to token
            body(token)
        }
        val alive = runCatching { kill(minted, out) }.getOrElse { "couldn't revoke the token: ${it.message}" }
        if (alive == null) return result.getOrThrow()
        val failed = result.exceptionOrNull() ?: error(alive)
        throw IllegalStateException("${failed.message ?: failed}. And $alive", failed)
    }

    /**
     * Revokes the grant and deletes the file its token came in. Null when that worked, or why the
     * token may still be alive: the app must say it revoked this grant, the file must be gone, and
     * the host must not take the token any more. A host that can't be reached can't take it either,
     * so an unanswered last check is only recorded.
     */
    private fun kill(known: Pair<String, String>?, out: JSONObject): String? {
        // A Mint that answered too late still left a token.
        val (id, token) = known ?: host.mintedToken() ?: return null.also { out.put("revoked", "nothing was minted") }
        report.scrub.add(token)
        // Nothing touched the screen while the stage ran.
        wake()
        var said = ""
        for (attempt in 1..REVOKE_ATTEMPTS) {
            host.launch("revoke")
            // Past the first attempt, its answer is still on screen for a moment.
            if (attempt > 1) SystemClock.sleep(3_000)
            said = host.await(20_000) { it.grant.startsWith("revoke $id ") || it.grant.startsWith("revoke:") }?.grant.orEmpty()
            if (Revoke.confirmed(said, id)) break
        }
        // Only now: the app takes the grant's id from this file.
        shell.run("run-as ${HostApp.PACKAGE} rm -f files/token.json")
        val deleted = host.lacks("files/token.json")

        var access = Doctor.Access.UNKNOWN
        for (attempt in 1..REFUSAL_ATTEMPTS) {
            if (attempt > 1) SystemClock.sleep(3_000)
            access = Doctor.access(azula("compute doctor", 30, token).stdout)
            if (access == Doctor.Access.REFUSED) break
        }
        out.put("revoked", said).put("tokenFileDeleted", deleted).put("afterRevoke", access.name)
        report.log("token: $said; token.json deleted: $deleted; the host then: $access")
        return listOfNotNull(
            "the host app didn't confirm the revoke of $id, it said '$said'".takeUnless { Revoke.confirmed(said, id) },
            "token.json is still there".takeUnless { deleted },
            "the host still accepts the token after the revoke".takeIf { access == Doctor.Access.ACCEPTED },
        ).takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    private fun grant(permission: String) {
        val r = shell.run("pm grant ${HostApp.PACKAGE} $permission")
        report.log("pm grant $permission: exit ${r.code} ${r.all.lines().first()}")
        if (r.ok) return
        CannotHost.fromPmGrant(permission, r.all)?.let { throw CannotHostException(it) }
        error("pm grant ${HostApp.PACKAGE} $permission exited ${r.code}: ${r.all}")
    }

    /**
     * Follows the status line to RUNNING, a final ERROR, or the deadline, retrying what the app says
     * is retryable. [before] is what the line said before Start: an ERROR it still shows just after
     * a tap is the one from before the tap.
     */
    private fun watch(started: Long, limitMs: Long, before: HostStatus): HostStatus {
        val phases = info("host").let { h -> JSONArray().also { h.put("phases", it) } }
        var last = HostStatus("STOPPED", null, 0, 0, "", false, 0, "", "")
        var phaseAt = started
        var pct = -1L
        var retries = 0
        var seenAt = SystemClock.elapsedRealtime()
        var staleKey = before.key
        var staleUntil = started + STALE_MS
        fun closePhase(now: Long) {
            if (now > phaseAt) phases.put(
                JSONObject().put("phase", last.phase).put("step", last.step ?: "").put("detail", last.detail)
                    .put("seconds", (now - phaseAt) / 1000.0),
            )
            phaseAt = now
        }
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val s = host.status()
            if (s == null) {
                // A crash, a dialog, the screen: bring the app back and keep watching the state it reports.
                if (now - seenAt > LOST_MS) {
                    report.log("the host's status line has been gone ${(now - seenAt) / 1000} s; processes: ${host.processes()}; reopening")
                    report.screenshot("lost-${now / 1000}")
                    wake()
                    host.launch()
                    seenAt = now
                }
            } else {
                seenAt = now
                val p = if (s.total > 0) s.done * 10 / s.total else -1
                if (s.key != last.key) {
                    closePhase(now)
                    report.mark(s)
                    last = s
                    pct = p
                } else if (p != pct) {
                    report.mark(s)
                    pct = p
                }
                val stale = s.phase == "ERROR" && s.key == staleKey && now < staleUntil
                when {
                    stale -> {}
                    s.phase == "RUNNING" -> { closePhase(now); return s }
                    s.phase == "ERROR" && CannotHost.fromErrorDetail(s.detail) == null && s.retryable && retries < MAX_RETRIES -> {
                        retries++
                        info("host").put("retries", retries)
                        report.log("retryable error, retry $retries of $MAX_RETRIES: ${s.detail}")
                        SystemClock.sleep(5_000)
                        host.tap("start")
                        staleKey = s.key
                        staleUntil = SystemClock.elapsedRealtime() + STALE_MS
                    }
                    s.phase == "ERROR" -> { closePhase(now); return s }
                }
            }
            if (now - started >= limitMs) { closePhase(now); return last }
            SystemClock.sleep(HostApp.POLL_MS)
        }
    }

    companion object {
        /** Test Lab's --other-files may write here, and the shell may exec from here (not from /sdcard). */
        private const val RUN = "/data/local/tmp/vmhost-ftl-run"
        private const val CANNOT_HOST_WAIT_MS = 60_000L
        private const val LOST_MS = 15_000L
        private const val STALE_MS = 8_000L
        private const val MAX_RETRIES = 2
        private const val DOCTOR_ATTEMPTS = 3
        private const val REVOKE_ATTEMPTS = 2
        private const val REFUSAL_ATTEMPTS = 2
        private val STAGES = listOf("azula", "grants", "boot", "doctor", "cloud")

        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val args: Bundle = InstrumentationRegistry.getArguments()
        private val azulaPath = args.getString("azulaPath", "/data/local/tmp/azula")
        private val bootTimeoutMs = args.getString("bootTimeoutMin", "15").toLong() * 60_000

        /** When the last stage stops starting things: what follows it needs a few minutes more. */
        private val runLimitS = args.getString("runLimitMin", "34").toDouble() * 60
        private val requireHost = args.getString("requireHost", "false").toBoolean()

        private val ui = UiDevice.getInstance(instrumentation)
        private val shell = Shell(instrumentation.uiAutomation)
        private val report = Report(
            shell, Scrub(),
            args.getString("outDir") ?: args.getString("additionalTestOutputDir") ?: "/sdcard/Download/vmhost-ftl",
        )
        private val host = HostApp(ui, shell, report)
        private val device = DeviceInfo(shell, report)
        private val verdict = Verdict()
        private var stayOn = ""

        private fun info(section: String): JSONObject =
            report.result.optJSONObject(section) ?: JSONObject().also { report.result.put(section, it) }

        private fun wake() {
            shell.run("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
        }

        /** `azula <command>` from the shell, relay-only, for at most [seconds]. */
        private fun azula(command: String, seconds: Long, token: String? = null): Shell.Result =
            client("timeout -k 5 $seconds $RUN/azula $command", seconds, token)

        /**
         * A script that runs azula, relay-only, given [seconds] plus some slack; it must bound its own
         * commands. The token goes through the script's stdin into the environment: not into argv, a
         * file or the log.
         */
        private fun client(script: String, seconds: Long, token: String? = null): Shell.Result = shell.run(
            "export HOME=$RUN/home TMPDIR=$RUN AZULA_RELAY_ONLY=1 NO_COLOR=1\n" +
                (if (token == null) "" else "export AZULA_COMPUTE_TOKEN='$token'\n") + script,
            (seconds + 15) * 1000,
        )

        /** Runs one stage: records how it ended, and turns that into pass, skip or fail for JUnit. */
        private fun stage(name: String, needs: List<String> = emptyList(), body: () -> String) {
            val unmet = needs.firstOrNull { verdict.outcomeOf(it) != Outcome.PASS }
            if (unmet != null) {
                val why = verdict.stages().firstOrNull { it.name == unmet }
                verdict.record(name, Outcome.NOT_RUN, "needs '$unmet', which ended ${verdict.outcomeOf(unmet)}")
                report.log("stage $name: not run (needs $unmet)")
                throw AssumptionViolatedException("not run: $unmet ended ${verdict.outcomeOf(unmet)}${why?.let { ": ${it.reason}" }.orEmpty()}")
            }
            val t = SystemClock.elapsedRealtime()
            fun seconds() = (SystemClock.elapsedRealtime() - t) / 1000.0
            report.log("stage $name: start")
            try {
                val detail = report.scrub.clean(body())
                verdict.record(name, Outcome.PASS, detail, seconds())
                report.log("stage $name: PASS: $detail")
            } catch (e: CannotHostException) {
                val why = "CANNOT_HOST: ${report.scrub.clean(e.reason)}"
                verdict.record(name, Outcome.CANNOT_HOST, report.scrub.clean(e.reason), seconds())
                report.log("stage $name: $why")
                if (requireHost) throw AssertionError(why) else throw AssumptionViolatedException(why)
            } catch (e: Throwable) {
                // Rethrown without the original: its message may hold something only the scrubbed copy may carry.
                val why = report.scrub.clean(e.message ?: e.toString())
                verdict.record(name, Outcome.FAIL, why, seconds())
                report.log("stage $name: FAIL: $why\n${e.stackTraceToString()}")
                throw AssertionError("FAILED at $name: $why").apply { stackTrace = e.stackTrace }
            }
        }

        @BeforeClass @JvmStatic fun prepare() {
            // The status line keeps changing while setup downloads; waiting for the UI to go idle
            // before each look would skip whole phases.
            Configurator.getInstance().waitForIdleTimeout = 0
            report.open()
            stayOn = shell.run("settings get global stay_on_while_plugged_in").stdout.trim()
            shell.run("svc power stayon true")
            wake()
            report.result.put("schema", 1)
            report.result.put("device", device.describe())
            device.save("before")
        }

        @AfterClass @JvmStatic fun collect() {
            runCatching { device.save("after") }
            runCatching { device.hostLogs(host) }
            // Each stage kills its own token. A file here is one that a stage couldn't: try again, and
            // delete it whatever comes of that. Before Stop, because only a running host can revoke.
            runCatching {
                host.mintedToken()?.let { (_, token) ->
                    report.scrub.add(token)
                    host.launch("revoke")
                    val said = host.await(20_000) { it.grant.startsWith("revoke") }?.grant.orEmpty()
                    info("host").put("revokedAtEnd", said)
                    report.log("a token was left over: $said")
                }
            }
            runCatching {
                if (host.status()?.phase !in listOf(null, "STOPPED", "ERROR")) {
                    host.tap("stop")
                    val s = host.await(30_000) { it.phase == "STOPPED" }
                    info("host").put("stopped", s?.phase == "STOPPED")
                }
            }
            runCatching {
                shell.run("run-as ${HostApp.PACKAGE} rm -f files/token.json; rm -rf $RUN")
                if (stayOn.toIntOrNull() != null) shell.run("settings put global stay_on_while_plugged_in $stayOn")
            }
            val overall = verdict.overall(STAGES)
            val why = verdict.decisive(STAGES)
            report.result.put("outcome", overall.name)
                .put("stage", why?.name ?: "")
                .put("reason", why?.reason ?: "")
                .put("seconds", report.elapsedSeconds())
                .put(
                    "stages",
                    JSONArray(STAGES.map { n ->
                        val s = verdict.stages().firstOrNull { it.name == n }
                        JSONObject().put("stage", n).put("outcome", verdict.outcomeOf(n).name)
                            .put("seconds", s?.seconds ?: 0.0).put("detail", s?.reason ?: "")
                    }),
                )
            runCatching { report.finish() }.onFailure { report.log("couldn't write the report: $it") }
            report.log("RESULT $overall${why?.let { " at ${it.name}: ${it.reason}" }.orEmpty()}")
            instrumentation.addResults(
                Bundle().apply {
                    putString("vmhost_outcome", overall.name)
                    putString("vmhost_stage", why?.name ?: "")
                    putString("vmhost_reason", why?.reason ?: "")
                },
            )
        }
    }
}
