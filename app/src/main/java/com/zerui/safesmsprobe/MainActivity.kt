package com.zerui.safesmsprobe

import android.content.Intent
import androidx.activity.enableEdgeToEdge
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zerui.safesmsprobe.activitylog.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) ActivityDarkColors else ActivityLightColors,
                shapes = Shapes(small = ActivityControlShape, medium = ActivityCardShape, large = ActivityCardShape)) {
                var page by rememberSaveable { mutableStateOf("home") }
                val dashboard = remember { androidx.lifecycle.ViewModelProvider(this@MainActivity)[DashboardState::class.java] }
                val local = rememberLocalStatus()
                var onboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(local?.configured) { if (onboarding == null && local != null) onboarding = !local.configured }
                DashboardRefresh(dashboard, local?.server?.takeIf { local.configured })
                BackHandler(page != "home") { page = "home" }
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when (page) {
                        "settings" -> ActivityScreen { page = "home" }
                        "details" -> DashboardScreen(dashboard) { page = "home" }
                        else -> CollectorHome(local, dashboard, onboarding == true, { onboarding = false }, { page = "settings" }, { page = "details" })
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (ActivitySettings(this).enabled) { ActivityWork.schedule(this); ActivityWork.now(this) }
    }

    @Composable private fun ActivityScreen(onBack: () -> Unit) {
        val settings = remember { ActivitySettings(this) }
        var interval by remember { mutableIntStateOf(settings.intervalMinutes) }
        var server by remember { mutableStateOf(settings.server) }
        var token by remember { mutableStateOf("") }
        var enabled by remember { mutableStateOf(settings.enabled) }
        var hasToken by remember { mutableStateOf(settings.token() != null) }
        var permission by remember { mutableStateOf(ActivityCollector.permitted(this)) }
        var status by remember { mutableStateOf("尚未同步") }
        var cursor by remember { mutableStateOf("0") }
        var lastUpload by remember { mutableStateOf("0") }
        var diagnostic by remember { mutableStateOf("") }
        var pending by remember { mutableStateOf(0L) }
        var message by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) {
                permission = ActivityCollector.permitted(this@MainActivity)
                val info = withContext(Dispatchers.IO) { ActivityStore(this@MainActivity).use {
                    listOf(it.get("status", "尚未同步"), it.get("cursor", "0"), it.get("last_upload", "0"), it.get("diagnostic"), it.pending().toString())
                } }
                status = info[0]; cursor = info[1]; lastUpload = info[2]; diagnostic = info[3]; pending = info[4].toLong()
                delay(3000)
            } }
        }
        fun time(value: String): String = value.toLongOrNull()?.takeIf { it > 0 }?.let {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }.format(Date(it))
        } ?: "尚无记录"
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(12.dp))
            TextButton(shape = ActivityControlShape, onClick = onBack) { Text("‹ 返回首页") }
            Text("设置", style = MaterialTheme.typography.headlineLarge)
            Text("让关心有处可看 · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("系统记录解锁与亮屏历史，本应用定期补采并上传。无需一直打开，后台同步可能延迟。")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接配置", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(server, { server = it }, label = { Text("HTTPS 服务器地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text(if(hasToken) "新上传密钥（留空保留）" else "上传密钥") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("网页公开查看，无需密码；上传密钥仅供本机同步使用。更换服务器会重新上传本机保留的记录。", style = MaterialTheme.typography.bodyMedium)
                Button(shape = ActivityControlShape, enabled = !saving, onClick = { saving = true; scope.launch {
                    message = withContext(Dispatchers.IO) { synchronized(ActivityWork.lock) { runCatching {
                        val old = settings.server
                        settings.save(server, token.trim().ifEmpty { settings.token().orEmpty() })
                        if (old != settings.server) ActivityStore(this@MainActivity).use { store ->
                            store.writableDatabase.execSQL("UPDATE events SET sent=0")
                            store.writableDatabase.execSQL("UPDATE coverage SET sent=0")
                            store.put("last_upload", "0")
                            store.put("sync_error", "")
                            store.put("status", "连接已更改，等待同步")
                        }
                        "连接配置已保存"
                    }.getOrElse { it.message ?: "保存失败" } } }
                hasToken = settings.token() != null
                if (message == "连接配置已保存") {
                    token = ""
                    if (settings.enabled) ActivityWork.now(this@MainActivity, retryUpload = true)
                }
                saving = false
                } }) { Text("保存连接配置") }
            } }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("采集与权限", style = MaterialTheme.typography.titleLarge)
                Text("使用情况访问：${if(permission) "已授权" else "未授权"}")
                OutlinedButton(shape = ActivityControlShape, onClick = { runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.onFailure { message = "请在系统设置中搜索：使用情况访问权限" } }) { Text("打开使用情况授权") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if(enabled) "采集已启用" else "采集已暂停", Modifier.weight(1f))
                    Switch(enabled, onCheckedChange = { checked ->
                        if (checked && (!hasToken || !permission)) message = "请先保存连接配置并授予使用情况访问权限"
                        else {
                            settings.enabled = checked; enabled = checked
                            if(checked) { ActivityWork.schedule(this@MainActivity); ActivityWork.now(this@MainActivity) }
                            else ActivityWork.cancel(this@MainActivity)
                        }
                    })
                }
                Text("采集目标间隔")
                ActivitySegmentedControl(
                    listOf(5 to "5 分钟", 15 to "15 分钟", 30 to "30 分钟"),
                    interval,
                    { interval = it }
                )
                Button(shape = ActivityControlShape, onClick = {
                    settings.intervalMinutes = interval
                    if (settings.enabled) ActivityWork.schedule(this@MainActivity)
                    message = if (settings.enabled) "间隔已保存，已更新唯一周期任务" else "间隔已保存，采集仍暂停"
                }) { Text("保存采集间隔") }
                Text("后台周期任务最短 15 分钟：选择 5 分钟时按 15 分钟调度。所有间隔均可能受系统限制而延迟，不保证准点。首次从今日零点补采，恢复后补采可用历史。", style = MaterialTheme.typography.bodyMedium)
            } }
            if(message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("诊断信息", style = MaterialTheme.typography.titleLarge)
                Text(status)
                Text("采集至：${time(cursor)}\n最近上传：${time(lastUpload)}\n待上传记录：$pending")
                Text(when(diagnostic) {
                    "permission_denied" -> "未授权：无法读取解锁与亮屏记录"
                    "user_locked" -> "重启后需先解锁一次"
                    "history_gap" -> "历史可能缺失，网页会保守统计"
                    "clock_changed" -> "手机时间回拨，请校准自动时间"
                    "query_failed" -> "系统历史读取失败，稍后重试"
                    else -> "解锁表示锁屏解除，不证明本人身份。没有记录不等于没有使用。"
                })
            } }
            Text("亲友网页：${settings.server}\n只采集活动时间、电量和同步状态。手机被强制停止后需重新打开本应用。", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
        }
    }
}


