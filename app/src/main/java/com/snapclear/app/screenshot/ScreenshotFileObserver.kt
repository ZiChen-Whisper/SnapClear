package com.snapclear.app.screenshot

import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.snapclear.app.diagnostic.DiagnosticEventType
import com.snapclear.app.diagnostic.DiagnosticLogger
import java.io.File

/**
 * 截图目录文件监听器（基于 Linux inotify）
 *
 * 使用 FileObserver 直接监听截图目录的文件创建事件，
 * 比 ContentObserver 更直接、更可靠，不受 MediaStore 索引延迟影响。
 *
 * 监听目录：
 * - Pictures/Screenshots（AOSP 标准）
 * - DCIM/Screenshots（部分 OEM）
 *
 * 功耗：事件驱动，无轮询，功耗极低。
 *
 * 工作原理：
 * FileObserver 基于 Linux 内核 inotify 机制，当被监听目录中有文件
 * 创建/移动/写入关闭时，内核会立即通知进程。只要进程存活（由前台服务保证），
 * 即使应用在后台，也能实时收到事件。
 */
class ScreenshotFileObserver(
    private val onScreenshotDetected: () -> Unit
) {
    private val observers = mutableListOf<FileObserver>()
    private val handlerThread = HandlerThread("ScreenshotFileObserver").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /** 防抖 Runnable：多个文件事件只触发一次检测 */
    private val debounceRunnable = Runnable {
        onScreenshotDetected()
    }

    companion object {
        private const val TAG = "ScreenshotFileObserver"
        private const val DEBOUNCE_DELAY_MS = 500L

        /** 监听文件创建、移入、写入关闭事件 */
        private val WATCH_MASK = FileObserver.CREATE or
                FileObserver.MOVED_TO or
                FileObserver.CLOSE_WRITE

        private fun getScreenshotDirs(): List<File> {
            val storage = Environment.getExternalStorageDirectory()
            return listOf(
                File(storage, "Pictures/Screenshots"),
                File(storage, "DCIM/Screenshots")
            )
        }

        private fun isImageFile(filename: String): Boolean {
            val lower = filename.lowercase()
            return lower.endsWith(".png") ||
                   lower.endsWith(".jpg") ||
                   lower.endsWith(".jpeg") ||
                   lower.endsWith(".webp") ||
                   lower.endsWith(".bmp") ||
                   lower.endsWith(".gif")
        }

        /**
         * 判断是否为 MediaStore 写入过程中的临时文件。
         *
         * Android MediaStore 写入图片时会先创建 .pending-XXXX-原文件名 临时文件，
         * 写入完成后才重命名为最终文件名并提交 MediaStore 条目。
         * 如果在 .pending 阶段就触发检测，MediaStore 中尚无对应条目，
         * detectAndAdvance 将返回空，导致漏检。
         *
         * 过滤此阶段的事件，只在最终文件出现时才触发检测。
         */
        private fun isPendingFile(filename: String): Boolean {
            return filename.startsWith(".pending-")
        }
    }

    /**
     * 开始监听截图目录
     * 如果目录不存在会自动创建
     */
    fun start() {
        val dirs = getScreenshotDirs()
        for (dir in dirs) {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if (!dir.isDirectory) continue

            val observer = object : FileObserver(dir, WATCH_MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    Log.d(TAG, "File event: $path, event=$event, dir=${dir.absolutePath}")
                    DiagnosticLogger.log(
                        DiagnosticEventType.FILE_OBS,
                        "事件: $path (dir=${dir.name})"
                    )
                    if (isImageFile(path) && !isPendingFile(path)) {
                        // 防抖：500ms 内多个事件只触发一次检测
                        // 同时给 MediaStore 500ms 时间索引新文件
                        handler.removeCallbacks(debounceRunnable)
                        handler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS)
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
            DiagnosticLogger.log(
                DiagnosticEventType.INFO,
                "FileObserver 开始监听: ${dir.absolutePath}"
            )
            Log.d(TAG, "Watching: ${dir.absolutePath}")
        }
    }

    /**
     * 停止监听，释放资源
     */
    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }
}
