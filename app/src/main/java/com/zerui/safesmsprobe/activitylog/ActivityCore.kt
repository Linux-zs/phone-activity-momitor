package com.zerui.safesmsprobe.activitylog

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

object ActivityCore {
    const val DAY = 86_400_000L
    fun id(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun serverUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "请输入 HTTPS 服务器地址，不包含路径、账号或参数"
        }
        return value.trim().trimEnd('/')
    }

    fun dayStart(now: Long): Long = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai"))
        .toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    fun queryStart(cursor: Long, now: Long): Long =
        if (cursor == 0L) dayStart(now) else maxOf(cursor - 60_000L, now - 2 * DAY)

    fun acknowledgeMatches(sent: List<String>, accepted: List<String>): Boolean =
        sent.size == accepted.size && sent.toSet().size == sent.size && sent.toSet() == accepted.toSet()

    fun retryableHttp(code: Int): Boolean = code == 429 || code >= 500
}
