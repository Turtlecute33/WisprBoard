package helium314.keyboard

import helium314.keyboard.latin.utils.LayoutType
import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutTest {
    // todo: add more
    @Test fun extraValueToMainLayout() {
        val extraValue = "KeyboardLayoutSet=MAIN:qwertz+,SupportTouchPositionCorrection"
        assertEquals("qwertz+", LayoutType.getMainLayoutFromExtraValue(extraValue))
    }

    // a subtype can still store the removed clipboard bottom row type (D-5)
    @Test fun removedLayoutTypeIsIgnored() {
        val map = LayoutType.getLayoutMap("MAIN:qwerty|CLIPBOARD_BOTTOM:custom.abc.|SYMBOLS:symbols_arabic")
        assertEquals(mapOf(LayoutType.MAIN to "qwerty", LayoutType.SYMBOLS to "symbols_arabic"), map.toMap())
    }
}