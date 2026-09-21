package com.zerui.safesmsprobe.activitylog

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun JSONObject.numberOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
// SQLite-backed snapshots use 0/1; also accept JSON booleans from newer servers.
fun JSONObject.flagOrNull(key: String): Boolean? = when (val value = opt(key)) {
    is Boolean -> value
    0 -> false
    1 -> true
    else -> null
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
object DashboardFormat {
    private val zone = ZoneId.of("Asia/Shanghai")
    fun date(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    fun time(at: Long?): String = at?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(it)) } ?: "—"
    fun stamp(at: Long?): String = at?.let { "${date(it).substring(5)} ${time(it)}" } ?: "尚无记录"
    fun duration(ms: Long?): String = when { ms == null -> "—"; ms in 1..59999 -> "<1分"; ms >= 3600000 -> "${ms / 3600000}小时${ms / 60000 % 60}分"; else -> "${ms / 60000}分" }
    fun available(snapshot: Boolean, incomplete: Boolean, unlocks: Long, screen: Long, unlocked: Long) = snapshot && (!incomplete || unlocks > 0 || screen > 0 || unlocked > 0)
    fun effectiveInterval(requested: Int) = requested.coerceAtLeast(15)
}
object DashboardApi {
    fun read(server: String): JSONObject {
        val connection = URL(ActivityCore.serverUrl(server) + "/api/v1/dashboard").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.useCaches = false
            // Public read endpoint: deliberately no upload credentials.
            check(connection.responseCode == 200) { "读取失败 HTTP ${connection.responseCode}" }
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            require(result.getJSONArray("daily").length() > 0)
            result.getJSONArray("timeline"); result.getLong("server_time")
            return result
        } finally { connection.disconnect() }
    }
}
