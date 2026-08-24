package com.snapclear.app.permission

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.snapclear.app.screenshot.ScreenshotAccessibilityService

/**
 * 权限管理工具类
 *
 * 负责：
 * - 判断所需权限是否已授权
 * - 提供需要申请的权限列表（根据 API 级别区分）
 * - 检测 OPPO/OnePlus/Realme 设备
 * - 电池优化豁免检查与请求
 * - OPPO 自启动引导
 * - 提供跳转系统设置页面（含 OPPO 特殊路径引导）
 */
object PermissionManager {

    /**
     * 获取当前设备所需的权限列表（按 API 级别动态处理）
     *
     * - API 31-32：使用 READ_EXTERNAL_STORAGE
     * - API 33+：使用 READ_MEDIA_IMAGES（细粒度媒体权限）+ POST_NOTIFICATIONS
     */
    fun getRequiredPermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * 检查所有必要权限是否均已授予
     */
    fun checkAllGranted(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 判断是否为 OPPO / OnePlus / Realme 设备（ColorOS 系统）
     */
    fun isOppoDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("oppo") ||
                manufacturer.contains("oneplus") ||
                manufacturer.contains("realme") ||
                brand.contains("oppo") ||
                brand.contains("oneplus") ||
                brand.contains("realme")
    }

    /** 检查系统是否已启用 SnapClear 的截图实时检测无障碍服务。 */
    fun isScreenshotAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ScreenshotAccessibilityService::class.java)

        // 系统真正的持久化来源。应用进程重启不会清除该值；直接读取它可以避免
        // AccessibilityManager 在服务尚未重新绑定的短窗口内误报“未授权”。
        val persistedEnabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
                ?.split(':')
                ?.mapNotNull(ComponentName::unflattenFromString)
                ?.any { it == expected }
                ?: false
        }.getOrDefault(false)
        if (persistedEnabled) return true

        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                val className = if (serviceInfo.name.startsWith('.')) {
                    serviceInfo.packageName + serviceInfo.name
                } else {
                    serviceInfo.name
                }
                ComponentName(serviceInfo.packageName, className) == expected ||
                    ComponentName.unflattenFromString(info.id) == expected
            }
    }

    /**
     * 无障碍权限不能通过运行时弹窗申请，只能由用户在系统设置中手动开启。
     * 优先打开 SnapClear 服务详情；ROM 不支持时回退到无障碍服务列表。
     */
    fun openScreenshotAccessibilitySettings(context: Context) {
        val component = ComponentName(context, ScreenshotAccessibilityService::class.java)
        // ACTION_ACCESSIBILITY_DETAILS_SETTINGS 尚未作为公开 SDK 常量暴露，
        // 但 AOSP/ColorOS Settings 均使用这个稳定 action；失败时回退到服务列表。
        val detailIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(detailIntent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                openAppSettings(context)
            }
        }
    }

    /**
     * 检查精确闹钟权限（后台 Doze 穿透必需）
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 检查应用是否已获得电池优化豁免（白名单）
     *
     * 豁免后系统不会对应用进行激进的省电限制，
     * 前台服务更不容易被杀死。
     */
    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 直接请求电池优化豁免（弹出系统对话框）
     *
     * 使用 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，
     * 会弹出系统确认对话框让用户一键加入白名单。
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 某些设备不支持直接请求，回退到电池优化设置列表
            openBatteryOptimizationSettings(context)
        }
    }

    /**
     * 打开电池优化设置列表页（让用户手动找到应用并设置）
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 最终回退到应用详情页
            openAppSettings(context)
        }
    }

    /**
     * 打开 ColorOS 自有的后台耗电/自启动页面。
     *
     * Android 的电池优化白名单不覆盖 Hans/Athena 等厂商冻结策略，而且这些开关
     * 没有公开的只读 API，因此只能把用户带到最接近的厂商页面手动确认。
     */
    fun openOppoBackgroundSettings(context: Context) {
        val candidates = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
                )
                putExtra("pkgName", context.packageName)
                putExtra("packageName", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                component = ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
                putExtra("pkgName", context.packageName)
                putExtra("packageName", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            createOppoIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            createOppoIntent(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        )

        for (intent in candidates) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                // 尝试下一个 ColorOS 版本对应的组件。
            }
        }
        openAppSettings(context)
    }

    /**
     * 打开系统精确闹钟权限设置页面（Android 12+）
     */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } else {
            openAppSettings(context)
        }
    }

    /**
     * 检查流体云（提升通知）权限
     *
     * Android 16+ 的 POST_PROMOTED_NOTIFICATIONS 是特殊权限，
     * 类似 USE_FULL_SCREEN_INTENT，需用户在系统设置中手动授予。
     * 仅 manifest 声明不够，必须用 NotificationManager.canPostPromotedNotifications() 检查。
     *
     * 授予后通知才能被 setRequestPromotedOngoing(true) 提升为流体云 / Live Updates，
     * 从而绕过后台通知延迟投递。
     */
    fun canPostPromotedNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canPostPromotedNotifications()
    }

    /**
     * 打开流体云（提升通知）权限设置页面
     *
     * 对应系统设置路径：设置 → 应用 → SnapClear → 通知 → 提升通知（或"流体云"）
     */
    fun openPromotedNotificationsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 部分设备不支持该 action，回退到应用通知设置页
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                openAppSettings(context)
            }
        }
    }

    /**
     * 打开应用设置页面
     *
     * 对于 OPPO 设备，优先尝试打开 ColorOS 的权限管理页面；
     * 如果跳转失败则回退到通用应用详情设置页。
     */
    fun openAppSettings(context: Context) {
        if (isOppoDevice()) {
            val oppoIntents = listOf(
                createOppoIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"
                ),
                createOppoIntent(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.PermissionManagerActivity"
                )
            )

            for (intent in oppoIntents) {
                try {
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        return
                    }
                } catch (_: Exception) {
                    // 该页面不可用，继续尝试下一个
                }
            }

            // ColorOS 兜底：打开安全管理页面
            try {
                val safeIntent = Intent().apply {
                    setClassName("com.coloros.safecenter", "com.coloros.safecenter.MainActivity")
                }
                if (safeIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(safeIntent)
                    return
                }
            } catch (_: Exception) {
                // 继续回退
            }
        }

        // 通用兜底：系统应用详情设置页
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun createOppoIntent(packageName: String, className: String): Intent {
        return Intent().apply {
            component = ComponentName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
