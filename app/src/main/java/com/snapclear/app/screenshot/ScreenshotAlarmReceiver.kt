package com.snapclear.app.screenshot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.snapclear.app.notification.NotificationHelper
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger
import com.snapclear.app.permission.PermissionManager

/**
 * AlarmManager 定时唤醒截图检测
 *
 * 调度策略：
 * - OPPO/OnePlus/Realme：5 分钟 AlarmClock watchdog。它由系统持有，能在 Hans/Athena
 *   暂停应用线程后重新给检测代码执行机会，但会显示系统闹钟标识并增加耗电。
 * - 其他设备：setExactAndAllowWhileIdle → setAndAllowWhileIdle → setAlarmClock 回退。
 *
 * 作为第 4 层兜底（FileObserver + ContentObserver + Handler 轮询为前 3 层）。正常情况
 * 仍由事件观察器实时处理；watchdog 只负责打破厂商冻结并补查 MediaStore。
 *
 * 关键改进：
 * - ColorOS 上使用 setAlarmClock 作为独立唤醒源，其他系统仍优先普通精确闹钟
 * - 检测前先调用 initLastDetectedId，防止进程被杀后 lastDetectedId=0 导致通知风暴
 * - startForegroundService 加 try-catch，处理 Android 12+ 后台启动限制
 * - 使用 WakeLock 确保检测完成前 CPU 不休眠
 */
class ScreenshotAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenshotAlarmReceiver"
        private const val ACTION_CHECK = "com.snapclear.app.action.ALARM_CHECK"
        private const val DEFAULT_INTERVAL_MS = 15 * 60_000L
        private const val OPPO_WATCHDOG_INTERVAL_MS = 5 * 60_000L
        private const val REQUEST_CODE = 200
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        fun schedule(context: Context) {
            // 注入 ApplicationContext 用于 lastDetectedId 持久化
            ScreenshotObserver.init(context)
            // 确保 lastDetectedId 已从持久化恢复
            ScreenshotObserver.initLastDetectedId(context.contentResolver)

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScreenshotAlarmReceiver::class.java).apply {
                action = ACTION_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = if (PermissionManager.isOppoDevice()) {
                OPPO_WATCHDOG_INTERVAL_MS
            } else {
                DEFAULT_INTERVAL_MS
            }
            val elapsedTrigger = SystemClock.elapsedRealtime() + interval

            // ColorOS 的 Hans/Athena 会在退到后台后暂停应用线程，甚至代理应用持有的
            // WakeLock。普通 allow-while-idle 闹钟又受系统限频，无法承担实时恢复。
            // AlarmClock 由系统持有，是检测代码运行之前的独立唤醒源。
            if (PermissionManager.isOppoDevice()) {
                try {
                    val wallTrigger = System.currentTimeMillis() + interval
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(wallTrigger, null),
                        pendingIntent
                    )
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "ColorOS watchdog AlarmClock failed; falling back", e)
                }
            }

            // 1) setExactAndAllowWhileIdle：最精确，穿透 Doze，需权限（Android 12+）
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedTrigger,
                    pendingIntent
                )
                return
            } catch (_: SecurityException) {
                // SCHEDULE_EXACT_ALARM 未授予，继续 fallback
            } catch (_: Exception) {
                // 其他异常，继续 fallback
            }

            // 2) setAndAllowWhileIdle：无需权限，穿透 Doze 但有 9 分钟批处理
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedTrigger,
                    pendingIntent
                )
                return
            } catch (_: Exception) {
                // 继续 fallback
            }

            // 3) setAlarmClock：最后兜底，会在状态栏显示闹钟图标
            try {
                val wallTrigger = System.currentTimeMillis() + interval
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(wallTrigger, null),
                    pendingIntent
                )
            } catch (_: Exception) {
                // 所有调度方式均失败，下次服务重启时会再次尝试
                Log.w(TAG, "All alarm scheduling methods failed")
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScreenshotAlarmReceiver::class.java).apply {
                action = ACTION_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return

        // 获取 WakeLock 确保检测完成前 CPU 不休眠
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SnapClear::AlarmDetection"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        try {
            // 注入 ApplicationContext 并恢复 lastDetectedId（防止进程被杀后归零）
            ScreenshotObserver.init(context)
            ScreenshotObserver.initLastDetectedId(context.contentResolver)

            val monitoringEnabled = ScreenshotMonitorService.isMonitoringEnabled(context)

            if (!monitoringEnabled) {
                cancel(context)
                return
            }

            // 先重排，避免本轮检测或服务启动异常导致 watchdog 链条永久中断。
            schedule(context)
            val heartbeatAge = ScreenshotMonitorService.heartbeatAgeMs(context)
            DiagnosticLogger.log(
                if (heartbeatAge > DEFAULT_INTERVAL_MS) {
                    DiagnosticEventType.WARNING
                } else {
                    DiagnosticEventType.INFO
                },
                "系统 watchdog 到达, 服务心跳=${if (heartbeatAge == Long.MAX_VALUE) "无" else "${heartbeatAge}ms 前"}"
            )

            // 即使静态 isRunning 仍为 true，也显式投递一次 start command。进程只是被
            // ColorOS 暂停时，这会刷新服务 WakeLock；进程被杀时则会重建前台服务。
            try {
                val serviceIntent = Intent(context, ScreenshotMonitorService::class.java).apply {
                    action = ScreenshotMonitorService.ACTION_WATCHDOG_WAKE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // 仍在 Receiver 的系统唤醒窗口内直接检测，服务启动失败不会造成漏检。
                Log.w(TAG, "Failed to wake foreground service", e)
            }

            ScreenshotObserver.detectAndAdvance(context.contentResolver) { imageUri ->
                NotificationHelper.showScreenshotNotification(context, imageUri)
            }
            ScreenshotMonitorService.recordHeartbeat(context)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
