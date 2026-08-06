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

/**
 * AlarmManager 定时唤醒截图检测
 *
 * 调度策略（优先级从高到低）：
 * 1. setExactAndAllowWhileIdle —— 需 SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM 权限，
 *    穿透 Doze，最精确
 * 2. setAndAllowWhileIdle —— 无需权限，穿透 Doze 但有 9 分钟批处理
 * 3. setAlarmClock —— 最后兜底，无需权限，穿透 Doze，但会在状态栏显示闹钟图标
 *
 * 间隔：30 秒。作为第 4 层兜底（FileObserver + ContentObserver + Handler 轮询为前 3 层），
 * 30 秒足够覆盖进程被杀后的检测空窗，同时功耗可忽略
 * （每天 2880 次 MediaStore 查询，每次 < 1ms）。
 *
 * 关键改进：
 * - 不再使用 setAlarmClock 作为首选（它面向用户闹钟，5s 轮询会被 Android 14+ 限流）
 * - 检测前先调用 initLastDetectedId，防止进程被杀后 lastDetectedId=0 导致通知风暴
 * - startForegroundService 加 try-catch，处理 Android 12+ 后台启动限制
 * - 使用 WakeLock 确保检测完成前 CPU 不休眠
 */
class ScreenshotAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenshotAlarmReceiver"
        private const val ACTION_CHECK = "com.snapclear.app.action.ALARM_CHECK"
        private const val INTERVAL_MS = 30_000L
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
            val elapsedTrigger = SystemClock.elapsedRealtime() + INTERVAL_MS

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
                val wallTrigger = System.currentTimeMillis() + INTERVAL_MS
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

            if (monitoringEnabled && !ScreenshotMonitorService.isRunning) {
                // 服务被系统杀死但用户仍希望监听 → 尝试自动重启服务
                // Android 12+ 后台启动前台服务可能被限制，需捕获异常
                try {
                    val serviceIntent = Intent(context, ScreenshotMonitorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    // 服务重启后会自行重新注册 ContentObserver + 重排闹钟
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException 或其他后台启动限制
                    // 仍执行一次检测，避免遗漏
                    Log.w(TAG, "Failed to restart foreground service from background", e)
                }
            }

            if (monitoringEnabled) {
                // 始终执行检测（不再因 isRunning=false 跳过）
                ScreenshotObserver.detectAndAdvance(context.contentResolver) { imageUri ->
                    NotificationHelper.showScreenshotNotification(context, imageUri)
                }

                // 重排下一次闹钟
                schedule(context)
            }
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
