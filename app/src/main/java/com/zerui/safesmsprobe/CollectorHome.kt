package com.zerui.safesmsprobe

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zerui.safesmsprobe.activitylog.*
import kotlinx.coroutines.*

data class LocalStatus(
    val server: String, val configured: Boolean, val enabled: Boolean, val permission: Boolean,
    val cursor: Long, val uploaded: Long, val pending: Long, val error: String, val diagnostic: String,
    val running: Boolean, val immediateQueued: Boolean, val queued: Boolean, val online: Boolean
) {
    fun phase(submitted: Boolean) = SyncPresentation.phase(enabled, running, queued, online, pending, uploaded, error, submitted)
}

@Composable
fun rememberLocalStatus(): LocalStatus? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var value by remember { mutableStateOf<LocalStatus?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) {
                    val settings = ActivitySettings(context)
                    val wm = WorkManager.getInstance(context)
                    val immediate = wm.getWorkInfosForUniqueWork(ActivityWork.IMMEDIATE).get()
                    val upload = wm.getWorkInfosForUniqueWork(ActivityWork.UPLOAD).get()
                    val periodic = wm.getWorkInfosForUniqueWork(ActivityWork.PERIODIC).get()
                    fun List<WorkInfo>.queued() = any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                    val cm = context.getSystemService(ConnectivityManager::class.java)
                    val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    ActivityStore(context).use {
                        val message = it.get("status")
                        val legacy = when {
                            message.contains("密钥") -> "auth"
                            message.contains("冲突") -> "conflict"
                            message.contains("时间异常") -> "clock"
                            message.contains("失败") -> "network"
                            else -> ""
                        }
                        LocalStatus(settings.server, settings.token() != null && runCatching { ActivityCore.serverUrl(settings.server) }.isSuccess,
                            settings.enabled, ActivityCollector.permitted(context),
                            it.get("cursor", "0").toLongOrNull() ?: 0, it.get("last_upload", "0").toLongOrNull() ?: 0,
                            it.pending(), it.get("sync_error", legacy), it.get("diagnostic"),
                            (immediate + upload + periodic).any { work -> work.state == WorkInfo.State.RUNNING },
                            immediate.queued(), immediate.queued() || upload.queued(), online)
                    }
                }
                delay(1000)
            }
        }
    }
    return value
}

