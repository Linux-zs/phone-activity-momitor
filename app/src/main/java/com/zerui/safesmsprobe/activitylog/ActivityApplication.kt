package com.zerui.safesmsprobe.activitylog

import android.app.Application
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.work.WorkManager

class ActivityApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Intent().setClassName(this, "com.zerui.safesmsprobe.alarm.AlarmReceiver")
            .setAction("com.zerui.safesmsprobe.action.FIRE_PROBE")
        PendingIntent.getBroadcast(this, 44001, old, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
        WorkManager.getInstance(this).cancelUniqueWork("current-wecom-probe-send")
    }
}
