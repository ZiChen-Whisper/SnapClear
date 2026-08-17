package com.snapclear.app

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.snapclear.app.notification.NotificationHelper
import com.snapclear.app.permission.PermissionManager
import com.snapclear.app.ui.PermissionsScreen
import com.snapclear.app.ui.theme.SnapClearTheme

/**
 * 权限管理二级 Activity
 *
 * 由主页入口卡片经 OPPO 无缝动画启动，承载权限授予流程。
 * 保持 edge-to-edge 与导航栏小白条沉浸。
 */
class PermissionsActivity : ComponentActivity() {

    private val permissionStates = mutableStateMapOf<String, Boolean>()
    private var exactAlarmGranted by mutableStateOf(false)
    private var batteryOptimizationExempt by mutableStateOf(false)
    private var promotedNotificationsGranted by mutableStateOf(false)
    private var screenshotAccessibilityEnabled by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (permission, granted) ->
            permissionStates[permission] = granted
        }
        refreshSpecialStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        NotificationHelper.createChannels(this)
        refreshPermissionStates()
        refreshSpecialStates()

        setContent {
            SnapClearTheme {
                PermissionsScreen(
                    permissionStates = permissionStates.toMap(),
                    exactAlarmGranted = exactAlarmGranted,
                    batteryOptimizationExempt = batteryOptimizationExempt,
                    promotedNotificationsGranted = promotedNotificationsGranted,
                    screenshotAccessibilityEnabled = screenshotAccessibilityEnabled,
                    onBack = { finish() },
                    onRequestPermission = { permission ->
                        if (shouldShowRequestPermissionRationale(permission)) {
                            showPermissionRationale(permission)
                        } else {
                            permissionLauncher.launch(arrayOf(permission))
                        }
                    },
                    onOpenAppSettings = { PermissionManager.openAppSettings(this) },
                    onOpenExactAlarmSettings = { PermissionManager.openExactAlarmSettings(this) },
                    onRequestBatteryOptimization = {
                        PermissionManager.requestBatteryOptimizationExemption(this)
                    },
                    onOpenOppoBackgroundSettings = {
                        PermissionManager.openOppoBackgroundSettings(this)
                    },
                    onOpenScreenshotAccessibilitySettings = {
                        showAccessibilityDisclosure()
                    },
                    onOpenPromotedNotificationsSettings = {
                        PermissionManager.openPromotedNotificationsSettings(this)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
        refreshSpecialStates()
    }

    private fun refreshPermissionStates() {
        PermissionManager.getRequiredPermissions().forEach { permission ->
            permissionStates[permission] = ContextCompat.checkSelfPermission(
                this, permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun refreshSpecialStates() {
        exactAlarmGranted = PermissionManager.canScheduleExactAlarms(this)
        batteryOptimizationExempt = PermissionManager.isBatteryOptimizationExempt(this)
        promotedNotificationsGranted = PermissionManager.canPostPromotedNotifications(this)
        screenshotAccessibilityEnabled =
            PermissionManager.isScreenshotAccessibilityEnabled(this)
    }

    private fun showAccessibilityDisclosure() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("开启截图实时检测")
            .setMessage(
                "ColorOS 会冻结后台截图监听。开启后，SnapClear 监听系统窗口的出现与消失，" +
                    "在截图浮层出现时唤醒应用并立即发送流体云；不会读取窗口内容、屏幕文字、" +
                    "输入内容或操作其他应用。"
            )
            .setPositiveButton("前往开启") { _, _ ->
                PermissionManager.openScreenshotAccessibilitySettings(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPermissionRationale(permission: String) {
        val message = when {
            permission == android.Manifest.permission.READ_MEDIA_IMAGES ||
            permission == android.Manifest.permission.READ_EXTERNAL_STORAGE ->
                getString(R.string.permission_rationale_storage)
            permission == android.Manifest.permission.POST_NOTIFICATIONS ->
                getString(R.string.permission_rationale_notification)
            else -> "此权限是应用正常运行所必需的"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(message)
            .setPositiveButton("授权") { _, _ ->
                permissionLauncher.launch(arrayOf(permission))
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
