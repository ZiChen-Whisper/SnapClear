package com.snapclear.app

import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snapclear.app.notification.NotificationHelper
import com.snapclear.app.permission.PermissionManager
import com.snapclear.app.screenshot.ScreenshotEvents
import com.snapclear.app.screenshot.ScreenshotMonitorService
import com.snapclear.app.screenshot.ScreenshotRepository
import com.snapclear.app.ui.MainScreen
import com.snapclear.app.ui.OplusSeamlessHelper
import com.snapclear.app.ui.theme.SnapClearTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主 Activity（仪表盘）
 *
 * 职责：
 * - 渲染主页仪表盘（监听开关 + 权限/诊断二级入口）
 * - 控制 ScreenshotMonitorService 的启动/停止
 * - 启用 edge-to-edge 沉浸式显示，导航栏小白条保持沉浸
 * - 处理通知 Action 的拷贝删除请求
 * - 提供最近截图数据给主页 Tab
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val SCREENSHOT_PAGE_SIZE = 20
    }

    private var isMonitoring by mutableStateOf(false)
    private var allPermissionsGranted by mutableStateOf(false)
    private var permissionGrantedCount by mutableStateOf(0)
    private var permissionTotalCount by mutableStateOf(0)
    private var homeScreenshots by mutableStateOf<List<com.snapclear.app.screenshot.ScreenshotItem>>(emptyList())
    private var recentPageScreenshots by mutableStateOf<List<com.snapclear.app.screenshot.ScreenshotItem>>(emptyList())
    private var currentScreenshotPage by mutableStateOf(0)
    private var recentPageHasNext by mutableStateOf(false)
    private var isLoadingScreenshotPage by mutableStateOf(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotQueryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScreenshotListQuery")
    }
    private val screenshotQueryRunning = AtomicBoolean(false)
    private val screenshotRefreshRequested = AtomicBoolean(false)
    private val activityDestroyed = AtomicBoolean(false)
    private val screenshotQueryGeneration = AtomicInteger(0)

    /** 截图事件监听器：检测到新截图或列表变化时自动刷新 */
    private val screenshotEventListener: () -> Unit = {
        refreshScreenshots()
    }

    /**
     * MediaStore 监听：拷贝并删除 / 删除走系统确认框，实际移入回收站是异步的，
     * 确认后 MediaStore 变化时自动刷新列表，确保已删除截图即时从面板消失。
     */
    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshScreenshots()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 禁用导航栏对比度强制，实现真正透明的小白条沉浸显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        NotificationHelper.createChannels(this)
        isMonitoring = ScreenshotMonitorService.isRunning
        refreshPermissionSummary()
        refreshScreenshots()

        handleIntent(intent)

        setContent {
            SnapClearTheme {
                MainScreen(
                    isMonitoring = isMonitoring,
                    allPermissionsGranted = allPermissionsGranted,
                    permissionGrantedCount = permissionGrantedCount,
                    permissionTotalCount = permissionTotalCount,
                    homeScreenshots = homeScreenshots,
                    recentPageScreenshots = recentPageScreenshots,
                    currentScreenshotPage = currentScreenshotPage,
                    recentPageHasNext = recentPageHasNext,
                    isLoadingScreenshotPage = isLoadingScreenshotPage,
                    onScreenshotPageSelected = { page -> loadScreenshotPage(page) },
                    onToggleMonitoring = { toggleMonitoring() },
                    onCopyDeleteScreenshot = { uri ->
                        com.snapclear.app.clipboard.ClipboardHelper.copyAndDelete(this@MainActivity, uri)
                    },
                    onDeleteScreenshot = { uri ->
                        val ok = com.snapclear.app.clipboard.ClipboardHelper.requestTrashScreenshot(this@MainActivity, uri)
                        if (!ok) {
                            Toast.makeText(this@MainActivity, "删除失败，请手动删除", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onScreenshotClick = { uri, view ->
                        // 点击截图卡片 → 通过 OPPO 无缝动画打开详情页
                        // 传入截图卡片本身的 View，让动画从卡片位置展开
                        val intent = ScreenshotDetailActivity.createIntent(this@MainActivity, uri)
                        OplusSeamlessHelper.startActivitySeamless(
                            view = view,
                            activity = this@MainActivity,
                            intent = intent,
                            cornerRadiusPx = 16f * resources.displayMetrics.density,
                            colorInt = android.graphics.Color.WHITE
                        )
                    },
                    permissionsIntent = Intent(this, PermissionsActivity::class.java),
                    diagnosticsIntent = Intent(this, DiagnosticsActivity::class.java)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isMonitoring = ScreenshotMonitorService.isRunning
        refreshPermissionSummary()
        refreshScreenshots()
        // 注册截图事件监听：前台时检测到新截图或列表变化自动刷新
        ScreenshotEvents.register(screenshotEventListener)
        // 注册 MediaStore 监听：删除确认后自动刷新列表
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaStoreObserver
        )
    }

    override fun onPause() {
        super.onPause()
        ScreenshotEvents.unregister(screenshotEventListener)
        contentResolver.unregisterContentObserver(mediaStoreObserver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 处理外部 Intent（通知 Action 的拷贝删除请求） */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.snapclear.app.action.COPY_DELETE") {
            val uriString = intent.getStringExtra(NotificationHelper.EXTRA_IMAGE_URI)
            val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 0)

            // 先消费 Action，避免 Activity 因配置变化重建时重复执行拷贝/删除。
            intent.action = null

            if (uriString != null) {
                val uri = Uri.parse(uriString)
                com.snapclear.app.clipboard.ClipboardHelper.copyAndDelete(this, uri)
            } else {
                Toast.makeText(this, R.string.toast_copy_failed, Toast.LENGTH_SHORT).show()
            }

            if (notificationId > 0) {
                NotificationHelper.cancelNotification(this, notificationId)
            }
            ScreenshotEvents.notifyScreenshotListChanged()
        }
    }

    /** 汇总权限授权情况，供仪表盘入口卡片展示 */
    private fun refreshPermissionSummary() {
        val required = PermissionManager.getRequiredPermissions()
        val requiredGranted = required.count {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, it
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val exactAlarmOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            PermissionManager.canScheduleExactAlarms(this)
        val batteryOk = PermissionManager.isBatteryOptimizationExempt(this)
        val promotedOk = Build.VERSION.SDK_INT < 36 ||
            PermissionManager.canPostPromotedNotifications(this)
        val accessibilityRequired = PermissionManager.isOppoDevice()
        val accessibilityOk = !accessibilityRequired ||
            PermissionManager.isScreenshotAccessibilityEnabled(this)

        val specialCount = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1 else 0) + 1 +
            (if (Build.VERSION.SDK_INT >= 36) 1 else 0) +
            (if (accessibilityRequired) 1 else 0)
        val specialGranted = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1 else 0) *
            (if (exactAlarmOk) 1 else 0) + (if (batteryOk) 1 else 0) +
            (if (Build.VERSION.SDK_INT >= 36) (if (promotedOk) 1 else 0) else 0) +
            (if (accessibilityRequired && accessibilityOk) 1 else 0)

        permissionGrantedCount = requiredGranted + specialGranted
        permissionTotalCount = required.size + specialCount
        allPermissionsGranted = requiredGranted == required.size &&
            exactAlarmOk && batteryOk && promotedOk && accessibilityOk
    }

    /** 刷新最近截图列表（包含未运行期间产生的截图） */
    private fun refreshScreenshots() {
        if (activityDestroyed.get()) return
        // 合并短时间内来自 onResume、截图事件和 MediaStore 的重复刷新，避免创建大量线程。
        screenshotQueryGeneration.incrementAndGet()
        screenshotRefreshRequested.set(true)
        if (!screenshotQueryRunning.compareAndSet(false, true)) return

        screenshotQueryExecutor.execute {
            try {
                do {
                    screenshotRefreshRequested.set(false)
                    val generation = screenshotQueryGeneration.get()
                    val page = ScreenshotRepository.queryRecentPage(
                        context = this,
                        limit = SCREENSHOT_PAGE_SIZE,
                        offset = 0
                    )
                    mainHandler.post {
                        if (!isDestroyed && generation == screenshotQueryGeneration.get()) {
                            homeScreenshots = page.items.take(10)
                            recentPageScreenshots = page.items
                            currentScreenshotPage = 0
                            recentPageHasNext = page.hasMore
                            isLoadingScreenshotPage = false
                        }
                    }
                } while (screenshotRefreshRequested.get())
            } finally {
                screenshotQueryRunning.set(false)
                // 请求可能恰好出现在循环结束与 running 复位之间，补一次调度避免丢刷新。
                if (!activityDestroyed.get() && screenshotRefreshRequested.get()) refreshScreenshots()
            }
        }
    }

    /** 显式切换最近截图页；内存中只保留当前页，不累积此前页面。 */
    private fun loadScreenshotPage(pageIndex: Int) {
        if (activityDestroyed.get() || isLoadingScreenshotPage || pageIndex < 0) return
        if (pageIndex > currentScreenshotPage && !recentPageHasNext) return
        isLoadingScreenshotPage = true
        val generation = screenshotQueryGeneration.get()
        screenshotQueryExecutor.execute {
            val page = ScreenshotRepository.queryRecentPage(
                context = this,
                limit = SCREENSHOT_PAGE_SIZE,
                offset = pageIndex * SCREENSHOT_PAGE_SIZE
            )
            mainHandler.post {
                if (!isDestroyed && generation == screenshotQueryGeneration.get()) {
                    recentPageScreenshots = page.items
                    currentScreenshotPage = pageIndex
                    recentPageHasNext = page.hasMore
                    isLoadingScreenshotPage = false
                } else if (!isDestroyed) {
                    isLoadingScreenshotPage = false
                }
            }
        }
    }

    private fun toggleMonitoring() {
        if (isMonitoring) {
            stopMonitoring()
        } else {
            if (!PermissionManager.checkAllGranted(this)) {
                Toast.makeText(this, "请先在权限管理中授予权限", Toast.LENGTH_SHORT).show()
                return
            }
            if (PermissionManager.isOppoDevice() &&
                !PermissionManager.isScreenshotAccessibilityEnabled(this)
            ) {
                Toast.makeText(this, "请先在权限管理中开启截图实时检测", Toast.LENGTH_LONG).show()
                return
            }
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, ScreenshotMonitorService::class.java)
        ScreenshotMonitorService.setMonitoringEnabled(this, true)
        startForegroundService(intent)
        isMonitoring = true
        Toast.makeText(this, "已开始监听截图", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        val intent = Intent(this, ScreenshotMonitorService::class.java)
        // 用户意愿必须在 stopService 前持久化；系统/OEM 销毁服务时则保留 true，
        // 这样两种 onDestroy 才能被可靠地区分。
        ScreenshotMonitorService.setMonitoringEnabled(this, false)
        com.snapclear.app.screenshot.ScreenshotContentJobService.cancel(this)
        stopService(intent)
        isMonitoring = false
        Toast.makeText(this, "已停止监听截图", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        activityDestroyed.set(true)
        super.onDestroy()
        screenshotQueryExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        // 前台服务在 Activity 销毁后继续运行
    }
}
