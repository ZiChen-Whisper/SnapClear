package com.snapclear.app

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import com.snapclear.app.notification.NotificationHelper
import com.snapclear.app.permission.PermissionManager
import com.snapclear.app.screenshot.ScreenshotEvents
import com.snapclear.app.screenshot.ScreenshotMonitorService
import com.snapclear.app.screenshot.ScreenshotRepository
import com.snapclear.app.ui.MainScreen
import com.snapclear.app.ui.OplusSeamlessHelper
import com.snapclear.app.ui.ScreenshotViewMode
import com.snapclear.app.ui.arePermissionsComplete
import com.snapclear.app.ui.parseScreenshotViewMode
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
        private const val PREFS_NAME = "snapclear_prefs"
        private const val PREF_VIEW_MODE = "screenshot_view_mode"
        private const val PREF_FORCE_LIGHT_MODE = "force_light_mode"
    }

    private var isMonitoring by mutableStateOf(false)
    private var allPermissionsGranted by mutableStateOf(false)
    private val permissionStates = mutableStateMapOf<String, Boolean>()
    private var exactAlarmGranted by mutableStateOf(false)
    private var batteryOptimizationExempt by mutableStateOf(false)
    private var promotedNotificationsGranted by mutableStateOf(false)
    private var screenshotAccessibilityEnabled by mutableStateOf(false)
    private var forceLightMode by mutableStateOf(false)
    private var screenshotViewMode by mutableStateOf(ScreenshotViewMode.CARD)
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
    private val requestedScreenshotPage = AtomicInteger(0)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        results.forEach { (permission, granted) -> permissionStates[permission] = granted }
        refreshPermissionSummary()
    }

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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        forceLightMode = prefs.getBoolean(PREF_FORCE_LIGHT_MODE, false)
        screenshotViewMode = parseScreenshotViewMode(prefs.getString(PREF_VIEW_MODE, ScreenshotViewMode.CARD.name))
        isMonitoring = ScreenshotMonitorService.isRunning
        refreshPermissionSummary()
        refreshScreenshots()

        handleIntent(intent)

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            SnapClearTheme(darkTheme = systemDarkTheme && !forceLightMode) {
                val seamlessBackground = MaterialTheme.colorScheme.background.toArgb()
                MainScreen(
                    isMonitoring = isMonitoring,
                    allPermissionsGranted = allPermissionsGranted,
                    permissionStates = permissionStates.toMap(),
                    exactAlarmGranted = exactAlarmGranted,
                    batteryOptimizationExempt = batteryOptimizationExempt,
                    promotedNotificationsGranted = promotedNotificationsGranted,
                    screenshotAccessibilityEnabled = screenshotAccessibilityEnabled,
                    forceLightMode = forceLightMode,
                    screenshotViewMode = screenshotViewMode,
                    homeScreenshots = homeScreenshots,
                    recentPageScreenshots = recentPageScreenshots,
                    currentScreenshotPage = currentScreenshotPage,
                    recentPageHasNext = recentPageHasNext,
                    isLoadingScreenshotPage = isLoadingScreenshotPage,
                    onScreenshotPageSelected = { page -> loadScreenshotPage(page) },
                    onScreenshotViewModeChange = { mode ->
                        screenshotViewMode = mode
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_VIEW_MODE, mode.name).apply()
                    },
                    onForceLightModeChange = { enabled ->
                        forceLightMode = enabled
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_FORCE_LIGHT_MODE, enabled).apply()
                    },
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
                            colorInt = seamlessBackground
                        )
                    },
                    onOpenAbout = { view ->
                        OplusSeamlessHelper.startActivitySeamless(
                            view = view,
                            activity = this@MainActivity,
                            intent = Intent(this@MainActivity, AboutActivity::class.java),
                            cornerRadiusPx = 8f * resources.displayMetrics.density,
                            colorInt = seamlessBackground
                        )
                    },
                    onRequestPermission = { requestPermission(it) },
                    onOpenAppSettings = { PermissionManager.openAppSettings(this@MainActivity) },
                    onOpenExactAlarmSettings = { PermissionManager.openExactAlarmSettings(this@MainActivity) },
                    onRequestBatteryOptimization = { PermissionManager.requestBatteryOptimizationExemption(this@MainActivity) },
                    onOpenOppoBackgroundSettings = { PermissionManager.openOppoBackgroundSettings(this@MainActivity) },
                    onOpenScreenshotAccessibilitySettings = { showAccessibilityDisclosure() },
                    onOpenPromotedNotificationsSettings = { PermissionManager.openPromotedNotificationsSettings(this@MainActivity) }
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
        required.forEach { permission ->
            permissionStates[permission] = androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        exactAlarmGranted = exactAlarmOk
        batteryOptimizationExempt = batteryOk
        promotedNotificationsGranted = promotedOk
        screenshotAccessibilityEnabled = !accessibilityRequired || PermissionManager.isScreenshotAccessibilityEnabled(this)

        allPermissionsGranted = arePermissionsComplete(requiredGranted == required.size,
            exactAlarmOk, batteryOk, promotedOk, accessibilityRequired, accessibilityOk)
    }

    /** 刷新最近截图列表（包含未运行期间产生的截图） */
    private fun refreshScreenshots() {
        if (activityDestroyed.get()) return
        // 合并短时间内来自 onResume、截图事件和 MediaStore 的重复刷新，避免创建大量线程。
        requestedScreenshotPage.set(currentScreenshotPage)
        screenshotQueryGeneration.incrementAndGet()
        screenshotRefreshRequested.set(true)
        if (!screenshotQueryRunning.compareAndSet(false, true)) return

        screenshotQueryExecutor.execute {
            try {
                do {
                    screenshotRefreshRequested.set(false)
                    val generation = screenshotQueryGeneration.get()
                    val visiblePageIndex = requestedScreenshotPage.get()
                    val firstPage = ScreenshotRepository.queryRecentPage(
                        context = this,
                        limit = SCREENSHOT_PAGE_SIZE,
                        offset = 0
                    )
                    val visiblePage = if (visiblePageIndex == 0) {
                        firstPage
                    } else {
                        ScreenshotRepository.queryRecentPage(
                            context = this,
                            limit = SCREENSHOT_PAGE_SIZE,
                            offset = visiblePageIndex * SCREENSHOT_PAGE_SIZE
                        )
                    }
                    mainHandler.post {
                        if (!isDestroyed && generation == screenshotQueryGeneration.get()) {
                            homeScreenshots = firstPage.items.take(10)
                            recentPageScreenshots = visiblePage.items
                            currentScreenshotPage = visiblePageIndex
                            recentPageHasNext = visiblePage.hasMore
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
        requestedScreenshotPage.set(pageIndex)
        val generation = screenshotQueryGeneration.incrementAndGet()
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
            if (!allPermissionsGranted) {
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

    private fun requestPermission(permission: String) {
        if (shouldShowRequestPermissionRationale(permission)) {
            val message = when (permission) {
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_EXTERNAL_STORAGE -> getString(R.string.permission_rationale_storage)
                android.Manifest.permission.POST_NOTIFICATIONS -> getString(R.string.permission_rationale_notification)
                else -> "此权限是应用正常运行所必需的"
            }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.permission_rationale_title)
                .setMessage(message).setPositiveButton("授权") { _, _ -> permissionLauncher.launch(arrayOf(permission)) }
                .setNegativeButton("取消", null).show()
        } else permissionLauncher.launch(arrayOf(permission))
    }

    private fun showAccessibilityDisclosure() {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("开启截图实时检测")
            .setMessage("ColorOS 会冻结后台截图监听。开启后，SnapClear 只监听系统窗口增删并在截图浮层出现时唤醒应用；不会读取窗口内容、屏幕文字、输入内容或操作其他应用。")
            .setPositiveButton("前往开启") { _, _ -> PermissionManager.openScreenshotAccessibilitySettings(this) }
            .setNegativeButton("取消", null).show()
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
