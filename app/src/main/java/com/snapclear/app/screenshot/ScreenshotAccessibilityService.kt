package com.snapclear.app.screenshot

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger
import com.snapclear.app.notification.NotificationHelper

/**
 * ColorOS 截图事件唤醒器。
 *
 * ColorOS/Hans 在应用退到后台后会冻结前台服务，并延迟 FileObserver、
 * ContentObserver 和 AlarmManager。系统截图浮层产生的无障碍事件由系统服务主动
 * 投递，可以唤醒被冻结的进程。收到事件后只查询 MediaStore，不读取事件节点、
 * 窗口文本或用户输入。
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SnapClear A11y"
        private const val EVENT_DEBOUNCE_MS = 300L
        private val RETRY_DELAYS_MS = longArrayOf(0L, 350L, 1_200L, 2_500L)
        private val SCREENSHOT_PACKAGES = setOf(
            "com.oplus.screenshot",
            "com.coloros.screenshot"
        )
    }

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var lastAcceptedEventElapsed = 0L
    @Volatile private var detectionGeneration = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        workerThread = HandlerThread("ScreenshotAccessibilityDetector").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
        ScreenshotObserver.init(this)
        ScreenshotObserver.initLastDetectedId(contentResolver)
        DiagnosticLogger.log(DiagnosticEventType.INFO, "无障碍截图唤醒器已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !ScreenshotMonitorService.isMonitoringEnabled(this)) return
        val packageName = event.packageName?.toString()
        // ColorOS 的截图浮层由系统窗口管理器添加，TYPE_WINDOWS_CHANGED 事件
        // 经常没有 com.oplus.screenshot 包名。清单不能按包过滤；这里只接受：
        // 1) OPPO 截图组件自身的窗口状态事件；2) 系统窗口集合发生增删。
        // 不读取 source、节点树、文本或输入内容。
        val isScreenshotPackage = packageName in SCREENSHOT_PACKAGES
        val isSystemWindowSetChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!isScreenshotPackage && !isSystemWindowSetChanged) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastAcceptedEventElapsed < EVENT_DEBOUNCE_MS) return
        lastAcceptedEventElapsed = now

        DiagnosticLogger.log(
            DiagnosticEventType.DETECT,
            "收到窗口唤醒事件(type=${event.eventType}, package=${packageName ?: "system"})，立即检测"
        )
        val generation = ++detectionGeneration
        RETRY_DELAYS_MS.forEachIndexed { index, delay ->
            workerHandler?.postDelayed({
                if (generation == detectionGeneration) detectScreenshot(index + 1, generation)
            }, delay)
        }
    }

    private fun detectScreenshot(attempt: Int, generation: Int) {
        if (!ScreenshotMonitorService.isMonitoringEnabled(this)) return
        try {
            var foundScreenshot = false
            ScreenshotObserver.detectAndAdvance(contentResolver) { uri ->
                foundScreenshot = true
                DiagnosticLogger.log(
                    DiagnosticEventType.SCREENSHOT,
                    "无障碍唤醒检测到截图(第${attempt}次): $uri"
                )
                NotificationHelper.showScreenshotNotification(applicationContext, uri)
                ScreenshotEvents.notifyScreenshotDetected()
            }
            if (foundScreenshot && generation == detectionGeneration) {
                // 截图已经找到，后续补偿查询无需继续执行。
                detectionGeneration++
            }
            ScreenshotMonitorService.recordHeartbeat(applicationContext)
        } catch (e: Exception) {
            DiagnosticLogger.log(
                DiagnosticEventType.ERROR,
                "无障碍唤醒检测异常(第${attempt}次): ${e.message}"
            )
            Log.e(TAG, "MediaStore detection failed", e)
        }
    }

    override fun onInterrupt() {
        DiagnosticLogger.log(DiagnosticEventType.WARNING, "无障碍截图唤醒器被中断")
    }

    override fun onDestroy() {
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        super.onDestroy()
    }
}
