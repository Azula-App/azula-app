import dev.azula.getWorld
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmWorldTest {
    @Test
    fun doTest() {
        assertEquals("desktop", getWorld())
    }
}
