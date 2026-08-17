package com.snapclear.app.ui.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import java.util.concurrent.Semaphore
import kotlin.math.max

/**
 * 截图 UI 的统一图片解码入口。
 *
 * 这里只负责前台页面的缩略图和详情预览，不参与截图监听、通知或后台服务。
 */
object ScreenshotImageLoader {

    /** 最近截图上下滚动时复用缩略图；容量固定，系统会按需回收淘汰。 */
    private val thumbnailCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val thumbnailDecodeSlots = Semaphore(2, true)

    fun peekThumbnail(uri: Uri, sizePx: Int): Bitmap? = synchronized(thumbnailCache) {
        thumbnailCache.get("$uri@$sizePx")
    }

    fun loadThumbnail(resolver: ContentResolver, uri: Uri, sizePx: Int): Bitmap? {
        val cacheKey = "$uri@$sizePx"
        synchronized(thumbnailCache) {
            thumbnailCache.get(cacheKey)?.let { return it }
        }
        thumbnailDecodeSlots.acquire()
        val bitmap = try {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                        resolver,
                        android.content.ContentUris.parseId(uri),
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
            }.getOrNull()
        } finally {
            thumbnailDecodeSlots.release()
        }
        return bitmap?.also {
            synchronized(thumbnailCache) {
                thumbnailCache.put(cacheKey, it)
            }
        }
    }

    /**
     * 按显示尺寸解码详情图，避免把常见的数千万像素截图完整展开到内存。
     * inSampleSize 使用 2 的幂，保持 BitmapFactory 的高效采样路径。
     */
    fun loadPreview(
        resolver: ContentResolver,
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidthPx,
                targetHeight = targetHeightPx
            )
        }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    internal fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        val safeTargetWidth = max(1, targetWidth)
        val safeTargetHeight = max(1, targetHeight)
        var sampleSize = 1
        while (
            sourceWidth / (sampleSize * 2) >= safeTargetWidth &&
            sourceHeight / (sampleSize * 2) >= safeTargetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
