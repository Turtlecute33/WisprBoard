// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log

/*
 possible extension for later: allow non-text
 setting whether to allow it at all (because it could be slow with large files)
 separate retention time setting
 add mime type column
 add file name column
 add hash column (sha 256) for quick unique check (check full content on hash conflict)
 more sophisticated content loading: some getContent that reads the file, with cache
 async file reads and writes
 caches should be dropped on low memory
 */

/** Class providing cached access to the clipboard table */
// currently we should not need to worry about synchronizing access (though maybe we could addClip in a coroutine, then it might be relevant)
class ClipboardDao private constructor(private val db: Database) {
    /** Notified whenever the clip list changed. The listener is expected to read the new state with [getAll]. */
    fun interface Listener {
        fun onClipboardHistoryChanged()
    }

    var listener: Listener? = null
        set(value) {
            field = value
            // clearOldClips does nothing while the panel shows, so plan it again when the panel closes
            if (value == null) scheduleClearOldClips()
        }

    // we clean up old clips when a new clip is added, but not too frequently
    private var lastClearOldClips = 0L

    /**
     * Without a timer, an old clip stays until the next event (a copy, a panel open) runs
     * [clearOldClips] (R-39). The handler clock stops in deep sleep, so the run can come late.
     */
    private val handler = Handler(Looper.getMainLooper())
    private val clearOldClipsRunnable = Runnable { clearOldClips(true) }

