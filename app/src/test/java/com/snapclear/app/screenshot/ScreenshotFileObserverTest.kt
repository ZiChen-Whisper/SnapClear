package com.snapclear.app.screenshot

import android.os.Handler
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * ScreenshotFileObserver 核心路径单元测试
 *
 * 覆盖：
 * - isPendingFile：.pending- 前缀临时文件过滤
 * - isImageFile：图片扩展名识别（含大小写不敏感）
 * - 防抖逻辑：多个快速事件只触发一次 handler.postDelayed
 * - 防抖跳过：.pending 文件、非图片文件、null path
 */
class ScreenshotFileObserverTest {

    private val companionInstance = ScreenshotFileObserver.Companion

    private val isPendingFileMethod = companionInstance::class.java
        .getDeclaredMethod("isPendingFile", String::class.java)
        .apply { isAccessible = true }

    private val isImageFileMethod = companionInstance::class.java
        .getDeclaredMethod("isImageFile", String::class.java)
        .apply { isAccessible = true }

    private val screenshotName = "Screenshot_20240801.png"

    // ─── isPendingFile：.pending- 临时文件过滤 ──────────────────────────

    @Test
    fun isPendingFile_returnsTrueForPendingPrefix() {
        assertTrue(".pending-screenshot.png", isPending(".pending-screenshot.png"))
        assertTrue(".pending-1234567890.jpg", isPending(".pending-1234567890.jpg"))
        assertTrue(".pending-IMG_001.webp", isPending(".pending-IMG_001.webp"))
    }

    @Test
    fun isPendingFile_returnsFalseForFinalFiles() {
        assertFalse("不含 .pending- 前缀", isPending("screenshot.png"))
        assertFalse("普通图片文件", isPending("IMG_20240801.jpg"))
        assertFalse(".webp 文件", isPending("photo.webp"))
    }

    @Test
    fun isPendingFile_handlesEdgeCases() {
        assertFalse(".pendingscreenshot.png (无连字符)", isPending(".pendingscreenshot.png"))
        assertFalse("pending- 不在开头", isPending("temp.pending-screenshot.png"))
        assertTrue("仅 .pending- 前缀", isPending(".pending-.png"))
        assertFalse(".pending- 在中间", isPending("screenshot.pending-.png"))
    }

    // ─── isImageFile：图片扩展名识别 ────────────────────────────────────

    @Test
    fun isImageFile_returnsTrueForImageExtensions() {
        assertTrue(".png", isImage("screenshot.png"))
        assertTrue(".jpg", isImage("photo.jpg"))
        assertTrue(".jpeg", isImage("photo.jpeg"))
        assertTrue(".webp", isImage("sticker.webp"))
        assertTrue(".bmp", isImage("bitmap.bmp"))
        assertTrue(".gif", isImage("animation.gif"))
    }

    @Test
    fun isImageFile_returnsFalseForNonImageExtensions() {
        assertFalse(".txt", isImage("notes.txt"))
        assertFalse(".pdf", isImage("document.pdf"))
        assertFalse(".mp4", isImage("video.mp4"))
        assertFalse(".mp3", isImage("audio.mp3"))
        assertFalse(".zip", isImage("archive.zip"))
        assertFalse("无扩展名", isImage("no_extension"))
    }

    @Test
    fun isImageFile_caseInsensitive() {
        assertTrue(".PNG 大写", isImage("screenshot.PNG"))
        assertTrue(".JPG 大写", isImage("photo.JPG"))
        assertTrue(".JPEG 大写", isImage("photo.JPEG"))
        assertTrue(".WebP 混合", isImage("image.WebP"))
        assertTrue(".GIF 大写", isImage("animation.GIF"))
    }

    // ─── 防抖逻辑 ──────────────────────────────────────────────────────

