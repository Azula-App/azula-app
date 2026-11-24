package dev.azula.vmhost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleMarksTest {
    @Test
    fun toolsLineGivesTheExitStatus() {
        assertEquals(0, ConsoleMarks.toolsRc("noise\r\nAZULA-SEED provision rc=0 uptime=238.10\r\n"))
        assertEquals(100, ConsoleMarks.toolsRc("AZULA-SEED provision rc=100 uptime=31.0\n"))
    }

    @Test
    fun theGuestReadyLineIsNotTheToolsLine() {
        assertNull(ConsoleMarks.toolsRc("AZULA-SEED guest=active proxy=active uptime=140.24\n"))
    }

    @Test
    fun aReadEndingMidNumberDoesNotMatchEarly() {
        var tail = ConsoleMarks.appendTail("", "AZULA-SEED provision rc=1")
        assertNull(ConsoleMarks.toolsRc(tail))
        tail = ConsoleMarks.appendTail(tail, "00 uptime=40.2\n")
        assertEquals(100, ConsoleMarks.toolsRc(tail))
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
