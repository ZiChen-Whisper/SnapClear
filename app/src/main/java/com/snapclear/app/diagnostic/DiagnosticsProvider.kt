package com.snapclear.app.diagnostic

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.snapclear.app.permission.PermissionManager
import com.snapclear.app.screenshot.ScreenshotMonitorService
import com.snapclear.app.screenshot.ScreenshotObserver

/**
 * 诊断数据收集器
 *
 * 查询系统所有相关状态，供诊断面板显示。
 */
object DiagnosticsProvider {

    data class DiagnosticsData(
        val serviceRunning: Boolean,
        val monitoringEnabled: Boolean,
        val lastDetectedId: Long,
        val persistedLastDetectedId: Long,
        val notificationPermissionGranted: Boolean,
        val mediaPermissionGranted: Boolean,
        val exactAlarmGranted: Boolean,
        val batteryOptExempt: Boolean,
        val mediaStoreImageCount: Int,
        val mediaStoreScreenshotCount: Int,
        val mediaStoreMaxId: Long,
        val recentImages: List<RecentImage>,
        val screenshotDirs: List<DirInfo>
    )

    data class RecentImage(
        val id: Long,
        val displayName: String,
        val relativePath: String,
        val isScreenshot: Boolean
    )

    data class DirInfo(
        val path: String,
        val exists: Boolean,
        val fileCount: Int
    )

    fun collect(context: Context): DiagnosticsData {
        val resolver = context.contentResolver

        // 服务状态
        val serviceRunning = ScreenshotMonitorService.isRunning
        val monitoringEnabled = ScreenshotMonitorService.isMonitoringEnabled(context)

        // lastDetectedId
        val lastDetectedId = ScreenshotObserver.lastDetectedId
        val persisted = context.getSharedPreferences("snapclear_prefs", Context.MODE_PRIVATE)
            .getLong("last_detected_id", -1L)

        // 权限
        val notifGranted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        val mediaGranted = PermissionManager.checkAllGranted(context)
        val exactAlarmGranted = PermissionManager.canScheduleExactAlarms(context)
        val batteryExempt = PermissionManager.isBatteryOptimizationExempt(context)

        // MediaStore 统计
        var imageCount = 0
        var screenshotCount = 0
        var maxId = 0L
        val recentImages = mutableListOf<RecentImage>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        // 总图片数 + 最大 ID
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null, null,
            "${MediaStore.Images.Media._ID} DESC"
        )?.use { cursor ->
            imageCount = cursor.count
            if (cursor.moveToFirst()) {
                maxId = cursor.getLong(0)
            }
        }

        // 截图数（按路径/文件名匹配）
        val screenshotSelection = buildString {
            val patterns = listOf("screenshots", "screenshot", "screen_shot", "截图", "截屏")
            patterns.forEachIndexed { index, pattern ->
                if (index > 0) append(" OR ")
                append("(")
                append(MediaStore.Images.Media.RELATIVE_PATH).append(" LIKE '%").append(pattern).append("%'")
                append(" OR ")
                append(MediaStore.Images.Media.DISPLAY_NAME).append(" LIKE '%").append(pattern).append("%'")
                append(")")
            }
        }
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            screenshotSelection, null, null
        )?.use { cursor ->
            screenshotCount = cursor.count
        }

        // 最近 10 张图片
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null,
            "${MediaStore.Images.Media._ID} DESC"
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < 10) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: ""
                recentImages.add(
                    RecentImage(
                        id = id,
                        displayName = name,
                        relativePath = path,
                        isScreenshot = isScreenshot(name, path)
                    )
                )
                count++
            }
        }

        // 截图目录状态
        val dirs = mutableListOf<DirInfo>()
        val storage = android.os.Environment.getExternalStorageDirectory()
        listOf(
            java.io.File(storage, "Pictures/Screenshots"),
            java.io.File(storage, "DCIM/Screenshots")
        ).forEach { dir ->
            dirs.add(
                DirInfo(
                    path = dir.absolutePath,
                    exists = dir.exists(),
                    fileCount = if (dir.exists()) (dir.listFiles()?.size ?: 0) else 0
                )
            )
        }

        return DiagnosticsData(
            serviceRunning = serviceRunning,
            monitoringEnabled = monitoringEnabled,
            lastDetectedId = lastDetectedId,
            persistedLastDetectedId = persisted,
            notificationPermissionGranted = notifGranted,
            mediaPermissionGranted = mediaGranted,
            exactAlarmGranted = exactAlarmGranted,
            batteryOptExempt = batteryExempt,
            mediaStoreImageCount = imageCount,
            mediaStoreScreenshotCount = screenshotCount,
            mediaStoreMaxId = maxId,
            recentImages = recentImages,
            screenshotDirs = dirs
        )
    }

    private fun isScreenshot(displayName: String, relativePath: String): Boolean {
        val lowerName = displayName.lowercase()
        val lowerPath = relativePath.lowercase()
        val namePatterns = listOf("screenshot", "screen_shot", "截图", "截屏")
        val pathPatterns = listOf("screenshots", "pictures/screenshots", "dcim/screenshots")
        return pathPatterns.any { lowerPath.contains(it) } ||
               namePatterns.any { lowerName.contains(it) }
    }
}
