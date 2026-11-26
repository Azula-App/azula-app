package app.azula.vmhost.ftl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private fun status(text: String) = HostStatus.parse(text).also { assertNotNull(text, it) }!!

    @Test fun statusWhenIdle() {
        val s = status("state: STOPPED · restarts 0\ncapability: can host · 8 cores · 5888 MB · 16 GB disk\n")
        assertEquals("STOPPED", s.phase)
        assertEquals(null, s.step)
        assertEquals("can host · 8 cores · 5888 MB · 16 GB disk", s.capability)
        assertNull(s.cannotHost)
        assertEquals("", s.grant)
    }

    @Test fun statusBeforeTheCapabilityCheck() {
        assertEquals("", status("state: STOPPED · restarts 0\ncapability: \n").capability)
    }

    @Test fun statusWhileDownloading() {
        val s = status("state: PROVISIONING · DOWNLOADING 1024/300000000 (a (nested) detail · more) [retry] · restarts 1\ncapability: can host\ngrant 12 -> token.json")
        assertEquals("DOWNLOADING", s.step)
        assertEquals(1024L, s.done)
        assertEquals(300000000L, s.total)
        assertEquals("a (nested) detail · more", s.detail)
        assertTrue(s.retryable)
        assertEquals(1, s.restarts)
        assertEquals("grant 12 -> token.json", s.grant)
        assertEquals("PROVISIONING / DOWNLOADING / a (nested) detail · more", s.key)
    }

    @Test fun statusWithAMultiLineDetail() {
        val s = status("state: ERROR (stopped — boom\nat line 2) · restarts 0\ncapability: can host\nmint failed: x")
        assertEquals("stopped — boom\nat line 2", s.detail)
        assertEquals("mint failed: x", s.grant)
        assertFalse(s.retryable)
    }

    @Test fun statusThatCannotHost() {
        val s = status("state: STOPPED · restarts 0\ncapability: cannot host: this phone can't run azula vms — it has no virtualization service\n")
        assertEquals("this phone can't run azula vms — it has no virtualization service", s.cannotHost)
    }

    @Test fun otherTextIsNotAStatus() {
        assertNull(HostStatus.parse("Start"))
        assertNull(HostStatus.parse("state: RUNNING"))
    }

    @Test fun errorDetailsThatMeanThePhoneCantHost() {
        assertEquals("needs 6 GB of ram · this phone has 2 GB", CannotHost.fromErrorDetail("needs 6 GB of ram · this phone has 2 GB"))
        assertEquals("needs 4.3 GB free · 1 GB free now", CannotHost.fromErrorDetail("stopped — needs 4.3 GB free · 1 GB free now"))
        assertNotNull(CannotHost.fromErrorDetail("this android version blocks the vm api azula needs"))
        assertNull(CannotHost.fromErrorDetail("stopped — vm error 3: out of memory"))
        assertNull(CannotHost.fromErrorDetail(""))
    }

    @Test fun pmGrantFailuresThatMeanThePhoneCantHost() {
        val p = "android.permission.MANAGE_VIRTUAL_MACHINE"
        assertEquals("this android build doesn't define $p", CannotHost.fromPmGrant(p, "java.lang.IllegalArgumentException: Unknown permission: $p"))
        assertNotNull(CannotHost.fromPmGrant(p, "SecurityException: Permission $p is not a changeable permission type"))
        assertNull(CannotHost.fromPmGrant(p, "java.lang.SecurityException: Package app.x has not requested permission $p"))
        assertNull(CannotHost.fromPmGrant(p, "Unknown package: app.x"))
    }

    @Test fun grantsFromDumpsys() {
        val dump = """
            install permissions:
              android.permission.INTERNET: granted=true
              android.permission.MANAGE_VIRTUAL_MACHINE: granted=true
              android.permission.USE_CUSTOM_VIRTUAL_MACHINE: granted=false
            User 0: ceDataInode=1
              runtime permissions:
                android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED]
            requested permissions:
              android.permission.WAKE_LOCK
        """.trimIndent()
        val m = DumpsysPackage.grants(
            dump,
            listOf("android.permission.MANAGE_VIRTUAL_MACHINE", "android.permission.USE_CUSTOM_VIRTUAL_MACHINE", "android.permission.POST_NOTIFICATIONS", "android.permission.WAKE_LOCK"),
        )
        assertEquals(true, m["android.permission.MANAGE_VIRTUAL_MACHINE"])
        assertEquals(false, m["android.permission.USE_CUSTOM_VIRTUAL_MACHINE"])
        assertEquals(true, m["android.permission.POST_NOTIFICATIONS"])
        assertNull(m["android.permission.WAKE_LOCK"])
    }

    @Test fun tokenFromJson() {
        assertEquals("azc1.abc", TokenJson.token("""{"id":"7","token":"azc1.abc"}"""))
        assertNull(TokenJson.token("""{"error":"no"}"""))
    }

    @Test fun doctorStages() {
        val out = "✓ token        0 ms  grant a, host b\n" +
            "2026-11-24T05:26:34Z  WARN azula::endpoint: system TLS roots unavailable\n" +
            "✗ dial     10003 ms  could not connect to the host: timed out\n    hint: Is it running?\n"
        val s = Doctor.stages(out)
        assertEquals(listOf("token", "dial"), s.map { it.name })
        assertEquals(listOf(true, false), s.map { it.ok })
        assertEquals(10003L, s[1].ms)
    }

    @Test fun grantIdFromJson() {
        assertEquals("g_12ab", TokenJson.id("""{"id":"g_12ab","token":"azc1.abc"}"""))
        assertNull(TokenJson.id("""{"token":"azc1.abc"}"""))
        assertNull(TokenJson.id(""))
    }

    @Test fun aRevokeIsConfirmedForItsOwnGrantOnly() {
        assertTrue(Revoke.confirmed("revoke g_12ab -> Success(true)", "g_12ab"))
        for (other in listOf(
            "revoke g_12ab -> Success(false)",
            "revoke g_12ab -> Failure(dev.azula.vmhost.HostException: the host isn't running)",
            "revoke g_99ff -> Success(true)",
            "revoke: no token.json",
            "grant g_12ab -> token.json",
            "",
        )) assertFalse(other, Revoke.confirmed(other, "g_12ab"))
    }

    @Test fun whatTheHostMadeOfTheToken() {
        val upToDial = "✓ token        0 ms  grant g_1, host b\n✓ bind        10 ms  local endpoint bound\n✓ dial        90 ms  connected to the host\n"
        assertEquals(Doctor.Access.ACCEPTED, Doctor.access("$upToDial✓ auth        80 ms  grant accepted by phone\n"))
        // What a later stage did doesn't change that the token was taken.
        assertEquals(Doctor.Access.ACCEPTED, Doctor.access("$upToDial✓ auth        80 ms  grant accepted by phone\n✗ guest     30001 ms  no answer\n"))
        assertEquals(Doctor.Access.REFUSED, Doctor.access("$upToDial✗ auth        80 ms  unauthorized: unauthorized\n    hint: Is the grant revoked or expired?\n"))
        assertEquals(Doctor.Access.UNKNOWN, Doctor.access("$upToDial✗ auth     10001 ms  no answer from the broker within 10s\n"))
        assertEquals(Doctor.Access.UNKNOWN, Doctor.access("✓ token        0 ms  grant g_1, host b\n✗ dial     20001 ms  timed out after 20s connecting to the host (relay x)\n"))
        assertEquals(Doctor.Access.UNKNOWN, Doctor.access(""))
    }
}