@Composable
fun StatusCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun CollectorHome(local: LocalStatus?, dashboard: DashboardState, onboarding: Boolean, finishSetup: () -> Unit, settings: () -> Unit, details: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    fun open(action: String) {
        runCatching { context.startActivity(Intent(action)) }.onFailure { notice = "无法打开系统设置，请手动在系统设置中处理。" }
    }
    fun sync(enable: Boolean = false) {
        if (submitting || local?.running == true || local?.immediateQueued == true) return
        submitting = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (enable) {
                        ActivitySettings(context).enabled = true
                        ActivityWork.schedule(context)
                    }
                    ActivityWork.now(context, retryUpload = local?.error?.isNotBlank() == true).result.get()
                }
                notice = "已提交任务，等待执行；完成上传后才会显示成功。"
                if (enable) finishSetup()
                delay(1500)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { notice = "⚠ 任务提交失败，请重试或查看诊断信息。"
            } finally { submitting = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ACTIVITY / " + DashboardFormat.date(System.currentTimeMillis()).replace('-', '.'),
                    fontSize = 10.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("手机活动", fontSize = 26.sp, fontWeight = FontWeight.Medium)
            }
            FilledTonalButton(shape = ActivityControlShape, onClick = settings, contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface)) { Text("⚙ 设置") }
        }
        CollectionDial(local)
        if (local == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            if (!local.configured || onboarding) {
                StatusCard("开始采集与同步") {
                    Text("1  连接服务器 → 2  授权 → 3  启用并首次同步")
                    Text("连接配置：" + if (local.configured) "已保存" else "尚未保存", style = MaterialTheme.typography.bodyMedium)
                    if (!local.configured) Button(shape = ActivityControlShape, onClick = settings) { Text("连接服务器") }
                    else if (!local.permission) Button(shape = ActivityControlShape, onClick = { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("去授权") }
                    else Button(shape = ActivityControlShape, onClick = { sync(true) }, enabled = !submitting && !local.running && !local.immediateQueued) { Text("启用并首次同步") }
                }
            }
            if (!local.permission || !local.enabled || (local.diagnostic.isNotBlank() && local.diagnostic != "ok" && local.diagnostic != "permission_denied")) StatusCard("采集提醒") {
                Text(if (local.permission) "✓ 使用情况访问已授权" else "⚠ 使用情况访问未授权",
                    color = if (local.permission) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                if (!local.permission) OutlinedButton(shape = ActivityControlShape, onClick = { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("去授权") }
                if (!local.enabled && local.configured && local.permission && !onboarding) {
                    Button(shape = ActivityControlShape, onClick = { sync(true) }, enabled = !submitting) { Text("恢复采集") }
                }
                if (local.diagnostic.isNotBlank() && local.diagnostic != "ok" && local.diagnostic != "permission_denied") {
                    Text("⚠ " + when(local.diagnostic) {
                        "history_gap" -> "历史采集存在缺口，缺失不等于没有使用。"
                        "clock_changed" -> "手机时间发生回拨，请检查自动时间。"
                        "user_locked" -> "重启后需先解锁手机。"
                        else -> "系统历史读取异常，请重试或查看诊断信息。"
                    }, color = MaterialTheme.colorScheme.error)
                    TextButton(shape = ActivityControlShape, onClick = settings) { Text("查看诊断") }
                }
            }
            val root = dashboard.data
            val day = root?.getJSONArray("daily")?.objects()?.firstOrNull {
                it.getString("date") == DashboardFormat.date(System.currentTimeMillis())
            }
            val available = day != null && DashboardFormat.available(root?.optJSONObject("snapshot") != null,
                day.getBoolean("incomplete"), day.getLong("unlocks"), day.getLong("screen_ms"), day.getLong("unlocked_ms"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("今日一览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(shape = ActivityControlShape, onClick = details) { Text("记录详情 ↗") }
                }
                val screenMs = day?.getLong("screen_ms")?.takeIf { available }
                val minutes = screenMs?.div(60000)
                val duration = when {
                    minutes == null -> "—"
                    screenMs > 0 && minutes == 0L -> "<1"
                    minutes < 60 -> minutes.toString()
                    else -> "%d:%02d".format(minutes / 60, minutes % 60)
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TodayMetric("亮屏时长", duration, if (minutes != null && minutes >= 60) "小时 : 分钟" else "分钟",
                        true, Modifier.weight(1f).fillMaxHeight().clickable(onClick = details))
                    TodayMetric("解锁次数", day?.getLong("unlocks")?.takeIf { available }?.toString() ?: "—", "次 · 今日累计",
                        false, Modifier.weight(1f).fillMaxHeight().clickable(onClick = details))
                }
                Text(if (!available) "暂无可用统计，等待服务器收到记录。" else if (day?.getBoolean("incomplete") == true)
                    "采集不完整，仅展示已知记录。" else "服务端统计 · 北京时间", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DashboardMessage(dashboard, compact = true)
            }
            val phase = local.phase(submitting)
            StatusCard("采集与同步") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text((if (phase == SyncPhase.FAILED) "⚠ " else "") + phase.label, Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (phase == SyncPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text("待上传 ${local.pending} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (phase == SyncPhase.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SyncDetail("采集截至", DashboardFormat.stamp(local.cursor.takeIf { it > 0 }))
                SyncDetail("最近成功同步", DashboardFormat.stamp(local.uploaded.takeIf { it > 0 }))
                Text("含事件和采集覆盖记录 · 北京时间", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (local.error.isNotBlank()) {
                    Text("⚠ " + when(local.error) {
                        "auth" -> "上传密钥无效或不可读取，请在设置中重新保存。"
                        "conflict" -> "设备绑定或事件冲突，请联系服务器管理员。"
                        "clock" -> "数据或手机时间异常，请检查自动时间。"
                        else -> "连接或响应失败，数据保留在本机；等待自动重试。"
                    }, color = MaterialTheme.colorScheme.error)
                    when(local.error) {
                        "auth", "conflict" -> TextButton(shape = ActivityControlShape, onClick = settings) { Text("检查连接配置") }
                        "clock" -> TextButton(shape = ActivityControlShape, onClick = { open(Settings.ACTION_DATE_SETTINGS) }) { Text("检查自动时间") }
                        else -> TextButton(shape = ActivityControlShape, onClick = { open(Settings.ACTION_WIRELESS_SETTINGS) }) { Text("检查网络") }
                    }
                }
                if (phase == SyncPhase.NETWORK) Text("恢复网络后自动上传，本机积压记录会保留。", style = MaterialTheme.typography.bodyMedium)
                Button(shape = ActivityControlShape, onClick = { sync() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = local.enabled && local.configured && local.permission && !local.running && !local.immediateQueued && !submitting) {
                    Text(if (submitting || local.immediateQueued) "已提交，等待执行" else if (local.running) "同步中…" else if (local.error.isNotBlank()) "重试同步" else "立即同步")
                }
                if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(shape = ActivityControlShape, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(local.server))) }
                    .onFailure { notice = "无法打开网页，请检查浏览器是否可用。" }
            }, modifier = Modifier.fillMaxWidth()) { Text("打开完整网页") }
        }
    }
}
