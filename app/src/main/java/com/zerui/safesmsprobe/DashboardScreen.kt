package com.zerui.safesmsprobe

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.zerui.safesmsprobe.activitylog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class DashboardState : androidx.lifecycle.ViewModel() {
    var data by mutableStateOf<JSONObject?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var server by mutableStateOf<String?>(null)
    private val mutex = Mutex()
    suspend fun refresh() {
        val address = server ?: return
        if (!mutex.tryLock()) return
        loading = true
        try {
            val next = withContext(Dispatchers.IO) { DashboardApi.read(address) }
            if (server == address) { data = next; error = null }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            if (server == address) error = if (data == null) "无法读取服务端统计，请检查网络和连接配置。" else "刷新失败，保留上次读取的数据。"
        } finally { loading = false; mutex.unlock() }
    }
}

@Composable
private fun CompactTrend(points: List<JSONObject>, field: String, date: String, hasSnapshot: Boolean, select: (String) -> Unit) {
    if (points.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val blue = Color(0xFF5B88FF)
    val amber = Color(0xFFFFBA59)
    val maximum = points.maxOf { it.getLong(field) }.coerceAtLeast(1)
    val selectedIndex = points.indexOfFirst { it.getString("date") == date }
    fun unknown(point: JSONObject) = !hasSnapshot || (point.getBoolean("incomplete") && point.getLong(field) == 0L)
    fun description(point: JSONObject): String = point.getString("date") + " · " +
        if (unknown(point)) "暂无记录" else
            (if (field == "screen_ms") "亮屏 " + DashboardFormat.duration(point.getLong(field)) else "解锁 ${point.getLong(field)} 次") +
                if (point.getBoolean("incomplete")) " · 采集不完整" else ""
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A narrow slot per day keeps 30 days in one view and seven days closely grouped.
        Canvas(Modifier.width(minOf(maxWidth, (points.size * 12).dp)).height(96.dp)
            .pointerInput(points, field) {
                detectTapGestures { offset -> select(points[(offset.x / size.width * points.size).toInt().coerceIn(points.indices)].getString("date")) }
            }
            .semantics {
                contentDescription = "每日趋势，" + if (field == "screen_ms") "亮屏时长" else "解锁次数"
                stateDescription = points.getOrNull(selectedIndex)?.let { description(it) } ?: "所选日期不在此范围"
                customActions = points.map { point -> CustomAccessibilityAction(description(point)) { select(point.getString("date")); true } }
            }) {
            val step = size.width / points.size
            val gap = minOf(3.dp.toPx(), step * .25f)
            val width = step - gap
            val baseline = size.height - 8.dp.toPx()
            val height = 80.dp.toPx()
            points.forEachIndexed { index, point ->
                val x = index * step + gap / 2
                val missing = unknown(point)
                val value = point.getLong(field)
                val color = when {
                    missing || value == 0L -> colors.outlineVariant
                    point.getBoolean("incomplete") || index == selectedIndex -> amber
                    else -> blue
                }
                val h = if (missing || value == 0L) 2.dp.toPx() else (value.toFloat() / maximum * height).coerceAtLeast(3.dp.toPx())
                if (missing) {
                    drawRect(color, Offset(x, baseline - h), Size(width * .35f, h))
                    drawRect(color, Offset(x + width * .65f, baseline - h), Size(width * .35f, h))
                } else drawRoundRect(color, Offset(x, baseline - h), Size(width, h), CornerRadius(1.5.dp.toPx()))
                if (index == selectedIndex) drawCircle(colors.onSurface, 1.5.dp.toPx(), Offset(x + width / 2, baseline + 5.dp.toPx()))
            }
        }
    }
    Text(points.first().getString("date").substring(5) + " — " + points.last().getString("date").substring(5),
        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(points.getOrNull(selectedIndex)?.let { description(it) } ?: "点选柱条查看日期与数值", Modifier.weight(1f)
            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        TextButton(shape = ActivityControlShape, onClick = { select(points[selectedIndex - 1].getString("date")) }, enabled = selectedIndex > 0,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "趋势前一天" }, contentPadding = PaddingValues(0.dp)) { Text("‹") }
        TextButton(shape = ActivityControlShape, onClick = { select(points[if (selectedIndex < 0) 0 else selectedIndex + 1].getString("date")) }, enabled = selectedIndex < points.lastIndex,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "趋势后一天" }, contentPadding = PaddingValues(0.dp)) { Text("›") }
    }
    Text("橙色：选中 / 采集不完整 · 灰线：零 · 灰虚线：缺失", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
}

