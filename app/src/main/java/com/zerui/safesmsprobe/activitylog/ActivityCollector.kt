package com.zerui.safesmsprobe.activitylog

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager

object ActivityCollector {
    fun permitted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun collect(context: Context, store: ActivityStore, device: String, now: Long): String {
        if (!permitted(context)) {
            store.put("gap", "1")
            return "permission_denied"
        }
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return "user_locked"
        val cursor = store.get("cursor", "0").toLong()
        if (cursor > now) {
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                // Keep anomalous records locally, outside the upload queue, for diagnostics.
                db.execSQL("UPDATE events SET sent=2 WHERE at>?", arrayOf(now))
                db.execSQL("UPDATE coverage SET sent=2 WHERE end>?", arrayOf(now))
                store.event(ActivityCore.id("$device:clock:$now"), now, "reset")
                store.put("cursor", now.toString())
                store.put("clock_warning", "1")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            store.put("gap", "1")
            return "clock_changed"
        }
        val start = ActivityCore.queryStart(cursor, now)
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val kinds = mutableMapOf(UsageEvents.Event.KEYGUARD_HIDDEN to "unlock", UsageEvents.Event.KEYGUARD_SHOWN to "lock",
            UsageEvents.Event.SCREEN_INTERACTIVE to "screen_on", UsageEvents.Event.SCREEN_NON_INTERACTIVE to "screen_off")
        if (Build.VERSION.SDK_INT >= 29) {
            kinds[UsageEvents.Event.DEVICE_SHUTDOWN] = "reset"
            kinds[UsageEvents.Event.DEVICE_STARTUP] = "reset"
        }
        val events = if (Build.VERSION.SDK_INT >= 35) manager.queryEvents(UsageEventsQuery.Builder(start, now).setEventTypes(
            UsageEvents.Event.KEYGUARD_HIDDEN, UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP).build())
            else manager.queryEvents(start, now)
        if (events == null) return "query_failed"
        val gap = cursor != 0L && (now - cursor > 2 * ActivityCore.DAY || store.get("gap") == "1")
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            if (gap) store.event(ActivityCore.id("$device:gap:$start"), start, "reset")
            val item = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(item)
                val kind = kinds[item.eventType] ?: continue
                store.event(ActivityCore.id("$device:${item.timeStamp}:${item.eventType}"), item.timeStamp, kind)
            }
            // A successful query is a query range, not proof OEM history is complete.
            store.coverage(device, start, now)
            store.put("cursor", now.toString())
            store.put("gap", "0")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return if (gap) "history_gap" else "ok"
    }
}
