package com.zerui.safesmsprobe.activitylog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class ActivityStore(context: Context) : SQLiteOpenHelper(context, "activity.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, sent INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE coverage(id TEXT PRIMARY KEY, start INTEGER NOT NULL, end INTEGER NOT NULL, sent INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun get(key: String, default: String = ""): String = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else default
    }
    fun put(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun event(id: String, at: Long, kind: String) {
        writableDatabase.insertWithOnConflict("events", null, ContentValues().apply {
            put("id", id); put("at", at); put("kind", kind)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun coverage(device: String, start: Long, end: Long) {
        writableDatabase.insertWithOnConflict("coverage", null, ContentValues().apply {
            put("id", ActivityCore.id("$device:coverage:$start:$end")); put("start", start); put("end", end)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun batch(table: String, limit: Int): JSONArray {
        require(table == "events" || table == "coverage")
        val out = JSONArray()
        val order = if (table == "events") "at" else "start"
        readableDatabase.rawQuery("SELECT * FROM $table WHERE sent=0 ORDER BY $order,id LIMIT $limit", null).use { c ->
            while (c.moveToNext()) {
                val item = JSONObject().put("id", c.getString(0))
                if (table == "events") item.put("at", c.getLong(1)).put("kind", c.getString(2))
                else item.put("start", c.getLong(1)).put("end", c.getLong(2))
                out.put(item)
            }
        }
        return out
    }
    fun acknowledge(table: String, ids: JSONArray) {
        require(table == "events" || table == "coverage")
        for (i in 0 until ids.length()) writableDatabase.execSQL("UPDATE $table SET sent=1 WHERE id=?", arrayOf(ids.getString(i)))
    }
    fun pending(): Long = readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM events WHERE sent=0)+(SELECT COUNT(*) FROM coverage WHERE sent=0)", null).use { it.moveToFirst(); it.getLong(0) }
    fun prune(now: Long) {
        // Unacknowledged records are retained until the server confirms them.
        writableDatabase.delete("events", "sent=1 AND at<?", arrayOf((now - 30 * ActivityCore.DAY).toString()))
        writableDatabase.delete("coverage", "sent=1 AND end<?", arrayOf((now - 30 * ActivityCore.DAY).toString()))
    }
}
