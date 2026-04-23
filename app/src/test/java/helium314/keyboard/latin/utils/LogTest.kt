package helium314.keyboard.latin.utils

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class LogTest {
    @Test
    fun exportLogDropsDebugLinesAndCollapsesMultilineEntries() {
        Log.clearForTests()

        Log.d("TestTag", "debug line")
        Log.w("TestTag", "first line\nsecond line")

        val exported = Log.getLogForExport()
        val exportedLine = exported.lastOrNull { it.contains(" W TestTag:") }

        assertNotNull(exportedLine)
        assertContains(exportedLine, "first line")
        assertContains(exportedLine, "[details omitted]")
        assertFalse(exportedLine.contains("second line"))
        assertFalse(exported.any { it.contains(" D TestTag:") || it.contains("debug line") })
    }
}
