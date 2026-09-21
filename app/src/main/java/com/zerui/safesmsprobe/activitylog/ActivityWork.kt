package com.zerui.safesmsprobe.activitylog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

object ActivityWork {
    const val PERIODIC = "activity-periodic-collect"
    const val IMMEDIATE = "activity-immediate-collect"
    const val UPLOAD = "activity-upload"
    val lock = Any()
    fun schedule(context: Context) {
        if (!ActivitySettings(context).enabled) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CollectWorker>(DashboardFormat.effectiveInterval(ActivitySettings(context).intervalMinutes).toLong(), TimeUnit.MINUTES).build())
    }
    fun now(context: Context, retryUpload: Boolean = false): Operation {
        return WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CollectWorker>().setInputData(workDataOf("retry_upload" to retryUpload)).build())
    }
    fun upload(context: Context, retry: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD, if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        listOf(PERIODIC, IMMEDIATE, UPLOAD).forEach { wm.cancelUniqueWork(it) }
    }
}

class CollectWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = synchronized(ActivityWork.lock) {
        val settings = ActivitySettings(applicationContext)
        if (!settings.enabled) return@synchronized Result.success()
        ActivityStore(applicationContext).use { store ->
            val diagnostic = try {
                ActivityCollector.collect(applicationContext, store, settings.device, System.currentTimeMillis())
            } catch (_: Exception) { "query_failed" }
            store.put("diagnostic", diagnostic)
            store.put("status", "采集任务已结束，等待上传")
        }
        ActivityWork.upload(applicationContext, inputData.getBoolean("retry_upload", false))
        Result.success()
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = synchronized(ActivityWork.lock) {
        val settings = ActivitySettings(applicationContext)
        if (!settings.enabled || isStopped) return@synchronized Result.success()
        ActivityStore(applicationContext).use { store ->
            val token = settings.token()
            if (token == null) {
                store.put("sync_error", "auth")
                store.put("status", "请重新保存上传密钥")
                return@synchronized Result.failure()
            }
            try {
                repeat(10) {
                    if (isStopped || !settings.enabled) return@synchronized Result.success()
                    val events = store.batch("events", 1000)
                    // The server atomically commits the final event page together with coverage.
                    val coverage = if (events.length() < 1000) store.batch("coverage", 200) else JSONArray()
                    val now = System.currentTimeMillis()
                    val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    val network = when {
                        caps == null -> "offline"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                        else -> "other"
                    }
                    val payload = JSONObject().put("device_id", settings.device).put("captured_at", now)
                        .put("covered_until", if (events.length() < 1000) store.get("cursor", "0").toLong().takeIf { it > 0 } ?: JSONObject.NULL else JSONObject.NULL)
                        .put("battery", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
                        .put("charging", if (battery == null) JSONObject.NULL else battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
                        .put("network", network).put("permission", ActivityCollector.permitted(applicationContext))
                        .put("diagnostic", store.get("diagnostic", "ok")).put("events", events).put("coverage", coverage)
                    val connection = URL(ActivityCore.serverUrl(settings.server) + "/api/v1/sync").openConnection() as HttpsURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15000
                        connection.readTimeout = 20000
                        connection.doOutput = true
                        connection.setRequestProperty("Authorization", "Bearer $token")
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                        val code = connection.responseCode
                        if (code != 200) {
                            store.put("sync_error", when(code) { 401,403 -> "auth"; 409 -> "conflict"; 422 -> "clock"; else -> "network" })
                            store.put("status", when(code) {
                                401,403 -> "上传密钥无效，请修正后保存"
                                409 -> "设备绑定或事件冲突，请联系管理员"
                                422 -> "数据或手机时间异常，请检查自动时间设置"
                                else -> "上传失败 HTTP $code，数据保留在本机"
                            })
                            return@synchronized if (ActivityCore.retryableHttp(code)) Result.retry() else Result.failure()
                        }
                        val ack = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val eventIds = ack.getJSONArray("accepted_event_ids")
                        val coverageIds = ack.getJSONArray("accepted_coverage_ids")
                        fun sameIds(sent: JSONArray, accepted: JSONArray): Boolean = ActivityCore.acknowledgeMatches(
                            (0 until sent.length()).map { sent.getJSONObject(it).getString("id") },
                            (0 until accepted.length()).map { accepted.getString(it) })
                        check(sameIds(events, eventIds) && sameIds(coverage, coverageIds))
                        val db = store.writableDatabase
                        db.beginTransaction()
                        try {
                            store.acknowledge("events", eventIds)
                            store.acknowledge("coverage", coverageIds)
                            store.put("last_upload", System.currentTimeMillis().toString())
                            store.put("sync_error", "")
                            store.put("status", "同步成功")
                            store.prune(now)
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    } finally { connection.disconnect() }
                    if (store.pending() == 0L) return@synchronized Result.success()
                }
                Result.retry()
            } catch (_: Exception) {
                store.put("sync_error", "network")
                store.put("status", "连接或响应失败，数据已保留，将自动重试；请检查网络及 HTTPS 证书")
                Result.retry()
            }
        }
    }
}
