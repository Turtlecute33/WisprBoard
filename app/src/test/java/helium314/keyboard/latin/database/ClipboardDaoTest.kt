// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardDaoTest {
    private val context = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun failedInsertIsNotCached() {
        val db = Database.getInstance(context).writableDatabase
        val dao = requireNotNull(ClipboardDao.getInstance(context))
        dao.clear()
        // the trigger makes each insert fail, as a full disk does; insert() then returns -1
        db.execSQL("CREATE TEMP TRIGGER fail_insert BEFORE INSERT ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            dao.addClip(1L, false, "alpha")
            dao.addClip(2L, false, "bravo")
        } finally {
            db.execSQL("DROP TRIGGER fail_insert")
        }
        assertEquals(0, dao.count())
    }
}
