package com.zerui.safesmsprobe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

val ActivityControlShape = RoundedCornerShape(12.dp)
val ActivityCardShape = RoundedCornerShape(20.dp)
private val ActivitySegmentGroupShape = RoundedCornerShape(10.dp)
private val ActivitySegmentItemShape = RoundedCornerShape(7.dp)

val ActivityDarkColors = darkColorScheme(
    primary = Color(0xFFACCDFF), onPrimary = Color(0xFF122D4B),
    primaryContainer = Color(0xFF294768), onPrimaryContainer = Color(0xFFD5E6FF),
    secondaryContainer = Color(0xFF28493D), onSecondaryContainer = Color(0xFFD7F3E6),
    background = Color(0xFF10151B), onBackground = Color(0xFFF0F3F5),
    surface = Color(0xFF10151B), onSurface = Color(0xFFF0F3F5),
    surfaceContainer = Color(0xFF1C242E), onSurfaceVariant = Color(0xFF9BAAB9),
    outline = Color(0xFF536170), outlineVariant = Color(0xFF303C49),
    secondary = Color(0xFFBCE6D4), error = Color(0xFFFFB4AB)
)
val ActivityLightColors = lightColorScheme(
    primary = Color(0xFF245DA0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E6FF), onPrimaryContainer = Color(0xFF122D4B),
    secondaryContainer = Color(0xFFD7EAE0), onSecondaryContainer = Color(0xFF244B3B),
    background = Color(0xFFF2F4F5), onBackground = Color(0xFF17212D),
    surface = Color(0xFFF2F4F5), onSurface = Color(0xFF17212D),
    surfaceContainer = Color.White, onSurfaceVariant = Color(0xFF5D6D7C),
    outline = Color(0xFF778694), outlineVariant = Color(0xFFD5DDE3),
    secondary = Color(0xFF326E58)
)

/** A compact, full-width single-choice control. The group owns the outline;
 * individual options stay rectangular enough to read as tabs instead of pills. */
@Composable
fun <T> ActivitySegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ActivitySegmentGroupShape,
        color = colors.surfaceVariant.copy(alpha = .7f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { (value, label) ->
                val active = value == selected
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = active,
                            onClick = { onSelect(value) },
                            role = Role.RadioButton
                        ),
                    shape = ActivitySegmentItemShape,
                    color = if (active) colors.primaryContainer else Color.Transparent,
                    contentColor = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The ring is a status ornament, not a percentage or a live background heartbeat. */
@Composable
fun CollectionDial(local: LocalStatus?) {
    val colors = MaterialTheme.colorScheme
    val ready = local?.let { it.configured && it.permission && it.enabled } == true
    val accent = when {
        local == null -> colors.onSurfaceVariant
        !local.configured -> colors.primary
        !local.permission -> colors.error
        !local.enabled -> colors.onSurfaceVariant
        else -> colors.primary
    }
    val title = when {
        local == null -> "读取中"
        !local.configured -> "待连接"
        !local.permission -> "待授权"
        !local.enabled -> "已暂停"
        else -> "采集已启用"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension * .43f
                // Fine outer markers give the status display the proportions of a dial.
                repeat(60) { index ->
                    val angle = Math.toRadians(index * 6.0 - 90)
                    val inner = radius + if (index % 5 == 0) 8.dp.toPx() else 11.dp.toPx()
                    val outer = radius + 15.dp.toPx()
                    drawLine(if (index % 5 == 0) accent.copy(alpha = .65f) else colors.outlineVariant,
                        Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                        Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                        1.dp.toPx(), StrokeCap.Round)
                }
                drawCircle(accent.copy(alpha = .035f), radius - 10.dp.toPx(), center)
                drawCircle(colors.outlineVariant, radius, center, style = Stroke(2.dp.toPx()))
                drawArc(accent, 140f, 260f, false,
                    Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(accent.copy(alpha = .13f), radius - 18.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 38.dp)) {
                Text("本 机 状 态", color = colors.onSurfaceVariant, fontSize = 11.sp)
                Text(title, fontSize = 29.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).background(if (ready) colors.secondary else accent, CircleShape))
                    Text(if (ready) "使用情况访问已授权" else "等待" + when {
                        local == null -> "读取本机状态"
                        !local.configured -> "连接服务器"
                        !local.permission -> "使用情况授权"
                        else -> "恢复采集"
                    }, fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
            }
        }
        Text("系统定期补采 · 后台可能延迟", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TodayMetric(label: String, value: String, unit: String, blue: Boolean, modifier: Modifier = Modifier) {
    val ink = Color(0xFF1D344C)
    Surface(modifier, shape = ActivityCardShape, color = if (blue) Color(0xFFACCDFF) else Color(0xFFE3ECE7), contentColor = ink) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontSize = 13.sp, color = ink.copy(alpha = .75f))
            Text(value, fontSize = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
            Text(unit, fontSize = 11.sp, color = ink.copy(alpha = .65f))
        }
    }
}

@Composable
fun SyncDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
