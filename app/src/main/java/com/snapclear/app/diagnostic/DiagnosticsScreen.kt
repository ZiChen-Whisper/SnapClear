package com.snapclear.app.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapclear.app.ui.StatusBadge
import com.snapclear.app.ui.theme.StatusDenied
import com.snapclear.app.ui.theme.StatusGranted
import com.snapclear.app.ui.theme.StatusWarning

private val LogBg = Color(0xFF101716)
private val LogMuted = Color(0xFF7E8C89)

/**
 * 诊断面板二级页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onRunDetection: () -> Unit,
    onCreateTestScreenshot: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<DiagnosticsProvider.DiagnosticsData?>(null) }
    var events by remember { mutableStateOf(DiagnosticLogger.getEvents()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val listener = { events = DiagnosticLogger.getEvents() }
        DiagnosticLogger.listener = listener
        onDispose { DiagnosticLogger.listener = null }
    }

    LaunchedEffect(refreshTrigger) {
        data = DiagnosticsProvider.collect(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("诊断面板", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    com.snapclear.app.ui.CircularBackButton(onBack = onBack)
                },
                actions = {
                    IconButton(onClick = { refreshTrigger++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 操作按钮：复制全部 / 分享
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TonalActionButton(
                    text = "复制全部",
                    icon = Icons.Default.ContentCopy,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val text = buildDiagnosticText(data, events)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SnapClear 诊断信息", text))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                )
                TonalActionButton(
                    text = "分享",
                    icon = Icons.Default.Share,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val text = buildDiagnosticText(data, events)
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "分享诊断信息"))
                    }
                )
            }

            // 操作按钮：立即检测 / 测试截图 / 测试通知
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TonalActionButton(
                    text = "立即检测",
                    icon = Icons.Default.Refresh,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                    onClick = { onRunDetection(); refreshTrigger++ }
                )
                TonalActionButton(
                    text = "测试截图",
                    icon = Icons.Default.BugReport,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                    onClick = { onCreateTestScreenshot(); refreshTrigger++ }
                )
                TonalActionButton(
                    text = "测试通知",
                    icon = Icons.Default.Share,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                    onClick = { onSendTestNotification() }
                )
            }

            val d = data

            if (d != null) {
                DiagCard(title = "服务状态") {
                    DiagRow("服务运行中", d.serviceRunning)
                    DiagRow("监听意愿 (persisted)", d.monitoringEnabled)
                    DiagRow("通知权限", d.notificationPermissionGranted)
                    DiagRow("媒体权限", d.mediaPermissionGranted)
                    DiagRow("精确闹钟权限", d.exactAlarmGranted)
                    DiagRow("电池优化豁免", d.batteryOptExempt)
                    DiagRow("全屏通知意图", d.fullScreenIntentGranted)
                    DiagRow("流体云(Live Updates)", d.promotedNotificationsGranted)
                }

                DiagCard(title = "lastDetectedId（检测游标）") {
                    InfoRow("内存值", d.lastDetectedId.toString())
                    InfoRow("持久化值 (SharedPreferences)", d.persistedLastDetectedId.toString())
                    InfoRow("MediaStore 最大 ID", d.mediaStoreMaxId.toString())
                    val diff = d.mediaStoreMaxId - d.lastDetectedId
                    InfoRow(
                        "差距 (max - cursor)",
                        diff.toString(),
                        valueColor = when {
                            diff == 0L -> StatusGranted
                            diff in 1..50 -> StatusWarning
                            else -> StatusDenied
                        }
                    )
                    if (d.lastDetectedId < d.persistedLastDetectedId) {
                        HintText("⚠ 内存值 < 持久化值！initLastDetectedId 可能未正确恢复", StatusDenied)
                    }
                    if (d.lastDetectedId > d.mediaStoreMaxId) {
                        HintText(
                            "⚠ 游标 > MediaStore 最大 ID（可能因截图被删除所致，新截图 ID 仍大于游标，不影响检测）",
                            StatusWarning
                        )
                    }
                }

                DiagCard(title = "MediaStore 统计") {
                    InfoRow("图片总数", d.mediaStoreImageCount.toString())
                    InfoRow("截图数 (匹配)", d.mediaStoreScreenshotCount.toString())
                }

                DiagCard(title = "截图目录状态") {
                    d.screenshotDirs.forEach { dir ->
                        InfoRow(dir.path, if (dir.exists) "存在, ${dir.fileCount} 个文件" else "不存在")
                    }
                }

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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(80.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(img.displayName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                Text(img.relativePath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = if (img.isScreenshot) "截图" else "非截图",
                                fontSize = 10.sp,
                                color = if (img.isScreenshot) StatusGranted else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Text("收集数据中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "事件日志 (${events.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = { DiagnosticLogger.clear(); events = emptyList() }) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清除")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LogBg)
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (events.isEmpty()) {
                        Text("暂无事件", color = LogMuted, fontSize = 11.sp)
                    }
                    events.takeLast(50).forEach { event ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                text = event.timeStr,
                                color = LogMuted,
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

            Spacer(modifier = Modifier.height(8.dp))
            Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun TonalActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun DiagRow(label: String, value: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StatusBadge(
            text = if (value) "是" else "否",
            color = if (value) StatusGranted else StatusDenied
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

@Composable
private fun HintText(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
}

private fun eventColor(type: DiagnosticEventType): Color = when (type) {
    DiagnosticEventType.SCREENSHOT -> Color(0xFF4CAF50)
    DiagnosticEventType.NOTIFY -> Color(0xFF2196F3)
    DiagnosticEventType.ERROR -> Color(0xFFFF5252)
    DiagnosticEventType.WARNING -> Color(0xFFFF9800)
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
        sb.appendLine("- 全屏通知意图: ${data.fullScreenIntentGranted}")
        sb.appendLine("- 流体云(Live Updates): ${data.promotedNotificationsGranted}")
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
            sb.appendLine("- ⚠ 游标 > MediaStore 最大 ID（可能因截图被删除所致，新截图 ID 仍大于游标，不影响检测）")
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
