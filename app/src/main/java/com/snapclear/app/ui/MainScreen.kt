package com.snapclear.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.snapclear.app.R
import com.snapclear.app.permission.PermissionManager
import com.snapclear.app.ui.theme.SnapClearTheme
import com.snapclear.app.ui.theme.StatusDenied
import com.snapclear.app.ui.theme.StatusGranted

/**
 * 主界面
 *
 * 显示权限状态列表、电池优化引导、监听开关按钮。
 * 支持 edge-to-edge 沉浸式显示，内容自动避开系统导航栏区域。
 * 底部 Tab 可切换到诊断面板，方便排查问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionStates: Map<String, Boolean>,
    isMonitoring: Boolean,
    exactAlarmGranted: Boolean,
    batteryOptimizationExempt: Boolean,
    onRequestPermission: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onRunDetection: () -> Unit,
    onCreateTestScreenshot: () -> Unit,
    onSendTestNotification: () -> Unit,
    modifier: Modifier = Modifier
) {
    val requiredPermissions = PermissionManager.getRequiredPermissions()
    val allGranted = requiredPermissions.all { permissionStates[it] == true }
    val needsExactAlarm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Tab 状态：0 = 主界面，1 = 诊断面板
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("主页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    label = { Text("诊断") }
                )
            }
        },
        // 让内容背景延伸到导航栏下方（小白条沉浸显示）
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        if (selectedTab == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // 权限状态区域
                Text(
                    text = "权限状态",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                requiredPermissions.forEach { permission ->
                    val isGranted = permissionStates[permission] == true
                    val (icon, name, description) = getPermissionInfo(permission)

                    PermissionCard(
                        icon = icon,
                        name = name,
                        description = description,
                        isGranted = isGranted,
                        onRequestPermission = {
                            if (isGranted) {
                                onOpenAppSettings()
                            } else {
                                onRequestPermission(permission)
                            }
                        }
                    )
                }

                // 精确闹钟权限（后台深度休眠时截屏检测必需）
                AnimatedVisibility(visible = needsExactAlarm) {
                    ExactAlarmCard(
                        isGranted = exactAlarmGranted,
                        onOpenSettings = onOpenExactAlarmSettings
                    )
                }

                // 电池优化豁免卡片
                BatteryOptimizationCard(
                    isExempt = batteryOptimizationExempt,
                    onRequestExemption = onRequestBatteryOptimization
                )

                HorizontalDivider()

                // 监听控制区域
                MonitoringControlCard(
                    isMonitoring = isMonitoring,
                    allGranted = allGranted,
                    onToggleMonitoring = onToggleMonitoring,
                    onOpenAppSettings = onOpenAppSettings
                )

                Spacer(modifier = Modifier.height(16.dp))
                // 导航栏底部留白，确保最后一张卡片可滚动到小白条上方
                Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                com.snapclear.app.diagnostic.DiagnosticsScreen(
                    onRunDetection = onRunDetection,
                    onCreateTestScreenshot = onCreateTestScreenshot,
                    onSendTestNotification = onSendTestNotification
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    name: String,
    description: String,
    isGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isGranted)
                        stringResource(R.string.permission_granted)
                    else
                        stringResource(R.string.permission_denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isGranted) StatusGranted else StatusDenied,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(onClick = onRequestPermission) {
                    Text(
                        text = if (isGranted)
                            stringResource(R.string.permission_goto_settings)
                        else
                            "授权"
                    )
                }
            }
        }
    }
}

@Composable
private fun ExactAlarmCard(
    isGranted: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Alarm,
                contentDescription = null,
                tint = if (isGranted) StatusGranted else StatusDenied,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "精确闹钟权限",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isGranted)
                        "后台深度休眠时及时检测截图"
                    else
                        "未开启将导致后台截图检测延迟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isGranted)
                        stringResource(R.string.permission_granted)
                    else
                        stringResource(R.string.permission_denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isGranted) StatusGranted else StatusDenied,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(onClick = onOpenSettings) {
                    Text(
                        text = if (isGranted)
                            stringResource(R.string.permission_goto_settings)
                        else
                            "去开启"
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    isExempt: Boolean,
    onRequestExemption: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isExempt)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = if (isExempt) StatusGranted else StatusDenied,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.battery_optimization_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isExempt)
                        stringResource(R.string.battery_optimization_granted)
                    else
                        stringResource(R.string.battery_optimization_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isExempt)
                        stringResource(R.string.permission_granted)
                    else
                        stringResource(R.string.permission_denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isExempt) StatusGranted else StatusDenied,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(onClick = onRequestExemption) {
                    Text(
                        text = if (isExempt)
                            stringResource(R.string.battery_optimization_goto_settings)
                        else
                            stringResource(R.string.battery_optimization_request)
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitoringControlCard(
    isMonitoring: Boolean,
    allGranted: Boolean,
    onToggleMonitoring: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (allGranted) {
                Text(
                    text = stringResource(R.string.all_permissions_granted),
                    style = MaterialTheme.typography.bodyLarge,
                    color = StatusGranted,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = stringResource(R.string.permission_required),
                    style = MaterialTheme.typography.bodyLarge,
                    color = StatusDenied,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (allGranted) {
                        onToggleMonitoring()
                    } else {
                        onOpenAppSettings()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (allGranted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(
                    imageVector = if (isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isMonitoring)
                        stringResource(R.string.stop_monitoring)
                    else
                        stringResource(R.string.start_monitoring)
                )
            }

            if (isMonitoring) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.monitoring_active),
                    style = MaterialTheme.typography.labelLarge,
                    color = StatusGranted
                )
            }
        }
    }
}

/**
 * 根据权限名返回对应的图标、显示名称和描述
 */
private fun getPermissionInfo(permission: String): Triple<ImageVector, String, String> {
    return when {
        permission == Manifest.permission.READ_MEDIA_IMAGES ||
        permission == Manifest.permission.READ_EXTERNAL_STORAGE -> {
            Triple(
                Icons.Default.Image,
                "存储/媒体权限",
                "用于读取截图文件"
            )
        }
        permission == Manifest.permission.POST_NOTIFICATIONS -> {
            Triple(
                Icons.Default.Notifications,
                "通知权限",
                "用于发送截图操作通知"
            )
        }
        permission == Manifest.permission.SYSTEM_ALERT_WINDOW -> {
            Triple(
                Icons.AutoMirrored.Filled.OpenInNew,
                "悬浮窗权限",
                "用于显示快捷操作（后续阶段）"
            )
        }
        else -> {
            Triple(Icons.Default.Info, permission, "")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    SnapClearTheme {
        MainScreen(
            permissionStates = mapOf(
                Manifest.permission.READ_MEDIA_IMAGES to true,
                Manifest.permission.POST_NOTIFICATIONS to false
            ),
            isMonitoring = false,
            exactAlarmGranted = false,
            batteryOptimizationExempt = false,
            onRequestPermission = {},
            onOpenAppSettings = {},
            onOpenExactAlarmSettings = {},
            onRequestBatteryOptimization = {},
            onToggleMonitoring = {},
            onRunDetection = {},
            onCreateTestScreenshot = {},
            onSendTestNotification = {}
        )
    }
}
