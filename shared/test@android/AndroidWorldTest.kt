import dev.azula.getWorld
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidWorldTest {
    @Test
    fun doTest() {
        assertEquals("android", getWorld())
    }
}
