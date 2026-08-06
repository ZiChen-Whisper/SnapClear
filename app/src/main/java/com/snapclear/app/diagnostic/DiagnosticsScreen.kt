package com.snapclear.app.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Orange = Color(0xFFEF6C00)
private val Blue = Color(0xFF1565C0)

/**
 * 诊断面板
 *
 * 显示检测管线的所有内部状态 + 事件日志，
 * 并提供「立即检测」「创建测试截图」按钮。
 */
@Composable
fun DiagnosticsScreen(
    onRunDetection: () -> Unit,
    onCreateTestScreenshot: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<DiagnosticsProvider.DiagnosticsData?>(null) }
    var events by remember { mutableStateOf(DiagnosticLogger.getEvents()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // 注册日志监听，新事件到来时刷新
    DisposableEffect(Unit) {
        val listener = {
            events = DiagnosticLogger.getEvents()
        }
        DiagnosticLogger.listener = listener
        onDispose {
            DiagnosticLogger.listener = null
        }
    }

    // 收集诊断数据
    LaunchedEffect(refreshTrigger) {
        data = DiagnosticsProvider.collect(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BugReport, contentDescription = null, tint = Orange)
            Spacer(Modifier.width(8.dp))
            Text("诊断面板", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refreshTrigger++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        // 操作按钮：复制全部 / 分享
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val text = buildDiagnosticText(data, events)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SnapClear 诊断信息", text))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制全部", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    val text = buildDiagnosticText(data, events)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "分享诊断信息"))
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("分享", fontSize = 12.sp)
            }
        }

        // 操作按钮：立即检测 / 测试截图 / 测试通知
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onRunDetection(); refreshTrigger++ },
                modifier = Modifier.weight(1f)
            ) { Text("立即检测", fontSize = 12.sp) }
            Button(
                onClick = { onCreateTestScreenshot(); refreshTrigger++ },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) { Text("创建测试截图", fontSize = 12.sp) }
            Button(
                onClick = { onSendTestNotification() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)
            ) { Text("测试通知", fontSize = 12.sp) }
        }

        val d = data

        // 1. 服务状态
        if (d != null) {
            DiagCard(title = "服务状态") {
                DiagRow("服务运行中", d.serviceRunning)
                DiagRow("监听意愿 (persisted)", d.monitoringEnabled)
                DiagRow("通知权限", d.notificationPermissionGranted)
                DiagRow("媒体权限", d.mediaPermissionGranted)
                DiagRow("精确闹钟权限", d.exactAlarmGranted)
                DiagRow("电池优化豁免", d.batteryOptExempt)
            }

            // 2. lastDetectedId 状态（关键！）
            DiagCard(title = "lastDetectedId（检测游标）") {
                InfoRow("内存值", d.lastDetectedId.toString())
                InfoRow("持久化值 (SharedPreferences)", d.persistedLastDetectedId.toString())
                InfoRow("MediaStore 最大 ID", d.mediaStoreMaxId.toString())
                val diff = d.mediaStoreMaxId - d.lastDetectedId
                InfoRow(
                    "差距 (max - cursor)",
                    diff.toString(),
                    valueColor = when {
                        diff == 0L -> Green
                        diff in 1..50 -> Orange
                        else -> Red
                    }
                )
                if (d.lastDetectedId < d.persistedLastDetectedId) {
                    Text(
                        "⚠ 内存值 < 持久化值！initLastDetectedId 可能未正确恢复",
                        color = Red, fontSize = 11.sp
                    )
                }
                if (d.lastDetectedId > d.mediaStoreMaxId) {
                    Text(
                        "⚠ 游标 > MediaStore 最大 ID！截图会被跳过",
                        color = Red, fontSize = 11.sp
                    )
                }
            }

            // 3. MediaStore 统计
            DiagCard(title = "MediaStore 统计") {
                InfoRow("图片总数", d.mediaStoreImageCount.toString())
                InfoRow("截图数 (匹配)", d.mediaStoreScreenshotCount.toString())
            }

            // 4. 截图目录
            DiagCard(title = "截图目录状态") {
                d.screenshotDirs.forEach { dir ->
                    InfoRow(dir.path, if (dir.exists) "存在, ${dir.fileCount} 个文件" else "不存在")
                }
            }

            // 5. 最近 10 张图片（关键！能看到截图在 MediaStore 里的真实样子）
            DiagCard(title = "MediaStore 最近 10 张图片") {
                if (d.recentImages.isEmpty()) {
                    Text("无图片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d.recentImages.forEach { img ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = img.id.toString(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(80.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(img.displayName, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text(img.relativePath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = if (img.isScreenshot) "截图" else "非截图",
                            fontSize = 10.sp,
                            color = if (img.isScreenshot) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            Text("收集数据中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 6. 事件日志
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "事件日志 (${events.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { DiagnosticLogger.clear(); events = emptyList() }) {
                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("清除")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                if (events.isEmpty()) {
                    Text("暂无事件", color = Color(0xFF888888), fontSize = 11.sp)
                }
                events.takeLast(50).forEach { event ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(
                            text = event.timeStr,
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            text = event.type.tag,
                            color = eventColor(event.type),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(50.dp)
                        )
                        Text(
                            text = event.message,
                            color = Color(0xFFDDDDDD),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DiagRow(label: String, value: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            text = if (value) "✓ 是" else "✗ 否",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (value) Green else Red
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

private fun eventColor(type: DiagnosticEventType): Color = when (type) {
    DiagnosticEventType.SCREENSHOT -> Color(0xFF4CAF50)
    DiagnosticEventType.NOTIFY -> Color(0xFF2196F3)
    DiagnosticEventType.ERROR -> Color(0xFFFF5252)
    DiagnosticEventType.POLL -> Color(0xFF9E9E9E)
    DiagnosticEventType.TEST -> Color(0xFFFF9800)
    DiagnosticEventType.SERVICE_START -> Color(0xFF8BC34A)
    DiagnosticEventType.SERVICE_STOP -> Color(0xFFFF5252)
    else -> Color(0xFFBDBDBD)
}

/**
 * 将所有诊断信息构建为可复制的纯文本
 */
private fun buildDiagnosticText(
    data: DiagnosticsProvider.DiagnosticsData?,
    events: List<DiagnosticEvent>
): String {
    val sb = StringBuilder()
    sb.appendLine("=== SnapClear 诊断信息 ===")
    sb.appendLine("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
    sb.appendLine()

    if (data == null) {
        sb.appendLine("[数据未加载]")
    } else {
        sb.appendLine("## 服务状态")
        sb.appendLine("- 服务运行中: ${data.serviceRunning}")
        sb.appendLine("- 监听意愿 (persisted): ${data.monitoringEnabled}")
        sb.appendLine("- 通知权限: ${data.notificationPermissionGranted}")
        sb.appendLine("- 媒体权限: ${data.mediaPermissionGranted}")
        sb.appendLine("- 精确闹钟权限: ${data.exactAlarmGranted}")
        sb.appendLine("- 电池优化豁免: ${data.batteryOptExempt}")
        sb.appendLine()

        sb.appendLine("## lastDetectedId (检测游标)")
        sb.appendLine("- 内存值: ${data.lastDetectedId}")
        sb.appendLine("- 持久化值 (SharedPreferences): ${data.persistedLastDetectedId}")
        sb.appendLine("- MediaStore 最大 ID: ${data.mediaStoreMaxId}")
        sb.appendLine("- 差距 (max - cursor): ${data.mediaStoreMaxId - data.lastDetectedId}")
        if (data.lastDetectedId < data.persistedLastDetectedId) {
            sb.appendLine("- ⚠ 内存值 < 持久化值！initLastDetectedId 可能未正确恢复")
        }
        if (data.lastDetectedId > data.mediaStoreMaxId) {
            sb.appendLine("- ⚠ 游标 > MediaStore 最大 ID！截图会被跳过")
        }
        sb.appendLine()

        sb.appendLine("## MediaStore 统计")
        sb.appendLine("- 图片总数: ${data.mediaStoreImageCount}")
        sb.appendLine("- 截图数 (匹配): ${data.mediaStoreScreenshotCount}")
        sb.appendLine()

        sb.appendLine("## 截图目录状态")
        data.screenshotDirs.forEach { dir ->
            sb.appendLine("- ${dir.path}: ${if (dir.exists) "存在, ${dir.fileCount} 个文件" else "不存在"}")
        }
        sb.appendLine()

        sb.appendLine("## MediaStore 最近 10 张图片")
        if (data.recentImages.isEmpty()) {
            sb.appendLine("(无图片)")
        }
        data.recentImages.forEach { img ->
            sb.appendLine("- id=${img.id} | ${img.displayName} | ${img.relativePath} | ${if (img.isScreenshot) "[截图]" else "[非截图]"}")
        }
        sb.appendLine()
    }

    sb.appendLine("## 事件日志 (${events.size} 条)")
    if (events.isEmpty()) {
        sb.appendLine("(暂无事件)")
    } else {
        events.forEach { event ->
            sb.appendLine("[${event.timeStr}] [${event.type.tag}] ${event.message}")
        }
    }

    sb.appendLine()
    sb.appendLine("=== 诊断信息结束 ===")
    return sb.toString()
}
