// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun failedUpdateAndDeleteDoNotThrow() {
        val db = Database.getInstance(context).writableDatabase
        val dao = requireNotNull(ClipboardDao.getInstance(context))
        dao.clear()
        dao.addClip(1L, false, "alpha")
        val id = dao.getAll().single().id
        // as a full disk does: each write fails, and the IME must not crash on the main thread (R-15)
        db.execSQL("CREATE TEMP TRIGGER fail_update BEFORE UPDATE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'test'); END")
        db.execSQL("CREATE TEMP TRIGGER fail_delete BEFORE DELETE ON CLIPBOARD BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            dao.togglePinned(id)
            dao.updateText(id, "bravo")
            dao.deleteClip(id)
        } finally {
            db.execSQL("DROP TRIGGER fail_update")
            db.execSQL("DROP TRIGGER fail_delete")
        }
        assertEquals(0, dao.count())
        db.execSQL("DELETE FROM CLIPBOARD") // the failed delete left the row in the table
    }

    @Test
    fun clearDelayEndsJustAfterTheExpiry() {
        val minute = 60 * 1000L
        // a clip from 2 minutes ago with 5 minutes retention expires in 3 minutes
        assertEquals(3 * minute + 1, ClipboardDao.clearDelayMillis(10 * minute, 5, 12 * minute))
        // at the delay, clearOldClips removes the clip, because it removes clips older than now - retention
        val now = 12 * minute + ClipboardDao.clearDelayMillis(10 * minute, 5, 12 * minute)
        assertTrue(10 * minute < now - 5 * minute)
    }

    @Test
    fun clearDelayIsZeroForAnExpiredClip() {
        assertEquals(0L, ClipboardDao.clearDelayMillis(0L, 1, 10 * 60 * 1000L))
    }
}
