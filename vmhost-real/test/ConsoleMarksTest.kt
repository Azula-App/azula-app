package dev.azula.vmhost

import dev.azula.vmhost.ConsoleMarks.Tools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleMarksTest {
    @Test
    fun nothingYetIsNull() {
        assertNull(ConsoleMarks.tools("azula-avf login: \r\n[ OK ] Reached target"))
        assertNull(ConsoleMarks.tools("AZULA-SEED guest=active proxy=active uptime=140.24\n"), "the guest-ready line isn't the tools line")
    }

    @Test
    fun rcZeroIsDone() {
        assertEquals(Tools.Done, ConsoleMarks.tools("noise\r\nAZULA-SEED provision rc=0 uptime=38.10\r\n"))
    }

    @Test
    fun aFailureCarriesItsStatusAndWhen() {
        assertEquals(Tools.Failed(100, "31.0"), ConsoleMarks.tools("AZULA-SEED provision rc=100 uptime=31.0\n"))
    }

    @Test
    fun theLatestFailureIsTheOneReported() {
        val two = "AZULA-SEED provision rc=100 uptime=31.0\nAZULA-SEED provision rc=7 uptime=52.3\n"
        assertEquals(Tools.Failed(7, "52.3"), ConsoleMarks.tools(two))
    }

    @Test
    fun aSuccessOutranksEarlierFailuresInTheTail() {
        val t = "AZULA-SEED provision rc=100 uptime=31.0\r\nAZULA-SEED provision rc=0 uptime=66.9\r\n"
        assertEquals(Tools.Done, ConsoleMarks.tools(t))
    }

    @Test
    fun aReadEndingMidNumberDoesNotMatchEarly() {
        var tail = ConsoleMarks.appendTail("", "AZULA-SEED provision rc=1")
        assertNull(ConsoleMarks.tools(tail))
        tail = ConsoleMarks.appendTail(tail, "00 uptime=40.2")
        assertNull(ConsoleMarks.tools(tail), "not complete until what follows the uptime arrives")
        tail = ConsoleMarks.appendTail(tail, "\n")
        assertEquals(Tools.Failed(100, "40.2"), ConsoleMarks.tools(tail))
    }

    @Test
    fun aMalformedLineIsIgnored() {
        assertNull(ConsoleMarks.tools("AZULA-SEED provision rc=\n"))
        assertNull(ConsoleMarks.tools("AZULA-SEED provision rc=0x0 uptime=1\n"))
        assertNull(ConsoleMarks.tools("provision rc=0 uptime=1.0\n"), "the marker is required")
    }

    @Test
    fun tailStaysBoundedAndKeepsTheNewestOutput() {
        var tail = ""
        repeat(100) { tail = ConsoleMarks.appendTail(tail, "[ $it.000] some systemd line\n") }
        assertTrue(tail.length <= ConsoleMarks.TAIL_CHARS)
        assertTrue(tail.endsWith("[ 99.000] some systemd line\n"))
    }

    @Test
    fun loginPrompt() {
        assertTrue(ConsoleMarks.loginPrompt("\nazula-avf login: "))
        assertFalse(ConsoleMarks.loginPrompt("Starting getty@tty1.service"))
    }
}
