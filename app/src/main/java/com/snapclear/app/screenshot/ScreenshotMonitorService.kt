package com.snapclear.app.screenshot

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.snapclear.app.MainActivity
import com.snapclear.app.R
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger
import com.snapclear.app.notification.NotificationHelper

/**
 * 截图监听前台服务
 *
 * 四层检测机制，确保万无一失：
 *
 * 第 1 层 — FileObserver（实时，功耗极低）
 *   基于 Linux inotify，直接监听截图目录的文件创建事件。
 *
 * 第 2 层 — ContentObserver（实时，功耗极低）
 *   监听 MediaStore.Images 变化，作为 FileObserver 的交叉备份。
 *
 * 第 3 层 — Handler 轮询（每 10 秒，进程内存活时最可靠的兜底）
 *   在前台服务进程内部用 Handler.postDelayed 定时查询 MediaStore。
 *   不依赖 AlarmManager / 系统调度，只要进程存活就能按时触发。
 *   在国产 ROM 上，FileObserver 和 ContentObserver 在后台可能被延迟
 *   投递（仅在回到前台时批量投递），此层是真正可靠的后台检测手段。
 *
 * 第 4 层 — AlarmManager（每 30 秒，进程被杀后的最终兜底）
 *   进程被系统杀死后，前 3 层全部失效。AlarmManager 唤醒后重启服务
 *   并执行一次检测。使用 setExactAndAllowWhileIdle 穿透 Doze。
 *
 * 进程存活保证：
 * - specialUse 前台服务（Android 14+ 无 6 小时超时限制）
 * - START_STICKY（系统杀死后自动重启）
 * - AlarmReceiver 检测到服务未运行时自动拉起
 * - BootReceiver 开机自启
 * - WakeLock（PARTIAL：防止 CPU 休眠导致进程被国产 ROM 冻结）
 *
 * 状态持久化：
 * - monitoring_enabled：用户监听意愿（控制自动重启）
 * - last_detected_id：已处理的最大图片 ID（防止通知风暴 / 漏检）
 */
class ScreenshotMonitorService : Service() {