    // cache is loaded at start and never dropped
    private val cache = mutableListOf<ClipboardHistoryEntry>().apply {
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED, $COLUMN_TIMESTAMP DESC" // was only relevant in the initial approach of using a cursor instead of a cache
        ).use {
            while (it.moveToNext()) {
                add(ClipboardHistoryEntry(it.getLong(0), it.getLong(1), it.getInt(2) != 0, it.getString(3)))
            }
        }
        sort()
    }

    fun addClip(timestamp: Long, pinned: Boolean, text: String) {
        clearOldClips()
        val existingIndex = cache.indexOfFirst { it.text == text }
        if (existingIndex >= 0 && cache[existingIndex].timeStamp == timestamp)
            return // nothing to do
        if (existingIndex >= 0) {
            updateTimestampAt(existingIndex, timestamp)
            return
        }
        insertNewEntry(timestamp, pinned, text)
    }

    /** Adds a pinned clip, or pins the existing clip with the same text. Used when restoring a backup. */
    fun addPinnedClip(timestamp: Long, text: String) {
        val existing = cache.firstOrNull { it.text == text }
        if (existing == null) {
            insertNewEntry(timestamp, true, text)
            return
        }
        if (existing.isPinned) return
        existing.isPinned = true
        cache.sort()
        listener?.onClipboardHistoryChanged()
        val cv = ContentValues(1)
        cv.put(COLUMN_PINNED, true)
        write { update(TABLE, cv, "$COLUMN_ID = ${existing.id}", null) }
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String) {
        val cv = ContentValues(3)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        cv.put(COLUMN_PINNED, pinned)
        cv.put(COLUMN_TEXT, text)
        val rowId = write { insertOrThrow(TABLE, null, cv) }
        if (rowId == null || rowId == -1L) return // insert failed: do not cache a clip that is not in the database

        val entry = ClipboardHistoryEntry(rowId, timestamp, pinned, text)
        cache.add(entry)
        cache.sort()
        listener?.onClipboardHistoryChanged()
        scheduleClearOldClips()
    }

    private fun updateTimestampAt(index: Int, timestamp: Long) {
        val entry = cache[index]
        entry.timeStamp = timestamp
        cache.sort()
        listener?.onClipboardHistoryChanged()
        val cv = ContentValues(1)
        cv.put(COLUMN_TIMESTAMP, timestamp)
        write { update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null) }
    }

    fun isPinned(index: Int) = cache[index].isPinned

    fun getAt(index: Int) = cache[index]

    fun get(id: Long) = cache.firstOrNull { it.id == id }

    /** Snapshot of all entries, sorted the same way as the cache */
    fun getAll(): List<ClipboardHistoryEntry> = cache.toList()

    fun count() = cache.size

    fun sort() {
        cache.sort()
        listener?.onClipboardHistoryChanged()
    }

    fun togglePinned(id: Long) {
        val entry = cache.firstOrNull { it.id == id } ?: return
        entry.isPinned = !entry.isPinned
        entry.timeStamp = System.currentTimeMillis()
        cache.sort()
        listener?.onClipboardHistoryChanged()
        val cv = ContentValues(2)
        cv.put(COLUMN_PINNED, entry.isPinned)
        cv.put(COLUMN_TIMESTAMP, entry.timeStamp)
        write { update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null) }
    }

    fun deleteClip(id: Long) {
        val entry = cache.firstOrNull { it.id == id } ?: return
        cache.remove(entry)
        listener?.onClipboardHistoryChanged()
        write { delete(TABLE, "$COLUMN_ID = ${entry.id}", null) }
    }

    /** Replaces the text of an entry, keeping its pinned state, and moves it to the top */
    fun updateText(id: Long, text: String) {
        val entry = cache.firstOrNull { it.id == id } ?: return
        if (entry.text == text) return
        val replacement = ClipboardHistoryEntry(entry.id, System.currentTimeMillis(), entry.isPinned, text)
        cache[cache.indexOf(entry)] = replacement
        cache.sort()
        listener?.onClipboardHistoryChanged()
        val cv = ContentValues(2)
        cv.put(COLUMN_TIMESTAMP, replacement.timeStamp)
        cv.put(COLUMN_TEXT, text)
        write { update(TABLE, cv, "$COLUMN_ID = ${entry.id}", null) }
    }

    fun clearOldClips(now: Boolean = false) {
        if (listener != null)
            return // never clear when clipboard is visible
        if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5 * 1000)
            return

        lastClearOldClips = SystemClock.elapsedRealtime()
        val retentionTime = retentionMinutes()
        if (retentionTime != null) {
            val minTime = System.currentTimeMillis() - retentionTime * 60 * 1000L
            if (cache.removeAll { it.timeStamp < minTime && !it.isPinned })
                write { delete(TABLE, "$COLUMN_TIMESTAMP < $minTime AND $COLUMN_PINNED = 0", null) }
        }
        scheduleClearOldClips()
    }

    /** Posts one [clearOldClips] for the time when the oldest clip that is not pinned expires (R-39). */
    private fun scheduleClearOldClips() {
        handler.removeCallbacks(clearOldClipsRunnable)
        val retentionTime = retentionMinutes() ?: return // no limit: nothing expires
        val oldest = cache.filter { !it.isPinned }.minOfOrNull { it.timeStamp } ?: return
        handler.postDelayed(clearOldClipsRunnable, clearDelayMillis(oldest, retentionTime, System.currentTimeMillis()))
    }

    /** Removes the planned [clearOldClips]. Call it when the IME stops. */
    fun cancelScheduledClear() = handler.removeCallbacks(clearOldClipsRunnable)

    /** The retention time in minutes, or null for no limit */
    private fun retentionMinutes(): Long? {
        val retentionTime = Settings.getValues()?.mClipboardHistoryRetentionTime
            ?: Settings.CLIPBOARD_RETENTION_UNLIMITED.toLong()
        return retentionTime.takeUnless { Settings.isClipboardRetentionUnlimited(it) }
    }

    fun clearNonPinned() {
        if (!cache.removeAll { !it.isPinned })
            return // nothing to remove
        listener?.onClipboardHistoryChanged()
        write { delete(TABLE, "$COLUMN_PINNED = 0", null) }
    }

    fun clear() {
        if (count() == 0) return
        cache.clear()
        listener?.onClipboardHistoryChanged()
        write { delete(TABLE, null, null) }
    }

    /**
     * All writes go through here (R-15). A full disk or an IO error must not crash the IME on the
     * main thread. The log has only the exception class: the message can have the clip text.
     * @return the result of [block], or null when it failed
     */
    private inline fun <T> write(block: SQLiteDatabase.() -> T): T? =
        runCatching { db.writableDatabase.block() }
            .onFailure { Log.e(TAG, "clipboard write failed: ${it.javaClass.simpleName}") }
            .getOrNull()

    companion object {
        private const val TAG = "ClipboardDao"

        /**
         * Milliseconds from [now] until a clip with [timestamp] expires, or 0 when it already
         * expired. One more millisecond, because [clearOldClips] removes only older clips.
         */
        fun clearDelayMillis(timestamp: Long, retentionMinutes: Long, now: Long): Long =
            (timestamp + retentionMinutes * 60 * 1000L - now + 1).coerceAtLeast(0L)

        private const val TABLE = "CLIPBOARD"
        // it's possible timestamp is not unique, so we use a separate ID
        // ID is generated and returned on insert, see https://sqlite.org/rowidtable.html
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT" // we could enforce unique text, but that's only necessary if we can drop the cache (later)
        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT
            )
        """

        private var instance: ClipboardDao? = null

        /** Returns the instance or creates a new one. Returns null if instance can't be created (e.g. no access to db due to device being locked) */
        fun getInstance(context: Context): ClipboardDao? {
            if (instance == null)
                try {
                    instance = ClipboardDao(Database.getInstance(context))
                } catch (e: Throwable) {
                    Log.e(TAG, "can't create ClipboardDao", e)
                }
            return instance
        }
    }
}
