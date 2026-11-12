// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** R-12: the upgrade deletes only learned-words folders that no current dictionary uses. */
class OrphanedDictTest {
    @Test
    fun currentDictionariesStay() {
        for (name in listOf(
            "UserHistoryDictionary.en-US.dict", "userunigram.it.dict", "contacts.en-US.dict",
            "apps.fa.dict", "spellcheck_contacts.en-US.dict", "spellcheck_userunigram.de.dict",
        )) assertFalse(isOrphanedLearnedWordsDict(name), name)
    }

    @Test
    fun otherFilesStay() {
        for (name in listOf("dicts", "layouts", "custom_background_image", "custom_font", "UserHistoryDictionary.en-US.dict.tmp"))
            assertFalse(isOrphanedLearnedWordsDict(name), name)
    }

    @Test
    fun shortR8NamesAreOrphans() {
        assertTrue(isOrphanedLearnedWordsDict("c.en-US.dict"))
        assertTrue(isOrphanedLearnedWordsDict("a0.it.dict"))
    }
}
