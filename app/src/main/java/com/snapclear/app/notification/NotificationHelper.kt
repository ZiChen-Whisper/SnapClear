package com.snapclear.app.notification

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.snapclear.app.MainActivity
import com.snapclear.app.R
import com.snapclear.app.clipboard.ClipboardHelper
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知管理工具类
 *
 * 职责：
 * - 创建截图操作通知渠道
 * - 发送截图检测通知（含「拷贝并删除」「不再提示」两个 Action，各自独立自定义图标）
 * - API 36+ (ColorOS 16) + POST_PROMOTED_NOTIFICATIONS 已授予：
 *   使用 Live Updates / 流体云通知（前后台均支持，ColorOS 16 完整接入 Android 16 API）
 *   · 折叠态（胶囊态）：由 SystemUI Chronometer 连续渲染倒计时
 *   · 展开态：左上角截图缩略图（largeIcon）+ 大文本 + 两个 Action
 *   · AlarmManager 仅负责 60 秒到期取消，不占用逐秒精确闹钟配额
 *   · 多张截图通知并存，各自独立倒计时
 * - 后台检测运行于前台服务，检测到截图后直接发送，避免精确闹钟限频造成延迟
 * - API 36+ 权限未授予 / 低版本：回退 PRIORITY_MAX + fullScreenIntent
 */
object NotificationHelper {

    const val CHANNEL_ID = "screenshot_action"
    const val CHANNEL_ID_MONITOR = "monitor_service"
    const val CHANNEL_ID_LIVE_UPDATE = "live_update_channel"
    const val MONITOR_NOTIFICATION_ID = 1

    // Action 常量
    const val ACTION_COPY_DELETE = "com.snapclear.app.action.COPY_DELETE"
    const val ACTION_IGNORE = "com.snapclear.app.action.IGNORE"
    const val EXTRA_IMAGE_URI = "extra_image_uri"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    // LiveUpdateTickReceiver 广播 Action
    const val ACTION_LIVE_UPDATE_TICK = "com.snapclear.app.action.LIVE_UPDATE_TICK"
    const val ACTION_LIVE_UPDATE_POST = "com.snapclear.app.action.LIVE_UPDATE_POST"
    const val EXTRA_LIVE_UPDATE_ID = "extra_live_update_id"
    const val EXTRA_LIVE_UPDATE_URI = "extra_live_update_uri"

    /** 每个流体云通知的状态持久化（进程被杀后 AlarmManager tick 仍能恢复） */
    private const val PREFS_NAME = "snapclear_live_updates"
    private const val KEY_PREFIX_URI = "lu_uri_"
    private const val KEY_PREFIX_START = "lu_start_"

    private val nextScreenshotNotificationId = AtomicInteger(100)

    /** 当前活跃的截图通知 ID 集合 —— 支持多通知并存，各自独立倒计时 */
    private val activeScreenshotIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** 流体云强调色（品牌青绿） */
    private const val ACCENT_COLOR = 0xFF0D9488.toInt()

    /** 流体云倒计时总时长（60 秒） */
    private const val LIVE_UPDATE_DURATION_MS = 60_000L
    /** 检测到截图后保持 CPU/进程可运行的时间，覆盖通知 Binder 投递窗口。 */
    private const val NOTIFICATION_WAKE_LOCK_TIMEOUT_MS = 10_000L
    /** 正常情况下通知发出后延迟释放；系统超时是冻结时的最终兜底。 */
    private const val NOTIFICATION_WAKE_LOCK_HOLD_MS = 3_000L
    /** 闹钟 PendingIntent requestCode 偏移，避免与 tick 闹钟冲突 */
    private const val POST_REQUEST_CODE_OFFSET = 50_000

    /** 流体云通知的持久化状态（图片 URI + 倒计时开始时间） */
    data class LiveUpdateState(val imageUri: Uri, val startTime: Long)

