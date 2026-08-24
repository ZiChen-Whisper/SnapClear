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
import android.os.SystemClock
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
 * 第 3 层 — Handler 轮询（每 30 秒，进程内存活时的兜底）
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
 * - 检测期间短时 WakeLock（不再常驻持锁，降低待机功耗）
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
        private const val KEY_LAST_HEARTBEAT_ELAPSED = "monitor_last_heartbeat_elapsed"

        /** AlarmReceiver 唤醒已存在的服务并刷新其保活状态。 */
        const val ACTION_WATCHDOG_WAKE = "com.snapclear.app.action.WATCHDOG_WAKE"

        /** Handler 轮询间隔（毫秒） */
        private const val POLL_INTERVAL_MS = 30_000L

        private const val DETECTION_WAKE_LOCK_TIMEOUT_MS = 8_000L
        private const val WAKELOCK_TAG = "snapclear:monitor_detection"

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

        fun recordHeartbeat(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_HEARTBEAT_ELAPSED, SystemClock.elapsedRealtime())
                .apply()
        }

        fun heartbeatAgeMs(context: Context): Long {
            val last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_HEARTBEAT_ELAPSED, 0L)
            if (last <= 0L) return Long.MAX_VALUE
            return (SystemClock.elapsedRealtime() - last).coerceAtLeast(0L)
        }
    }

    // 第 1 层：FileObserver
    private var fileObserver: ScreenshotFileObserver? = null

    // 第 2 层：ContentObserver
    private var contentObserver: ScreenshotObserver? = null

    // 第 3 层：Handler 轮询
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            withDetectionWakeLock("poll") {
                try {
                    DiagnosticLogger.log(DiagnosticEventType.POLL, "轮询触发, lastDetectedId=${ScreenshotObserver.lastDetectedId}")
                    NotificationHelper.reconcileActiveScreenshotNotifications(applicationContext)
                    ScreenshotObserver.detectAndAdvance(contentResolver) { uri ->
                        DiagnosticLogger.log(DiagnosticEventType.POLL, "轮询发现截图: $uri")
                        NotificationHelper.showScreenshotNotification(applicationContext, uri)
                        ScreenshotEvents.notifyScreenshotDetected()
                    }
                    recordHeartbeat(applicationContext)
                } catch (e: Exception) {
                    DiagnosticLogger.log(DiagnosticEventType.ERROR, "轮询异常: ${e.message}")
                    Log.e(TAG, "Poll tick failed", e)
                }
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
        // 先写入用户监听意愿，后续各系统级调度层才能在首次启动时注册成功。
        setMonitoringEnabled(this, true)
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
                        ScreenshotEvents.notifyScreenshotDetected()
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
                    ScreenshotEvents.notifyScreenshotDetected()
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

            // 第 5 层：系统 JobScheduler 监听 MediaStore。观察者由系统持有，
            // ColorOS 冻结应用线程时也能因新图片而唤醒本应用。
            ScreenshotContentJobService.schedule(this)
            Log.d(TAG, "Layer 5 (MediaStore content job) scheduled")

            recordHeartbeat(applicationContext)
            DiagnosticLogger.log(DiagnosticEventType.INFO, "5层检测全部就绪")
        }

        if (intent?.action == ACTION_WATCHDOG_WAKE) {
            val age = heartbeatAgeMs(this)
            DiagnosticLogger.log(
                if (age > POLL_INTERVAL_MS * 3) DiagnosticEventType.WARNING else DiagnosticEventType.INFO,
                "watchdog 唤醒服务, 上次心跳=${if (age == Long.MAX_VALUE) "无" else "${age}ms 前"}"
            )
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
        val shouldRecover = isMonitoringEnabled(this)
        if (shouldRecover) {
            // OEM 回收服务时 onDestroy 也可能被调用。这不是用户关闭监听，保留用户
            // 意愿并让 AlarmReceiver 拉起服务；否则退出最近任务后监听会永久失效。
            ScreenshotAlarmReceiver.schedule(this)
            ScreenshotContentJobService.schedule(this)
        } else {
            ScreenshotAlarmReceiver.cancel(this)
            ScreenshotContentJobService.cancel(this)
        }

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
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ColorOS 清理最近任务后可能紧接着回收进程，提前确保恢复闹钟仍存在。
        if (isMonitoringEnabled(this)) {
            ScreenshotAlarmReceiver.schedule(this)
            ScreenshotContentJobService.schedule(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    private inline fun withDetectionWakeLock(source: String, block: () -> Unit) {
        val lock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(DETECTION_WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrElse { error ->
            Log.w(TAG, "Short WakeLock acquire failed for $source", error)
            null
        }
        try {
            block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun buildMonitorNotification() = NotificationCompat.Builder(
        this, NotificationHelper.CHANNEL_ID_MONITOR
    )
        .setSmallIcon(R.drawable.ic_notification)
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
