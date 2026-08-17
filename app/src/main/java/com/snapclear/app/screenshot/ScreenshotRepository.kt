package com.snapclear.app.screenshot

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger

/**
 * 最近截图数据仓库
 *
 * 独立于 [ScreenshotObserver.lastDetectedId]，直接查询 MediaStore 获取近一月截图。
 * 用于「最近截图」面板展示，包括应用未运行期间产生的截图。
 *
 * 排除已移入回收站的项（IS_TRASHED=0）。
 */
object ScreenshotRepository {

    private const val TAG = "ScreenshotRepo"
    /** 查询窗口：最近 30 天 */
    private const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    private val PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT
    )

    private val SCREENSHOT_NAME_PATTERNS = listOf(
        "screenshot", "screen_shot", "截图", "截屏"
    )
    private val SCREENSHOT_PATH_PATTERNS = listOf(
        "screenshots", "pictures/screenshots", "dcim/screenshots"
    )

    /**
     * 查询最近 30 天的截图列表（按拍摄时间降序）
     */
    fun queryRecent(context: Context): List<ScreenshotItem> {
        val resolver = context.contentResolver
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        val results = mutableListOf<ScreenshotItem>()

        val selection = buildString {
            append("${MediaStore.Images.Media.DATE_TAKEN} >= ?")
            append(" AND ${MediaStore.Images.Media.IS_TRASHED} = 0")
        }
        val selectionArgs = arrayOf(cutoff.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: ""
                    val path = cursor.getString(pathCol) ?: ""
                    if (!isScreenshot(name, path)) continue

                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    results.add(
                        ScreenshotItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            relativePath = path,
                            dateTaken = cursor.getLong(dateCol),
                            size = cursor.getLong(sizeCol),
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryRecent failed", e)
            DiagnosticLogger.log(DiagnosticEventType.ERROR, "查询最近截图失败: ${e.message}")
        }

        Log.d(TAG, "queryRecent: found ${results.size} screenshots in last 30 days")
        return results
    }

    /**
     * 根据单个 URI 查询截图详情
     *
     * 用于截图详情页加载完整文件信息。
     */
    fun queryByUri(context: Context, uri: Uri): ScreenshotItem? {
        val resolver = context.contentResolver
        val id = try {
            ContentUris.parseId(uri)
        } catch (e: Exception) {
            return null
        }

        return try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    ) ?: ""
                    val path = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    ) ?: ""
                    ScreenshotItem(
                        id = id,
                        uri = uri,
                        displayName = name,
                        relativePath = path,
                        dateTaken = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                        ),
                        size = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                        ),
                        width = cursor.getInt(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                        ),
                        height = cursor.getInt(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                        )
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryByUri failed", e)
            null
        }
    }

    private fun isScreenshot(displayName: String, relativePath: String): Boolean {
        val lowerName = displayName.lowercase()
        val lowerPath = relativePath.lowercase()
        if (SCREENSHOT_PATH_PATTERNS.any { lowerPath.contains(it) }) return true
        if (SCREENSHOT_NAME_PATTERNS.any { lowerName.contains(it) }) return true
        return false
    }
}

/**
 * 截图数据项
 */
data class ScreenshotItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int
)
