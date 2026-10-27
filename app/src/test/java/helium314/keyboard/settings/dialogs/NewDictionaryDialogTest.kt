// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewDictionaryDialogTest {
    @Test
    fun normalTypesAreSafe() {
        assertTrue(isSafeDictType("main"))
        assertTrue(isSafeDictType("emoji"))
        assertTrue(isSafeDictType("en"))
    }

    @Test
    fun typesWithAPathAreRefused() {
        assertFalse(isSafeDictType("../../x"))
        assertFalse(isSafeDictType("a/b"))
        assertFalse(isSafeDictType("main\u0000"))
    }
}
