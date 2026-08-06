package com.snapclear.app.screenshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机/应用更新后自动恢复截图监听服务
 *
 * 仅当用户之前主动开启过监听时才自动恢复。
 * 从 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED
 * 启动前台服务属于系统豁免范围，不受后台启动限制。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (ScreenshotMonitorService.isMonitoringEnabled(context)) {
                    // 注入 ApplicationContext 用于 lastDetectedId 持久化
                    ScreenshotObserver.init(context)

                    try {
                        val serviceIntent = Intent(context, ScreenshotMonitorService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to start monitor service on boot", e)
                    }
                }
            }
        }
    }
}
