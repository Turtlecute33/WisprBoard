package helium314.keyboard.event

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class CombinerChainTest {
    @Test
    fun processEventAcceptsNullPreviousEvents() {
        val event = Event.createEventForCodePointFromUnknownSource('a'.code)
        val processed = CombinerChain("", "").processEvent(null, event)

        assertEquals(event, processed)
    }
}
