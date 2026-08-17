package com.snapclear.app.screenshot

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger

/**
 * 截图监听器：ContentObserver + 统一检测逻辑
 *
 * - ContentObserver：监听 MediaStore 变化，实时响应
 * - ScreenshotFileObserver：监听截图目录文件创建（更直接，在 Service 中集成）
 * - ScreenshotAlarmReceiver：后台深度休眠兜底
 *
 * 核心原则：每查到一张图片（无论是否截图）都推进 lastDetectedId，
 * 防止非截图图片阻塞后续检测。
 *
 * 通知策略（v2）：只在服务运行期间产生的截图才触发流体云通知。
 * initLastDetectedId 始终把 lastDetectedId 推进到 MediaStore 当前最大 ID，
 * 跳过未运行期间的历史截图（避免「通知风暴」）。这些历史截图仍会通过
 * 「最近截图」面板的独立 MediaStore 查询展示，用户可手动拷贝并删除。
 */
class ScreenshotObserver(
    private val contentResolver: ContentResolver,
    private val onScreenshotDetected: (Uri) -> Unit
) {
    private val handlerThread = HandlerThread("ScreenshotObserver").apply { start() }
    private val backgroundHandler = Handler(handlerThread.looper)

    private val observer = object : ContentObserver(backgroundHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            DiagnosticLogger.log(
                DiagnosticEventType.CONTENT_OBS,
                "MediaStore 变化: uri=$uri"
            )
            val beforeId = lastDetectedId
            detectAndAdvance(contentResolver, onScreenshotDetected)
            // ContentObserver 回调在 MediaStore 条目刚创建时就会触发，
            // 但此时条目可能仍处于 IS_PENDING 状态，查询可能返回空。
            // 添加延迟重试：如果首次检测未推进游标，说明 MediaStore
            // 尚未提交该条目，1s 后重试一次。
            if (lastDetectedId == beforeId) {
                backgroundHandler.postDelayed({
                    DiagnosticLogger.log(
                        DiagnosticEventType.CONTENT_OBS,
                        "重试检测 (首次未推进, lastDetectedId=$lastDetectedId)"
                    )
                    detectAndAdvance(contentResolver, onScreenshotDetected)
                }, 1000L)
            }
        }
    }

    fun register() {
        initLastDetectedId(contentResolver)

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
    }

    fun unregister() {
        contentResolver.unregisterContentObserver(observer)
        handlerThread.quitSafely()
    }

    companion object {
        private const val TAG = "ScreenshotObserver"
        private const val PREFS_NAME = "snapclear_prefs"
        private const val KEY_LAST_DETECTED_ID = "last_detected_id"

        /** 统一检测锁：ContentObserver、FileObserver 和 AlarmReceiver 共用 */
        @JvmField
        val detectionLock = Any()

        @Volatile
        var lastDetectedId: Long = 0L
            private set

        @Volatile
        private var initialized = false

        @Volatile
        private var appContext: Context? = null

        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        private val SCREENSHOT_NAME_PATTERNS = listOf(
            "screenshot", "screen_shot", "截图", "截屏"
        )
        private val SCREENSHOT_PATH_PATTERNS = listOf(
            "screenshots", "pictures/screenshots", "dcim/screenshots"
        )

        /**
         * 必须在使用其他 companion 函数前调用一次，注入 ApplicationContext
         * 用于 lastDetectedId 的持久化。
         */
        fun init(context: Context) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }

        private fun persistLastDetectedId(id: Long) {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putLong(KEY_LAST_DETECTED_ID, id)
                ?.apply()
        }

        fun initLastDetectedId(resolver: ContentResolver) {
            if (initialized) return
            synchronized(detectionLock) {
                if (initialized) return

                if (appContext == null) {
                    Log.w(TAG, "init() not called before initLastDetectedId() — persistence unavailable")
                }

                // 设计变更：始终推进到 MediaStore 当前最大 ID。
                // 只在服务运行期间产生的截图才触发流体云通知，未运行期间的历史截图
                // 不弹通知（避免打开应用后弹出一堆历史截图通知的「通知风暴」）。
                // 这些历史截图仍会通过「最近截图」面板的独立 MediaStore 查询展示。
                val cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null,
                    "${MediaStore.Images.Media._ID} DESC"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        lastDetectedId = it.getLong(0)
                        Log.d(TAG, "Advanced lastDetectedId to MediaStore max: $lastDetectedId (skip historical notifications)")
                    }
                }
                persistLastDetectedId(lastDetectedId)
                Log.d(TAG, "initLastDetectedId complete: $lastDetectedId")
                initialized = true
            }
        }

        private fun setLastDetectedId(id: Long) {
            lastDetectedId = id
            persistLastDetectedId(id)
        }

        /**
         * 统一检测入口（ContentObserver、FileObserver 和 AlarmReceiver 共用）
         *
         * 查询 _ID > lastDetectedId 的最新图片，无论是否是截图都推进 cursor，
         * 防止非截图图片永久阻塞检测流水线。
         */
        fun detectAndAdvance(
            resolver: ContentResolver,
            onDetected: (Uri) -> Unit
        ) {
            synchronized(detectionLock) {
                val cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    "${MediaStore.Images.Media._ID} > ?",
                    arrayOf(lastDetectedId.toString()),
                    "${MediaStore.Images.Media._ID} ASC"
                )
                if (cursor == null) {
                    Log.w(TAG, "detectAndAdvance: cursor is null, lastDetectedId=$lastDetectedId")
                    return
                }

                var newImageCount = 0
                var screenshotCount = 0
                cursor.use {
                    while (it.moveToNext()) {
                        newImageCount++
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val displayName = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""
                        val relativePath = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: ""

                        if (isScreenshot(displayName, relativePath)) {
                            screenshotCount++
                            val imageUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            )
                            Log.d(TAG, "Screenshot detected: id=$id, name=$displayName, path=$relativePath")
                            DiagnosticLogger.log(
                                DiagnosticEventType.SCREENSHOT,
                                "发现截图: id=$id, name=$displayName, path=$relativePath"
                            )
                            onDetected(imageUri)
                        }

                        // 无论是否截图，推进到当前已处理的最大 ID
                        if (id > lastDetectedId) {
                            setLastDetectedId(id)
                        }
                    }
                }
                if (newImageCount > 0) {
                    Log.d(TAG, "detectAndAdvance: found $newImageCount new images ($screenshotCount screenshots), lastDetectedId now=$lastDetectedId")
                }
            }
        }

        /**
         * 判断是否为截图
         *
         * 判断逻辑（满足任一即为截图）：
         * 1. 文件路径包含 screenshots 关键字（最可靠）
         * 2. 文件名包含 screenshot/截图/截屏 关键字
         */
        private fun isScreenshot(displayName: String, relativePath: String): Boolean {
            val lowerName = displayName.lowercase()
            val lowerPath = relativePath.lowercase()

            // 路径匹配：文件在 Screenshots 目录下，一定是截图
            if (SCREENSHOT_PATH_PATTERNS.any { lowerPath.contains(it) }) {
                return true
            }

            // 文件名匹配：文件名包含截图关键字
            if (SCREENSHOT_NAME_PATTERNS.any { lowerName.contains(it) }) {
                return true
            }

            return false
        }
    }
}
