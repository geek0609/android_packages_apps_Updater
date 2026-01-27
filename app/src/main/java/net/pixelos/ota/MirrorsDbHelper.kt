/*
 * Copyright (C) 2019 ArrowOS
 * Copyright (C) 2025 PixelOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.pixelos.ota

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns

class MirrorsDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(SQL_DELETE_ENTRIES)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    fun setUpdate(downloadId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID, downloadId)
        }
        db.insert(MirrorsEntry.TABLE_NAME, null, values)
    }

    fun isUpdateExists(downloadId: String): Boolean {
        val db = writableDatabase
        val selection = "${MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val selectionArgs = arrayOf(downloadId)
        db.query(
            MirrorsEntry.TABLE_NAME,
            null,
            selection,
            selectionArgs,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = cursor.getColumnIndex(MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID)
                if (index >= 0) {
                    val res = cursor.getString(index)
                    if (res == downloadId) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun delUpdate(downloadId: String) {
        val db = writableDatabase
        val selection = "${MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val selectionArgs = arrayOf(downloadId)
        db.delete(MirrorsEntry.TABLE_NAME, selection, selectionArgs)
    }

    fun setMirrorUrl(mirrorUrl: String, downloadId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(MirrorsEntry.COLUMN_NAME_MIRROR_URL, mirrorUrl)
        }
        val downloadIdColumn = "${MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val args = arrayOf(downloadId)
        db.update(MirrorsEntry.TABLE_NAME, values, downloadIdColumn, args)
    }

    fun getMirrorUrl(downloadId: String): String? {
        val db = writableDatabase
        val downloadIdColumn = "${MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val getColumns = arrayOf(MirrorsEntry.COLUMN_NAME_MIRROR_URL)
        val args = arrayOf(downloadId)
        db.query(
            MirrorsEntry.TABLE_NAME,
            getColumns,
            downloadIdColumn,
            args,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = cursor.getColumnIndex(MirrorsEntry.COLUMN_NAME_MIRROR_URL)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return null
    }

    fun setMirrorName(mirrorName: String, downloadId: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(MirrorsEntry.COLUMN_NAME_MIRROR, mirrorName)
        }
        val downloadIdColumn = "${MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val args = arrayOf(downloadId)
        db.update(MirrorsEntry.TABLE_NAME, values, downloadIdColumn, args)
    }

    fun getMirrorName(downloadId: String): String {
        val db = writableDatabase
        val downloadIdColumn = "${MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID} = ?"
        val getColumns = arrayOf(MirrorsEntry.COLUMN_NAME_MIRROR)
        val args = arrayOf(downloadId)
        db.query(
            MirrorsEntry.TABLE_NAME,
            getColumns,
            downloadIdColumn,
            args,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = cursor.getColumnIndex(MirrorsEntry.COLUMN_NAME_MIRROR)
                if (index >= 0) {
                    val result = cursor.getString(index)
                    if (result != null) {
                        return result
                    }
                }
            }
        }
        return R.string.mirror_default.toString()
    }

    object MirrorsEntry : BaseColumns {
        const val TABLE_NAME = "mirrors"
        const val COLUMN_NAME_DOWNLOAD_ID = "download_id"
        const val COLUMN_NAME_MIRROR = "mirror_name"
        const val COLUMN_NAME_MIRROR_URL = "mirror_url"
    }

    companion object {
        const val DATABASE_VERSION = 1
        const val DATABASE_NAME = "mirrors.db"

        private const val SQL_CREATE_ENTRIES =
            "CREATE TABLE ${MirrorsEntry.TABLE_NAME} (" +
                    "${BaseColumns._ID} INTEGER PRIMARY KEY," +
                    "${MirrorsEntry.COLUMN_NAME_DOWNLOAD_ID} TEXT NOT NULL UNIQUE," +
                    "${MirrorsEntry.COLUMN_NAME_MIRROR} TEXT," +
                    "${MirrorsEntry.COLUMN_NAME_MIRROR_URL} TEXT)"

        private const val SQL_DELETE_ENTRIES =
            "DROP TABLE IF EXISTS ${MirrorsEntry.TABLE_NAME}"

        @Volatile
        private var instance: MirrorsDbHelper? = null

        @JvmStatic
        fun getInstance(context: Context): MirrorsDbHelper {
            return instance ?: synchronized(this) {
                instance ?: MirrorsDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
