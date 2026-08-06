package com.snapclear.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
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
 * - 创建截图操作通知渠道（IMPORTANCE_HIGH，弹出式通知）
 * - 发送截图检测通知（含「拷贝并删除」和「忽略」两个 Action 按钮）
 * - 提供 NotificationActionReceiver 处理 Action 点击
 */
object NotificationHelper {

    const val CHANNEL_ID = "screenshot_action"
    const val CHANNEL_ID_MONITOR = "monitor_service"
    const val MONITOR_NOTIFICATION_ID = 1

    // Action 常量
    const val ACTION_COPY_DELETE = "com.snapclear.app.action.COPY_DELETE"
    const val ACTION_IGNORE = "com.snapclear.app.action.IGNORE"
    const val EXTRA_IMAGE_URI = "extra_image_uri"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    private val nextScreenshotNotificationId = AtomicInteger(100)

    /**
     * 创建所有通知渠道（应在应用启动时调用一次）
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            // 截图操作通知渠道（高优先级，弹出式）
            val actionChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(actionChannel)

            // 监听服务通知渠道（低优先级，静默显示在状态栏）
            val monitorChannel = NotificationChannel(
                CHANNEL_ID_MONITOR,
                context.getString(R.string.notification_channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_monitor_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(monitorChannel)
        }
    }

    /**
     * 弹出截图操作通知
     */
    fun showScreenshotNotification(context: Context, imageUri: Uri) {
        val notificationId = nextScreenshotNotificationId.incrementAndGet()
        Log.d("SnapClear Notify", "showScreenshotNotification: id=$notificationId, uri=$imageUri")

        // 「拷贝并删除」Action → BroadcastReceiver
        val copyDeleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_COPY_DELETE
            putExtra(EXTRA_IMAGE_URI, imageUri.toString())
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val copyDeletePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            copyDeleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 「忽略」Action → BroadcastReceiver
        val ignoreIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_IGNORE
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val ignorePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 10_000,
            ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 通知点击时打开主界面
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId + 20_000,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(context.getString(R.string.notification_screenshot_title))
            .setContentText(context.getString(R.string.notification_screenshot_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_save,
                context.getString(R.string.notification_action_copy_delete),
                copyDeletePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notification_action_ignore),
                ignorePendingIntent
            )
            .setDeleteIntent(ignorePendingIntent)
            .setFullScreenIntent(contentIntent, true)

        val notification = builder.build()

        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            manager.notify(notificationId, notification)
            DiagnosticLogger.log(
                DiagnosticEventType.NOTIFY,
                "通知已发送: id=$notificationId, uri=$imageUri"
            )
        } catch (e: Exception) {
            Log.e("SnapClear Notify", "notify() failed", e)
            DiagnosticLogger.log(
                DiagnosticEventType.ERROR,
                "通知发送失败: ${e.message}"
            )
        }
    }

    /**
     * 取消指定通知
     */
    fun cancelNotification(context: Context, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(id)
    }
}

/**
 * 通知 Action 广播接收器
 *
 * 静态注册在 AndroidManifest.xml 中，android:exported="false"（仅供内部通知使用）。
 * 负责处理通知中的「拷贝并删除」和「忽略」操作。
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
            }

            NotificationHelper.ACTION_IGNORE -> {
                if (notificationId > 0) {
                    NotificationHelper.cancelNotification(context, notificationId)
                }
            }
        }
    }
}
