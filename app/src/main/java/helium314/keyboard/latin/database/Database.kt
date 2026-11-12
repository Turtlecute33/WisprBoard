// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import helium314.keyboard.latin.utils.GestureDataDao

class Database private constructor(context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ClipboardDao.CREATE_TABLE)
        onUpgrade(db, 0, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion <= 1) {
            db.execSQL(GestureDataDao.CREATE_TABLE)
        }
    }

    companion object {
        private const val VERSION = 2
        const val NAME = "heliboard.db"
        private var instance: Database? = null
        @Synchronized
        fun getInstance(context: Context): Database {
            if (instance == null)
                instance = Database(context.applicationContext)
            return instance!!
        }
    }
}