@Composable
private fun CompactTimeline(day: JSONObject) {
    val elapsed = day.getLong("elapsed_ms")
    var selectedBin by rememberSaveable(day.getString("date")) {
        mutableIntStateOf(((elapsed - 1).coerceAtLeast(0) / 600000).toInt().coerceIn(0, 143))
    }
    val colors = MaterialTheme.colorScheme
    val coverage = day.getJSONArray("coverage_bins")
    val screen = day.getJSONArray("screen_bins")
    fun future(i: Int) = i * 600000L >= elapsed
    fun missing(i: Int) = coverage.getLong(i) < minOf(600000L, (elapsed - i * 600000L).coerceAtLeast(0))
    val detail = "%02d:%02d · ".format(selectedBin / 6, selectedBin % 6 * 10) + when {
        future(selectedBin) -> "尚未发生"
        missing(selectedBin) && screen.getLong(selectedBin) == 0L -> "— · 采集缺口"
        else -> "亮屏 " + DashboardFormat.duration(screen.getLong(selectedBin)) + if (missing(selectedBin)) " · 有缺口" else " · 已采集"
    }
    Text("全天 · 每格 10 分钟", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(48.dp)
        .pointerInput(day) { detectTapGestures { selectedBin = (it.x / size.width * 144).toInt().coerceIn(0, 143) } }
        .semantics {
            contentDescription = "亮屏时间轴"
            stateDescription = detail
            progressBarRangeInfo = ProgressBarRangeInfo(selectedBin.toFloat(), 0f..143f, 142)
            setProgress { selectedBin = it.toInt().coerceIn(0, 143); true }
        }) {
        val step = size.width / 144
        val barWidth = (step - .7.dp.toPx()).coerceAtLeast(.5f)
        repeat(144) { i ->
            val x = i * step
            val h = if (future(i)) 2.dp.toPx() else 22.dp.toPx()
            val y = (size.height - h) / 2
            val color = when {
                future(i) -> colors.onSurfaceVariant.copy(alpha = .4f)
                screen.getLong(i) > 0 -> colors.primary
                missing(i) -> colors.error
                else -> colors.outlineVariant
            }
            if (!future(i) && missing(i)) {
                repeat(6) { stripe -> drawRect(color, Offset(x, y + stripe * 4.dp.toPx()), Size(barWidth, 2.dp.toPx())) }
            } else drawRect(color, Offset(x, y), Size(barWidth, h))
        }
        val x = (selectedBin + .5f) * step
        drawLine(colors.onSurface, Offset(x, 8.dp.toPx()), Offset(x, size.height - 8.dp.toPx()), 1.dp.toPx())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("00", "06", "12", "18", "24").forEach { Text(it, fontSize = 10.sp, color = colors.onSurfaceVariant) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(detail, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        TextButton(shape = ActivityControlShape, onClick = { selectedBin-- }, enabled = selectedBin > 0,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "上一时间段" }, contentPadding = PaddingValues(0.dp)) { Text("‹") }
        TextButton(shape = ActivityControlShape, onClick = { selectedBin++ }, enabled = selectedBin < 143,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "下一时间段" }, contentPadding = PaddingValues(0.dp)) { Text("›") }
    }
    Text("实线 亮屏 / 已采集 · 虚线 缺口 · 灰点 未发生", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
}

@Composable
fun DashboardRefresh(state: DashboardState, server: String?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(server, lifecycle) {
        if (state.server != server) { state.data = null; state.error = null; state.server = server }
        if (server != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { state.refresh(); delay(60000) }
        }
    }
}

@Composable
fun DashboardMessage(state: DashboardState, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    if (state.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.data == null) Text("正在读取统计…", style = MaterialTheme.typography.bodyMedium)
    }
    state.error?.let { Text("⚠ " + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    state.data?.let {
        if (!compact) Text("更新于 " + DashboardFormat.stamp(it.getLong("server_time")), style = MaterialTheme.typography.bodyMedium)
        val received = it.optJSONObject("snapshot")?.numberOrNull("received")
        if (received != null && System.currentTimeMillis() - received > 7200000)
            Text("⚠ 最近上报已超过 2 小时，统计可能不完整。", style = MaterialTheme.typography.bodyMedium)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (compact) Text(state.data?.let { "更新于 " + DashboardFormat.stamp(it.getLong("server_time")) } ?: "尚无统计",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(shape = ActivityControlShape, onClick = { scope.launch { state.refresh() } }, enabled = !state.loading && state.server != null) {
            Text(if (state.error == null) "刷新统计" else "重试读取", style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun DashboardScreen(state: DashboardState, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf("") }
    var range by rememberSaveable { mutableIntStateOf(7) }
    var field by rememberSaveable { mutableStateOf("screen_ms") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val root = state.data
    val days = root?.getJSONArray("daily")?.objects().orEmpty()
    val day = days.firstOrNull { it.getString("date") == selected } ?: days.lastOrNull()
    val date = day?.getString("date").orEmpty()
    val events = root?.getJSONArray("timeline")?.objects().orEmpty().filter {
        DashboardFormat.date(it.getLong("at")) == date && (filter == "all" || filter == it.getString("kind"))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(shape = ActivityControlShape, onClick = onBack) { Text("‹ 返回首页") }
            Text("记录详情", style = MaterialTheme.typography.headlineSmall)
            Text("服务端统计 · 北京时间", style = MaterialTheme.typography.bodyMedium)
            DashboardMessage(state)
        }
        if (day != null) {
            item {
                StatusCard("日期") {
                    TextButton(shape = ActivityControlShape, onClick = {
                        val parsed = LocalDate.parse(date)
                        DatePickerDialog(context, { _, y, m, d -> selected = LocalDate.of(y, m + 1, d).toString() },
                            parsed.year, parsed.monthValue - 1, parsed.dayOfMonth).apply {
                            datePicker.minDate = LocalDate.parse(days.first().getString("date")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
                            datePicker.maxDate = LocalDate.parse(days.last().getString("date")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
                        }.show()
                    }) { Text(date + " · 选择日期") }
                    Row {
                        TextButton(shape = ActivityControlShape, enabled = date != days.first().getString("date"), onClick = { selected = LocalDate.parse(date).minusDays(1).toString() }) { Text("‹ 前一天") }
                        TextButton(shape = ActivityControlShape, enabled = date != days.last().getString("date"), onClick = { selected = LocalDate.parse(date).plusDays(1).toString() }) { Text("后一天 ›") }
                    }
                    val available = DashboardFormat.available(root?.optJSONObject("snapshot") != null, day.getBoolean("incomplete"),
                        day.getLong("unlocks"), day.getLong("screen_ms"), day.getLong("unlocked_ms"))
                    Text("亮屏时长  " + DashboardFormat.duration(day.getLong("screen_ms").takeIf { available }))
                    Text("解锁次数  " + if (available) day.getLong("unlocks").toString() else "—")
                    Text(if (!available) "暂无可用记录。" else if (day.getBoolean("incomplete")) "⚠ 采集不完整，缺失不等于没有使用。" else "仅计完整区间；系统仍可能漏记。",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                StatusCard("趋势") {
                    Text("时间范围", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActivitySegmentedControl(listOf(7 to "近 7 天", 30 to "近 30 天"), range, { range = it })
                    Spacer(Modifier.height(4.dp))
                    Text("统计指标", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActivitySegmentedControl(listOf("screen_ms" to "亮屏时长", "unlocks" to "解锁次数"), field, { field = it })
                    CompactTrend(days.takeLast(range), field, date, root?.optJSONObject("snapshot") != null) { selected = it }
                }
            }
            item {
                StatusCard("亮屏时间轴") {
                    CompactTimeline(day)
                }
            }
            item {
                Text("事件记录", style = MaterialTheme.typography.titleLarge)
                Text("从最近 100 条原始事件中筛选", style = MaterialTheme.typography.bodyMedium)
                ActivitySegmentedControl(
                    listOf("all" to "全部", "unlock" to "解锁", "screen_on" to "亮屏", "screen_off" to "熄屏", "lock" to "锁屏"),
                    filter,
                    { filter = it }
                )
            }
            if (events.isEmpty()) item { Text("暂无符合条件的事件；较早事件可能超出最近 100 条范围。") }
            items(events, key = { it.getString("id") }) { event ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DashboardFormat.time(event.getLong("at")) + "  " +
                        (mapOf("unlock" to "解锁", "lock" to "锁屏", "screen_on" to "亮屏", "screen_off" to "熄屏", "reset" to "采集边界重置")[event.getString("kind")] ?: "其他事件"))
                    HorizontalDivider()
                }
            }
        }
    }
}