    /**
     * 创建所有通知渠道。
     *
     * 每次调用先删除再重建截图渠道 —— 国产 ROM 会在后台悄悄把渠道重要性从
     * IMPORTANCE_HIGH 降级为静默，只有删除后重建才能恢复。
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            manager.deleteNotificationChannel(CHANNEL_ID)
            val actionChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(actionChannel)

            val monitorChannel = NotificationChannel(
                CHANNEL_ID_MONITOR,
                context.getString(R.string.notification_channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_monitor_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(monitorChannel)

            manager.deleteNotificationChannel(CHANNEL_ID_LIVE_UPDATE)
            val liveUpdateChannel = NotificationChannel(
                CHANNEL_ID_LIVE_UPDATE,
                "实时更新",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "截图实时通知"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(liveUpdateChannel)
        }
    }

    /**
     * 弹出截图操作通知（路由入口）
     *
     * 本入口由截图监听前台服务调用，可直接发送前后台通知。精确闹钟只用于
     * 到期取消，避免 Android 对 allow-while-idle 闹钟的按应用限频影响新通知。
     *
     * 多通知并存策略：每张截图都使用独立 notificationId，新通知不取消旧通知，
     * 各自独立倒计时 60 秒后自动消失。
     */
    fun showScreenshotNotification(context: Context, imageUri: Uri) {
        val notificationId = nextScreenshotNotificationId.incrementAndGet()
        activeScreenshotIds.add(notificationId)
        val wakeLock = acquireNotificationWakeLock(context, notificationId)
        val startTime = System.currentTimeMillis()
        // 持久化状态：AlarmManager tick / 进程重建后仍可恢复
        persistLiveUpdateState(context, notificationId, imageUri, startTime)
        Log.d("SnapClear Notify", "showScreenshotNotification: id=$notificationId, uri=$imageUri")

        val isForeground = isAppInForeground(context)

        try {
            // WakeLock 已先于通知获得：第一时间同步 notify，后台也先尝试直发。
            postScreenshotNotification(context, imageUri, notificationId, isForeground)

            if (!isForeground) {
                // ColorOS 偶尔会压住后台进程直发的流体云；在 WakeLock 保持期间
                // 用相同 ID 安排一次系统 Receiver 补投，不会产生重复提醒。
                scheduleBackgroundRepost(context, notificationId, imageUri)
            }
        } finally {
            releaseNotificationWakeLockLater(wakeLock)
        }
    }

