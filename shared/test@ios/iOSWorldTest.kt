import dev.azula.getWorld
import kotlin.test.Test
import kotlin.test.assertEquals

class iOSWorldTest {
    @Test
    fun doTest() {
        assertEquals("ios", getWorld())
    }
}
