package com.snapclear.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapclear.app.permission.PermissionManager
import com.snapclear.app.ui.theme.StatusDenied
import com.snapclear.app.ui.theme.StatusGranted

/**
 * 权限管理二级页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    permissionStates: Map<String, Boolean>,
    exactAlarmGranted: Boolean,
    batteryOptimizationExempt: Boolean,
    promotedNotificationsGranted: Boolean,
    screenshotAccessibilityEnabled: Boolean,
    onBack: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenOppoBackgroundSettings: () -> Unit,
    onOpenScreenshotAccessibilitySettings: () -> Unit,
    onOpenPromotedNotificationsSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val requiredPermissions = PermissionManager.getRequiredPermissions()
    val grantedCount = requiredPermissions.count { permissionStates[it] == true }
    val needsAccessibility = PermissionManager.isOppoDevice()
    val extraCount = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1 else 0) + 1 +
        (if (Build.VERSION.SDK_INT >= 36) 1 else 0) + (if (needsAccessibility) 1 else 0)
    val totalCount = requiredPermissions.size + extraCount
    val extraGranted = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAlarmGranted) &&
        batteryOptimizationExempt &&
        (Build.VERSION.SDK_INT < 36 || promotedNotificationsGranted) &&
        (!needsAccessibility || screenshotAccessibilityEnabled)
    val allGranted = grantedCount == requiredPermissions.size && extraGranted
    val needsExactAlarm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val needsPromoted = Build.VERSION.SDK_INT >= 36

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("权限管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    CircularBackButton(onBack = onBack)
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
            SummaryCard(allGranted = allGranted)

            requiredPermissions.forEach { permission ->
                val (icon, name, desc) = getPermissionInfo(permission)
                PermissionCard(
                    icon = icon,
                    name = name,
                    description = desc,
                    isGranted = permissionStates[permission] == true,
                    onRequest = {
                        if (permissionStates[permission] == true) onOpenAppSettings()
                        else onRequestPermission(permission)
                    }
                )
            }

            if (needsExactAlarm) {
                PermissionCard(
                    icon = Icons.Default.Alarm,
                    name = "精确闹钟权限",
                    description = if (exactAlarmGranted)
                        "后台深度休眠时及时检测截图"
                    else
                        "未开启将导致后台截图检测延迟",
                    isGranted = exactAlarmGranted,
                    onRequest = onOpenExactAlarmSettings
                )
            }

            PermissionCard(
                icon = if (batteryOptimizationExempt)
                    Icons.Default.BatteryChargingFull
                else
                    Icons.Default.BatteryAlert,
                name = "电池优化豁免",
                description = if (batteryOptimizationExempt)
                    "系统白名单已开启；ColorOS 后台活动仍需单独确认"
                else
                    "未豁免，后台服务可能被系统杀死",
                isGranted = batteryOptimizationExempt,
                onRequest = onRequestBatteryOptimization
            )

            if (PermissionManager.isOppoDevice()) {
                PermissionCard(
                    icon = Icons.Default.TouchApp,
                    name = "截图实时检测（无障碍）",
                    description = if (screenshotAccessibilityEnabled)
                        "已开启；监听系统窗口增删，不读取窗口或输入内容"
                    else
                        "需手动开启，否则 ColorOS 后台冻结时无法实时通知",
                    isGranted = screenshotAccessibilityEnabled,
                    actionLabel = "去开启",
                    onRequest = onOpenScreenshotAccessibilitySettings
                )

                PermissionCard(
                    icon = Icons.Default.BatteryAlert,
                    name = "ColorOS 后台运行",
                    description = "请确认允许后台活动和自启动，并在最近任务中锁定 SnapClear",
                    isGranted = false,
                    actionLabel = "去设置",
                    onRequest = onOpenOppoBackgroundSettings
                )
            }

            if (needsPromoted) {
                PermissionCard(
                    icon = Icons.Default.Waves,
                    name = "流体云通知",
                    description = if (promotedNotificationsGranted)
                        "截图通知将以流体云形式实时显示"
                    else
                        "未开启将导致后台截图通知延迟",
                    isGranted = promotedNotificationsGranted,
                    onRequest = onOpenPromotedNotificationsSettings
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun SummaryCard(allGranted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (allGranted)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (allGranted) Icons.Default.Waves else Icons.Default.BatteryAlert,
            contentDescription = null,
            tint = if (allGranted) StatusGranted else StatusDenied,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = if (allGranted) "全部权限已就绪" else "部分权限待授予",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (allGranted)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = if (allGranted)
                    "可返回主页开启监听"
                else
                    "授权后才能稳定进行后台截图检测",
                style = MaterialTheme.typography.bodySmall,
                color = if (allGranted)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                else
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    name: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String = "授权",
    onRequest: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isGranted)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isGranted) {
                StatusBadge(text = "已授权", color = StatusGranted)
            } else {
                Button(
                    onClick = onRequest,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(actionLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun getPermissionInfo(permission: String): Triple<ImageVector, String, String> {
    return when {
        permission == Manifest.permission.READ_MEDIA_IMAGES ||
        permission == Manifest.permission.READ_EXTERNAL_STORAGE -> {
            Triple(Icons.Default.Image, "存储/媒体权限", "用于读取截图文件")
        }
        permission == Manifest.permission.POST_NOTIFICATIONS -> {
            Triple(Icons.Default.Notifications, "通知权限", "用于发送截图操作通知")
        }
        else -> {
            Triple(Icons.Default.Image, permission, "")
        }
    }
}