    companion object {
        private const val TAG = "SnapClear Monitor"
        private const val PREFS_NAME = "snapclear_prefs"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"

        /** Handler 轮询间隔（毫秒） */
        private const val POLL_INTERVAL_MS = 10_000L

        /** WakeLock tag */
        private const val WAKELOCK_TAG = "snapclear:monitor_wakelock"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun setMonitoringEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MONITORING_ENABLED, enabled)
                .apply()
        }

        fun isMonitoringEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MONITORING_ENABLED, false)
        }
    }

    // 第 1 层：FileObserver
    private var fileObserver: ScreenshotFileObserver? = null

    // 第 2 层：ContentObserver
    private var contentObserver: ScreenshotObserver? = null

    // 第 3 层：Handler 轮询
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    // WakeLock：防止 CPU 休眠导致进程被国产 ROM 冻结
    private var wakeLock: PowerManager.WakeLock? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                DiagnosticLogger.log(DiagnosticEventType.POLL, "轮询触发, lastDetectedId=${ScreenshotObserver.lastDetectedId}")
                ScreenshotObserver.detectAndAdvance(contentResolver) { uri ->
                    DiagnosticLogger.log(DiagnosticEventType.POLL, "轮询发现截图: $uri")
                    NotificationHelper.showScreenshotNotification(applicationContext, uri)
                }
            } catch (e: Exception) {
                DiagnosticLogger.log(DiagnosticEventType.ERROR, "轮询异常: ${e.message}")
                Log.e(TAG, "Poll tick failed", e)
            }
            // 安排下一次轮询
            pollHandler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (contentObserver == null) {
            DiagnosticLogger.log(DiagnosticEventType.SERVICE_START, "服务启动, 初始化4层检测...")

            // 0. 启动前台服务（保活进程）
            val notification = buildMonitorNotification()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NotificationHelper.MONITOR_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID, notification)
                }
                Log.d(TAG, "Foreground service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed — service will run as background (may be killed)", e)
                DiagnosticLogger.log(DiagnosticEventType.ERROR, "startForeground 失败: ${e.message}")
            }

            // 初始化持久化上下文 + 恢复 lastDetectedId
            ScreenshotObserver.init(this)
            ScreenshotObserver.initLastDetectedId(contentResolver)
            DiagnosticLogger.log(DiagnosticEventType.INFO, "lastDetectedId 恢复完成: ${ScreenshotObserver.lastDetectedId}")

            // 第 1 层：FileObserver — 直接监听截图目录文件创建（最实时）
            fileObserver = ScreenshotFileObserver(
                onScreenshotDetected = {
                    Log.d(TAG, "FileObserver triggered")
                    ScreenshotObserver.detectAndAdvance(contentResolver) { uri ->
                        Log.d(TAG, "FileObserver detected screenshot: $uri")
                        NotificationHelper.showScreenshotNotification(applicationContext, uri)
                    }
                }
            )
            fileObserver?.start()
            Log.d(TAG, "Layer 1 (FileObserver) started")

            // 第 2 层：ContentObserver — 监听 MediaStore 变化（备份）
            contentObserver = ScreenshotObserver(
                contentResolver = contentResolver,
                onScreenshotDetected = { uri ->
                    Log.d(TAG, "ContentObserver detected screenshot: $uri")
                    NotificationHelper.showScreenshotNotification(applicationContext, uri)
                }
            )
            contentObserver?.register()
            Log.d(TAG, "Layer 2 (ContentObserver) started")

            // 第 3 层：Handler 轮询 — 进程内存活时最可靠的兜底
            // 在国产 ROM 上，FileObserver/ContentObserver 在后台可能被延迟投递，
            // 此层是真正可靠的后台检测手段
            pollThread = HandlerThread("ScreenshotPoller").apply { start() }
            pollHandler = Handler(pollThread!!.looper)
            pollHandler?.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            Log.d(TAG, "Layer 3 (Handler poll, ${POLL_INTERVAL_MS}ms) started")

            // 第 4 层：AlarmManager — 进程被杀后唤醒兜底
            ScreenshotAlarmReceiver.schedule(this)
            Log.d(TAG, "Layer 4 (AlarmManager) scheduled")

            // WakeLock：防止 CPU 休眠导致进程被国产 ROM 冻结
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire(30 * 60 * 1000L)
            }
            Log.d(TAG, "WakeLock acquired (PARTIAL)")

            DiagnosticLogger.log(DiagnosticEventType.INFO, "4层检测全部就绪")
        }
        isRunning = true
        setMonitoringEnabled(this, true)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户主动停止时调用 stopService() → onDestroy()
     * 此时清除监听意愿标记，防止 AlarmReceiver / BootReceiver 再次拉起服务。
     *
     * 注意：系统强制杀死进程时不会调用 onDestroy()，
     * 监听意愿标记保持 true，AlarmReceiver 会自动重启服务。
     */
    override fun onDestroy() {
        DiagnosticLogger.log(DiagnosticEventType.SERVICE_STOP, "服务销毁, 停止所有检测层...")
        Log.d(TAG, "Service destroying, stopping all layers...")
        ScreenshotAlarmReceiver.cancel(this)

        // 释放 WakeLock
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release failed", e)
        }
        wakeLock = null

        // 停止 Handler 轮询
        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollThread = null
        pollHandler = null

        fileObserver?.stop()
        fileObserver = null
        contentObserver?.unregister()
        contentObserver = null
        isRunning = false
        setMonitoringEnabled(this, false)
        super.onDestroy()
    }

    private fun buildMonitorNotification() = NotificationCompat.Builder(
        this, NotificationHelper.CHANNEL_ID_MONITOR
    )
        .setSmallIcon(android.R.drawable.ic_menu_gallery)
        .setContentTitle(getString(R.string.notification_monitor_title))
        .setContentText(getString(R.string.notification_monitor_text))
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}
