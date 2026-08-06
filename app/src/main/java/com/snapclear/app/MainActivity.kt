package com.snapclear.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.snapclear.app.notification.NotificationHelper
import com.snapclear.app.permission.PermissionManager
import com.snapclear.app.screenshot.ScreenshotMonitorService
import com.snapclear.app.ui.MainScreen
import com.snapclear.app.ui.theme.SnapClearTheme

/**
 * 主 Activity
 *
 * 职责：
 * - 管理权限请求流程
 * - 控制 ScreenshotMonitorService 的启动/停止
 * - 启用 edge-to-edge 沉浸式显示
 * - 渲染 Compose UI
 */
class MainActivity : ComponentActivity() {

    // 跟踪各权限的授权状态
    private val permissionStates = mutableStateMapOf<String, Boolean>()

    // 是否正在监听截图
    private var isMonitoring by mutableStateOf(false)

    // 精确闹钟权限状态
    private var exactAlarmGranted by mutableStateOf(false)

    // 电池优化豁免状态
    private var batteryOptimizationExempt by mutableStateOf(false)

    // 权限请求 Launcher
    private val permissionLauncher = registerForActivityResult(
        RequestMultiplePermissions()
    ) { results ->
        // 更新权限状态
        results.forEach { (permission, granted) ->
            permissionStates[permission] = granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 禁用导航栏对比度强制，实现真正透明的小白条沉浸显示
        // enableEdgeToEdge 默认可能开启对比度 scrim，需手动关闭
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        // 创建通知渠道
        NotificationHelper.createChannels(this)

        // 同步监听状态（服务可能在 Activity 销毁后仍在运行）
        isMonitoring = ScreenshotMonitorService.isRunning

        // 同步精确闹钟权限状态
        exactAlarmGranted = PermissionManager.canScheduleExactAlarms(this)

        // 同步电池优化豁免状态
        batteryOptimizationExempt = PermissionManager.isBatteryOptimizationExempt(this)

        // 初始化权限状态
        refreshPermissionStates()

        // 处理来自通知 Action 的 Intent
        handleIntent(intent)

        setContent {
            SnapClearTheme {
                MainScreen(
                    permissionStates = permissionStates.toMap(),
                    isMonitoring = isMonitoring,
                    exactAlarmGranted = exactAlarmGranted,
                    batteryOptimizationExempt = batteryOptimizationExempt,
                    onRequestPermission = { permission ->
                        requestPermission(permission)
                    },
                    onOpenAppSettings = {
                        PermissionManager.openAppSettings(this)
                    },
                    onOpenExactAlarmSettings = {
                        PermissionManager.openExactAlarmSettings(this)
                    },
                    onRequestBatteryOptimization = {
                        PermissionManager.requestBatteryOptimizationExemption(this)
                    },
                    onToggleMonitoring = {
                        toggleMonitoring()
                    },
                    onRunDetection = {
                        runDetectionNow()
                    },
                    onCreateTestScreenshot = {
                        createTestScreenshot()
                    },
                    onSendTestNotification = {
                        sendTestNotification()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台时同步所有状态
        isMonitoring = ScreenshotMonitorService.isRunning
        exactAlarmGranted = PermissionManager.canScheduleExactAlarms(this)
        batteryOptimizationExempt = PermissionManager.isBatteryOptimizationExempt(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 处理外部 Intent（如通知 Action 的拷贝删除请求）
     */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == "com.snapclear.app.action.COPY_DELETE") {
            val uriString = intent.getStringExtra(NotificationHelper.EXTRA_IMAGE_URI)
            if (uriString != null) {
                val uri = android.net.Uri.parse(uriString)
                com.snapclear.app.clipboard.ClipboardHelper.copyAndDelete(this, uri)
            }
        }
    }

    /**
     * 刷新所有权限的授权状态
     */
    private fun refreshPermissionStates() {
        val permissions = PermissionManager.getRequiredPermissions()
        permissions.forEach { permission ->
            permissionStates[permission] = ContextCompat.checkSelfPermission(
                this, permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求单个权限
     */
    private fun requestPermission(permission: String) {
        if (shouldShowRequestPermissionRationale(permission)) {
            showPermissionRationale(permission)
        } else {
            permissionLauncher.launch(arrayOf(permission))
        }
    }

    /**
     * 显示权限解释对话框，然后再请求
     */
    private fun showPermissionRationale(permission: String) {
        val message = when {
            permission == android.Manifest.permission.READ_MEDIA_IMAGES ||
            permission == android.Manifest.permission.READ_EXTERNAL_STORAGE -> {
                getString(R.string.permission_rationale_storage)
            }
            permission == android.Manifest.permission.POST_NOTIFICATIONS -> {
                getString(R.string.permission_rationale_notification)
            }
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

    /**
     * 切换截图监听状态
     */
    private fun toggleMonitoring() {
        if (isMonitoring) {
            stopMonitoring()
        } else {
            if (!PermissionManager.checkAllGranted(this)) {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show()
                return
            }
            startMonitoring()
        }
    }

    /**
     * 启动截图监听前台服务
     */
    private fun startMonitoring() {
        val intent = Intent(this, ScreenshotMonitorService::class.java)
        startForegroundService(intent)
        isMonitoring = true
        Toast.makeText(this, "已开始监听截图", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止截图监听前台服务
     */
    private fun stopMonitoring() {
        val intent = Intent(this, ScreenshotMonitorService::class.java)
        stopService(intent)
        isMonitoring = false
        Toast.makeText(this, "已停止监听截图", Toast.LENGTH_SHORT).show()
    }

    /**
     * 诊断：立即运行一次截图检测
     */
    private fun runDetectionNow() {
        Thread {
            try {
                com.snapclear.app.diagnostic.DiagnosticLogger.log(
                    com.snapclear.app.diagnostic.DiagnosticEventType.TEST,
                    "手动触发检测开始"
                )
                com.snapclear.app.screenshot.ScreenshotObserver.init(this)
                com.snapclear.app.screenshot.ScreenshotObserver.initLastDetectedId(contentResolver)
                com.snapclear.app.screenshot.ScreenshotObserver.detectAndAdvance(contentResolver) { uri ->
                    com.snapclear.app.diagnostic.DiagnosticLogger.log(
                        com.snapclear.app.diagnostic.DiagnosticEventType.SCREENSHOT,
                        "手动检测发现截图: $uri"
                    )
                    com.snapclear.app.notification.NotificationHelper.showScreenshotNotification(this, uri)
                }
                com.snapclear.app.diagnostic.DiagnosticLogger.log(
                    com.snapclear.app.diagnostic.DiagnosticEventType.TEST,
                    "手动触发检测完成, lastDetectedId=${com.snapclear.app.screenshot.ScreenshotObserver.lastDetectedId}"
                )
            } catch (e: Exception) {
                com.snapclear.app.diagnostic.DiagnosticLogger.log(
                    com.snapclear.app.diagnostic.DiagnosticEventType.ERROR,
                    "手动检测异常: ${e.message}"
                )
            }
        }.start()
    }

    /**
     * 诊断：在 Screenshots 目录创建一个测试图片文件
     *
     * 这会触发 FileObserver / ContentObserver / 轮询三层检测，
     * 用来验证整个检测管线是否正常工作。
     */
    private fun createTestScreenshot() {
        Thread {
            try {
                val dir = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Pictures/Screenshots"
                )
                if (!dir.exists()) dir.mkdirs()

                val timestamp = System.currentTimeMillis()
                val file = java.io.File(dir, "SnapClear_test_$timestamp.png")

                // 创建一个最小有效 PNG (1x1 像素)
                val pngBytes = byteArrayOf(
                    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
                    0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 115, -53, 71,
                    0, 0, 0, 12, 73, 68, 65, 84, 8, -39, 99, -8, -15, 0, 0, 1,
                    0, 1, -75, -127, -92, -44, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126
                )
                file.writeBytes(pngBytes)

                // 通过 MediaScannerConnection 让 MediaStore 索引此文件
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("image/png")
                ) { path, uri ->
                    com.snapclear.app.diagnostic.DiagnosticLogger.log(
                        com.snapclear.app.diagnostic.DiagnosticEventType.TEST,
                        "测试截图已创建并扫描: $path, uri=$uri"
                    )
                }

                com.snapclear.app.diagnostic.DiagnosticLogger.log(
                    com.snapclear.app.diagnostic.DiagnosticEventType.TEST,
                    "测试截图文件已写入: ${file.absolutePath}"
                )
            } catch (e: Exception) {
                com.snapclear.app.diagnostic.DiagnosticLogger.log(
                    com.snapclear.app.diagnostic.DiagnosticEventType.ERROR,
                    "创建测试截图失败: ${e.message}"
                )
            }
        }.start()
    }

    /**
     * 诊断：发送一个测试通知，验证通知渠道是否正常
     */
    private fun sendTestNotification() {
        try {
            // 使用一个假的 Uri 触发通知
            val fakeUri = android.net.Uri.parse("content://media/external/images/media/test")
            com.snapclear.app.notification.NotificationHelper.showScreenshotNotification(this, fakeUri)
            com.snapclear.app.diagnostic.DiagnosticLogger.log(
                com.snapclear.app.diagnostic.DiagnosticEventType.TEST,
                "测试通知已发送"
            )
        } catch (e: Exception) {
            com.snapclear.app.diagnostic.DiagnosticLogger.log(
                com.snapclear.app.diagnostic.DiagnosticEventType.ERROR,
                "发送测试通知失败: ${e.message}"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不再停止监听 —— 前台服务在 Activity 销毁后继续运行
    }
}