    /**
     * 截图检测线程能够执行但通知被 ColorOS 延迟时，先通过短时 PARTIAL_WAKE_LOCK
     * 把 CPU 和本进程保持在可运行状态，再调用 NotificationManager。
     */
    private fun acquireNotificationWakeLock(
        context: Context,
        notificationId: Int
    ): PowerManager.WakeLock? = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SnapClear::ScreenshotNotification:$notificationId"
        ).apply {
            setReferenceCounted(false)
            acquire(NOTIFICATION_WAKE_LOCK_TIMEOUT_MS)
        }
    } catch (e: Exception) {
        Log.w("SnapClear Notify", "notification WakeLock acquire failed", e)
        null
    }

    private fun releaseNotificationWakeLockLater(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                Log.w("SnapClear Notify", "notification WakeLock release failed", e)
            }
        }, NOTIFICATION_WAKE_LOCK_HOLD_MS)
    }

    /**
     * 实际发送截图通知（由 [showScreenshotNotification] 或闹钟唤醒的 Receiver 调用）
     */
    fun postScreenshotNotification(context: Context, imageUri: Uri, notificationId: Int) {
        postScreenshotNotification(context, imageUri, notificationId, isForeground = true)
    }

    private fun postScreenshotNotification(
        context: Context,
        imageUri: Uri,
        notificationId: Int,
        isForeground: Boolean
    ) {
        val appContext = context.applicationContext
        val (contentIntent, copyDeletePendingIntent, ignorePendingIntent) =
            buildPendingIntents(context, imageUri, notificationId)

        val manager = appContext.getSystemService(NotificationManager::class.java)
        // ColorOS 16 完整接入 Android 16 Live Updates API，前后台均支持流体云
        // 仅需 POST_PROMOTED_NOTIFICATIONS 权限（API 36+），未授权时回退
        val promotedGranted = Build.VERSION.SDK_INT < 36 || manager.canPostPromotedNotifications()
        val useLiveUpdate = Build.VERSION.SDK_INT >= 36 && promotedGranted
        if (Build.VERSION.SDK_INT >= 36 && !promotedGranted) {
            DiagnosticLogger.log(
                DiagnosticEventType.WARNING,
                "POST_PROMOTED_NOTIFICATIONS 权限未授予，流体云无法常驻顶部，回退高优先级通知。请在权限管理中开启「流体云通知」"
            )
        }
        try {
            if (useLiveUpdate) {
                val notif = buildLiveUpdateNotification(
                    appContext,
                    imageUri,
                    contentIntent,
                    copyDeletePendingIntent,
                    ignorePendingIntent,
                    liveUpdateEndTime(appContext, notificationId)
                )
                // 调试：验证通知是否具备可提升特征
                if (Build.VERSION.SDK_INT >= 36) {
                    val promotable = try {
                        notif.hasPromotableCharacteristics()
                    } catch (e: Throwable) {
                        Log.w("SnapClear Notify", "hasPromotableCharacteristics failed", e)
                        false
                    }
                    DiagnosticLogger.log(
                        DiagnosticEventType.INFO,
                        "流体云通知可提升特征检查: hasPromotableCharacteristics=$promotable, flags=0x${notif.flags.toString(16)}"
                    )
                }
                manager.notify(notificationId, notif)
            } else {
                manager.notify(
                    notificationId,
                    buildLegacyNotification(context, contentIntent, copyDeletePendingIntent, ignorePendingIntent)
                )
            }
            DiagnosticLogger.log(
                DiagnosticEventType.NOTIFY,
                "通知已发送: id=$notificationId, uri=$imageUri, mode=${if (useLiveUpdate) "流体云" else "legacy"}, promoted=$promotedGranted, foreground=$isForeground"
            )
        } catch (e: Exception) {
            Log.e("SnapClear Notify", "notify() failed", e)
            DiagnosticLogger.log(DiagnosticEventType.ERROR, "通知发送失败: ${e.message}")
        }
    }

    /**
     * 构建通知相关的三个 PendingIntent（内容 / 拷贝并删除 / 不再提示）
     */
    private fun buildPendingIntents(
        context: Context,
        imageUri: Uri,
        notificationId: Int
    ): Triple<PendingIntent, PendingIntent, PendingIntent> {
        val copyDeleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_COPY_DELETE
            putExtra(EXTRA_IMAGE_URI, imageUri.toString())
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val copyDeletePendingIntent = PendingIntent.getBroadcast(
            context, notificationId, copyDeleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ignoreIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_IGNORE
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val ignorePendingIntent = PendingIntent.getBroadcast(
            context, notificationId + 10_000, ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            context, notificationId + 20_000,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Triple(contentIntent, copyDeletePendingIntent, ignorePendingIntent)
    }

    /**
     * API 36+ 流体云通知（Live Updates）
     *
     * 折叠态（胶囊态）：左侧应用图标 + 右侧倒计时文本（shortCriticalText）
     * 展开态：左上角截图缩略图（largeIcon）+ 标题 + 大文本 + 两个 Action
     *
     * 关键设计（基于实测问题修复）：
     * - 使用 setWhen + Chronometer 倒计时，由 SystemUI/流体云自行走时；
     *   应用进程被冻结/杀死后仍能连续更新
     * - AlarmManager 仅在终点触发一次以取消通知
     * - setLargeIcon 显示截图缩略图：展开态卡片左上角提示用户操作对象
     *
     * 被提升为实时更新（流体云）的必需条件（Android 官方文档）：
     * 1. setRequestPromotedOngoing(true)
     * 2. setOngoing(true)
     * 3. BigTextStyle 样式（Live Updates 支持 ProgressStyle/BigTextStyle/CallStyle/MetricStyle）
     * 4. 设置 contentTitle
     * 5. 必须不能 setColorized(true)
     * 6. 通知渠道不能是 IMPORTANCE_MIN
     */
    private fun buildLiveUpdateNotification(
        context: Context,
        imageUri: Uri,
        contentIntent: PendingIntent,
        copyDeletePendingIntent: PendingIntent,
        ignorePendingIntent: PendingIntent,
        endTime: Long
    ): Notification {
        val baseText = context.getString(R.string.notification_screenshot_text)

        val builder = Notification.Builder(context, CHANNEL_ID_LIVE_UPDATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_screenshot_title))
            .setContentText(baseText)
            .setColor(ACCENT_COLOR)
            .setRequestPromotedOngoing(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 由 NotificationManager/SystemUI 到时直接删除，不依赖应用进程或闹钟。
            .setTimeoutAfter(LIVE_UPDATE_DURATION_MS)
            // 交给 SystemUI/流体云自身渲染倒计时；进程被冻结后仍会连续走时。
            .setWhen(endTime)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(ignorePendingIntent)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("$baseText\n请在倒计时结束前处理")
            )

        // 展开态左上角：对应截图缩略图（largeIcon 渲染在卡片头部左侧）
        loadThumbnail(context, imageUri)?.let { thumbnail ->
            builder.setLargeIcon(Icon.createWithBitmap(thumbnail))
        }

        return builder
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_action_copy_delete),
                    context.getString(R.string.notification_action_copy_delete),
                    copyDeletePendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_action_ignore),
                    context.getString(R.string.notification_action_ignore),
                    ignorePendingIntent
                ).build()
            )
            .build()
    }

    /**
     * 后台即时补投递。截图意味着设备屏幕处于活跃状态，不需要穿透 Doze；使用
     * 普通 setExact 可避免 setExactAndAllowWhileIdle 的按应用限频，也不会出现
     * AlarmClock 的调度延迟和状态栏闹钟标识。
     */
    private fun scheduleBackgroundRepost(
        context: Context,
        notificationId: Int,
        imageUri: Uri
    ): Boolean = try {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, LiveUpdateTickReceiver::class.java).apply {
            action = ACTION_LIVE_UPDATE_POST
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_LIVE_UPDATE_ID, notificationId)
            putExtra(EXTRA_LIVE_UPDATE_URI, imageUri.toString())
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + POST_REQUEST_CODE_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExact(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1L,
            pendingIntent
        )
        true
    } catch (e: Exception) {
        // 直发已经完成，补投失败不影响前台或 AOSP 路径。
        Log.w("SnapClear Notify", "scheduleBackgroundRepost failed", e)
        false
    }

    /**
     * 读取截图缩略图（展开态左上角展示）
     */
    private fun loadThumbnail(context: Context, imageUri: Uri): Bitmap? {
        return try {
            context.contentResolver.loadThumbnail(imageUri, Size(360, 360), null)
        } catch (e: Exception) {
            Log.w("SnapClear Notify", "loadThumbnail failed for $imageUri", e)
            null
        }
    }

    /**
     * 只调度一次到期闹钟。倒计时显示由 SystemUI 的 Chronometer 驱动，
     * 无需也不能用每秒精确闹钟（Android 会对 allow-while-idle 闹钟限频）。
     */
    private fun scheduleExpiry(context: Context, notificationId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, LiveUpdateTickReceiver::class.java).apply {
            action = ACTION_LIVE_UPDATE_TICK
            putExtra(EXTRA_LIVE_UPDATE_ID, notificationId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = loadLiveUpdateState(context, notificationId)?.startTime
            ?.plus(LIVE_UPDATE_DURATION_MS) ?: return
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            return
        } catch (_: SecurityException) {
            // SCHEDULE_EXACT_ALARM 未授予，继续 fallback
        } catch (_: Exception) {
            // 其他异常，继续 fallback
        }
        try {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } catch (_: Exception) {
            Log.w("SnapClear Notify", "scheduleExpiry failed for id=$notificationId")
        }
    }

    /**
     * 倒计时 tick（由 LiveUpdateTickReceiver 调用）
     *
     * 每秒重新发送通知以更新倒计时文本；倒计时结束时自动取消（「不再提示」）。
     * 状态从 SharedPreferences 恢复，进程被杀后 tick 仍可工作。
     */
    fun onLiveUpdateTick(context: Context, notificationId: Int) {
        val appContext = context.applicationContext
        val state = loadLiveUpdateState(appContext, notificationId)
        if (state == null) {
            // 状态已清除（通知已被处理）→ 兜底取消
            cancelNotification(appContext, notificationId)
            return
        }

        val remainingMs = state.startTime + LIVE_UPDATE_DURATION_MS - System.currentTimeMillis()
        if (remainingMs <= 0) {
            DiagnosticLogger.log(
                DiagnosticEventType.INFO,
                "流体云倒计时结束(id=$notificationId)，自动取消"
            )
            cancelNotification(appContext, notificationId)
            return
        }

        // 提前触发只可能来自升级前遗留的逐秒闹钟；不要刷新通知或继续重排，
        // 仅补排真正的到期闹钟。
        scheduleExpiry(appContext, notificationId)
    }

    /** 计算指定通知的倒计时终点 */
    private fun liveUpdateEndTime(context: Context, notificationId: Int): Long {
        val start = loadLiveUpdateState(context, notificationId)?.startTime
            ?: System.currentTimeMillis()
        return start + LIVE_UPDATE_DURATION_MS
    }

    /**
     * 低版本通知（API < 36）及 API 36+ 权限未授予时的回退通知
     */
    private fun buildLegacyNotification(
        context: Context,
        contentIntent: PendingIntent,
        copyDeletePendingIntent: PendingIntent,
        ignorePendingIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_screenshot_title))
            .setContentText(context.getString(R.string.notification_screenshot_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(LIVE_UPDATE_DURATION_MS)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .addAction(
                R.drawable.ic_action_copy_delete,
                context.getString(R.string.notification_action_copy_delete),
                copyDeletePendingIntent
            )
            .addAction(
                R.drawable.ic_action_ignore,
                context.getString(R.string.notification_action_ignore),
                ignorePendingIntent
            )
            .setDeleteIntent(ignorePendingIntent)
            .build()
    }

    /**
     * 取消指定通知，同时停止其倒计时闹钟并清除持久化状态
     */
    fun cancelNotification(context: Context, id: Int) {
        val appContext = context.applicationContext
        cancelAlarms(appContext, id)
        appContext.getSystemService(NotificationManager::class.java).cancel(id)
        activeScreenshotIds.remove(id)
        clearLiveUpdateState(appContext, id)
    }

    /** 取消指定通知的 tick / post 闹钟 */
    private fun cancelAlarms(context: Context, notificationId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val tickIntent = Intent(context, LiveUpdateTickReceiver::class.java).apply {
            action = ACTION_LIVE_UPDATE_TICK
        }
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context, notificationId, tickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        val postIntent = Intent(context, LiveUpdateTickReceiver::class.java).apply {
            action = ACTION_LIVE_UPDATE_POST
        }
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context, notificationId + POST_REQUEST_CODE_OFFSET, postIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    // ---------- 状态持久化（进程被杀后恢复） ----------

    private fun persistLiveUpdateState(context: Context, id: Int, uri: Uri, startTime: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX_URI + id, uri.toString())
            .putLong(KEY_PREFIX_START + id, startTime)
            .commit()
    }

    private fun loadLiveUpdateState(context: Context, id: Int): LiveUpdateState? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_PREFIX_URI + id, null) ?: return null
        return LiveUpdateState(
            imageUri = Uri.parse(uriString),
            startTime = prefs.getLong(KEY_PREFIX_START + id, 0L)
        )
    }

    private fun clearLiveUpdateState(context: Context, id: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX_URI + id)
            .remove(KEY_PREFIX_START + id)
            .apply()
    }

    /** 是否有权限发送流体云通知 */
    private fun canPostPromoted(context: Context): Boolean {
        return try {
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        } catch (e: Throwable) {
            false
        }
    }

    /** 前台服务的重要性为 IMPORTANCE_FOREGROUND_SERVICE，不会被误判为界面前台。 */
    private fun isAppInForeground(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return activityManager.runningAppProcesses?.any {
            it.processName == context.packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

}

/**
 * 通知 Action 广播接收器
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 0)
        when (intent.action) {
            NotificationHelper.ACTION_COPY_DELETE -> {
                val uriString = intent.getStringExtra(NotificationHelper.EXTRA_IMAGE_URI)
                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    ClipboardHelper.copyAndDelete(context, uri)
                } else {
                    Toast.makeText(context, R.string.toast_copy_failed, Toast.LENGTH_SHORT).show()
                }
                if (notificationId > 0) {
                    NotificationHelper.cancelNotification(context, notificationId)
                }
                // 通知主页刷新最近截图列表
                com.snapclear.app.screenshot.ScreenshotEvents.notifyScreenshotListChanged()
            }

            NotificationHelper.ACTION_IGNORE -> {
                if (notificationId > 0) {
                    NotificationHelper.cancelNotification(context, notificationId)
                }
                // 不再提示后也刷新列表（截图仍应出现在最近截图面板）
                com.snapclear.app.screenshot.ScreenshotEvents.notifyScreenshotListChanged()
            }
        }
    }
}
