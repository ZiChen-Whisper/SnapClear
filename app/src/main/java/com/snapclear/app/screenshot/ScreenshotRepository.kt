package com.snapclear.app.screenshot

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.Immutable
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
        MediaStore.Images.Media.DATE_ADDED,
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
     * 查询最近 30 天的截图列表（按 MediaStore 入库时间降序）
     */
    fun queryRecent(context: Context): List<ScreenshotItem> =
        queryRecentPage(context, limit = 20, offset = 0).items

    /**
     * 分页查询最近截图。筛选在 MediaStore 内完成，避免先读取一个月的全部图片再过滤。
     */
    fun queryRecentPage(context: Context, limit: Int, offset: Int): ScreenshotPage {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val resolver = context.contentResolver
        // DATE_ADDED 的单位是秒；DATE_TAKEN 来自图片元数据，在 ColorOS 上可能滞后或不可靠。
        val cutoffSeconds = (System.currentTimeMillis() - RECENT_WINDOW_MS) / 1000L
        val results = mutableListOf<ScreenshotItem>()

        val screenshotClauses = buildList {
            SCREENSHOT_PATH_PATTERNS.forEach {
                add("${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            }
            SCREENSHOT_NAME_PATTERNS.forEach {
                add("${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?")
            }
        }
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?" +
            " AND ${MediaStore.Images.Media.IS_TRASHED} = 0" +
            " AND (${screenshotClauses.joinToString(" OR ")})"
        val selectionArgs = buildList {
            add(cutoffSeconds.toString())
            SCREENSHOT_PATH_PATTERNS.forEach { add("%${it.lowercase()}%") }
            SCREENSHOT_NAME_PATTERNS.forEach { add("%${it.lowercase()}%") }
        }.toTypedArray()
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            // ColorOS 的 MediaProvider 对多列 QUERY_ARG_SORT_COLUMNS + SORT_DIRECTION
            // 存在方向兼容差异，使用明确 SQL 排序保证最新截图始终排在最前。
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }

        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                queryArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
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
                            dateTaken = effectiveScreenshotTime(
                                dateTakenMs = cursor.getLong(dateCol),
                                dateAddedSeconds = cursor.getLong(dateAddedCol)
                            ),
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

        val hasMore = results.size > limit
        if (hasMore) results.removeAt(results.lastIndex)
        Log.d(TAG, "queryRecentPage: offset=$offset, found=${results.size}, hasMore=$hasMore")
        return ScreenshotPage(results, hasMore)
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
                        dateTaken = effectiveScreenshotTime(
                            dateTakenMs = cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                            ),
                            dateAddedSeconds = cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                            )
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

    /**
     * 列表筛选以 DATE_ADDED 为准；展示时间优先使用同一字段，确保用户看到的日期
     * 与“最近 30 天”的分页顺序一致。DATE_ADDED 缺失时才回退图片元数据时间。
     */
    internal fun effectiveScreenshotTime(dateTakenMs: Long, dateAddedSeconds: Long): Long {
        val dateAddedMs = dateAddedSeconds * 1000L
        return if (dateAddedMs > 0L) dateAddedMs else dateTakenMs
    }
}

/**
 * 截图数据项
 */
@Immutable
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

data class ScreenshotPage(
    val items: List<ScreenshotItem>,
    val hasMore: Boolean
)
