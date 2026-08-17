package com.snapclear.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger
import com.snapclear.app.diagnostic.DiagnosticsScreen
import com.snapclear.app.notification.NotificationHelper
import com.snapclear.app.screenshot.ScreenshotObserver
import com.snapclear.app.ui.theme.SnapClearTheme

/**
 * 诊断面板二级 Activity
 *
 * 由主页入口卡片经 OPPO 无缝动画启动，承载检测管线状态查看与测试操作。
 * 保持 edge-to-edge 与导航栏小白条沉浸。
 */
class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        NotificationHelper.createChannels(this)

        setContent {
            SnapClearTheme {
                DiagnosticsScreen(
                    onBack = { finish() },
                    onRunDetection = { runDetectionNow() },
                    onCreateTestScreenshot = { createTestScreenshot() },
                    onSendTestNotification = { sendTestNotification() }
                )
            }
        }
    }

    /** 诊断：立即运行一次截图检测 */
    private fun runDetectionNow() {
        Thread {
            try {
                DiagnosticLogger.log(DiagnosticEventType.TEST, "手动触发检测开始")
                ScreenshotObserver.init(this)
                ScreenshotObserver.initLastDetectedId(contentResolver)
                ScreenshotObserver.detectAndAdvance(contentResolver) { uri ->
                    DiagnosticLogger.log(
                        DiagnosticEventType.SCREENSHOT,
                        "手动检测发现截图: $uri"
                    )
                    NotificationHelper.showScreenshotNotification(this, uri)
                }
                DiagnosticLogger.log(
                    DiagnosticEventType.TEST,
                    "手动触发检测完成, lastDetectedId=${ScreenshotObserver.lastDetectedId}"
                )
            } catch (e: Exception) {
                DiagnosticLogger.log(
                    DiagnosticEventType.ERROR,
                    "手动检测异常: ${e.message}"
                )
            }
        }.start()
    }

    /** 诊断：在 Screenshots 目录创建一个测试图片文件，触发三层检测管线 */
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

                // 最小有效 PNG (1x1 像素)
                val pngBytes = byteArrayOf(
                    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
                    0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 115, -53, 71,
                    0, 0, 0, 12, 73, 68, 65, 84, 8, -39, 99, -8, -15, 0, 0, 1,
                    0, 1, -75, -127, -92, -44, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126
                )
                file.writeBytes(pngBytes)

                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("image/png")
                ) { path, uri ->
                    DiagnosticLogger.log(
                        DiagnosticEventType.TEST,
                        "测试截图已创建并扫描: $path, uri=$uri"
                    )
                }

                DiagnosticLogger.log(
                    DiagnosticEventType.TEST,
                    "测试截图文件已写入: ${file.absolutePath}"
                )
            } catch (e: Exception) {
                DiagnosticLogger.log(
                    DiagnosticEventType.ERROR,
                    "创建测试截图失败: ${e.message}"
                )
            }
        }.start()
    }

    /** 诊断：发送一个测试通知，验证通知渠道是否正常 */
    private fun sendTestNotification() {
        try {
            val fakeUri = android.net.Uri.parse("content://media/external/images/media/test")
            NotificationHelper.showScreenshotNotification(this, fakeUri)
            DiagnosticLogger.log(DiagnosticEventType.TEST, "测试通知已发送")
        } catch (e: Exception) {
            DiagnosticLogger.log(DiagnosticEventType.ERROR, "发送测试通知失败: ${e.message}")
        }
    }
}