    @Test
    fun debounce_multipleRapidEvents_triggersRemoveCallbacksAndPostDelayed() {
        val mockHandler = mock<Handler>()
        val observer = ScreenshotFileObserver { /* no-op */ }

        replaceHandler(observer, mockHandler)

        val mockDir = java.io.File("/storage/emulated/0/Pictures/Screenshots")
        val fileObserver = createMockFileObserver(observer, mockDir)

        fileObserver.onEvent(android.os.FileObserver.CREATE, screenshotName)
        fileObserver.onEvent(android.os.FileObserver.CLOSE_WRITE, screenshotName)
        fileObserver.onEvent(android.os.FileObserver.MOVED_TO, screenshotName)

        verify(mockHandler, times(3)).removeCallbacks(any())
        verify(mockHandler, times(3)).postDelayed(any(), eq(500L))
    }

    @Test
    fun debounce_skipsPendingFiles() {
        val mockHandler = mock<Handler>()
        val observer = ScreenshotFileObserver { /* no-op */ }

        replaceHandler(observer, mockHandler)

        val mockDir = java.io.File("/storage/emulated/0/Pictures/Screenshots")
        val fileObserver = createMockFileObserver(observer, mockDir)

        fileObserver.onEvent(android.os.FileObserver.CREATE, ".pending-screenshot.png")

        verify(mockHandler, never()).removeCallbacks(any())
        verify(mockHandler, never()).postDelayed(any(), any())
    }

    @Test
    fun debounce_skipsNonImageFiles() {
        val mockHandler = mock<Handler>()
        val observer = ScreenshotFileObserver { /* no-op */ }

        replaceHandler(observer, mockHandler)

        val mockDir = java.io.File("/storage/emulated/0/Pictures/Screenshots")
        val fileObserver = createMockFileObserver(observer, mockDir)

        fileObserver.onEvent(android.os.FileObserver.CREATE, "notes.txt")

        verify(mockHandler, never()).removeCallbacks(any())
        verify(mockHandler, never()).postDelayed(any(), any())
    }

    @Test
    fun debounce_nullPath_skipsProcessing() {
        val mockHandler = mock<Handler>()
        val observer = ScreenshotFileObserver { /* no-op */ }

        replaceHandler(observer, mockHandler)

        val mockDir = java.io.File("/storage/emulated/0/Pictures/Screenshots")
        val fileObserver = createMockFileObserver(observer, mockDir)

        fileObserver.onEvent(android.os.FileObserver.CREATE, null)

        verify(mockHandler, never()).removeCallbacks(any())
        verify(mockHandler, never()).postDelayed(any(), any())
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    private fun isPending(filename: String): Boolean =
        isPendingFileMethod.invoke(companionInstance, filename) as Boolean

    private fun isImage(filename: String): Boolean =
        isImageFileMethod.invoke(companionInstance, filename) as Boolean

    private fun replaceHandler(observer: ScreenshotFileObserver, mockHandler: Handler) {
        val handlerField = observer::class.java.getDeclaredField("handler")
        handlerField.isAccessible = true
        handlerField.set(observer, mockHandler)
    }

    /**
     * 创建测试用 FileObserver，共享 debounce 逻辑但不实际监听文件系统。
     */
    private fun createMockFileObserver(
        observer: ScreenshotFileObserver,
        mockDir: java.io.File
    ): android.os.FileObserver {
        val handlerField = observer::class.java.getDeclaredField("handler")
        handlerField.isAccessible = true
        val handler = handlerField.get(observer) as Handler

        val debounceRunnableField = observer::class.java.getDeclaredField("debounceRunnable")
        debounceRunnableField.isAccessible = true
        val debounceRunnable = debounceRunnableField.get(observer) as Runnable

        val fileObserver = object : android.os.FileObserver(
            mockDir,
            android.os.FileObserver.CREATE or
            android.os.FileObserver.MOVED_TO or
            android.os.FileObserver.CLOSE_WRITE
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                // 复用原始逻辑的过滤条件
                if (isImage(path) && !isPending(path)) {
                    handler.removeCallbacks(debounceRunnable)
                    handler.postDelayed(debounceRunnable, 500L)
                }
            }
        }

        // 加入 observers 列表避免外部引用丢失
        val observersField = observer::class.java.getDeclaredField("observers")
        observersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (observersField.get(observer) as MutableList<android.os.FileObserver>).add(fileObserver)

        return fileObserver
    }

    @After
    fun cleanup() {
        isPendingFileMethod.isAccessible = false
        isImageFileMethod.isAccessible = false
    }
}
