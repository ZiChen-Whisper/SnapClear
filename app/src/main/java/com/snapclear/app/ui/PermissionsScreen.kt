package com.snapclear.app.ui

import android.Manifest
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapclear.app.R
import com.snapclear.app.permission.PermissionManager

@Composable
fun PermissionsScreen(
    permissionStates: Map<String, Boolean>, exactAlarmGranted: Boolean,
    batteryOptimizationExempt: Boolean, promotedNotificationsGranted: Boolean,
    screenshotAccessibilityEnabled: Boolean, forceLightMode: Boolean,
    onForceLightModeChange: (Boolean) -> Unit,
    onRequestPermission: (String) -> Unit, onOpenAppSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit, onRequestBatteryOptimization: () -> Unit,
    onOpenOppoBackgroundSettings: () -> Unit,
    onOpenScreenshotAccessibilitySettings: () -> Unit,
    onOpenPromotedNotificationsSettings: () -> Unit, modifier: Modifier = Modifier
) {
    val required = PermissionManager.getRequiredPermissions(); val isOppo = PermissionManager.isOppoDevice()
    val allGranted = required.all { permissionStates[it] == true } &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAlarmGranted) && batteryOptimizationExempt &&
        (Build.VERSION.SDK_INT < 36 || promotedNotificationsGranted) &&
        (!isOppo || screenshotAccessibilityEnabled)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Spacer(Modifier.height(102.dp)); SummaryCard(allGranted)
            required.forEach { permission ->
                val info = permissionInfo(permission)
                PermissionCard(info.first, info.second, info.third, permissionStates[permission] == true,
                    onRequest = { if (permissionStates[permission] == true) onOpenAppSettings() else onRequestPermission(permission) })
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PermissionCard(R.drawable.ic_permission_alarm, "精确闹钟权限", if (exactAlarmGranted) "后台深度休眠时及时检测截图" else "未开启将导致后台检测延迟", exactAlarmGranted, onRequest = onOpenExactAlarmSettings)
            PermissionCard(R.drawable.ic_permission_battery, "电池优化豁免", if (batteryOptimizationExempt) "系统白名单已开启" else "后台服务可能被系统杀死", batteryOptimizationExempt, onRequest = onRequestBatteryOptimization)
            if (isOppo) {
                PermissionCard(R.drawable.ic_permission_accessibility, "无障碍", "用于截图实时监测", screenshotAccessibilityEnabled, "去开启", onOpenScreenshotAccessibilitySettings)
                ColorOsBackgroundCard(onOpenOppoBackgroundSettings)
            }
            if (Build.VERSION.SDK_INT >= 36) PermissionCard(R.drawable.ic_permission_live, "流体云通知", if (promotedNotificationsGranted) "截图通知将以流体云显示" else "未开启将导致通知能力降级", promotedNotificationsGranted, onRequest = onOpenPromotedNotificationsSettings)
            LightModeCard(forceLightMode, onForceLightModeChange)
            Spacer(Modifier.height(112.dp).windowInsetsPadding(WindowInsets.navigationBars))
        }
        ImmersiveTopBar { PageHeader("权限管理", "授权后保持截图检测稳定运行") }
    }
}

@Composable private fun SummaryCard(granted: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(if (granted) R.drawable.ic_status_enabled else R.drawable.ic_status_attention), null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
        Column { Text(if (granted) "全部权限已就绪" else "部分权限待授予", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text(if (granted) "可在主页开启监听" else "完成下列项目以保证后台检测", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun PermissionCard(@DrawableRes icon: Int, name: String, description: String, granted: Boolean, actionLabel: String = "授权", onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (granted) StatusBadge("已授权", MaterialTheme.colorScheme.primary) else Button(onClick = onRequest, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { Text(actionLabel) }
    }
}

@Composable private fun ColorOsBackgroundCard(onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_permission_battery), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("ColorOS 后台运行", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface); Text("允许后台活动、自启动并锁定最近任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("去设置") }
    }
}

@Composable private fun LightModeCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("始终显示浅色模式", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface); Text("忽略系统深色模式设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = enabled, onCheckedChange = onEnabledChange, thumbContent = if (enabled) {{ Icon(painterResource(R.drawable.ic_status_enabled), null, Modifier.size(14.dp)) }} else null)
    }
}

private fun permissionInfo(permission: String): Triple<Int, String, String> = when (permission) {
    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_EXTERNAL_STORAGE -> Triple(R.drawable.ic_permission_media, "存储/媒体权限", "用于读取截图文件")
    Manifest.permission.POST_NOTIFICATIONS -> Triple(R.drawable.ic_permission_notification, "通知权限", "用于发送截图操作通知")
    else -> Triple(R.drawable.ic_permission_media, permission, "")
}
